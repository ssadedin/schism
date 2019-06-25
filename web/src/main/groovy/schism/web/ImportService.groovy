package schism.web

import javax.inject.Inject
import javax.sql.DataSource

import groovy.sql.GroovyResultSet
import groovy.sql.Sql
import groovy.util.logging.Slf4j


@javax.inject.Singleton
@Slf4j
class ImportService {
    
    @Inject
    DataSource dataSource
    
    @Slf4j
    class ImporterThread extends Thread {
        
        Import importSpec
    
        @Override
        public void run() {
            log.info "I too am responsible for importing $importSpec"
            
            Sql sql = Sql.newInstance("jdbc:sqlite:./import/${importSpec.path}")
            
            importSpec.numBreakpoints = sql.firstRow("select count(*) as cnt from breakpoint").cnt
            log.info "Importing ${importSpec.numBreakpoints} breakpoints from $importSpec.path"
            
            Sql db  = new Sql(dataSource)
            
            
            
            try {
                int i = 0
                db.withBatch(50) { stmt ->
                    sql.eachRow("select * from breakpoint") { GroovyResultSet bp ->
                        stmt.addBatch("""
                           MERGE INTO breakpoint
                           USING (VALUES $bp.id, $bp.chr, $bp.pos, $bp.sample_count, $bp.obs) newbp (id, chr, pos, sample_count, obs)
                           ON (breakpoint.id = newbp.id)
                           WHEN MATCHED THEN 
                               UPDATE SET breakpoint.sample_count = breakpoint.sample_count + 1
                           WHEN NOT MATCHED THEN 
                               insert into breakpoint (id, chr, pos, sample_count, obs, version) values ($bp.id, $bp.chr, $bp.pos, $bp.sample_count, $bp.obs, 0)
                        """)
                        if(i++ % 50 == 0) {
                            importSpec.breakpointsProcessed = i
                            log.info "Processed ${i} / ${importSpec.numBreakpoints} breakpoints in first pass"
                        }
                    }
                }
            } finally {
                db.close()
                sql.close()
            }
            
            log.info "Import of $importSpec is finished"
            finished(importSpec)
        }
    }
        
    static List<ImporterThread> currentImports = Collections.synchronizedList([])
    
    void importDatabase(Import importSpec) {
        log.info "Starting to import $importSpec"
        ImporterThread importer = new ImporterThread(importSpec:importSpec)
        currentImports << importer
        importer.start()
    }
    
    void finished(Import importSpec) {
        log.info "The import $importSpec is finished"
    }
}
