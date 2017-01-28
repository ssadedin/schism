import groovy.sql.BatchingPreparedStatementWrapper
import groovy.sql.BatchingStatementWrapper;
import groovy.sql.Sql
import groovy.transform.CompileStatic;
import groovy.util.logging.Log;
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.SAMFileHeader
import htsjdk.samtools.SAMFileWriter
import htsjdk.samtools.SAMFileWriterFactory
import htsjdk.samtools.SAMRecord

import java.sql.Statement
import java.util.logging.FileHandler
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics

//import Long as BreakpointId
//import String as SampleId

/**
 * An actor that acts as a sink for breakpoint events that writes them
 * to a database.
 */
@Log
class WriteBreakpointDBActor extends DefaultActor {
    
    File outputFile
    
    long countWritten = 0
    
    String debugRead = null // "HJYFJCCXX160204:8:2106:27275:50463"
    
    TreeMap<Long, BreakpointInfo> breakpoints = new TreeMap()
    
    Map<String, Long> extractorPositions 
    
    Sql db
    
    Set<Long> processed = new HashSet()
    
    int minPerBpObs = 3
    
    int minPerSampleBpObs = 3
    
    int softClipSize = 15
    
    /**
     * Optional fasta file for reference sequence - if provided, the reference sequence adjacent
     * to each breakpoint will be annotated.
     */
    FASTA reference = null
    
    Logger filterLog = Logger.getLogger("Filter")
    
    WriteBreakpointDBActor(List<String> samples, File outputFile)  {
        this.outputFile = outputFile
        this.extractorPositions = samples.collectEntries { [it, 0] }
    }
    
    void act() {
        loop {
            react { msg ->
                if(msg instanceof BreakpointMessage) {
                    BreakpointInfo bpInfo = processReads(msg)
                    flushCoveredPositions(msg.sample, bpInfo)
                }
                else 
                if(msg == "init") {
                    log.info "Initializing database"
                    this.db = init(outputFile.absolutePath)
                    
                    assert this.db != null
                }
                else 
                if(msg == "end") {
                    log.info "Closing database file $outputFile after writing $countWritten breakpoints"
                    finalizeDatabase()
                    terminate()
                }
                else
                if(msg == "flush") {
                    flush()
                }
            }
        }
    }
    
    /**
     * If there is already a breakpoint at this position, update it to include
     * the information from this msg. Otherwise, create a new one
     * 
     * @param msg
     */
    BreakpointInfo processReads(BreakpointMessage msg) {
        Long bpId = XPos.computePos(msg.chr, msg.pos)
        if(bpId in processed)
            throw new IllegalStateException("Breakpoint $bpId discovered out of order: this is an internal bug")
            
        BreakpointInfo bpInfo = breakpoints.get(bpId, new BreakpointInfo(id: bpId, chr: msg.chr, pos: msg.pos))
        bpInfo.add(msg)
        return bpInfo
    }
    
    void flushCoveredPositions(String sampleId, BreakpointInfo bpInfo) {
        
        extractorPositions[sampleId] = bpInfo.id
        
        // Flush all the positions prior to the minimum covered position
        Long minPos = extractorPositions*.value.min()
        Map.Entry bpEntry = null
        while((bpEntry = breakpoints.lowerEntry(minPos+1))) {
            BreakpointInfo bp = bpEntry.value
            writeBreakpointToDB(bp)
            
            breakpoints.remove(bpEntry.key)
//            processed << bpEntry.key
        }
    }
    
    @CompileStatic
    boolean isBreakpointFiltered(BreakpointInfo bp) {
        int totalReadCount = bp.obs
        int maxPerSample = bp.observations*.obs.max()
        String id = bp.id
        
        if(totalReadCount < minPerBpObs) {
            filterLog.info("Breakpoint ${id} has too few total obs ($totalReadCount<${minPerBpObs})")
        }
        else
        if(maxPerSample < minPerSampleBpObs) {
            filterLog.info("Breakpoint ${id} has no samples with enough obs ($maxPerSample<${minPerSampleBpObs})")
        }
        else
        if(bp.mapQStats.max == 0) {
            filterLog.info("Breakpoint ${id} has only reads with MAPQ=0")
        }
        else {
            return false
        }
        
        return true // filtered
    }
    
