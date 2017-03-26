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

import groovy.transform.CompileStatic;
import groovy.util.logging.Log
import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import static htsjdk.samtools.CigarOperator.*;
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SamPairUtil;

import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.ml.clustering.Clusterable
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer

import graxxia.Stats


/**
 * Given a base position, annotate interesting features that might indicate whether it 
 * is a true breakpoint or not.
 * 
 * @author simon
 */
@Log
class BreakpointAnnotator {
    
    SAM bam
    
    int windowSize = 200
    
    /**
     * Create a BreakpointFeatureExtractor for the given BAM file
     * 
     * @param bam
     */
    BreakpointAnnotator(SAM bam) {
        this.bam = bam
    }
    
    /**
     * Analyze the BAM file at this location to determine support for / against
     * a genomic breakpoint existing at this position.
     * 
     * @param chr   chromosome
     * @param pos   genomic position
     * 
     * @return  a popuated BreakpointFeatures object containing annotations 
     *          that indicate the level of support for a breakpoint existing.
     */
    BreakpointFeatures analyze(String chr, int pos) {
        
        Region region = new Region(chr, pos-windowSize, pos+windowSize)
        
        // Grab all the reads over the region
        List<SAMRecord> reads = bam.withIterator(region) { Iterator i ->
           i.toList()
        }
        
        // Grab all the reads over the region
        List<SAMRecord> pairedReads = reads.grep { it.readPairedFlag }
        
        // Find all the reads supporting the breakpoint vs not
        Map<Boolean,List<SAMRecord>> support = computeReadSupport(reads, pos)
        
        BreakpointFeatures result = new BreakpointFeatures(
            breakpointSupport: new ReadSupport(support[SupportType.SUPPORT]?:[]),
            referenceSupport: new ReadSupport(support[SupportType.CONTRA]?:[]),
            insertSizeStats: new InsertSizeStats()
        )
        result.insertSizeStats.calcTwoMeanInsertSizeClusterSdRatio(pairedReads)
        return result
    }

    /**
     * Group the given reads by whether or not they support the given breakpoint.
     * 
     * @param reads
     * @param pos
     * 
     * @return Map keyed on the type of support, with list of reads having that support 
     *         type as values
     */
    @CompileStatic
    Map<SupportType,List<SAMRecord>> computeReadSupport(List<SAMRecord> reads, int pos) {
        Map<SupportType,List<SAMRecord>> groupBySupport = reads.groupBy {
            supportsBreakpoint(it, pos)
        }
    }
    
    /**
     * Compute whether the given reads supports a breakpoint at the given position.
     * 
     * @param read  read to examine
     * @param pos   genomic position - assumed to be on the same chromosome as read
     *              mapping
     * @return  the type of support (or lack of)
     */
    SupportType supportsBreakpoint(SAMRecord read, int pos) {
        List<CigarElement> cigars = read.cigar.cigarElements
        
        CigarOperator startOp = cigars[0]?.operator?:M
        CigarOperator endOp = cigars.isEmpty() ? M : cigars[-1].operator
        
        int readStart = read.alignmentStart
        if(startOp == S) { // start clipped
            readStart -= cigars[0].length
            if(readStart > pos)
                return SupportType.NO_OVERLAP
            
            if(pos == read.alignmentStart)
                return SupportType.SUPPORT
                
        }
        
        int readEnd = read.alignmentEnd
        if(endOp == S) { // end clipped
           readEnd = read.alignmentEnd + cigars[-1].length
           if(readEnd < pos)
                return SupportType.NO_OVERLAP
  
            if(pos == read.alignmentEnd)
                return SupportType.SUPPORT
        }
        
        // Check overlap from alignment position adjusted for clipping
        if(readStart > pos || readEnd < pos)
            return SupportType.NO_OVERLAP
            
        return SupportType.CONTRA // must overlap, but no clipping or pos doesn't match
    }
    
