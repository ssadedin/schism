import static org.junit.Assert.*;

import org.junit.Test

import gngs.BED
import gngs.Region
import gngs.SAM
import groovyx.gpars.actor.Actors
import htsjdk.samtools.SAMRecord;;;


class BreakpointExtractorTest {
    
    List<BreakpointMessage> breakpoints = []
    
    def actor = Actors.actor {
        loop {
            react { msg ->
                breakpoints << msg
            }
        }
    }
    
    @Test
    void testIdentifyGiabBreakpoints() {
        BED bpRegions = new BED("src/test/data/giab_startbp.chr21.bed").load()
        SAM bam = new SAM("src/test/data/na12878.chr21.bps.bam")
        
        
//        actor.start()
        
        Thread.sleep(100)
        
        for(Region region in bpRegions) {
            
            println "Test " + region
            BreakpointExtractor bpe = new BreakpointExtractor(bam)
            bpe.breakpointListener = actor
            bpe.run(region)
            
            Thread.sleep(100)
            
            assert breakpoints.find { it.reads.size() >= 3  }
            
            breakpoints.clear()
        }
        
//        actor.terminate()
    }
    
    @Test
    void testIdentifyNoise() {
        SAM bam = new SAM("src/test/data/giab_noise.bam")
        
        BreakpointExtractor bpe = new BreakpointExtractor(bam)
        bpe.run(new Region("21:10,819,661-10,822,287"))
        
        println "Noisy regions are: " + bpe.noiseState.noisyRegions
        bpe.noiseState.noisyRegions.save("noisy.bed")
    }
    
    @Test
    void testMultimappingFalsePositive() {
        SAM bam = new SAM("src/test/data/multimapping_fp.bam")
        
        BreakpointExtractor bpe = new BreakpointExtractor(bam)
        bpe.breakpointListener = actor
        bpe.run(new Region("X:31,243,730-31,243,993"))
        
        WriteBreakpointDBActor dbActor = new WriteBreakpointDBActor([], null)
        
        List msgs = breakpoints.collect { dbActor.processReads(it) }
        
        assert msgs.every { dbActor.isBreakpointFiltered(it) }
    }
    
    @Test
    void testIsDupe() {
        SAM bam = new SAM("src/test/data/chimeric_dup.bam")
        Region region = new Region("14:64440000-64460000")        
        List<SAMRecord> dupeReads = bam.withIterator(region) { i -> i.grep { it.alignmentStart == 64450884 } }
        
        println "Found ${dupeReads.size()} dupe reads to test"
        
        BreakpointExtractor bpe = new BreakpointExtractor(bam)
        List deduped = bpe.dedupe(dupeReads)
        
        println "After dedupe: ${deduped.size()}"
        
        assert deduped.size() == 1
        
        
    }
    
    @Test
    void testIsEndDupe() {
        SAM bam = new SAM("src/test/data/chimeric_dup.bam")
        Region region = new Region("14:64440000-64460000")        
        List<SAMRecord> dupeReads = bam.withIterator(region) { i -> i.grep { it.alignmentStart == 64450986 } }
        
        println "Found ${dupeReads.size()} dupe reads to test"
        
        BreakpointExtractor bpe = new BreakpointExtractor(bam)
        List deduped = bpe.dedupe(dupeReads)
        
        println "After dedupe: ${deduped.size()}"
        
        assert deduped.size() == 1
    }
    
    @Test
    void testDedup() {
        SAM bam = new SAM("src/test/data/chimeric_dup.bam")
        
        BreakpointExtractor bpe = new BreakpointExtractor(bam)
        bpe.breakpointListener = actor
        bpe.debugPosition = 64450986
        bpe.run(new Region("14:64440000-64460000"))
        
        println "Breakpoints are : " + breakpoints*.toString().join("\n")
        
        assert !breakpoints.any { it.pos == 64450884 && it.reads.size()>1 }
    }
}