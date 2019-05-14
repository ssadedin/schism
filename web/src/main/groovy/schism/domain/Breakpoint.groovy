package schism.domain

import com.fasterxml.jackson.annotation.JsonInclude

import groovy.json.JsonSlurper
import groovy.sql.GroovyRowResult

class Breakpoint {
    
    Long id
    
    List<BreakpointObservation> observations
    
    String chr
    
    int pos
    
    int sampleCount
    
    int obs
    
    Map annotations
    
    @JsonInclude(JsonInclude.Include.ALWAYS)
    List<String> samples = []
    
    Breakpoint(Map options=null, GroovyRowResult row) {
        this.id = row[options?.id?:'id']
        this.chr = row.CHR
        this.pos = row.POS
        this.obs = row.OBS
        this.sampleCount = row.SAMPLE_COUNT
        this.annotations = new JsonSlurper().parseText(row.annotations.characterStream.withReader { it.text })
    }
}
