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
package schism

import gngs.*
import groovy.util.logging.Log

/**
 * Annotates a VCF containing (potentially) break ends with obs counts from schism at the location.
 * 
 * @author simon.sadedin
 */
@Log
class VCFAnnotator extends ToolBase {

    @Override
    public void run() {
        
        VCF vcf = new VCF(opts.i)
        
        Map contigs = 
            vcf.getContigInfo()
               .grep { Map.Entry e ->
                   !Region.isMinorContig(e.value.chr)
               }
               .collectEntries {
                   [it.value.chr, it.value.length]
               }
               
        log.info "VCFAnnotator processing $opts.i with breakpoint database $opts.dbs"
        
        BreakpointDatabaseSet breakpoints =  new BreakpointDatabaseSet(opts.dbs*.absolutePath, contigs)
        
        int range = opts.r ?: 2
        
        ProgressCounter counter = new ProgressCounter(withRate: true, withTime: true, lineInterval: 100)

        VCF.filter(opts.i) { Variant v ->
            log.info "Processing variant $v"
            v.update('Schism breakpoint frequency annotation') {
                List bps = (-range..range).collect { offset -> breakpoints.getBreakpointData(v.chr, v.pos+offset) }
                if(v.info.SVLEN) {
                    int endPoint = (v.pos + v.info.SVLEN.toInteger())
                    List endBps = (-range..range).collect { offset -> breakpoints.getBreakpointData(v.chr, endPoint+offset) }
                    bps.addAll(endBps)
                }
                
                v.info.SCHISM_OBS = bps*.obs.join(',')
                v.info.SCHISM_OBS_MAX = bps*.obs.max()
                v.info.SCHISM_SC = bps*.sampleCount.join(',')
                v.info.SCHISM_SC_MAX = bps*.sampleCount.max()
            }
            counter.count()
            return true
        }
        counter.end()
        log.info("Done.")
    }
    
    static void main(String [] args) {
        cli('annotatevcf -db <breakpoint database> -i <vcf file>', 'Annotate a VCF containing SV breakpoints with frequency from Schism database', args) {
            i 'VCF file to annotate', longOpt: 'input', args:1, type: File, required: true
            r 'Range to scan upstream / downstream to annotate breakpoint position', args:1, type: Integer, required :false
            db 'Schism Sqlite Breakpoint Datbase', args:'*', type: File, required: true
        }
    }
}
