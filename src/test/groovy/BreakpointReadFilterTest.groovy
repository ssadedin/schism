import static org.junit.Assert.*;

import htsjdk.samtools.SAMRecord
import org.junit.Test;

class BreakpointReadFilterTest {
    
    @Test
    void testAdapterOfUnmappedMate() {
        
        // When  mate is unmapped, should not look for adapter contam
        // because the insert size is unknown
        SAMRecord read = [
            alignmentStart: 30263765,
            mateAlignmentStart: 30263765,
            mateUnmappedFlag: true,
            readPairedFlag: true,
            readString: "ACTTGAAAACAGTGCAAATTTTCTTTCTTAACATTTCATTTTTGGGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCGCGAGGTCAGGAGATCGAGACCATCCTGGCTAACACAGTGAAACC"
        ] as SAMRecord
    
        def rf = new BreakpointReadFilter()
        assert !rf.hasAdapterContaminationPattern(read) : "Read falsely signified to have adapter contamination"
    }
}