    static void main(String [] args) {
        
        Cli cli = new Cli()
        cli.with {
            i 'Breakpoints to annotate', args:1, required:true
            bam 'BAM file for sample', args:1, required:true
            o 'Output file containing original breakpoints plus annotations', args:1
        }
        
        Banner.banner()
        
        def opts = cli.parse(args)
        if(!opts)
            System.exit(1)
        
        Utils.configureSimpleLogging()
        
        SAM bam = new SAM(opts.bam)
        BreakpointAnnotator bpfe = new BreakpointAnnotator(bam)
        
        
        ProgressCounter progress = new ProgressCounter(withRate: true, withTime: true, log: log)
        PrintStream ps = opts.o ? new PrintStream(new File(opts.o).newOutputStream()) : System.out
        try {
            
            ps.println(
                (
                 bpfe.coreInfo + 
                 bpfe.readSupportFeatures.collect { "sup_"+it} + 
                 bpfe.readSupportFeatures.collect{ "nsup_"+it} + 
                 bpfe.insertSizeFeatures
                ).join('\t')
            )
            
            TSV bps = new TSV(opts.i, columnTypes: [0:String])
            for(bp in bps) {
                BreakpointFeatures features = bpfe.analyze(bp.chr, bp.start)
                bpfe.writeAnnotatedBreakpoint(bp, features, ps)
                progress.count()
            }
        }
        finally {
            if(opts.o)
                ps.close() 
            progress.end()
        }
        
        log.info "Finished."
        System.exit(0)
    }
    
    List<String> coreInfo = [
            "chr",
            "start",
            "sample",
            "depth",
            "sample_count",
            "cscore",
            "partner",
            "genes",
            "cdsdist"
    ]  
    
    List<String> readSupportFeatures = [
            "forward",
            "reverse",
            "lowMapQ",
            "multimapping",
            "numberOfMateChromosomes",
            "nonFROrientation",
            "softClipped"
    ]
    
    List<String> insertSizeFeatures = [
           "insertSizeClusterMeanShift",
           "insertSizeClusterSdRatio"
    ]
    
    void writeAnnotatedBreakpoint(def bp, BreakpointFeatures bpfe, PrintStream ps) {
        ps.println(
             (
                 coreInfo.collect { bp[it] } +
                 readSupportFeatures.collect { bpfe.breakpointSupport[it] } +
                 readSupportFeatures.collect { bpfe.referenceSupport[it] }  +
                 insertSizeFeatures.collect { bpfe.insertSizeStats[it] }
             ).join('\t')
        )
    }
}

@CompileStatic
class InsertSizeClusterable implements Clusterable {
    
    double insertSize = 0
    
    InsertSizeClusterable(SAMRecord r1, SAMRecord r2) {
        insertSize = SamPairUtil.computeInsertSize(r1, r2)
    }
    
    InsertSizeClusterable(int isize) {
        this.insertSize = (double)isize
    }
    

    @Override
    public double[] getPoint() {
        return [insertSize] as double[]
    }
}

class ReadSupport {
    
    int lowMapQThreshold = 10
    ReadSupport() {
        // Unit testing only
    }
   
    ReadSupport(List<SAMRecord> reads) {
        fromReads(reads)
    }
    
    @CompileStatic
    void fromReads(List<SAMRecord> reads) {
        Map strandCounts = reads.countBy { SAMRecord r ->
            !r.readNegativeStrandFlag
        }
        
        forward = strandCounts[true] ?: 0
        reverse = strandCounts[false] ?: 0
        
        List<SAMRecord> pairedReads = reads.grep { SAMRecord r -> r.readPairedFlag && !r.readUnmappedFlag && !r.mateUnmappedFlag }
        paired = pairedReads.size()
        chimeric = (int)reads.count { 
            it.readPairedFlag && (it.referenceIndex != it.mateReferenceIndex)
        }
        
        reads.each {
           if(it.mappingQuality  == 0)
               ++multimapping
               
           if(it.mappingQuality < lowMapQThreshold)
               ++lowMapQ
        }
        
        int referenceIndex = -1
        if(!reads.isEmpty())
            referenceIndex = reads[0].referenceIndex
        
        computeCountTopChimeric(referenceIndex, pairedReads)
       
        nonFROrientation = (int)pairedReads.count {
            SamPairUtil.getPairOrientation(it) != SamPairUtil.PairOrientation.FR
        }
        
        softClipped = (int)reads.count { SAMRecord r ->
            List<CigarElement> cigar = r.cigar?.cigarElements
            if(cigar) {
                cigar[0].operator == S || cigar[-1].operator == S
            }
            else
                false
        }?:0
    }
    
