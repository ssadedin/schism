package schism
// vim: shiftwidth=4:ts=4:expandtab:cindent
/////////////////////////////////////////////////////////////////////////////////
//
// This file is part of Schism.
// 
// Schism is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, under version 3 of the License, subject
// to additional terms compatible with the GNU General Public License version 3,
// specified in the LICENSE file that is part of the Schism distribution.
//
// Schism is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Schism.  If not, see <http://www.gnu.org/licenses/>.
//
/////////////////////////////////////////////////////////////////////////////////

import gngs.Consensus
import gngs.XPos
import graxxia.IntegerStats
import groovy.transform.CompileStatic;
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord

enum SoftClipDirection {
    FORWARD, REVERSE
    
    String encodeChar() {
        this == SoftClipDirection.REVERSE ? 'r' : 'f'        
    }
    
    static SoftClipDirection decodeChar(String value) {
        value == 'r' ? SoftClipDirection.REVERSE : SoftClipDirection.FORWARD
    }
}

@CompileStatic
class BreakpointSampleInfo {
    
    final static int softClipSize = 15
    
    IntegerStats baseQuals = new IntegerStats(40) 
    
    // For unit testing
    BreakpointSampleInfo() {
    }
    
    BreakpointSampleInfo(String sample, List<SAMRecord> reads) {
        this.sample = sample
        this.obs = reads.size()
        
        if(reads.size()>1) {
            List<String> softClips = reads.collect {  read ->
                this.mateXPos.add(XPos.computePos(read.mateReferenceName, read.mateAlignmentStart))
                extractSoftClip(read, softClipSize, baseQuals) 
            }
            Consensus consensus = new Consensus(softClips, softClipSize).build() 
            consensusScore = consensus.score
            bases = consensus.bases
        }
        else {
            bases = extractSoftClip(reads[0], softClipSize, baseQuals)
            consensusScore = 0.0d
        }
    }
    
    /**
     * Extract the soft clipped bases from the given read, on the assumption that the soft clip portion is either 
     * at the start or the end of the read.
     * @param numBases
     * @return
     */
    String extractSoftClip(SAMRecord read, int numBases, IntegerStats baseQuals) {
        List<CigarElement> cigars = read.cigar.cigarElements
        CigarElement cigar0 = cigars[0]
        final byte [] baseQualities = read.baseQualities
        if(cigar0.getOperator() == CigarOperator.S) {
            ++startClips
            final int startPos = Math.max(0,cigar0.length-numBases)
            final int endPos = cigar0.length
            for(int i=startPos; i<endPos; ++i) { baseQuals.addValue(baseQualities[i]) }
            return read.readString.substring(startPos, endPos)
        }
        else
        if(cigars[-1].getOperator() == CigarOperator.S) {
            final String readString = read.readString
            ++endClips
            final int numBasesToInclude = Math.min(numBases, cigars[-1].length)
            final int startPos = readString.size()-cigars[-1].length
            final int endPos = startPos+numBasesToInclude
            for(int i=startPos; i<endPos; ++i) { baseQuals.addValue(baseQualities[i]) }
            return readString.substring(startPos, endPos)
        }
        else
            assert false
    }
    
    SoftClipDirection direction
    
    /**
     * Count of reads whose start is clipped supporting the breakpoint
     */
    int startClips = 0
    
    /**
     * Count of reads whose end is clipped supporting the breakpoint
     */
    int endClips = 0
    
    /**
     * Sample in which this breakpoint was identified
     */
    String sample
    
    /**
     * The first #softClipSize soft clipped bases
     */
    String bases
    
    /**
     * A score representing the consensus between the reads supporting the breakpoint
     */
    double consensusScore
    
    /**
     * Count of reads supporting the breakpoint
     */
    int obs
    
    List<Long> mateXPos = []
    
    /**
     * If a partner was identified for this breakpoint, it can be set here
     */
    BreakpointInfo partner
}

