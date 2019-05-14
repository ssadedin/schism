package schism.web

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import schism.domain.Breakpoint
import schism.domain.BreakpointObservation
import schism.domain.Cohort
import schism.domain.DB
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType

import javax.inject.Inject
import javax.sql.DataSource
import groovy.json.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import groovy.sql.*

import gngs.Region

import static DB.getDb


@Controller("/cohort")
//@Secured(SecurityRule.IS_AUTHENTICATED)
class CohortController {
	
    static Logger log = LoggerFactory.getLogger('CohortController')
    
	@Inject
	DataSource dataSource

//    @Produces(MediaType.TEXT_HTML)
    @Get("/") 
    def index() {
        DB.from(dataSource) {
       		def results = db.rows("select * from cohort")
    		return [
    			cohorts: results.collect { new Cohort(it) }
    		]
        }
    }
    
    @Get("/{id}/summary") 
    def summary(Long id) {
        DB.from(dataSource) { 
            
            Cohort cohort = Cohort.query(id)
           
            def n = db.firstRow("""
                select count(1) as cnt 
                from breakpoint_observation bpo,
                sample s,
                cohort_samples cs
                where bpo.sample_id = s.id
                  and s.id = cs.sample_id
                  and cs.cohort_id = ${cohort.id}
            """).cnt
            
            log.info "There are $n breakpoints"
            
            return [
                cohort:  [
                    name: cohort.name, 
                    description: cohort.description, 
                    summary: [numberOfBreakpoints: n], 
                    samples: cohort.samples,
                    status: 'ok'
                ]
            ]
        }
    }
    
    @Get("/{id}/samples") 
    def cohort(Long id) {
//        User user = springSecurityService.currentUser
        
        DB.from(dataSource) { 
            log.info "Showing samples for cohort: " + id
            Cohort cohort = Cohort.query(id)
//    		List<GroovyRowResult> samples = sql.rows("""
//                select * from sample s
//                inner join cohort_samples sc on sc.cohort_id = ${id}
//            """)
            
            cohort.samples*.mother
//    		
    		return [
    			samples : cohort.samples
    		]
        }
    } 
    
    @Get("/{id}/breakpoints") 
    def breakpoints(Long id) { 
        
        log.info "Show breakpoints for cohort: " + id
            
        DB.from(dataSource) { 
            List<Breakpoint> breakpoints = db.rows("""
                select bp.*, bpo.*, bpo.id as bpo_id, bp.id as bp_id, s.sample_id as sample, s.id as s_id, bpo.total as depth, pbp.id as partner_xpos
                from breakpoint_observation bpo
                    left join breakpoint_observation pobs on pobs.id = bpo.partner_id
                    left join breakpoint pbp on pbp.id = pobs.breakpoint_id
                    inner join breakpoint bp on bp.id = bpo.breakpoint_id
                    inner join sample s on s.id = bpo.sample_id
                    inner join cohort_samples cs on s.id = cs.sample_id and cs.cohort_id = ${id}
                where bp.obs > 2
            """).collect { new BreakpointObservation(it) }
            
            
                
//            breakpoints.each { b ->
//                b.annotations = [:] // b.annotations?.value ? new JsonSlurper().parseText(b.annotations.value) : [:]
//                b.samples = ['TestSample1','TestSample2']
//                    
////                if(b.partner_xpos) {
////                    Region r = XPos.parsePos(b.partner_xpos)
////                    b.partner = r.chr + ':' + r.from
////                }
//            }
//                
            return [breakpoints: breakpoints, 'status': 'ok']
        }
    }
}
