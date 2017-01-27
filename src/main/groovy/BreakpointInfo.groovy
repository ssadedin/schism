import groovy.transform.CompileStatic;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics

@CompileStatic
class BreakpointInfo {
    
    Long id
    
    String chr
    
    Integer pos
    
    SummaryStatistics mapQStats = new SummaryStatistics()
    
    Integer obs = 0 
    
    Integer sampleCount = 0
    
    List<BreakpointSampleInfo> observations = []
    
    List<String> genes
    
    List<Integer> exonDistances
    
    void annotateGenes(RefGenes refGene, int window) {
        Region checkRegion = new Region(chr, (pos - window)..(pos + window))
        this.genes = refGene.getGenes(checkRegion)
        
        this.exonDistances = genes.collect { String gene ->
//            refGene.getExons(gene).overlaps(this.chr, this.pos-1, this.pos+1)
            Regions exons = refGene.getExons(gene)
            
            // Non coding?
            if(exons.numberOfRanges == 0)
                return -1
            
            if(exons.overlaps(this.chr, this.pos-1, this.pos+1))
                return 0
                
            IntRange nearestExon = exons.nearest(this.chr, this.pos)
            return Math.min(Math.abs(nearestExon.from - pos), Math.abs(nearestExon.to - pos))
        }
    }
    
    void add(BreakpointMessage msg) {
        obs += msg.reads.size()
        ++sampleCount
        msg.reads.each { mapQStats.addValue(it.mappingQuality) }
        observations << new BreakpointSampleInfo(msg.sample, msg.reads)
    }
    
    String queryReference(FASTA reference, int numBases) {
        if(observations.any { it.startClips > 0 }) {
            reference.basesAt(chr, pos, pos+numBases)
        }
        else {
            reference.basesAt(chr,pos-numBases,pos)
        }
    }
    
    @Override
    String toString() {
        "Breakpoint $id: $chr:$pos, samples=$sampleCount, obs=$obs, consensus score=" +observations*.consensusScore.join(",")
    }
}