    @CompileStatic
    void writeBreakpointToDB(BreakpointInfo bp) {
        
        if(isBreakpointFiltered(bp)) {
            return
        }
        
        log.info "Writing position $bp.id to db with $bp.obs observations from $bp.sampleCount samples"
        
        String ref = null
        if(reference) {
            ref = bp.queryReference(reference, softClipSize)
        }
        
        db.execute """
          insert into breakpoint (id, chr, pos, sample_count, obs, ref)
          values ($bp.id, $bp.chr, $bp.pos, $bp.sampleCount, $bp.obs, $ref)
        """
            
        String insertBreakpointObservation = """
          insert into breakpoint_observation (id, bp_id, sample, obs, bases, consensus)
          values (NULL, ?, ?, ?, ?, ?)
        """
        db.withBatch(bp.observations.size(), insertBreakpointObservation) { BatchingPreparedStatementWrapper stmt ->
            for(BreakpointSampleInfo bpObs in bp.observations) {
                stmt.addBatch((List<Object>)[bp.id, bpObs.sample, bpObs.obs, bpObs.bases, bpObs.consensusScore])
            }
        }
        
        ++countWritten
    }
    
    /**
     * Write all remaining breakpoints to db and clear breakpoint tracking info
     */
    void flush() {
        log.info "Flushing database (${breakpoints.size()} breakpoints to write)"
        
        breakpoints.each { bpId, bp ->
            writeBreakpointToDB(bp)
        }
        breakpoints.clear()
        
        db.execute("commit;")
        db.close()
        
        db = connect(outputFile.absolutePath)
//        db.execute("begin transaction;")
        
        log.info "Database has been flushed"
    }
    
    static Sql connect(String outputFile) {
        Sql db = Sql.newInstance("jdbc:sqlite:${outputFile}")
        db.cacheConnection = true
        // causes weird errors!
//        db.cacheStatements = true
        db.execute("PRAGMA synchronous = 0;");
        db.execute("PRAGMA journal_mode = WAL;")
        db.connection.autoCommit = false
        return db
    }
    
    static Sql init(String outputFile) {
        
        Sql db = connect(outputFile)
        
        try {
            def countRow = db.firstRow("select count(1) as cnt from breakpoint;")
            log.info "Database already contains $countRow.cnt breakpoints: bypassing schema creation"
            return db
        }
        catch(Exception e) {
            // Ignore, expected    
        }
        
        db.execute("""
              CREATE TABLE breakpoint (id INTEGER PRIMARY KEY,
                                       chr VARCHAR NOT NULL , 
                                       pos INTEGER NOT NULL , 
                                       sample_count INTEGER NOT NULL, 
                                       obs INTEGER NOT NULL,
                                       ref VARCHAR);
                """)
  
        db.execute("""
                  CREATE TABLE breakpoint_observation (id INTEGER PRIMARY KEY AUTOINCREMENT, 
                                                       bp_id BIGINT NOT NULL, 
                                                       sample VARCHAR NOT NULL,
                                                       obs INTEGER NOT NULL,
                                                       bases VARCHAR,
                                                       consensus REAL);
            """)
        
        
        return db
    }
    
    void finalizeDatabase() {
        flush()
        indexDb(db)
        db.close()
    }
    
    static void indexDb(Sql db) {
        
        try {
            db.execute """
                CREATE INDEX breakpoint_sample_count_idx ON breakpoint ( sample_count );
            """
            
            db.execute """
                CREATE INDEX breakpoint_pos_idx ON breakpoint ( pos );
            """
            
            db.execute """
                CREATE INDEX breakpoint_obs_idx ON breakpoint ( obs );
            """
            db.execute """
                CREATE INDEX breakpoint_observation_sample_idx on breakpoint_observation (sample);
            """
            
            db.execute """
                CREATE INDEX breakpoint_observation_bp_idx on breakpoint_observation(bp_id);
            """ 
        }
        catch(Exception e) {
            log.warning("Index db failed: this may be because schema already existed: $e")
        }
        
    }
}
