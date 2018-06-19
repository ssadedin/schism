package schism

import gngs.ReadWindow
import gngs.Region
import gngs.Regions
import groovy.transform.CompileStatic
import groovy.util.logging.Log
import htsjdk.samtools.CigarOperator
import htsjdk.samtools.SAMRecord

/**
 * Tracks soft clipping statistics for a window.
 * <p>
 * The statistics that are tracked include:
 * <li>Total number of soft clipped reads
 * <li>Total number of positions with greater than a threshold number of soft clips starting there (see {@link #minPositionSC})
 * <p>
 * Note that for tracking positions with soft clipping, only "left" clipping is counted. This is
 * because the genome is scanned from right to left, making left clipping computationally easier to 
 * count. While it would be better to track right clipping too, the goal of understanding how many
 * total breakpoints are in the window is served by only tracking left clips.
 * 
 * @author Simon Sadedin
 */
@Log
class WindowStatistics {
    
    int halfWindowSize = 0
    
    WindowStatistics(int windowSize) {
        this.halfWindowSize = windowSize / 2
    }
    
    int softClipped = 0
    
    int startPosition = 0
    
    int positionsClipped = 0
    
    final int minPositionSC = 2
    
    @CompileStatic
    boolean anyLeftClipped(List<SAMRecord> reads) {
        int scCount = 0
        if(reads.size()>=minPositionSC) {
            return reads[0].cigar.cigarElements[0].operator == CigarOperator.S
        }
        return false
    }
    
    @CompileStatic
    void update(ReadWindow readWindow) {
        
        int pos = readWindow.pos
        
        TreeMap<Integer, List<SAMRecord>> window = readWindow.window
        
        int before = softClipped
        
        // Add the soft clipped reads from the leading edge, subtract from trailing edge
        int trailingEdge = pos - halfWindowSize + 2
        List<SAMRecord> trailingReads = window[trailingEdge]
        if(trailingEdge > startPosition && trailingReads) {
            softClipped -= trailingReads.size()
//            log.info "$pos: Removing ${trailingReads*.readName} from noise"
            
            if(anyLeftClipped(trailingReads)) {
                --positionsClipped
            }
        }
                
        int leadingEdge = pos + halfWindowSize -1
        List<SAMRecord> leadingReads = window[leadingEdge]
        if(leadingEdge>startPosition && leadingReads) {
//            log.info "$pos: Adding ${leadingReads*.readName} from noise"
            softClipped += leadingReads.size()
            
            if(anyLeftClipped(leadingReads)) {
                ++positionsClipped
            }
        }
        
//        String debugRead = "H23LJCCXX150122:2:1123:10967:46367"
            
        if(before != softClipped)
//        log.info "Noise at $pos ($trailingEdge - $leadingEdge) = $softClipped -${trailingReads?.size()} +${leadingReads?.size()} debugRead at " +
//            ((window.find { e -> e.value.find { it.readName == debugRead } != null }?.key?:0) - trailingEdge)
            
//        if(pos < startPosition && softClipped<0)
//            softClipped = 0

        if(softClipped<0)
            softClipped=0
        assert softClipped >= 0
    }
}
