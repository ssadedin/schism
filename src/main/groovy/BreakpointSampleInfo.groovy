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

import gngs.XPos
import groovy.transform.CompileStatic;
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord

enum SoftClipDirection {
    FORWARD, REVERSE
}

@CompileStatic
class BreakpointSampleInfo {
    
    final static int softClipSize = 15
    
    
    
    // For unit testing
    BreakpointSampleInfo() {
    }
    
    BreakpointSampleInfo(String sample, List<SAMRecord> reads) {
        this.sample = sample
        this.obs = reads.size()
        
        if(reads.size()>1) {
            List<String> softClips = reads.collect {  read ->
                this.mateXPos.add(XPos.computePos(read.mateReferenceName, read.mateAlignmentStart))
                extractSoftClip(read, softClipSize) 
            }
            Consensus consensus = new Consensus(softClips, softClipSize).build() 
            consensusScore = consensus.score
            bases = consensus.bases
        }
        else {
            bases = extractSoftClip(reads[0], softClipSize)
            consensusScore = 0.0d
        }
    }
    
    /**
     * Extract the soft clipped bases from the given read, on the assumption that the soft clip portion is either 
     * at the start or the end of the read.
     * @param numBases
     * @return
     */
    String extractSoftClip(SAMRecord read, int numBases) {
        List<CigarElement> cigars = read.cigar.cigarElements
        CigarElement cigar0 = cigars[0]
        if(cigar0.getOperator() == CigarOperator.S) {
            ++startClips
            read.readString.substring(Math.max(0,cigar0.length-numBases), cigar0.length)
        }
        else
        if(cigars[-1].getOperator() == CigarOperator.S) {
            String readString = read.readString
            ++endClips
            int numBasesToInclude = Math.min(numBases, cigars[-1].length)
            int start = readString.size()-cigars[-1].length
            readString.substring(start, start+numBasesToInclude)
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

