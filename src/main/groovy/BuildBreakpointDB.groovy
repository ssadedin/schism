import org.apache.log4j.BasicConfigurator
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource
import com.j256.ormlite.jdbc.JdbcDatabaseConnection
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.PreparedQuery
import com.j256.ormlite.stmt.SelectArg;
import com.j256.ormlite.support.ConnectionSource
import com.j256.ormlite.support.DatabaseConnection;
import com.j256.ormlite.table.TableUtils;

import groovy.sql.Sql
import groovy.transform.CompileStatic;
import groovy.util.logging.Log
import groovy.util.logging.Log4j;
import groovy.util.logging.Log4j2;
import htsjdk.samtools.SAMRecord;

import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement;;

/**
 * Build a database of break points based on BAM files containing soft 
 * clipped reads
 * 
 * @author simon
 */
@Log4j
class BuildBreakpointDB {
    
    public static final int BREAKPOINT_CACHE_SIZE = 300000
    
    /**
     * The file name of the database that is connected to
     */
    String connectString
    
    /**
     * The file name of the database that is connected to
     */
    String driver
    
    /**
     * The actual database connection
     */
    Sql db
    
    /**
     * The connection source used for loading the data
     */
    ConnectionSource conn
    
    boolean includeReadName = false
    
    BuildBreakpointDB(String fileName) {
       this.connectString = "jdbc:sqlite:$fileName"
       this.driver = "org.sqlite.JDBC"     
       this.init()
    }
    
    /**
     * Check the database exists and upgrade it if necessary
     */
    void init() {
        
        log.info "Creating database connection to $connectString"
        Class.forName(this.driver)
        conn = new JdbcConnectionSource(connectString)
        
        log.info "Single connection database: " + conn.isSingleConnection("breakpoint")
        
        JdbcDatabaseConnection pragmaConn = conn.getReadWriteConnection("breakpoint")
        Connection internalConn = pragmaConn.getInternalConnection()
        Statement s = internalConn.createStatement()
        s.execute("PRAGMA synchronous = 0;");
        s.close()
        
        s = internalConn.createStatement()
        s.execute("PRAGMA journal_mode = WAL;")
        s.close()
        
        for(Class table in [Breakpoint,BreakpointObservation]) {
            AutoDao.init(conn, table)
        }
    }
    
    @CompileStatic
    void load(SAM bam) {
        TransactionManager.callInTransaction(conn) {
            loadInternal(bam)
        }
    }
    
    @CompileStatic
    void loadInternal(SAM bam) {
        
        String sample = bam.samples[0]
        
        int newBreakpoints = 0
        int totalBreakpoints = 0
        int cacheHits = 0
         
        Closure showInfo = {
            "new: $newBreakpoints, total: $totalBreakpoints cacheHits: $cacheHits"
        }
        
        SelectArg posArg = new SelectArg()
        SelectArg chrArg = new SelectArg()
            
        PreparedQuery query = Breakpoint.DAO.queryBuilder().where()
                                        .eq("pos",posArg)
                                        .and()
                                        .eq("chr", chrArg)
                                        .prepare()
            
        
        ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true, extra: showInfo) 
        bam.eachRecord { SAMRecord r ->
            
            for(int pos in [r.alignmentStart, r.alignmentEnd]) {
                long bpId = XPos.computeId(r.referenceIndex,pos)
                String readName = includeReadName ? r.readName : ''
                BreakpointObservation obs = new BreakpointObservation(bpId:bpId,sample:sample,readName:readName)
                BreakpointObservation.DAO.create(obs)
            }
            
            counter.count()
        }
        counter.end()
    }
    
    void resetCounts() {
        try {
            Breakpoint.DAO.executeRaw(
                """delete from breakpoint"""
            )
        }
        catch(Exception ex) {
            log.warn("Failed to delete existing breakpoint counts",ex)
        }
    }
    
    
//        Breakpoint lastStart = new Breakpoint() 
//        Breakpoint lastEnd = new Breakpoint()
//            
//        TreeMap<Long,Breakpoint> breakpointCache = new TreeMap<Long,Breakpoint>()
//                                        
//    
    
