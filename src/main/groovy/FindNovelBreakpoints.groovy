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

import java.nio.file.Files
import java.text.NumberFormat;

import groovy.json.JsonOutput
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Log;
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;;

/**
 * Search for breakpoints in a BAM file, filtered against the given database of known
 * breakpoints to a given frequency (count).
 * 
 * @author ssadedin@broadinstitute.org
 */
@Log
class FindNovelBreakpoints extends DefaultActor {
    
    /**
     * Options - could be Map or OptionAccessor
     */
    def options
    
    // Filtering parameters
    int minDepth = 3 
    int maxSampleCount = 10
    
    // Statistics
    int total = 0
    int nonFiltered = 0
    int tooCommon = 0
    int partnered = 0
    int mapQ = 0
    
    /**
     * Maximum number of samples for which individual samples will be annotated
     * in JSON output when a soft clip is observed in the database
     */
    final static int SAMPLE_ANNOTATION_LIMIT = 5
    
    String chrPrefix = null
    
    Regions databases = new Regions()
    
    /**
     * Optional refgene instance to annotate genes for breakpoints
     */
    RefGenes refGene = null
    
    // BAM file to scan
    SAM bam
    
	/**
	 * Optional reference sequence that will be used to identify partnered breakpoints
	 */
    FASTA reference = null
    
    // Sample contained in BAM file
    String sample = null
    
    List<String> excludeSamples
	
	List<Sql> databaseConnections
    
	/**
	 * The output to write to - an object supporting println
	 * (PrintStream, Writer, etc)
	 */
    def output
    
    /**
     * Create a breakpoint finder based on the given options
     * 
     * @param options
     * @param dbFiles
     */
    FindNovelBreakpoints(def options, String bamFile, List<String> dbFiles) {
        this.options = options
        if(options.mindp)
            this.minDepth = options.mindp.toInteger()
        if(options.maxsc)
            this.maxSampleCount = options.maxsc.toInteger()
            
        if(options.o) {
            File outputFile = new File(options.o)
            log.info "Output file is " + outputFile.absolutePath
            this.output = new PrintStream(outputFile.newOutputStream())
        }
        else
            this.output = System.out
            
        log.info "Opening databases ..."
        List<Sql> dbs = dbFiles.collect {  dbFile ->
            Sql db = Sql.newInstance("jdbc:sqlite:${dbFile}")
            db.cacheConnection = true
            db.cacheStatements = true
            db
        }
		
		this.databaseConnections = dbs
        
        log.info "Checking bam file $bamFile"
        bam = new SAM(bamFile)
        if(bam.samples.unique().size() > 1)
            throw new IllegalArgumentException("This tool supports only single sample BAM files. The given BAM file has samples " + bam.samples.join(","))
            
        log.info "Determining database ranges ..."
        this.databases = determineDatabaseRanges(dbs)
        
        log.info "Found sample ${bam.samples[0]} in bam file" 
        this.sample = bam.samples[0]
        
        if(options.xss) {
            this.excludeSamples = options.xss as List
            this.excludeSamples.add(this.sample)
        }
    }
    
    Regions determineDatabaseRanges(List<Sql> databases) {
        
        this.chrPrefix = bam.contigs*.key.any { it.startsWith('chr') } ? "chr" : ""
        
        Regions dbRegions = new Regions()
        def contigs = bam.getContigs()
        for(Sql db in databases) {
            def minmax = db.firstRow("select min(id) as min_bp, max(id) as max_bp from breakpoint;")
            Region minRegion = XPos.parsePos(minmax.min_bp)
            Region maxRegion = XPos.parsePos(minmax.max_bp)
            
            log.info "Database $db spans $minRegion - $maxRegion"
            
            if(minRegion.chr == maxRegion.chr)
                dbRegions.addRegion(dbRegion(minRegion.chr, minRegion.from,maxRegion.to, db))
            else
                dbRegions.addRegion(dbRegion(minRegion.chr, minRegion.from, contigs[chrPrefix+minRegion.chr], db))
            
            for(i in XPos.chrToInt(minRegion.chr)..<XPos.chrToInt(maxRegion.chr)) {
                def chr = chrPrefix + XPos.intToChr(i)
                if(contigs[chrPrefix + chr])
                    dbRegions.addRegion(dbRegion(chr, 0, contigs[chrPrefix + chr], db))
            }
            dbRegions.addRegion(dbRegion(maxRegion.chr, 0,maxRegion.to, db))
        }
        return dbRegions
    }
    
