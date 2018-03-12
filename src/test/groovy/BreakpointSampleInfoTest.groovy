
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord
import schism.BreakpointSampleInfo

import org.junit.Test;

import gngs.Region
import gngs.SAM

class BreakpointSampleInfoTest {
    
    SAM bam = new SAM("src/test/data/giab_one_del.bam")
    Region deletionStart = new Region("21:19,913,990-19,914,490")
//    Region deletionEnd = new Region("21:19,914,281-19,914,507")
    
    SAMRecord read(String name) {
        SAMRecord r = bam.withIterator(deletionStart) { iter ->
            iter.find { it.readName == name }
        }
    }
    
    List<SAMRecord> readsAt(int pos) {
        bam.withIterator(deletionStart) { iter ->
            iter.grep { it.alignmentStart == pos || it.alignmentEnd == pos && it.cigar.cigarElements.any { it.operator == CigarOperator.S } }
        }
    }
        
    @org.junit.Test
    void testForwardRead() {
        
        SAMRecord r = read("NA12878.cram:3412928")
        
        assert r != null
        println "Position " + r.alignmentEnd
        
        def bpsi = new BreakpointSampleInfo("NA12878",[r])
        
        println "soft clip = " + bpsi.bases
        
        assert bpsi.bases.startsWith("CAGTATAT")
    }
    
    @Test
    void testReverseRead() {
        
        SAMRecord r = bam.withIterator(deletionStart) { iter ->
            iter.find { it.readName == "NA12878.cram:3412888" }
        }
        println "Position " + r.alignmentEnd
        assert r != null
        def bpsi = new BreakpointSampleInfo("NA12878",[r])
        
        println "soft clip = " + bpsi.bases
        assert bpsi.bases.startsWith("CAGTATATCTG")
    }
    
    
    @Test
    void testLeadingBp() {
        
        SAMRecord r = read("HK3T5CCXX160204:2:1217:13230:31230")
        
        println "Position " + r.alignmentEnd
        
        assert r != null
        
        def bpsi = new BreakpointSampleInfo("NA12878",[r])
        
        println "soft clip = " + bpsi.bases
        
        assert bpsi.bases.reverse().startsWith("AAAACCG")
    }
    
    @Test
    void testAtPos() {
        def reads = readsAt(19914056)
        def bpsi = new BreakpointSampleInfo("NA12878",reads)
        println "soft clip = " + bpsi.bases
        assert bpsi.bases.startsWith("CAGTATATCT")
        
        reads = readsAt(19914458)
        bpsi = new BreakpointSampleInfo("NA12878",reads)
        println "soft clip = " + bpsi.bases
        assert bpsi.bases.reverse().startsWith("AAAACCGTTATA")
    }
}
