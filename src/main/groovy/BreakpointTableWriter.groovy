import java.nio.file.Files
import java.text.NumberFormat;
import java.util.stream.Stream

import gngs.ProgressCounter
import gngs.RefGenes
import gngs.SAM
import groovy.json.JsonOutput
import groovy.sql.Sql
import groovy.util.logging.Log


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


@Log
class BreakpointTableWriter {
    
    BreakpointDatabaseSet databases
    
    RefGenes refGene
    
    List<SAM> bams
   
    OptionAccessor options
    
    /**
     * Maximum number of samples for which individual samples will be annotated
     * in JSON output when a soft clip is observed in the database
     */
    final static int SAMPLE_ANNOTATION_LIMIT = 5
    
    /**
     * List of headers for the columns in the output table
     */
    private static final List<String> headers = [
            "chr",
            "start",
            "end",
            "sample",
            "depth",
            "sample_count",
            "cscore",
            "partner",
            "genes",
            "cdsdist"
    ]
        
    static List<String> HTML_ASSETS = [
        'DOMBuilder.dom.min.js',
        'd3.js',
        'date_fns.js',
        'errors.css',
        'goldenlayout-base.css',
        'goldenlayout-light-theme.css',
        'goldenlayout.js',
        'grails.css',
        'jquery-2.2.0.min.js',
        'jquery.dataTables.min.css',
        'jquery.dataTables.min.js',
        'store.modern.min.js',
        'main.css',
        'models.js',
        'nv.d3.css',
        'nv.d3.js',
        'report.html',
        'schism.css',
        'schism.js',
        'views.js',
        'vue.js',
        'vue-js-modal.js',
        'vuetiful.css',
        'vuetiful.js',
        'underscore.js'
    ]
    
    void copyStaticAssetsToHTMLDir(File htmlDir) {
        htmlDir.mkdirs()
        for(String asset in HTML_ASSETS) {
            File outFile = new File(htmlDir, asset)
            log.info "Copy: $asset => $outFile"
            FindNovelBreakpoints.class.classLoader.getResourceAsStream(asset).withStream { ins ->
                outFile.withOutputStream { outs -> 
                    Files.copy(ins, outs)
                }
            }
        }
    }
    
    /**
     * Write breakpoints out to a file, including optionally to HTML
     * 
     * @param options
     * @param refGenes
     * @param output
     */
    void outputBreakpoints(Stream<BreakpointInfo> breakpoints,
                           int breakpointCount,
                           def output) {
        
        NumberFormat fmt = NumberFormat.getIntegerInstance()
        fmt.maximumFractionDigits = 3
        
        NumberFormat percFormat = NumberFormat.getPercentInstance()
        percFormat.maximumFractionDigits = 1
        
        Writer jsonWriter = null
        File htmlFile = null
        if(options.html) {
            
            File htmlDir = new File(options.html).absoluteFile
            copyStaticAssetsToHTMLDir(htmlDir)
            
            htmlFile = new File(htmlDir, 'index.html').absoluteFile
            jsonWriter = new File(htmlDir, 'breakpoints.js').newWriter()
            if(options.localBamPath) {
                jsonWriter.println('defaultBamFilePrefix = ' + JsonOutput.toJson(options.localBamPath) + ';')
            }
            else {
                jsonWriter.println('defaultBamFilePrefix = null;')
            }
            
            Map<String,String> bamFilesBySample = bams.collectEntries { 
                [it.samples[0], (options.localBamPath?:'') + it.samFile.name]
            }
            jsonWriter.println('bamFiles = ' + JsonOutput.toJson(bamFilesBySample))
                
            jsonWriter.println('js_load_data = [')
        }
                
        output.println(headers.join('\t'))
        
        List<String> jsonHeaders = headers + ["samples"]
        
        boolean first = true
        int count = 0
        Set<String> genes = new HashSet()
        
        ProgressCounter progress = new ProgressCounter(withTime:true, withRate:true, extra: {
            percFormat.format((double)(count+1)/(breakpointCount+1)) + " complete" 
        })
        
        for(BreakpointInfo bp in breakpoints) {
            
            boolean verbose = false
            

            // Note that in this case we are searching only a single sample,
            // so each breakpoint will have exactly 1 observation
            BreakpointSampleInfo bpo = bp.observations[0]
           
            // if there is a consensus among the soft clip, check if there is a partner sequence
            // inthe database
            BreakpointInfo partner = bpo.partner
            
            // Check for overlapping genes
            List breakpointLine = [bp.chr, bp.pos, bp.pos+1, bpo.sample, bpo.obs, bp.sampleCount, fmt.format(bpo.consensusScore/bpo.bases.size()), partner?"$partner.chr:$partner.pos":""]
            if(refGene) {
                bp.annotateGenes(refGene, 5000)
                String geneList = bp.genes.join(",")
                breakpointLine.add(geneList)
                breakpointLine.add(bp.exonDistances.join(","))
            }
            
            output.println(breakpointLine.join('\t'))
            
            genes.addAll(bp.genes)
            
            if(jsonWriter) {
                
                if(count)
                    jsonWriter.println(',')
                
                if(refGene) {
                    breakpointLine[-2] = bp.genes
                    breakpointLine[-1] = bp.exonDistances
                }
                else {
                   breakpointLine.add('') 
                   breakpointLine.add('') 
                }
                
                // if less than SAMPLE_ANNOTATION_LIMIT samples, then find the samples
                // and annotate them
                // TODO: do this in background while search is running so we don't slow down this process?
                List<String> samples
                if(bp.sampleCount < SAMPLE_ANNOTATION_LIMIT) {
                    samples = databases.collectFromDbs(bp.chr, bp.pos) { Sql db ->
                        db.rows("""select sample from breakpoint_observation where bp_id = $bp.id limit $SAMPLE_ANNOTATION_LIMIT""")*.sample
                    }.sum()
                }
                
                if(samples == null)
                    samples = []
                
                jsonWriter.print(
                    JsonOutput.toJson(
                        [jsonHeaders, breakpointLine + [samples]].transpose().collectEntries()
                    ) 
                )
                
            }
            progress.count()
            
            ++count
        }
        
        if(options.bed) {
            new File(options.bed).withWriter { w ->
                for(BreakpointInfo bp in breakpoints) {
                    w.println([bp.chr, bp.pos - 100, bp.pos + 100, bp.observations[0].sample].join('\t'))
                }
            }
        }
        
        
        if(jsonWriter) {
            jsonWriter.println('\n]')
            writeGeneInfo(jsonWriter,genes)
            jsonWriter.close()
        }
        
        progress.end()
    }
    
    void writeGeneInfo(Writer jsonWriter, Set<String> genes) {
        
        assert refGene != null
        
        log.info "Writing meta data for ${genes.size()} genes: $genes"
        
        Map geneInfo = genes.collectEntries {  gene ->
            def exons = refGene.getExons(gene)
            [ 
              gene, 
              [
                  exons: exons.collect {[ from: it.from, to: it.to]},
                  strand: exons.getAt(0)?.strand
              ]
            ]
        }
        
        jsonWriter.println("genes = " + JsonOutput.toJson(geneInfo) + ";")
    }
}
