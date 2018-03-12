
import org.junit.BeforeClass;
import org.junit.Test

import gngs.Region
import gngs.Regions
import gngs.SAM
import gngs.Utils
import schism.FindNovelBreakpoints

class FindNovelBreakpointsTest {
    
    @BeforeClass
    static void setupAll() {
        Utils.configureSimpleLogging()
    }
    
    @org.junit.Test
    void testFindTranslocation() {
        
        String translocationFile = "src/test/data/translocation.bam"
        SAM translocation = new SAM(translocationFile)
        FindNovelBreakpoints fnb = new FindNovelBreakpoints([:], translocationFile, []).start()
        
        Regions regions = new Regions([new Region("X:32276095-32278021")])
        fnb.run(regions)
        
        println fnb.breakpoints*.toString().join("\n")
        
        assert fnb.breakpoints.find { it.pos == 32276895 } != null
        assert fnb.breakpoints.find { it.pos == 32277020 } != null
    }

//    @org.junit.Test
    void testFindXBreakpoint() {
        
        String breakpointFile = "src/test/data/giab_chrx_miss.bam"
        SAM translocation = new SAM(breakpointFile)
        FindNovelBreakpoints fnb = new FindNovelBreakpoints([:], breakpointFile, []).start()
        
        Regions regions = new Regions([new Region("X:32276095-32278021")])
        fnb.run(regions)
        
        println fnb.breakpoints*.toString().join("\n")
        
        assert fnb.breakpoints.find { it.pos == 32276895 } != null
        assert fnb.breakpoints.find { it.pos == 32277020 } != null
    }
    
}
