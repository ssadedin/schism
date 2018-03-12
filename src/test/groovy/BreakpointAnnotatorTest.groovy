import static org.junit.Assert.*;

import org.junit.Test

import gngs.SAM
import htsjdk.samtools.SAMRecord;;
import schism.BreakpointAnnotator
import schism.BreakpointFeatures
import schism.ReadSupport
import schism.SupportType

class BreakpointAnnotatorTest {
    
    SAM bam
    
    def delOne = [
        start: 32916627,
        end: 32916732
    ]
    
    @Test
    void testCountReadSupport() {
        
        bam = new SAM("src/test/data/giab_small_del.bam")
        List<SAMRecord> reads = bam.withIterator { Iterator i ->
           i.toList()
        }
        
        println "Computing support for ${reads.size()} reads"
        
        BreakpointAnnotator bpfe = new BreakpointAnnotator(bam)
        
        Map support = bpfe.computeReadSupport(reads, delOne.start)
        
        println "Support = " + support.collectEntries { [it.key, it.value.size() ]}
        
        assert support[SupportType.CONTRA].size() in 20..24
        assert support[SupportType.SUPPORT].size() in 20..24
        assert support[SupportType.NO_OVERLAP].size() in 160..200
    }
    
    @Test
    void testReadSupportMetrics() {
        bam = new SAM("src/test/data/giab_small_del.bam")
        List<SAMRecord> reads = bam.withIterator { Iterator i ->
           i.toList()
        }
        BreakpointAnnotator bpfe = new BreakpointAnnotator(bam)
        Map support = bpfe.computeReadSupport(reads, delOne.start)
        
        ReadSupport bpMetrics = new ReadSupport(support[SupportType.SUPPORT])
        println "Metrics for breakpoint = " + bpMetrics
        
        println "fracMisoriented " + bpMetrics.fracMisoriented()
    }
    
    @Test
    void testReadSupportsBp() {
        bam = new SAM("src/test/data/giab_small_del.bam")
        BreakpointAnnotator bpfe = new BreakpointAnnotator(bam)
        def read 
        read = getRead("NA12878.cram:7019358")
        
        
        assert bpfe.supportsBreakpoint(read, delOne.start)
        
        read= getRead("HK35MCCXX160204:1:1207:28686:1959")
        assert bpfe.supportsBreakpoint(read, delOne.end)
    }
    
    @Test
    void testInsertSizeCalcs() {
        
        bam = new SAM("src/test/data/giab_large_del.bam")
       
        BreakpointAnnotator bpfe = new BreakpointAnnotator(bam)
        BreakpointFeatures f = bpfe.analyze("21",18612332)
        
        println "Mean shift = " + f.insertSizeStats.insertSizeClusterMeanShift
        
        assert f.insertSizeStats.insertSizeClusterMeanShift > 1000.0d
        
        println "Sd Ratio = " + f.insertSizeStats.insertSizeClusterSdRatio
    }
    
    @Test
    void testNonSupSoftClips() {
        
        bam = new SAM("src/test/data/multichr_noise.bam")
       
        BreakpointAnnotator bpfe = new BreakpointAnnotator(bam)
        def result = bpfe.analyze("11",43332836)
        
        ReadSupport rs = result.breakpointSupport
        ReadSupport ns = result.referenceSupport
        
        println "non sup reads containing soft clips: " + ns.softClipped

    }
    
    @Test
    void testMultiChr() {
        
        bam = new SAM("src/test/data/multichr_noise.bam")
       
        BreakpointAnnotator bpfe = new BreakpointAnnotator(bam)
        def result = bpfe.analyze("11",43332836)
        
        ReadSupport rs = result.breakpointSupport
        ReadSupport ns = result.referenceSupport
        
        println "No. chimeric chrs at breakpoint " + rs.numberOfMateChromosomes
        println "Number of reads for top chimeric chr at breakpoint " + rs.countTopChimericChromosome
        
        println "No. chimeric chrs in ns reads at breakpoint " + ns.numberOfMateChromosomes
        println "Number of reads for ns reads, top chimeric chr at breakpoint " + ns.countTopChimericChromosome
    }
    
    SAMRecord getRead(String readName) {
       bam.withIterator { it.find{it.readName == readName} } 
    }

}
