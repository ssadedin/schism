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

import java.util.logging.FileHandler
import java.util.logging.Level;
import java.util.logging.Logger;

import gngs.BED
import gngs.Cli
import gngs.FASTA
import gngs.Region
import gngs.Regions
import gngs.SAM
import gngs.SimpleLogFormatter
import gngs.Utils
import groovy.sql.Sql
import groovy.time.TimeCategory;
import groovy.util.logging.Log
import groovyx.gpars.GParsPool;

/**
 * My 3rd attempt at writing a scalable, efficient breakpoint database creator
 * 
 *  * using actors
 *  * using more finegrained streaming
 *  
 * @author simon
 *
 */
@Log
class CreateBreakpointDB {
    
    static int chunkBp = 200000
    
    static long startTimeMs = System.currentTimeMillis()
    
    static void main(String [] args) {
        
        Banner.banner()
        
        Cli cli = new Cli(usage:"builddb <options> <bam file 1> [<bam file 2> ...]")
        cli.with {
            db 'Database file to create', args: 1, required: true
            chunkSize 'Size of chunks to process genome in', args:1, required:false
            t 'Number of threads to use (<= number of samples)', args:1, required:false
            r 'Number of retries if error occurs scanning region', args:1, required:false
            s 'Write run statistics to file', args:1, required: false
            sampleids 'List of comma separated sample ids to associate to BAM files (default: used sample id from BAM file)', args:1, required:false
            adapter 'Set adapter sequence to <arg> for filtering out adapter contamination', args:1, required:false
            fresh 'Overwrite / recreate existing database instead of adding to it', required:false
            region 'Region to process (if not provided, all contigs in first BAM file)', args: Cli.UNLIMITED
            mask 'Mask containing regions to exclude (skip over these regions)', args:1, required: false
            ref 'Reference sequence (optional) to annotate reference sequence at each breakpoint', args:1, required: false
            v 'Verbose logging of filtering decisions (appears in file <db>.log)', required:false
            'L' 'Mask of regions to include (scan these interesected with that specified by -region)', args:1, required: false
        }
        
        def opts = cli.parse(args)
        if(!opts) {
            System.exit(1)
        }
        
        Utils.configureSimpleLogging()
        
        int concurrency = opts.t ? opts.t.toInteger() : 1
        
        List<SAM> bams = opts.arguments().collect { new SAM(it) }
        
        File dbFile = new File(opts.db)
        if(opts.fresh && dbFile.exists()) {
            log.info "Deleting existing database $opts.db due to use of -fresh flag"
            if(!dbFile.delete())
                throw new RuntimeException("Unable to delete existing database $dbFile.absolutePath")
        }
        
        List<Region> regions = resolveRegions(opts, bams)
       
        int retries = opts.r ? opts.r.toInteger() : 0
        
        List samples = opts.sampleids ? opts.sampleids.tokenize(',') : bams.collect { it.samples[0] }
        
        log.info "Samples are: " + samples.join(",")
        
        Logger filterLog = createFilterLog(dbFile)
        
        WriteBreakpointDBActor dbWriter = new WriteBreakpointDBActor(samples, dbFile)
        dbWriter.filterLog = filterLog
        
        if(opts.ref) {
            if(opts.ref == "auto") {
                // hack: if we are using CRAM we can just use the same reference as for that.
                dbWriter.reference = new FASTA(System.getProperty("samjdk.reference_fasta"))
                
                // TODO: if no property, get reference from BAM file?
            }
            else 
                dbWriter.reference = new FASTA(opts.ref)
        }
        
        dbWriter.start()
        dbWriter.send "init"
        
        dbWriter.send([bams: bams])
        
        String adapterSeq = opts.adapter ?: null
        
        List breakpointExtractors = scanBAMRegions(regions, concurrency, bams, dbWriter, filterLog, opts.v, retries, adapterSeq, samples)
        
        dbWriter.send("end")
        log.info("Waiting for db writer to finalize")
        
        dbWriter.join()
        log.info("Finished")
        
        writeRunStatistics(opts, breakpointExtractors)
    }
    
    
    static Logger createFilterLog(File outputFile) {
        Logger filterLog = Logger.getLogger("Filter")
        filterLog.useParentHandlers = false
        FileHandler filterHandler = new FileHandler(outputFile.path + ".log")
        filterHandler.setLevel(Level.INFO)
        filterHandler.setFormatter(new SimpleLogFormatter())
        filterLog.addHandler(filterHandler)
        filterLog
    }
    
    
    final static int minRegionSize = 500

    /**
     * Resolve a list of regions from the genome which should be scanned, based on 
     * the options specified by the user, and a pre-configured chunk size for processing
     * the genome. 
     * 
     * @param opts  options object containing one or more of <code>L</code> (region to run over), 
     *              and <code>mask</code>.
     *              
     * @param region    region to use as a base for producing sub-regions
     * @return
     */
    private static List resolveSubRegions(def opts, Region region) {
        
        log.info "Resolving sub regions of $region"
        
        // First decide the chunks
        List<Region> subRegions = (region.from..region.to).step(chunkBp).collect { from ->
            int end = Math.min(region.to, (from+chunkBp))
            log.info "Sub region $from - $end"
            if(end - from > minRegionSize)
                new Region(region.chr, from..<end)
            else
                null
        }.grep { it != null }
        
        // Mask out excluded regions
        List<Region> maskedRegions = subRegions
        if(opts.mask) {
            // Load the BED file
            BED bed = new BED(opts.mask).load()
            maskedRegions = subRegions.collect { Region r ->
                bed.subtractFrom(r)
            }.flatten().grep { 
                it && it.size() > minRegionSize
            }
            log.info "${maskedRegions*.size().sum()}bp/${region.size()}bp remaining after excluding masked regions"
        }
        
        
        // Intersect with inclusion mask
        List<Region> finalRegions = maskedRegions
        if(opts.L) {
            BED bed = new BED(opts.L).load()
            finalRegions = finalRegions.collect { Region r ->
                bed.intersect(r).collect { new Region(region.chr, it.from..it.to) }
            }.flatten().grep { 
                it && it.size() > minRegionSize
            }
            log.info "${finalRegions*.size().sum()}bp/${region.size()} remaining after intersecting with included regions"
        }
        
        
        if(finalRegions.isEmpty())
            finalRegions = []
        
        log.info "Resolved ${finalRegions.size()} regions beginning with ${finalRegions[0]} and ending with ${finalRegions[-1]}"
        
        return finalRegions
    }

