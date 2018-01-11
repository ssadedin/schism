// vim: shiftwidth=4:ts=4:expandtab:cindent
/////////////////////////////////////////////////////////////////////////////////
//
// This file is part of Schism.
// 
// Schism is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, under version 3 of the License, subject
// to additional terms compatible with the GNU General Public License version 3,
// specified in the LICENSE file that is part of the Schism distribution.
//
// Schism is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Schism.  If not, see <http://www.gnu.org/licenses/>.
//
/////////////////////////////////////////////////////////////////////////////////

import java.util.logging.Logger;

import gngs.Cli
import gngs.ProgressCounter
import gngs.Utils
import groovy.sql.Sql
import groovy.util.logging.Log;
import groovyx.gpars.GParsPool;
import groovyx.gpars.actor.DefaultActor;


@Log
class CombineBreakpointDBs {
    
    List<String> inputDbFiles = null
    
    String outputDbFile
    
    List<Sql> inputDbs
    
    Sql outputDb
    
    int concurrency = 1
    
    boolean useNative = true
    
    CombineBreakpointDBs(List<String> inputDbFiles, String outputDbFile) {
        
        this.inputDbFiles = inputDbFiles
        this.outputDbFile = outputDbFile
        
        outputDb = WriteBreakpointDBActor.init(outputDbFile)
        
        this.inputDbs = this.inputDbFiles.collect { dbFile ->
            Sql inDb = Sql.newInstance("jdbc:sqlite:${dbFile}")
            inDb.cacheConnection = true
            inDb.cacheStatements = true
        }
    }
   
    long countNew = 0
    long countUpdated = 0
    long total = 0
    long totalSampleObs = 0
    
    void run() {
        
        log.info " Phase 1: Add Breakpoints ".center(80,"=")
        
        if(useNative) {
            outputDb.execute("commit")
            addBreakpointsNative()
        }
        else {
            addBreakpoints()
            outputDb.execute("commit")
        }
        
        log.info " Phase 2: Add Per Sample Observations ".center(80,"=")
        addObservationsNative()
        
        log.info "Indexing final database ..."
        WriteBreakpointDBActor.indexDb(outputDb)
        
//        outputDb.execute("commit")
        outputDb.close()
    }
    
    void addObservationsNative() {
        
        
        int dbCount = 0
        ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true, log:log, extra: {
            "$dbCount / ${inputDbFiles.size()} processed"
        }) 
        for(String dbFile in inputDbFiles) {

            outputDb.execute("attach '" + dbFile + "' as other;")
            outputDb.execute("""
                insert into breakpoint_observation (id, bp_id, sample, obs, bases, consensus)
                select NULL,bp_id,sample,obs,bases,consensus from other.breakpoint_observation
            """)
            outputDb.execute("detach other;")
            
            counter.count()
            ++dbCount
        }
        counter.end()
    }
    
    void addObservations() {
        long countObs = 0
        ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true, log:log, extra: {
            "$countObs / $totalSampleObs processed"
        })
        
        for(String dbFile in inputDbFiles) {
            log.info "Processing $dbFile"
            Sql db = WriteBreakpointDBActor.connect(dbFile)
            try {
                db.eachRow("select * from breakpoint_observation bpo") { bpo ->
                    outputDb.execute """
                        insert into breakpoint_observation (id, bp_id, sample, obs, bases, consensus)
                        values (NULL, $bpo.bp_id, $bpo.sample, $bpo.obs, $bpo.bases, $bpo.consensusScore)
                    """                        
                    ++countObs
                    counter.count()
                }

            }
            finally {
                db.close()
            }
        }
        counter.end()
    }
    
    void addBreakpointsNative() {
        
        ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true, log: log)
        for(String dbFile in inputDbFiles) {
            log.info "Processing $dbFile"
            Sql db = WriteBreakpointDBActor.connect(dbFile)
            try {
                
                outputDb.execute("attach '" + dbFile + "' as other;")
                outputDb.execute("""
                    replace into breakpoint
                    select bp.id, bp.chr, bp.pos, bp.sample_count + ifnull(bp2.sample_count,0), bp.obs + ifnull(bp2.obs,0), NULL
                    from
                    other.breakpoint bp
                    left join breakpoint bp2 on bp.id = bp2.id
                """)
                outputDb.execute("detach other;")
                
                counter.count()

            }
            finally {
                db.close()
            }
        }        
        counter.end()
    }
    
    void addBreakpoints() {
        for(String dbFile in inputDbFiles) {
            log.info "Processing $dbFile"
            Sql db = WriteBreakpointDBActor.connect(dbFile)
            try {
                
                ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true, log: log, extra: {
                    "total=$total, updated=$countUpdated, new=$countNew"
                })
                
                db.eachRow("select * from breakpoint bp") { bp ->
                    
                    int updated = outputDb.executeUpdate """
                        update breakpoint set sample_count = sample_count + ${bp.sample_count}, obs=$bp.obs
                        where id = $bp.id
                    """
                    
                    if(updated) {
                        ++countUpdated
                    }
                    else {
                        outputDb.execute """
                            insert into breakpoint (id, chr, pos, sample_count, obs)
                            values ($bp.id, $bp.chr, $bp.pos, $bp.sample_count, $bp.obs)
                        """
                        ++countNew
                    }
                    
                    totalSampleObs += bp.sample_count
                    ++total
                    counter.count()
                }
                counter.end()
            }
            finally {
                db.close()
            }
        }
    }
    
    static void main(String [] args) {
        
        Banner.banner()
        
        Cli cli = new Cli(usage: "combinedb <options> <input db 1> <input db 2> ...")
        cli.with {
            'in' 'Database to combine (use multiple times)', args:Cli.UNLIMITED, required:false
            'native' 'Use native sqlite functions to combine', required:false
            'out' 'Database to write', args:1, required:true
        }
        
        def opts = cli.parse(args)
        if(!opts)
            System.exit(1)
        
        List inputDbs = opts.arguments()
        if(opts.ins)
            inputDbs = inputDbs + opts.ins
        
        if(!inputDbs) {
            System.err.println "ERROR: no input databases supplied: please use -in multiple times or supply databases as last argument(s)"
            System.exit(1)
        }
            
        Utils.configureSimpleLogging()
        Logger.getLogger("groovy.sql.Sql").useParentHandlers = false
        
        log.info "Input databases: " + inputDbs.join(",")
        
        CombineBreakpointDBs cbdb = new CombineBreakpointDBs(inputDbs, opts.out)
        if(opts.t)
            cbdb.concurrency = opts.t
        cbdb.run()
        
        log.info "Finished."
    }
}
