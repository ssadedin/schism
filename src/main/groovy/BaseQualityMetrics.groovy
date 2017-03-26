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
class BaseQualityMetrics {
    
    @CompileStatic
    void run(SAM bam, Region region, File outputFile, int minSoftClippedBases, int maxInsertSize) {
        
        int countInteresting = 0
        int countAnomalous = 0
        int countChimeric = 0
        int countLowQual = 0
            
        ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true)
        counter.extra = {
            "Base Quality Stats : $countInteresting, anomalous=$countAnomalous, chimeric=$countChimeric, lowqual=$countLowQual"
        }
            
        SAMRecordIterator iter = bam.newReader().query(region.chr.replaceAll('^chr',''), region.from,region.to, false)
        while(iter.hasNext()) {
                
            counter.count()
                
            SAMRecord read = iter.next()
    
            // TODO: really we want to know whether they mismatch or not rather than
            // just whether > 20 bases with low quality
            if(Arrays.asList(read.baseQualities).count { ((int)it) < 20 } > 20) {
                ++countLowQual
                continue
            }
        }
        counter.end()
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
        
        new BaseQualityMetrics().run(sam, region, new File(opts.o), minSoftClipped, maxInsertSize)
    }
}