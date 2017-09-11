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

import groovy.transform.AutoClone
import groovy.transform.CompileStatic;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics

@CompileStatic
@AutoClone
class BreakpointInfo {
    
    /**
     * A universal identifier for the breakpoint position, in XPos form
     * 
     * @see {@link XPos} 
     */
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
        msg.reads.each { mapQStats.addValue(it.mappingQuality) }
        add(new BreakpointSampleInfo(msg.sample, msg.reads))
    }
    
    /**
     * Merge the information from the given bpInfo into this BreakpointInfo
     * 
     * @param bpInfo
     */
    void add(BreakpointInfo bpInfo) {
        for(BreakpointSampleInfo sampleInfo in bpInfo.observations) {
            add(sampleInfo)
        }
        
        // It is a (bad) approximation, but we just add N values at the
        // mean Q value
        double meanQ = bpInfo.mapQStats.mean
        long n = bpInfo.mapQStats.N
        for(long i=0; i<n; ++i) {
            mapQStats.addValue(meanQ)
        }
    }
    
    /**
     * Add the given sample info to this BreakpointInfo record
     * 
     * @param sampleInfo
     */
    void add(BreakpointSampleInfo sampleInfo) {
        obs += sampleInfo.obs
        ++sampleCount
        observations << sampleInfo
    }
  
    
    /**
     * Returns the reference sequence before and after a breakpoint.
     * 
     * @param reference
     * @param numBases
     * @return  a two element string array. The first element is the reference sequence 
     *          on the opposite side of the soft clips that support the breakpoint. 
     *          The second element is the reference sequence on the same side as the 
     *          soft clips that support the breakpoint.
     */
    @CompileStatic
    String [] queryReference(FASTA reference, int numBases) {
        String bases = reference.basesAt(chr, pos-numBases, pos+numBases)
        String [] result = new String[2]
        if(observations.any { it.startClips > 0 }) {
            result[1] = bases.substring(0,numBases)
            result[0] = bases.substring(numBases)
        }
        else {
            result[0] = bases.substring(0,numBases)
            result[1] = bases.substring(numBases)
        }
        return result
    }
    
    @Override
    String toString() {
        "Breakpoint $id: $chr:$pos, samples=$sampleCount, obs=$obs, consensus score=" +observations*.consensusScore.join(",")
    }
}