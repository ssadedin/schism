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
import htsjdk.samtools.SAMRecord
import java.util.zip.GZIPOutputStream

import graxxia.IntegerStats;

/**
 * 
 * Extract reads from a BAM and write them as FASTQ, only where there are fewer than 
 * a specified number of low quality bases in the read.
 * 
 * @author simon
 */
class UnmappedtoFASTQ {
    
    int minQ = 20
    
    int maxLowQ = 4
    
    @CompileStatic
    void run(SAM bam, Writer w1, Writer w2) {
        
        int countBad = 0
        int includedReads = 0
        
        IntegerStats stats = new IntegerStats(500)
        
        ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true, extra: {"Bad Reads=$countBad Included=$includedReads Avg.Bad=${stats.mean}"})
        
//        bam.verbose = true
        bam.eachPair(includeUnmapped:true) { SAMRecord r1, SAMRecord r2 ->
            
            counter.count()
            
            if(!r1.getReadUnmappedFlag() && !r2.getReadUnmappedFlag()) {
                return
            }
            
            // Check quality of bases
            int badBases1 = Arrays.asList(r1.baseQualities).count { ((int)it) < minQ }.toInteger()
            stats.addValue((double)badBases1)
            if(badBases1 > maxLowQ) {
                ++countBad
                return
            }
            
            int badBases2 = Arrays.asList(r2.baseQualities).count { ((int)it) < minQ }.toInteger()
            if(badBases2 > maxLowQ) {
                ++countBad
                return
            }
            
            ++includedReads
            
            FASTQRead r1FastQ = new FASTQRead(r1.readName + " /1", r1.readString, r1.baseQualityString)
            FASTQRead r2FastQ = new FASTQRead(r2.readName + " /2", FASTA.reverseComplement(r2.readString), r2.baseQualityString.reverse())
            
            r1FastQ.write(w1)
            r2FastQ.write(w2)
            
        }
        counter.end()
    }
    
    static void main(String [] args) {
        
        println "*" * 70
        println "UnmappedToFASTQ"
        println "*" * 70
        
        Cli cli = new Cli()
        cli.with {
            bam 'BAM file to process', required:true, args:1
            fq1 'GZipped FASTQ output for read 1', required:true, args:1
            fq2 'GZipped FASTQ output for read 2', required:true, args:1
            minQ 'Minimum quality threshold to count base as "bad"', required:true, args:1
            maxLowQ 'Maximum number of low quality bases before rejecting read', required:true, args:1
        }
        
        def opts = cli.parse(args)
        if(!opts) {
            System.exit(1)
        }
        
        UnmappedtoFASTQ samToFQ = new UnmappedtoFASTQ(minQ:opts.minQ.toInteger(), maxLowQ: opts.maxLowQ.toInteger())
        
        SAM sam = new SAM(opts.bam)
        new GZIPOutputStream(new FileOutputStream(opts.fq1)).withWriter { w1 ->
            new GZIPOutputStream(new FileOutputStream(opts.fq2)).withWriter { w2 ->
                println "Running ..."
                samToFQ.run(sam, w1, w2)
            }
        }
    }
}
