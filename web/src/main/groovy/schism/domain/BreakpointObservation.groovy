package schism.domain

import groovy.sql.GroovyRowResult
import groovy.util.logging.Slf4j

@Slf4j
class BreakpointObservation {
    
    Long id

    Breakpoint breakpoint
    
    Sample sample
    
    int total
    
    int startClips
    
    int endClips
    
    String bases
    
    BreakpointObservation partner
    
    BreakpointObservation(Map options=null, GroovyRowResult row) {
        this.id = row[options?.id?:'id']
        
        this.breakpoint = new Breakpoint(row)
//        if(options.sample_id)
//            this.sample = new Sample(row[options.sample_id])
        
        log.info "Sample id is: $row.sample_id"
        this.sample = new Sample(row.sample_id)
        this.total = row.TOTAL
        this.startClips = row.START_CLIPS
        this.endClips = row.END_CLIPS
    }
}