    /**
     * The main routine that runs the scan over each BAM file
     * 
     * @param subRegions
     * @param concurrency
     * @param bams
     * @param dbWriter
     * @return  List of breakpoint extractors created
     */
    private static List scanBAMRegions(List subRegions, int concurrency, List bams, WriteBreakpointDBActor dbWriter, Logger filterLog, boolean verbose, int retries, String adapterSeq, List<String> samples) {
        
        List breakpointExtractors = []
        int subRegionCount = 0
        for(Region subRegion in subRegions) {
            log.info("Processing subregion $subRegion (size=${subRegion.to - subRegion.from})")
            List bpes = [bams,samples].transpose().collect { bamSampleIdPair ->
                
                SAM bam = bamSampleIdPair[0]
                String sampleId = bamSampleIdPair[1]
                
                BreakpointExtractor bpExtractor = new BreakpointExtractor(bam, sampleId) 
                bpExtractor.breakpointListener = dbWriter
                bpExtractor.filter.filterLog = filterLog
                if(verbose)
                    bpExtractor.filter.verbose = true
                    
                if(adapterSeq != null)
                    bpExtractor.filter.setAdapterSequence(adapterSeq)
                    
                bpExtractor
            }
            breakpointExtractors.addAll(bpes)
                
            if(concurrency  > 1) {
                GParsPool.withPool(concurrency) {
                    bpes.eachParallel { BreakpointExtractor bpe ->
                        bpe.run(subRegion, retries)
                    }
                }
            }
            else {
                for(BreakpointExtractor bpe in bpes) {
                    bpe.run(subRegion, retries)
                }
            }
            ++subRegionCount
            log.info "Number of open files = " + Utils.getNumberOfOpenFiles()
            log.info "Finished region $subRegion ($subRegionCount / ${subRegions.size()}): flushing database"
            dbWriter.send("flush")
        }
        return breakpointExtractors
    }
    
    static void writeRunStatistics(opts, List<BreakpointExtractor> bpes) {
        
        def stats = [
            runtime: TimeCategory.minus(new Date(), new Date(startTimeMs)),
            breakpoints: bpes*.countInteresting.sum(),
            noisy:  bpes*.countNoisy.sum(),
            contam: bpes*.filter*.countContam.sum(),
            lowqual:bpes*.filter*.countLowQual.sum(),
            adapter:bpes*.filter*.countAdapter.sum()
        ]
        
        String statsValue = stats.collect { name, value ->
            name.padRight(20) + ": " + value
        }.join('\n')
        
        println " Statistics ".center(80,"=")
        println statsValue
        
        if(opts.s)
            new File(opts.s).text = statsValue
        
    }
    
    static List<Region> resolveRegions(OptionAccessor opts, List<SAM> bams) {
        
        List regionValues = []
        if(opts.regions) 
            regionValues = opts.regions
        else {
            // Use all the contigs in the first BAM file
            regionValues = bams[0].contigs*.key.grep { !Region.isMinorContig(it) }
            log.info "Inferred contigs to process from bam file: " + regionValues
        }
        
        List<Region> subRegions = regionValues.collect { String regionValue ->
            Region region = resolveRegion(regionValue, bams, opts.db)
            
            log.info "Adding scanned region $region"
            
            resolveSubRegions(opts, region)
            
        }.flatten()
        
        Regions result = new Regions(subRegions).reduce()
        
        if(result.size() < 1) {
            log.info "Region to scan is empty: database already processed? Use -fresh to force recreation"
            System.exit(0)
        }
        
        return result as List
    }
    

    private static Region resolveRegion(String regionValue, List bams, String databaseFile) {
        // If region is just a chromosome then look for the chromosome length in the first bam file
        if(!regionValue.contains(":")) {
            regionValue = regionValue + ":0-" + bams[0].contigs[regionValue]
        }
        
        Region region = new Region(regionValue)
        
        // If database exists, determine the maximum breakpoint and resume from there
        if(new File(databaseFile).exists()) {
            log.info "Database file $databaseFile already exists: scanning will start from highest breakpoint"
            Sql db = Sql.newInstance("jdbc:sqlite:$databaseFile")
       
            try {
                Integer pos = db.firstRow("select max(pos) as pos_max from breakpoint where pos > $region.from and pos < $region.to")     
                            .pos_max
                if(pos != null && pos < region.to) {
                    log.info "Skipping ${pos - region.from} bases due to these positions already being present in database"
                    region = new Region(region.chr, (pos+1)..region.to)
                    if(region.to < region.from)
                        region.to = region.from
                }
            }
            finally  {
                db.close()
            }
            
        }
        
        return region
    }
}
