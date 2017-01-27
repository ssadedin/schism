import groovy.transform.CompileStatic;
import htsjdk.samtools.SAMRecord

@CompileStatic
class BreakpointMessage {
    String chr
    String sample
    int pos
    List<SAMRecord> reads
    
    @Override
    String toString() {
        "$sample: $chr:$pos (${reads.size()} reads)"
    }
}

