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

import groovy.transform.CompileStatic
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import java.util.logging.Logger

/**
 * Identifies reads that have potential to contain legitimate 
 * soft clipped regions.
 * 
 * @author simon
 */
class BreakpointReadFilter {
    
    /**
     * Some reads have soft clipping because they contain adapter sequence. We exclude any read having
     * 7 bases or more of adapter at each end.
     */
    static final String DEFAULT_ADAPTER = "AGATCGGAAGAG"
    
    String ADAPTER_SEED
    
    String ADAPTER_SEED_COMPLEMENT 
    
    BreakpointReadFilter() {
        setAdapterSequence(DEFAULT_ADAPTER)
    }
    
    void setAdapterSequence(String seq) {
        ADAPTER_SEED = seq.substring(0,5)
        ADAPTER_SEED_COMPLEMENT = FASTA.reverseComplement(ADAPTER_SEED)
    }
    
    boolean verbose = false
    
    boolean ignoreChimeric = false
    
    String debugRead // = "1335"
    
    // ----------  Parameters controlling filtering
    int minSoftClippedBases = 20
    int maxInsertSize = 100000
    int minNonScBases = 22
    
    /**
     * Optional log file to write log of action to (for debugging)
     */
    Logger filterLog = Logger.getLogger("Filter")
  
    /**
     * Useful metrics we can log
     */
    int countAnomalous = 0
    int countChimeric = 0
    int countLowQual = 0
    int countAdapter = 0
    int countContam = 0    
    
    
    @CompileStatic
    Integer getMaxSoftClipped(SAMRecord read) {
        List<CigarElement> cigars = read.cigar.cigarElements
        Integer maxSoftClipped = cigars.collect { CigarElement e -> 
            e.operator == htsjdk.samtools.CigarOperator.S ?
                e.length 
                : 
                0
        }.max()
    }
    
    @CompileStatic
    boolean matchesContaminationSignature(SAMRecord read) {
        List<CigarElement> cigars = read.cigar.cigarElements
        if(cigars.size() > 2) {
            if(cigars[0].operator == CigarOperator.S && cigars[-1].operator == CigarOperator.S) {
                Integer countMatch = (Integer)cigars.grep { CigarElement cig -> cig.operator == CigarOperator.M }*.length.sum()
                if(countMatch < minNonScBases) {
                    return true
                }
            }
        }
        return false
    }
    
    private final static byte qualScoreCutoff = 12i
    
    @CompileStatic
    boolean hasPoorBaseQualities(SAMRecord read) {
//       Arrays.asList(read.baseQualities).count { ((int)it) < 12 } > 20 
       int n=0;
       byte [] quals = read.baseQualities
       for(int i=0; i<quals.length; ++i) {
           if(quals[i] < qualScoreCutoff) {
               ++n
           }
       }
       
       return n > 20i
    }
    
    @CompileStatic
    boolean hasAdapterContaminationPattern(SAMRecord read) {
        
        // When mate is not mapped better not to check because the insert size
        // is unknown
        if(read.readPairedFlag && read.mateUnmappedFlag)
            return false
        
        // When both reads in pair are aligned to nearly the same position then we 
        // have a possibility of small fragment containing adapter contamination
        int alignmentGap = Math.min(
            Math.abs(read.mateAlignmentStart - read.alignmentStart),
            Math.abs(read.mateAlignmentStart - read.alignmentEnd)
        )
        
        if(debugRead != null && read.readName == debugRead) {
            "Debug read: " + debugRead + ": alignmentGap = " + alignmentGap + read.readString
        }
        
        if(alignmentGap< 10) {
            
            if(read.readString.indexOf(ADAPTER_SEED) >=0 || (read.readString.indexOf(ADAPTER_SEED_COMPLEMENT)>=0)) {
                return true
            }
        }
        return false
    }
    
    @CompileStatic
    boolean isReadInteresting(SAMRecord read) {
        
        boolean verbose = this.verbose
        
        if(debugRead != null && read.readName == debugRead) {
            filterLog.info "Processing $read with cigar string $read.cigarString"
            verbose = true
        }
        
       Integer maxSoftClipped = getMaxSoftClipped(read) 
        
        boolean isInteresting = maxSoftClipped > minSoftClippedBases
                    
        if(!isInteresting) {
            if(verbose)
                filterLog.info "$read has too few soft clipped bases (nSoftClipped = $maxSoftClipped)"
            return false
        }
                    
        // For now, ignore chimeric reads
        if(ignoreChimeric && (read.referenceIndex != read.mateReferenceIndex)) {
            if(verbose)
                filterLog.info "$read.readName is chimeric"
            ++countChimeric
            return false
        }
                    
        int r1Start = read.alignmentStart
        int r2Start = read.mateAlignmentStart
        /*
        if(read.isSecondaryOrSupplementary()) {
            ++countAnomalous
            return false
        }
        */
        
        int insertSize = Math.abs(r2Start - read.alignmentEnd)
        if( /*!read.properPairFlag || */ (insertSize > maxInsertSize)) {
            
            if(verbose) {
                if(!read.properPairFlag) {
                    filterLog.info "$read.readName is not proper pair"
                }
                else {
                    filterLog.info "$read.readName insert size = " + insertSize + " > $maxInsertSize"
                }
            }
                
            ++countAnomalous
            return false
        }
  
                    
        if(hasPoorBaseQualities(read)) {
            if(verbose) {
                filterLog.info "$read.readName is too low quality"
            }
            ++countLowQual
            return false
        }
                    
        if(hasAdapterContaminationPattern(read)) {
            if(verbose)
                filterLog.info "$read.readName has a signature of adapter contamination"
            ++countAdapter
            return false
        }
        
        // Check if the read matches the characteristics of contaminants with small section of homology in the middle
        // These reads have the approximate form S-M-S in the cigar string (3 or more elements)
        if(matchesContaminationSignature(read)) {
            if(verbose)
                filterLog.info "$read.readName has a signature of contamination"
            ++countContam
            return false
        }
        
        if(verbose && read.readName == debugRead) {
            println "Including read $read.readName at $read.alignmentStart"
        }
        
        return true
    }
}
