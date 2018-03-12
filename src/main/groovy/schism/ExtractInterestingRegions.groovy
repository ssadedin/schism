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

import gngs.Cli
import gngs.FASTA
import gngs.ProgressCounter
import gngs.Region
import gngs.SAM
import groovy.transform.CompileStatic
import groovy.util.logging.Log;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SAMRecordIterator;

/**
 * Scans a BAM file for regions where high quality reads have large numbers
 * of bases soft clipped. These are then written to a BED file for further
 * downstream processing.
 * 
 * @author simon
 */
@Log
class ExtractInterestingRegions {
    
    /**
     * Some reads have soft clipping because they contain adapter sequence. We exclude any read having
     * 7 bases or more of adapter at each end.
     */
    static final String DEFAULT_ADAPTER = "AGATCGGAAGAG"
    
    String ADAPTER_SEED = DEFAULT_ADAPTER.substring(0,5)
    
    String ADAPTER_SEED_COMPLEMENT = FASTA.reverseComplement(ADAPTER_SEED)
    
    static final int READ_WINDOW_SIZE = 500
    
    /**
     * Moving window containing all reads within READ_WINDOW_SIZE bp of the current position
     */
    List<List<SAMRecord>> window = []
    
    @CompileStatic
    void run(SAM bam, Region region, File outputFile, int minSoftClippedBases, int maxInsertSize) {
        
        if(bam.samples.unique().size()>1)
            throw new IllegalArgumentException("This tool only supports single-sample bam files")
        
        String sampleId = bam.samples[0]
        
        outputFile.withWriter { w ->
            
            int countInteresting = 0
            int countAnomalous = 0
            int countChimeric = 0
            int countLowQual = 0
            int countAdapter = 0
            
            ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true)
            counter.extra = {
                "Interesting Regions Found: $countInteresting, anomalous=$countAnomalous, chimeric=$countChimeric, lowqual=$countLowQual, adapter=$countAdapter"
            }
            
            SAMRecordIterator iter = bam.newReader().query(region.chr.replaceAll('^chr',''), region.from,region.to, false)
            while(iter.hasNext()) {
                
                counter.count()
                
                SAMRecord read = iter.next()
                
                boolean isInteresting = read.cigar.cigarElements.any { CigarElement e -> 
                    e.operator == htsjdk.samtools.CigarOperator.S && 
                        e.length > minSoftClippedBases
                }
                
                if(!isInteresting)
                    continue
                
                // For now, ignore chimeric reads
                if(read.referenceIndex != read.mateReferenceIndex) {
                    ++countChimeric
                    continue
                }
                
                
                int r1Start = read.alignmentStart
                int r2Start = read.mateAlignmentStart
                if(read.isSecondaryOrSupplementary() || !read.properPairFlag || (Math.abs(r2Start - read.alignmentEnd) > maxInsertSize)) {
                    ++countAnomalous
                    continue
                }
                
                if(Arrays.asList(read.baseQualities).count { ((int)it) < 20 } > 20) {
                    ++countLowQual
                    continue
                }
                
                // When both reads in pair are aligned to nearly the same position then we 
                // have a possibility of small fragment containing adapter contamination
                if(Math.abs(read.mateAlignmentStart - read.alignmentStart) < 10) {
                    if(read.readString.indexOf(ADAPTER_SEED) >=0 || (read.readString.indexOf(ADAPTER_SEED_COMPLEMENT)>=0)) {
                        ++countAdapter
                        continue
                    }
                }
                
                ++countInteresting
                
                int readLength = read.alignmentEnd-r1Start
                if(r2Start >= r1Start) {
                    w.println([read.referenceName, r1Start, read.mateAlignmentStart+readLength, sampleId].join('\t'))
                }
                else {
                    w.println([read.referenceName, read.mateAlignmentStart, read.alignmentStart + readLength, sampleId ].join('\t'))
                }
            }
            counter.end()
        }
    }
    
    public static void main(String [] args) {
        
        Cli cli = new Cli(usage:"ExtractInterestingReads <opts>")
        cli.with {
            bam 'BAM file to extract from', args:1, required:true
            'L' 'Region to extract from', args:1, required:true
            o 'The output BED file containing regions with interesting reads', args:1, required:true
            'size' 'The minimum number of soft clipped reads to qualify a read for inclusion', args:1
            'ins' 'The maximum insert size of reads to qualify a read for inclusion', args:1
        }
        
        def opts = cli.parse(args)
        if(!opts) {
            System.exit(1)
        }
        
        Region region = new Region(opts.L)
        
        SAM sam = new SAM(opts.bam)
        
        int minSoftClipped = opts['size'] ? opts['size'].toInteger() : 10
        
        int maxInsertSize = opts['ins'] ? opts['ins'].toInteger() : 900
        
        new ExtractInterestingRegions().run(sam, region, new File(opts.o), minSoftClipped, maxInsertSize)
    } 
}