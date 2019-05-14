package schism.web

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces

import javax.inject.Inject
import javax.sql.DataSource
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType


@Controller("/breakpoint")
class BreakpointController {
    
    @Inject DataSource dataSource

    @Produces(MediaType.TEXT_HTML)
    @Get("/") 
    String index() {
        println "yes" 
        return "<html><h2>Mooooolllll</h2></html>"
    }
}