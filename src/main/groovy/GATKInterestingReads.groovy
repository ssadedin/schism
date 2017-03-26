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

import htsjdk.samtools.CigarElement;
import htsjdk.samtools.SAMFileWriter
import htsjdk.samtools.SAMRecord

import java.util.Collection;

import org.broadinstitute.gatk.engine.walkers.ReadPairWalker
import org.broadinstitute.gatk.engine.walkers.Walker
import org.broadinstitute.gatk.utils.commandline.Argument;
import org.broadinstitute.gatk.utils.commandline.Output

import groovy.transform.AutoClone;;

@AutoClone
class ReadStatistics {
    int countInteresting = 0
    int countAnomalous = 0
    int countChimeric = 0
    int countLowQual = 0
    int found = 0
    
    void merge(ReadStatistics other) {
        countInteresting += other.countInteresting
        countAnomalous += other.countAnomalous
        countChimeric += other.countChimeric
        countLowQual += other.countLowQual
        found += other.found
    }
}

class InterestingReads {
    List<SAMRecordPair> pairs = []
    
    ReadStatistics stats = new ReadStatistics()
}

class GATKInterestingReads extends ReadPairWalker<InterestingReads, ReadStatistics> {
    
    @Output
    SAMFileWriter outputBamFile = null;
    
    @Argument(fullName = "minSoftClippedBases", shortName = "msc", doc = "Minimum number of soft clipped bases", required = false, minValue = 0d, maxValue = 1000d)
    int minSoftClippedBases = 10;
    
    InterestingReads map(Collection<SAMRecord> reads) {
        
        InterestingReads intx = new InterestingReads()
        ReadStatistics stats = intx.stats
        
        // If any read of a pair is interesting, write both out
        boolean isActuallyInteresting = false
        for(SAMRecord read in reads) {
               
            boolean isInteresting = read.cigar.cigarElements.any { CigarElement e -> 
                e.operator == htsjdk.samtools.CigarOperator.S && 
                    e.length > minSoftClippedBases
            }
                
            if(!isInteresting)
                continue
                
            // For now, ignore chimeric reads
            if(read.referenceIndex != read.mateReferenceIndex) {
                ++stats.countChimeric
                continue
            }
                
            ++stats.countInteresting
                
            int r1Start = read.alignmentStart
            int r2Start = read.mateAlignmentStart
            if(read.isSecondaryOrSupplementary() || !read.properPairFlag || (Math.abs(r2Start - read.alignmentEnd) > maxInsertSize)) {
                ++stats.countAnomalous
                continue
            }
                
            if(Arrays.asList(read.baseQualities).count { ((int)it) < 20 } > 20) {
                ++stats.countLowQual
                continue
            }
                
            
            isActuallyInteresting = true
            
//            int readLength = read.alignmentEnd-r1Start
//            if(r2Start >= r1Start) {
//                w.println([read.referenceName, r1Start, read.mateAlignmentStart+readLength].join('\t'))
//            }
//            else {
//                w.println([read.referenceName, read.mateAlignmentStart, read.alignmentStart + readLength ].join('\t'))
//            }        
        
        }
        
        if(isActuallyInteresting)
            intx.pairs.add(new SAMRecordPair(reads[0], reads[1]))
        
        return intx
    }
    
    ReadStatistics reduceInit() {
       return new ReadStatistics()
    }
    
    ReadStatistics reduce(InterestingReads reads, ReadStatistics r2) {
        for(SAMRecordPair pair in reads.pairs) {
            outputBamFile.addAlignment(pair.r1)
            outputBamFile.addAlignment(pair.r2)
        }
        ReadStatistics newStats = r2.clone()
        newStats.merge(reads.stats)
    }
}