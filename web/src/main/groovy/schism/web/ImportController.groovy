package schism.web

import javax.inject.Inject

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post

@ToString(includeNames=true)
class Import {
    String path
    String state
    int numBreakpoints
    int breakpointsProcessed
    Date created = new Date()
}


@Controller('/import')
@Slf4j
class ImportController {
    
    @Inject ImportService importService
    
    @Get('pending')
    def pendingImports() {
        new File('./import').listFiles().grep { it.name.endsWith('.db') }*.name
    }
    
    @Post('init')
    def initialiseImport(@Body Import importSpec) {
        log.info "Starting import for data: $importSpec"
        
        importService.importDatabase(importSpec)
        
        return [
            status: 'ok'
        ]
    } 
}