    Region dbRegion(String chr, int from, int to, Sql db) {
        if(chrPrefix && !chr.startsWith(chrPrefix))
            chr = chrPrefix + chr
            
        Region r = new Region(chr, from..to)
        r.db = db
        r
    }
    
    void act() {
        loop {
            react { msg ->
                if(msg instanceof BreakpointMessage) {
                    processBreakpoint(msg)
                }
                else
                if(msg == "end") {
                    terminate()
                }
            }
        }
    }
    
    Map<String,BreakpointInfo> breakpointPartners = [:]
    
    List<BreakpointInfo> breakpoints = []
    
    int partnerIndexBases = 5
    
    int softClipSize = 15
    
    int indexLength = softClipSize-partnerIndexBases
    
    void processBreakpoint(BreakpointMessage msg) {
        ++total
          
        int freq = isFiltered(msg)
        if(freq < 0)
            return
            
        ++nonFiltered 
        
        boolean verbose = false
//        if(msg.pos == 19914056 || msg.pos == 19914394) {
//            println "Debug breakpoint: $bpInfo"
//            verbose = true
//        }
//        
        Long bpId = XPos.computePos(msg.chr, msg.pos)
        BreakpointInfo bpInfo = new BreakpointInfo(id: bpId, chr: msg.chr, pos: msg.pos, sampleCount: freq)

        // Check if any other breakpoint ties to this one
        bpInfo.add(msg)
        breakpoints.add(bpInfo)
        
        // Index this breakpoint
        if(reference) {
            String reference = bpInfo.queryReference(reference, softClipSize)
            for(int i=0; i<partnerIndexBases; ++i) {
                String indexKey = reference.substring(i, i+indexLength)
                if(verbose)
                    log.info "Adding indexed ref seq " + indexKey
                breakpointPartners[indexKey] = bpInfo
            }
        }
    }
    
    /**
     * Checks if the given breakpoint should be filtered out
     * <p>
     * If filtered out, returns -1, else returns the frequency of the
     * breakpoint in the configured breakpoint databases.
     */
    @CompileStatic
    int isFiltered(BreakpointMessage msg) {
        List<SAMRecord> reads = msg.reads
        
        if(reads.size() < minDepth)
            return -1

        int freq = getBreakpointFrequency(msg.chr, msg.pos)
        if(freq > maxSampleCount) {
            ++tooCommon
            return -1
        }

        if(msg.reads.every { SAMRecord r -> r.mappingQuality == 0 }) {
            ++mapQ
            return -1
        }
        
        // Not filtered out
        return freq
    }
    