    int softClipped
    
    int forward
    
    int reverse
    
    int paired
    
    int chimeric
    
    int multimapping
    
    int lowMapQ
    
    int numberOfMateChromosomes
    
    int nonFROrientation 
    
    int countTopChimericChromosome
    
    double fraqLowMapQ() {
        lowMapQ / total
    }
    
    double fracMisoriented() {
        nonFROrientation / total
    }
    
    @CompileStatic
    int getTotal() {
        forward + reverse
    }
    
    String debugRead = "HTCH7CCXX160526:6:2215:9638:70223"
    
    void computeCountTopChimeric(int referenceIndex, List<SAMRecord> pairedReads) {
        
        Map<Integer, Integer> mateRefCounts = pairedReads.countBy { 
            if(it.readName==debugRead) {
//                println "Debug read = $debugRead"
            }
            it.mateReferenceIndex 
        }
        
        numberOfMateChromosomes = mateRefCounts.size()
        
        Integer topChimeric = mateRefCounts.grep { Map.Entry<Integer,Integer> refCount ->
            refCount.key != referenceIndex
        }.collect { refCount ->
            ((Map.Entry<Integer,Integer>)refCount).value 
        }.max()
        
        countTopChimericChromosome = topChimeric ?: 0
    }
  
    
    String toString() {
        "total=$total, forward=$forward, reverse=$reverse, paired=$paired, " + 
        "chimeric=$chimeric, multimap=$multimapping, lowMapQ=$lowMapQ, " + 
        "noMateChrs=$numberOfMateChromosomes, misoriented=$nonFROrientation, " +
        "topChimeric=$countTopChimericChromosome"
    }
}

enum SupportType {
    
    /**
     * The read contains a soft clip that coincides with the breakpoint
     */
    SUPPORT,
    
    /**
     * The read contains bases that contradict the breakpoint. This is expected
     * when an event is heterozygous, but the balance may be informative.
     */
    CONTRA,
    
    /**
     * The read is not informative about the breakpoint, because it does not 
     * overlap it.
     */
    NO_OVERLAP
}

@Log
class InsertSizeStats {
    
    int minClusterSize = 4
    
    InsertSizeStats() {
        // for unit testing
    }
    
    InsertSizeStats(List<SAMRecord> reads) {
        calcTwoMeanInsertSizeClusterSdRatio(reads)
    }
   
    @CompileStatic
    void calcTwoMeanInsertSizeClusterSdRatio(List<SAMRecord> reads) {
        try {
            List<InsertSizeClusterable> clusterables = reads*.inferredInsertSize.grep { int isize -> isize > 0i }.collect { int isize ->
                new InsertSizeClusterable(isize) 
            }
            
            if(clusterables.size() < 10)
                return
            
            KMeansPlusPlusClusterer<InsertSizeClusterable> clusterer = new KMeansPlusPlusClusterer<InsertSizeClusterable>(2, 100);
            List<CentroidCluster<InsertSizeClusterable>> clusters = clusterer.cluster(clusterables)
            List<Stats> stats = [
                Stats.from(clusters[0].points*.insertSize),
                Stats.from(clusters[1].points*.insertSize)
            ]
            
            // Don't accept a situation where just one or two reads form a cluster
            if(clusters*.points*.size().min()<minClusterSize) {
                return
            }
            
            // Note: adding 1 to avoid div by zero
            insertSizeClusterSdRatio = 
                Math.sqrt(stats[0].variance + stats[1].variance +1) / 
                (Stats.from(clusterables*.insertSize).standardDeviation+1)
            
            // Can't predict what order the clusters may come out in
            if(insertSizeClusterSdRatio>1.0d)
                insertSizeClusterSdRatio = 1 / insertSizeClusterSdRatio
                
            insertSizeClusterMeanShift = Math.abs(stats[0].mean - stats[1].mean)
        }
        catch(Exception e) {
            log.warning "Clustering failed : " + e
        }
    }
    
    double insertSizeClusterMeanShift = 0.0d
    
    double insertSizeClusterSdRatio = 1.0d
    
    
}

class BreakpointFeatures {
    
    ReadSupport breakpointSupport
    
    ReadSupport referenceSupport
    
    InsertSizeStats insertSizeStats
}
