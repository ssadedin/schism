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

/**
 * 
 * Filter a BED file
 * 
 * @author simon
 */
class FilterBEDByCoverage {
    
    static void main(String [] args) {
        
        Cli cli = new Cli(usage:"FilterBEDByCoverage <options>")
        cli.with {
            i 'Input bed file', args: 1, required:true
            o 'Output bed file', args: 1, required: true
            cov 'Minimum coverage for regions to output', args:1, required: true 
        }
        
        def opts = cli.parse(args)
        if(!opts) {
            System.exit(1)
        }
        
        int covThreshold = opts.cov.toInteger()
        BED regions = new BED(opts.i).load()
        Regions flat = regions.reduce()
        new File(opts.o).withWriter { w ->
            
            int countIn = 0
            ProgressCounter counter = 
                new ProgressCounter(withTime:true, withRate:true, extra: {"Included = $countIn"})
                
            for(Region r in flat) {
                int cov = regions.getOverlaps(r).size()
                if(cov > covThreshold) {
                    ++countIn
                    w.println([r.chr, r.from, r.to+1, cov].join('\t'))
                }
                counter.count()
            }
            counter.end()
        }
    }
}