    @CompileStatic
    void partnerBreakpoints() {
        for(BreakpointInfo bp in breakpoints) {
            
            boolean verbose = false
//            if(bp.pos == 19914056 || bp.pos == 19914394) {
//                println "Debug breakpoint: $bp with bases: " + bp.observations[0].bases
//                verbose = true
//            }

            // if there is a consensus among the soft clip, check if there is a partner sequence
            // inthe database
            BreakpointInfo partner = null
            BreakpointSampleInfo bpsi = bp.observations[0]
            if(bpsi.consensusScore > 10.0) { // a bit arbitrary
                final int maxSearchIndex = Math.min(bpsi.bases.size() - indexLength, 5)
                for(int i=0; i<maxSearchIndex; ++i) {
                    String softClippedBases = bpsi.bases[i..<(indexLength+i)]
                    if(verbose)
                        println "looking up $softClippedBases"
                        
                    partner = breakpointPartners[softClippedBases]
                    if(partner && (partner.id != bp.id)) {
                        // log.info "Found partner for $bp.id: " + partner
                        bpsi.partner = partner
                        ++partnered
                        break;
                    }
                }
            }
        }
        
    }
    
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
        'main.css',
        'models.js',
        'nv.d3.css',
        'nv.d3.js',
        'report.html',
        'schism.css',
        'schism.js',
        'views.js',
        'vue.js',
        'vuetiful.css',
        'vuetiful.js'
    ]
    
    void outputBreakpoints() {
        
        NumberFormat fmt = NumberFormat.getIntegerInstance()
        fmt.maximumFractionDigits = 3
        
        NumberFormat percFormat = NumberFormat.getPercentInstance()
        percFormat.maximumFractionDigits = 1
        
        Writer jsonWriter = null
        File htmlFile = null
        if(options.html) {
            
            File htmlDir = new File(options.html).absoluteFile
            htmlDir.mkdirs()
            
            for(String asset in HTML_ASSETS) {
                File outFile = new File(htmlDir, asset)
                log.info "Copy: $asset => $outFile"
                getClass().classLoader.getResourceAsStream(asset).withStream { ins ->
                    outFile.withOutputStream { outs -> 
                        Files.copy(ins, outs)
                    }
                }
            }
            
            htmlFile = new File(htmlDir, 'index.html').absoluteFile
            jsonWriter = new File(htmlDir, 'breakpoints.js').newWriter()
            jsonWriter.println('js_load_data = [')
        }
                
        output.println(headers.join('\t'))
        
        List<String> jsonHeaders = headers + ["samples"]
        
        boolean first = true
        int count = 0
        
        ProgressCounter progress = new ProgressCounter(withTime:true, withRate:true, extra: {
            percFormat.format((double)(count+1)/(breakpoints.size()+1)) + " complete" 
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
                    samples = collectFromDbs(bp.chr, bp.pos) { Sql db ->
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
            jsonWriter.close()
        }
        
        progress.end()
        
    }
    
    boolean warnedAboutNoOverlap = false
    
    @CompileStatic
    Object collectFromDbs(String chr, int pos, Closure c) {
        List<Sql> dbs = (List<Sql>)databases.getOverlaps(chr, pos, pos+1).collect { r ->
            GRange gr = (GRange)r
            Expando e = (Expando)gr.extra
            e.getProperty('db')
        }.unique {System.identityHashCode(it)}
        
        if(!dbs && !warnedAboutNoOverlap) {
            log.info "WARNING: No provided databases overlap breakpoint $chr:$pos"
            warnedAboutNoOverlap = true
        }
            
        return dbs.collect(c)
    }
    
    
    @CompileStatic
    int getBreakpointFrequency(String chr, int pos) {
        List<Sql> dbs = (List<Sql>)databases.getOverlaps(chr, pos, pos+1).collect { r ->
            GRange gr = (GRange)r
            Expando e = (Expando)gr.extra
            e.getProperty('db')
        }.unique {System.identityHashCode(it)}
        
        if(!dbs) {
            if(!warnedAboutNoOverlap) {
                log.info "WARNING: No provided databases overlap breakpoint $chr:$pos"
                warnedAboutNoOverlap = true
            }
            return 0
        }
        
            
        return (int)dbs.collect { db ->
            getDbBreakpointFrequency(db, chr,pos)
        }.sum()?:0
    }
    
    @CompileStatic
    int getDbBreakpointFrequency(Sql db, String chr, int pos) {
        long breakpointId = XPos.computePos(chr, pos)
        def dbBreakpoint = db.firstRow("select sample_count from breakpoint where id = $breakpointId")        
        if(dbBreakpoint == null)
            return 0
        else {
            def excludeResult
            if(excludeSamples) {
                db.firstRow("""
                    select count(1) as exclude_count from breakpoint_observation 
                    where id = $breakpointId 
                    and sample in (""" + "${excludeSamples.join("','")})" + """
                """)
            }
            else {
                excludeResult = db.firstRow("""
                   select count(1) as exclude_count from breakpoint_observation where id = $breakpointId and sample=$sample
                """)        
            }
            
            int excludeCount = excludeResult ? (int)excludeResult.exclude_count : 0
            
            return ((int)dbBreakpoint.sample_count) - excludeCount
        }
    }
    
    void run(Regions regionsToAnalyse=null) {
        
        log.info "Searching for breakpoints supported by at least ${minDepth} reads and observed in fewer than ${maxSampleCount} samples in control database"
        
        List<Region> includedRegions 
        if(regionsToAnalyse) {
            includedRegions = regionsToAnalyse as List
        }
        else {
            List<Region> regions = bam.contigs.collect { new Region(it.key, 0..it.value) }
            
            // Filter to include only 
            //
            // a) major contigs
            // b) contigs which have at least one read
            //
            log.info "Probing for included regions ..."
            includedRegions = regions.grep { r ->
                def chr = r.chr.replaceAll('^chr','')
                !chr.startsWith('Un') && !chr.startsWith('GL') && !chr.startsWith('NC')
            }.grep { Region r ->
                bam.withIterator(r) { it.hasNext() }
            }
            
            log.info "BAM file includes regions $includedRegions"
        }
        
        for(region in includedRegions) {
            def bpe = new BreakpointExtractor(bam)
            bpe.breakpointListener = this
            if(options.adapter) 
                bpe.filter.setAdapterSequence(options.adapter)
            bpe.run(region)
        }

        this.send "end"
        this.join()
        
        log.info "Partnering ${breakpoints.size()} breakpoints ..."
        this.partnerBreakpoints()
    }
	
	/**
	 * Close database connections
	 */
	void close() {
		if(this.databaseConnections) {
			databaseConnections.each { db ->
				try {
					db.close()
				}
				catch(Exception e) {
					// Ignore	
					log.warning("Failed to close database connection: " + e)
				}
			}
		}
	}
    
    static void main(String [] args) {
        Cli cli = new Cli(usage: "FindNovelBreakpoints <options>")
        cli.with {
            bam 'BAM file to scan' ,args: 1, required:true
            db 'Breakpoint database(s)', args:Cli.UNLIMITED, required:false
            dbdir 'Breakpoint database directory - all files ending with .db are treated as databases', required: false, args:1
            mindp 'Minimum depth of breakpoints to report (3)', args:1, required: false
            maxsc 'Maximum sample count of breakpoints to report', args: 1, required: false
            region 'Region of genome to analyse', args:Cli.UNLIMITED, required:false
            ref "Reference sequence (optional) to annotate reference sequence at each breakpoint (use 'auto' to try and find it automatically via various means)", args:1, required: false
            xs 'Exclude given samples from sample counts', args:Cli.UNLIMITED, required:false
            adapter 'Set sequence used to remove adapter contamination', args:1, required:false
            pad 'Number of base pairs to pad any regions specified with', args:1, required: false
            gene 'Scan the given gene (HGNC symbol)', args:Cli.UNLIMITED, required: false
            genelist 'Scan the given file of genes (HGNC symbols)', args:1, required:false
            mask 'BED file containing regions to intersect scan regions with', args:1, required:false
            bed 'Write out a BED file containing regions of breakpoints found (100bp padding)', args:1, required:false
            o 'Output file (BED format)', longOpt: 'output', args: 1, required: false
            html 'Create HTML report in given directory', args:1, required: false
            genome 'Specify genome build (if not specified, determined automatically)', args:1, required:false
        }
        
        Banner.banner()
        
        Utils.configureSimpleLogging()
       
        def opts = cli.parse(args)
        if(!opts) 
            System.exit(1)
        
        List<String> dbFiles = resolveDatabases(opts)

        if(!dbFiles) {
            System.err.println "\nNo control database could be found! Please specify one with -db, -dbdir or place one in the databases directory"
            System.exit(1)
        }
            
        SAM bam = new SAM(opts.bam)
        FindNovelBreakpoints fnb = new FindNovelBreakpoints(opts, opts.bam, dbFiles).start()
		try {
	        if(opts.ref) {
	            if(opts.ref == "auto") {
	                // hack: if we are using CRAM we can just use the same reference as for that.
	                fnb.reference = new FASTA(System.getProperty("samjdk.reference_fasta"))
	                
	                // TODO: if no property, get reference from BAM file?
	            }
	            else 
	                fnb.reference = new FASTA(opts.ref)
	        }
	        
            String genomeBuild = opts.genome ? opts.genome : bam.sniffGenomeBuild()
            
            log.info "Genome build appears to be ${genomeBuild} - if this is not correct, please re-run with -genome"
	        RefGenes refGene
            if(genomeBuild?.startsWith('hg') || genomeBuild.startsWith('GRCh'))
                refGene = RefGenes.download(genomeBuild)
                
	        Regions regions = resolveRegions(refGene, bam, opts)
	        fnb.refGene = refGene
	        
	        if(regions) {
	            fnb.run(regions)
	        }
	        else {
	            fnb.run()
	        }
	            
	        fnb.log.info(" Summary ".center(100,"="))
	        fnb.log.info "Unfiltered breakpoint candidates: " + fnb.total
	        fnb.log.info "Common breakpoints: " + fnb.tooCommon
	        fnb.log.info "Reportable breakpoints: " + fnb.nonFiltered
	        fnb.log.info "Partnered breakpoints: " + fnb.partnered
	        fnb.log.info("="*100)
	
	        fnb.outputBreakpoints()
	        
	        if(opts.o)
	            fnb.output.close()
		}
		finally {
			fnb.close()
		}
    }
    
    static Regions resolveRegions(RefGenes refGene, SAM bam, OptionAccessor opts) {
        Regions regions = null
        if(opts.regions) {
            
            regions = new Regions()
            
            for(regionValue in opts.regions) {
                Region region = null
                if(opts.region.contains(":"))
                    region = new Region(regionValue)
                else {
                    region = new Region(regionValue, 0..bam.contigs[opts.region])
                }
                regions.addRegion(region)
                log.info "Added region to scan: $region"
            }
        }
        
        regions = addGeneRegions(refGene, opts,regions)
        
        if(regions != null && opts.pad) {
            int padding = opts.pad.toInteger()
            log.info "Adding ${padding}bp to regions"
            regions = new Regions(regions.collect { Region r ->
                new Region(r.chr, Math.max(0, (r.from-padding))..(r.to + padding))
            })
        }
        
        if(regions == null) {
            if(opts.mask) {
                return new BED(opts.mask).load().reduce()
            }
            else
                return null
        }
            
        // Flatten the regions after so we don't process the same region multiple times
        regions = regions.reduce()
        
        // If there is a mask, apply it
        if(opts.mask) {
            Regions maskBED = new BED(opts.mask).load().reduce()
            regions = regions.intersect(maskBED)
        }
        
        return regions 
    }
    
    static Regions addGeneRegions(RefGenes refGene, OptionAccessor opts, Regions regions) {
        List genes = []
        if(opts.genes) 
            genes.addAll(opts.genes)
        
        if(opts.genelist) 
            genes.addAll(new File(opts.genelist).readLines()*.trim())
        
        if(!genes.isEmpty()) {
            if(regions == null)
                regions = new Regions()
            for(gene in genes) {
                Region geneRegion = refGene.getGeneRegion(gene)
                if(geneRegion == null)
                    throw new IllegalArgumentException("Gene $gene not found in RefSeq database")
                log.info "Gene $gene translated to $geneRegion"
                regions.addRegion(geneRegion)
            }
        } 
        return regions
    }

    static List<String> resolveDatabases(OptionAccessor opts) {

        if(opts.dbs)
            return opts.dbs

        def dbDir = opts.dbdir
        if(!dbDir) {
            String loadPath = FindNovelBreakpoints.class.classLoader.getResource("SAM.class").path
            String rootPath = loadPath.replaceAll('/lib/.*$','').tokenize(':')[-1]
            dbDir = new File(rootPath,"databases").absolutePath
            log.info "Searching for databases in install database directory: $dbDir"
        }

        return new File(dbDir).listFiles().grep { it.name.endsWith('.db') }*.absolutePath
    }
}
