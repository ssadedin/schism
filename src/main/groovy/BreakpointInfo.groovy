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