//                // Often we will be processing reads that have the same breakpoint
//                Breakpoint bp = null
//                if(bpId == lastStart.id) {
//                    bp = lastStart
//                    ++cacheHits
//                }
//                else
//                if(bpId == lastEnd.id) {
//                    bp = lastEnd
//                    ++cacheHits
//                }
//                else {
//                    bp = breakpointCache[bpId]
//                }
//                
//                if(bp == null) {
//                    posArg.setValue(pos)
//                    chrArg.setValue(r.referenceIndex)
//                    
//                    // Try to find the breakpoint in the database
//                    bp = Breakpoint.DAO.queryForFirst(query)
//                }
//                    
//                if(bp == null) {
//                    bp = new Breakpoint(pos:pos, chr: r.referenceIndex)
//                    Breakpoint.DAO.create(bp)
//                    ++newBreakpoints
//                }
//                ++totalBreakpoints
//                    
    
//                if(pos == r.alignmentStart)
//                    lastStart = bp
//                else
//                    lastEnd = bp
//                
//                breakpointCache[bpId] = bp
//                while(breakpointCache.size()>BREAKPOINT_CACHE_SIZE) {
//                    breakpointCache.pollFirstEntry()
//                }
//    
    
    
    static void main(String [] args) {
        
        println "=" * 80
        println "Breakpoint DB Builder"
        println "=" * 80
        
        BasicConfigurator.resetConfiguration()
        BasicConfigurator.configure()
        Logger.getRootLogger().setLevel(Level.INFO);
        
        Cli cli = new Cli(usage:"builddb <options> <bam file 1> [<bam file 2> ...]")
        cli.with {
            bam 'BAM file(s) to process', args:Cli.UNLIMITED
            db 'SQLite database to create', args:1, required:true
            rn 'Include read name in database'
            inc 'Incremental mode: add files to existing database, recompute counts'
        }
        
        def opts = cli.parse(args)
        if(!opts) {
            System.exit(1)
        }
        
        if(!opts.bams) {
            System.err.println "Please provide one or more BAM files using the -bam option"
            System.exit(1)
        }
        
        BuildBreakpointDB db = new BuildBreakpointDB(opts.db)
        if(opts.i) {
            db.resetCounts()
        }
        
        if(opts.rn) {
            db.includeReadName = true
        }
        
        int bamCount = 0
        for(String bamFile in opts.bams) {
            ++bamCount
            log.info "Loading $bamFile ($bamCount of ${opts.bams.size()})"
            try {
                db.load(new SAM(bamFile))
            }
            catch(Exception ex) {
                log.error("Failed to process file $bamFile", ex)
            }
        }
        
        // Update the count columns
        Utils.time("Creating counts ($opts.db)") {
            Breakpoint.DAO.executeRaw(
                """
                    insert into breakpoint (id, chr, pos, obs, sample_count) select bpo.bp_id, 1+(bpo.bp_id - (bpo.bp_id % 1000000000)) / 1000000000, bpo.bp_id % 1000000000, count(1), count(distinct(bpo.sample)) 
                    from breakpoint_observation bpo 
                    group by bpo.bp_id
                """.stripIndent())
        }
                
        
        log.info "Creating indexes ..."
        Breakpoint.DAO.executeRaw("CREATE INDEX breakpoint_sample_count_idx ON breakpoint ( sample_count )")
        Breakpoint.DAO.executeRaw("CREATE INDEX breakpoint_obs_idx ON breakpoint ( obs )")
        
        log.info "Done."
    }
}

@Log4j
class AutoDao {
    
    static void init(ConnectionSource conn, Class modelClass) {
        Dao dao = DaoManager.createDao(conn, modelClass)
        modelClass.getDeclaredField("DAO").set(Dao, dao)
        try {
            TableUtils.createTable(dao)
        }
        catch(SQLException existsEx) {
            log.info "Table creation failed ($existsEx): assume already exists"
        }
    }
}

