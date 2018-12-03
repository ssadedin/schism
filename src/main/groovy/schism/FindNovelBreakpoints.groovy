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

import java.util.logging.Level
import java.util.logging.Logger

import org.codehaus.groovy.runtime.StackTraceUtils

import gngs.BED
import gngs.Cli
import gngs.FASTA
import gngs.PrefixTrie
import gngs.ProgressCounter
import gngs.RefGenes
import gngs.Region
import gngs.Regions
import gngs.SAM
import gngs.Utils
import gngs.XPos
import graxxia.IntegerStats
import groovy.transform.CompileStatic
import groovyx.gpars.GParsPool
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.SAMRecord;;
import trie.TrieNode
import trie.TrieQuery

/**
 * Search for breakpoints in a BAM file, filtered against the given database of known
 * breakpoints to a given frequency (count).
 * 
 * @author ssadedin@broadinstitute.org
 */
class FindNovelBreakpoints extends DefaultActor {
    
    static Logger log = Logger.getLogger("FindNovelBreakpoints")
    
    /**
     * Options - could be Map or OptionAccessor
     */
    def options
    
    // Filtering parameters
    int minDepth = 3 
    int maxSampleCount = 10
    int maxPartnersForOutput = 40
    int maxPartnersToJoin = 7
    int minMedianBaseQualityScore = 15
    
    // Statistics
    int total = 0
    int nonFiltered = 0
    int tooCommon = 0
    int tooPromiscuous = 0
    int mapQ = 0
    
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
    
    BreakpointDatabaseSet databaseSet = null
    
    /**
     * Set to true while running, then false after the "end" message is processed
     */
    String phase = "none"
    
    BreakpointConnector breakpointConnector 
	
    /**
     * Create a breakpoint finder based on the given options
     * 
     * @param options
     * @param dbFiles
     */
    FindNovelBreakpoints(def options, String bamFile, BreakpointDatabaseSet databaseSet) {
        this.options = options
        if(options.mindp)
            this.minDepth = options.mindp.toInteger()
        if(options.maxsc)
            this.maxSampleCount = options.maxsc.toInteger()
            
        log.info "Checking bam file $bamFile"
        bam = new SAM(bamFile)
        if((bam.samples.unique().size() > 1) && !options.multi)
            throw new IllegalArgumentException("This tool supports only single sample BAM files. The given BAM file has samples " + bam.samples.join(",") + ". Use -multi flag to treat all these as the same sample.")
            
        log.info "Found sample ${bam.samples[0]} in bam file" 
        this.sample = bam.samples[0]
        
        this.databaseSet = databaseSet
        
        this.breakpointConnector = 
            new BreakpointConnector(
                maxPartnersForOutput: maxPartnersForOutput,  
                partnerIndexBases: partnerIndexBases, 
                softClipSize: softClipSize, 
                maxPartnersToJoin: maxPartnersToJoin
            )
    }
    
    FindNovelBreakpoints() {
    }
    
    /**
     * Aysynchronous callback to receive messages about discovered breakpoints
     */
    @CompileStatic
    void act() {
        loop {
            react { msg ->
                if(msg instanceof BreakpointMessage) {
                    processBreakpoint(msg)
                }
                else
                if(String.valueOf(msg).startsWith("phase:")) {
                    this.phase = String.valueOf(msg).replaceAll('^phase:','')
                }
                else
                if(msg == "end") {
                    terminate()
                    this.phase = "finished"
                }
            }
        }
    }
    
    Map<String,BreakpointInfo> breakpointPartners = [:]
    
    PrefixTrie<BreakpointInfo> breakpointSequenceIndex = new PrefixTrie()
    
    List<BreakpointInfo> breakpoints = []
    
    int partnerIndexBases = 5
    
    int softClipSize = 15
    
    int indexLength = softClipSize-partnerIndexBases
    
    int errorCount = 0
    
    private final static long debugPos = 101791685
//    private final static long debugPos = 101791695
    
    void processBreakpoint(BreakpointMessage msg) {
        ++total
        
        boolean verbose = false
        if(msg.pos == debugPos) {
            println "Debug breakpoint: $msg"
            verbose = true
        }
            
        int freq = isFiltered(msg)
        if(freq < 0)
            return
            
        try {
            Long bpId = XPos.computePos(msg.chr, msg.pos)
            
            BreakpointInfo bpInfo = new BreakpointInfo(id: bpId, chr: msg.chr, pos: msg.pos, sampleCount: freq)
    
            // Check if any other breakpoint ties to this one
            bpInfo.add(msg)
            
            // Index this breakpoint
            addBreakpoint(bpInfo, verbose)
        }
        catch(Exception e) {
            ++errorCount
            log.warning "Failed to add breakpoint at $msg.chr:$msg.pos: " + e
            if(errorCount < 3)
                log.log(Level.SEVERE, "Failed to add breakpoint at $msg.chr:$msg.pos: ", e)
        }
    }
    
    @CompileStatic
    void addBreakpoint(BreakpointInfo bpInfo, boolean verbose) {
        long bpId = bpInfo.id
        
        int bqMedian = bpInfo.observations[0].baseQuals.median
        if(bqMedian < this.minMedianBaseQualityScore) {
            log.info "Breakpoint $bpId has median base quality $bqMedian for the soft cliped bases: ignoring"
            return
        }
        
        if(reference) {
            String [] reference = bpInfo.queryReference(reference, softClipSize)

            // If opposite side of breakpoint is entirely homopolymer sequence
            // then ignore this breakpoint - while such breakpoints could be real,
            // in practice they are nearly always sequencing or mapping artefacts caused by the
            // homopolymer run
            log.info "Ref for $bpId: " + reference.join(":")
            if(isHomopolymer(reference[1], -5) || isHomopolymer(reference[0], 5)) {
                log.info "Breakpoint $bpId is adjacent to homopolymer / low complexity sequence: ignoring"
                return
            }
            
            if(hasAdjacentNBases(reference)) {
                log.info "Breakpoint $bpId is adjacent to unknown sequence: ignoring"
                return
            }
            
            breakpointConnector.indexBreakpoint(bpInfo, reference, verbose)
        }
        
        ++nonFiltered
        breakpoints.add(bpInfo)
    }
    
    @CompileStatic
    private static boolean hasAdjacentNBases(String [] bases) {
        return bases[0].endsWith('NNN') || bases[1].startsWith('NNN')
    }
    
    @CompileStatic
    private static boolean isHomopolymer(String bases, final int endBasesCount) {
        
        // TODO: Profile / optimize based on charAt(n)
        double maxFracSameBase = bases.toUpperCase().iterator().countBy { it }.max { it.value }.value / bases.size()
        
        log.info "Bases: $bases   Homopolymer fraction: $maxFracSameBase"
        
        // Overall homopolymer
        if(maxFracSameBase > 0.8)
            return true
        
        String endBases = endBasesCount > 0 ? bases.substring(0, endBasesCount) : bases.substring(bases.size()+endBasesCount, bases.size())
        final char firstBase = endBases.charAt(0)
        final int numEndBases = endBases.size()
        for(int i=1; i<numEndBases; ++i) {
            if(endBases.charAt(i) != firstBase)
                return false
        }
        return true
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

        int freq = databaseSet.getBreakpointFrequency(msg.chr, msg.pos, this.sample)
        if(freq > maxSampleCount) {
            ++tooCommon
            return -1
        }
        
        
        IntegerStats qualStats = new IntegerStats(10,msg.reads*.mappingQuality)
        
        // No reads greater than 2?
        if(qualStats.max < 2) {
            ++mapQ
            return -1
        }
        
        // Less than two reads above qual == 1
        if(qualStats.N - (qualStats.values[0] + qualStats.values[1]) < 2) {
            ++mapQ
            return -1
        }
        
        Map<Integer, Integer> chrCounts = msg.reads.countBy { SAMRecord r -> r.mateReferenceIndex }
        
        if(chrCounts.size() > 4) {
            ++tooPromiscuous
            return -1
        }
        
        // Not filtered out
        return freq
    }
    
    /**
     * Run the analysis for the configured BAM file over the given regions.
     */
    void run(Regions regionsToAnalyse=null) {
        
        log.info "Searching for breakpoints supported by at least ${minDepth} reads and observed in fewer than ${maxSampleCount} samples in control database"
        log.info "Regions provided? " + regionsToAnalyse
        
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
                !chr.startsWith('Un') && !chr.startsWith('GL') && !chr.startsWith('NC') && !chr.startsWith('hs37')
            }.grep { Region r ->
                bam.withIterator(r) { it.hasNext() }
            }
            
            log.info "BAM file includes regions $includedRegions"
        }
        
        this.phase = "discover"
        this.runOverRegions(includedRegions)
        
        this.send "phase:extend"
       
        while(phase != "extend") {
            Thread.sleep(100)
        }
        
        log.info "Partnering ${breakpoints.size()} breakpoints ..."
        this.breakpointConnector.partnerBreakpoints()
        
        if(this.options.extend) {
            log.info "Searching for extended regions in $bam.samFile ..."
            Regions extendedRegions = this.findExtendedRegions(new Regions(includedRegions))
            log.info "Processing ${extendedRegions.numberOfRanges} extended regions (${extendedRegions.size()}bp)"
            runOverRegions(extendedRegions, false)
        }
        else {
            log.info "Exploration of extended regions is disabled"
        }
        
        this.send "end"
        this.join()
    }
    
    void runOverRegions(Iterable<Region> includedRegions, failHard=true) {
        for(region in includedRegions) {
            def bpe = new BreakpointExtractor(bam, allowMultiSample: options.multi)
            if(this.options.debugpos)
                bpe.debugPosition = this.options.debugpos.toInteger()
                
            if(this.options.debugread)
                bpe.debugRead = this.options.debugread
                
            bpe.breakpointListener = this
            if(options.adapter) 
                bpe.filter.setAdapterSequence(options.adapter)
            if(options.minclip)
                bpe.filter.minSoftClippedBases = options.minclip.toInteger()
                
            try {
                bpe.run(region)
            } catch (Exception e) {
                if(failHard) {
                    throw e
                }
                else {
                    log.warning "Failed to process region $region: $e"
                }
            }
        }
    }
    
    /**
     * Identify regions that are of interest based on linkage to discovered breakpoints
     * 
     * @return
     */
    @CompileStatic
    Regions findExtendedRegions(Regions existingRegions) {
        Regions result = new Regions()
        
        for(BreakpointInfo bp in breakpoints) {
            
            String prefix = (bp.chr.startsWith('chr') ? 'chr' : '')
            
            // does the breakpoint have multiple reads linking to an unexplored region?
            Regions bpMates = new Regions()
            for(BreakpointSampleInfo obs in bp.observations) {
                for(Long xpos in obs.mateXPos) {
                    Region r = XPos.parsePos(xpos)
                    if(!r.isMinorContig() && (r.chr != 'Unk')) {
                        bpMates.addRegion(new Region(prefix+r.chr, r.from-150, r.to+150))
                    }
                }
            }
            
            Regions mateCoverage = bpMates.coverage()
            
            // Add all the regions where at least mates
            // of breakpoint reads overlap
            mateCoverage.grep {Region r -> (int)r.extra > 2 }.each { Object robj ->
                Region r = (Region)robj
                if(!r.overlaps(existingRegions)) {
                    log.info "Found breakpoint mate region: $r for $bp"
                    result.addRegion(r.chr, Math.max(0,r.from-500), r.to+500)
                }
            }
        }
        return result.reduce()
    }
	
	/**
	 * Close any connections owned by this instance
	 */
	void close() {
//        log.info "Closing databases"
//	    this.databaseSet.close()
	}
    
    /**
     * Attempt to find / download the RefGene database for looking up regions and annotating
     * breakpoint positions.
     * 
     * @param opts  Command line options
     * @return  RefGenes object
     */
    static RefGenes getRefGene(OptionAccessor opts, SAM bam) {
        String genomeBuild = opts.genome ? opts.genome : bam.sniffGenomeBuild()
            
        log.info "Genome build appears to be ${genomeBuild} - if this is not correct, please re-run with -genome"
        RefGenes refGene
        if(genomeBuild?.startsWith('hg') || genomeBuild.startsWith('GRCh'))
            refGene = RefGenes.download(genomeBuild)
    }
    
    /**
     * Attempt to resolve a reference sequence to use for annotating breakpoint sequences
     * 
     * @param opts
     * @return  a FASTA instance representing the reference, or null
     */
    static FASTA resolveReference(OptionAccessor opts) {
        if(opts.ref) {
            if(opts.ref == "auto") {
                // hack: if we are using CRAM we can just use the same reference as for that.
                return new FASTA(System.getProperty("samjdk.reference_fasta"))
                
                // TODO: if no property, get reference from BAM file?
            }
            else 
                return new FASTA(opts.ref)
        }
    }
    
    /**
     * Return a CLI argument parser configured to parse arguments for this tool
     * 
     * @param args
     */
    static Cli getCliParser() {
        Cli cli = new Cli(usage: "FindNovelBreakpoints <options>")
        cli.with {
            bam 'BAM file to scan', args: Cli.UNLIMITED, required:true
            db 'Breakpoint database(s)', args:Cli.UNLIMITED, required:false
            dbdir 'Breakpoint database directory - all files ending with .db are treated as databases', required: false, args:1
            mindp 'Minimum depth of breakpoints to report (3)', args:1, required: false
            minclip 'Minimum amount of soft clipped bases to record a breakpoint (12)', args: 1, required: false
            maxsc 'Maximum sample count of breakpoints to report (default=10)', args: 1, required: false
            region 'Region of genome to analyse', args:Cli.UNLIMITED, required:false
            ref "Reference sequence (optional) to annotate reference sequence at each breakpoint (use 'auto' to try and find it automatically via various means)", args:1, required: false
            xs 'Exclude given samples from sample counts', args:Cli.UNLIMITED, required:false
            adapter 'Set sequence used to remove adapter contamination', args:1, required:false
            pad 'Number of base pairs to pad any regions specified with', args:1, required: false
            gene 'Scan the given gene (HGNC symbol)', args:Cli.UNLIMITED, required: false
            genelist 'Scan the given file of genes (HGNC symbols)', args:1, required:false
            mask 'BED file containing regions to intersect scan regions with', args:1, required:false
            bed 'Write out a BED file containing regions of breakpoints found (100bp padding)', args:1, required:false
            multi 'Allow multi sample BAM file (treat as single)', required:false
            idmask 'Regular expression to replace samples with first subexpression', args:1, required:false
            debugpos 'Specify a position to write verbose informationa about', args:1, required:false
            debugread 'Specify a read to write verbose informationa about', args:1, required:false
            o 'Output file (BED format)', longOpt: 'output', args: 1, required: false
            html 'Create HTML report in given directory', args:1, required: false
            genome 'Specify genome build (if not specified, determined automatically)', args:1, required:false
            n 'Number of threads to use', args:1, required:false
            localBamPath 'Prefix to path to BAM files, to enable loading in the HTML interface via IGV', args:1, required: false
            extend 'Enable exploration of extended regions linked to identified breakpoints', args:1, required: false
        }
        return cli
    }
    
    /**
     * Main starting point
     * 
     * @param args  Command line arguments
     */
    static void main(String [] args) {

        Cli cli = getCliParser()
               
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
        
        if(opts.bams.size() == 1) {
            processSingleBam(opts, opts.bams[0], dbFiles)
        }
        else {
            processMultiBams(opts, opts.bams, dbFiles)
        }
    }
    
    static processMultiBams(OptionAccessor opts, List<String> bamFilePaths, List<String> dbFiles) {
        
        log.info "Analying BAM Files: $bamFilePaths"
        
        BreakpointDatabaseSet databaseSet 
        try {
            int concurrency = opts.n ? opts.n.toInteger() : 1
            SAM bam = new SAM(bamFilePaths[0])
            
            List<FindNovelBreakpoints> fnbs
            databaseSet = getBreakpointDatabases(opts, bam, dbFiles)
            
            fnbs = GParsPool.withPool(concurrency) {
                bamFilePaths.collectParallel { String bamFilePath ->
                    analyseSingleBam(opts, bamFilePath, databaseSet)
                }
            }
                
            // Merge the outputs
            Map<Long, BreakpointInfo> mergedBreakpoints = mergeBreakpointInfos(fnbs*.breakpoints)
                    
            FindNovelBreakpoints fnb = fnbs[0]
            PrintStream output = getOutput(opts)
            try {
                new BreakpointTableWriter(
                    databases: fnb.databaseSet,
                    refGene: fnb.refGene,
                    options: opts,
                    bams: fnb*.bam
                ).outputBreakpoints(
                    mergedBreakpoints.entrySet().stream().map { it.value },
                    mergedBreakpoints.size(),
                    output
                )
            }
            finally {
                output.close()
            }

        }
        catch(Exception e) {
            StackTraceUtils.sanitize(e)
            throw e
        }
        finally {
            if(databaseSet != null)
                databaseSet.close()
        }
    }
    
    /**
     * 
     */
    static Map<Long, BreakpointInfo> mergeBreakpointInfos(List<List<BreakpointInfo>> breakpoints) {
        log.info "Merging ${breakpoints*.size().sum()} breakpoints from ${breakpoints.size()} sets"
        Map<Long, BreakpointInfo> result = new TreeMap()
        ProgressCounter progress = new ProgressCounter(withTime: true, withRate: true)
        for(List<BreakpointInfo> bpInfos in breakpoints) {
            for(BreakpointInfo bpInfo in bpInfos) {
                mergeBreakpointInfo(result, bpInfo)
                progress.count()
            }
        }
        progress.end()
        return result
    }
    
    /**
     * If the given breakpoint exists, merge the information from the given breakpoint into it.
     * Otherwise, create a new entry reflecting the given breakpoint.
     * 
     * @param mergeTo   target to merge into
     * @param bpInfo    breakpoint to merge
     */
    static void mergeBreakpointInfo(Map<Long, BreakpointInfo> mergeTo, BreakpointInfo bpInfo) {
        BreakpointInfo mergeInfo = mergeTo[bpInfo.id]
        if(mergeInfo == null) {
            mergeInfo = bpInfo.clone()
            mergeTo[bpInfo.id] = mergeInfo
        }
        else {
            mergeInfo.add(bpInfo)
        }
    }
    
    static void processSingleBam(OptionAccessor opts, String bamFilePath, List<String> dbFiles) {
        PrintStream output = getOutput(opts)
        
        SAM bam = new SAM(bamFilePath)
        BreakpointDatabaseSet databaseSet = getBreakpointDatabases(opts, bam, dbFiles)
            
        FindNovelBreakpoints fnb = analyseSingleBam(opts, bamFilePath, databaseSet)
        try {
            fnb.printSummaryInfo()
            
            new BreakpointTableWriter(
                databases: fnb.databaseSet,
                refGene: fnb.refGene,
                options: opts,
                bams: [fnb.bam]
            ).outputBreakpoints(
                fnb.breakpoints.stream(), 
                fnb.breakpoints.size(),
                output
            )
            
            if(opts.o)
                output.close()
        }
        finally {
            fnb.close()
        }
    }
    
    /**
     * Analyse a single bam file and return the resulting analysis object
     * 
     * @param opts          Options to control the analysis (thresholds, etc)
     * @param bamFilePath   Path to BAM file to analyse
     * @param dbFiles       List of control databases to use for filtering
     * @return              a FindNovelBreakpoints object containing the breakpoints found
     */
    static FindNovelBreakpoints analyseSingleBam(OptionAccessor opts, String bamFilePath, BreakpointDatabaseSet databaseSet) {
        
        try {
            log.info "Analyzing ${bamFilePath}"
            
            SAM bam = new SAM(bamFilePath)
            RefGenes refGene = getRefGene(opts, bam)
            Regions regions = resolveRegions(refGene, bam, opts)
            
            log.info "Regions cover chromosomes: " + regions?.collect { it.chr }?.unique()
            
            if(regions.numberOfRanges == 0) {
                log.info "Note: regions provided but empty"
                regions = null
            }
                
            FindNovelBreakpoints fnb = new FindNovelBreakpoints(opts, bamFilePath, databaseSet).start()
    		try {
                fnb.reference = resolveReference(opts)
    	        fnb.refGene = refGene
    	        fnb.run(regions)
                log.info "Analysis of $bamFilePath complete"
    		}
    		catch(Exception e) {
    			fnb.close()
                throw e
    		}
            return fnb
        }
        catch(Exception e) {
            log.severe "========================================================================"
            log.severe "Error!"
            e.printStackTrace()
            log.severe "========================================================================"
            throw e
        }
    }
    
    static BreakpointDatabaseSet getBreakpointDatabases(OptionAccessor opts, SAM bam, List<String> dbFiles) {
        BreakpointDatabaseSet databaseSet = new BreakpointDatabaseSet(dbFiles, bam.contigs)       
        if(opts.xss) {
            databaseSet.excludeSamples = opts.xss as List
        }
        return databaseSet
    }
    
    /**
     * Print summary statistics from analyzing a BAM file
     * 
     * @param fnb
     */
    void printSummaryInfo() {
        log.info(" Summary ".center(100,"="))
        log.info "Unfiltered breakpoint candidates: " + total
        log.info "Common breakpoints: " + tooCommon
        log.info "Promiscuous breakpoints: " + tooCommon
        log.info "Reportable breakpoints: " + nonFiltered
        log.info "Partnered breakpoints: " + breakpointConnector.partnered
        log.info("="*100)
    }
    
    /**
     * Create an output PrintStream object appropriate for the given
     * options. If no option specifies an output format, System.out
     * is returned.
     * 
     * @param options
     * 
     * @return PrintStream to which output should be printed
     */
    static PrintStream getOutput(OptionAccessor options) {
        if(options.o) {
            File outputFile = new File(options.o)
            log.info "Output file is " + outputFile.absolutePath
            return new PrintStream(outputFile.newOutputStream())
        }
        else
            return System.out
    }
    
    /**
     * Resolve a set of regions to analyse based on the given BAM file and
     * on settings provided in the given options.
     * 
     * @param refGene
     * @param bam
     * @param opts  Options object containing settings: region (multi), pad, mask
     * 
     * @return  A flat set of regions representing the genomic regions to be analysed
     */
    static Regions resolveRegions(RefGenes refGene, SAM bam, OptionAccessor opts) {
        Regions regions = null
        if(opts.regions) {
            
            regions = new Regions()
            
            for(regionValue in opts.regions) {
                log.info "Adding region: $regionValue"
                Region region = null
                if(opts.region.contains(":"))
                    region = new Region(regionValue)
                else {
                    if(new File(regionValue).exists()) {
                        Regions loadRegions = new BED(regionValue).load()
                        loadRegions.each { regions.addRegion(it) }
                    }
                    else {
                        region = new Region(regionValue, 0..bam.contigs[opts.region])
                    }
                }
                if(region)
                    regions.addRegion(region)
                log.info "Added region to scan: $region"
            }
        }
        
        regions = addGeneRegions(refGene, opts,regions)
        
        if(regions)
            log.info "Resolved ${regions.numberOfRanges} regions (${regions.size()}bp) after adding genes"
        
        if(regions != null && opts.pad) {
            int padding = opts.pad.toInteger()
            log.info "Adding ${padding}bp to regions"
            regions = new Regions(regions.collect { Region r ->
                new Region(r.chr, Math.max(0, (r.from-padding))..(r.to + padding))
            })
            log.info "After adding padding there are ${regions.size()}bp to analyse"
        }
        
        if(regions == null) {
            if(opts.mask) {
                return new BED(opts.mask).load().reduce()
            }
            else
            return bam.contigs.collect { chr, chrSize ->
                new Region(chr, 0, chrSize)
            } as Regions 
        }
            
        // Flatten the regions after so we don't process the same region multiple times
        regions = regions.reduce()
        
        // If there is a mask, apply it
        if(opts.mask) {
            Regions maskBED = new BED(opts.mask).load().reduce()
            regions = regions.intersect(maskBED)
            log.info "After intersecting with masked regions have ${regions.size()}bp to analyse"
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
                if(geneRegion == null) {
                    log.warning "Gene $gene not found in RefSeq database"
                }
                else {
                    log.info "Gene $gene translated to $geneRegion"
                    regions.addRegion(geneRegion)
                }
            }
        } 
        return regions
    }

    static List<String> resolveDatabases(OptionAccessor opts) {

        if(opts.dbs)
            return opts.dbs

        def dbDir = opts.dbdir
        if(!dbDir) {
            String loadPath = FindNovelBreakpoints.class.classLoader.getResource("SAM.class")?.path
            String rootPath 
            if(loadPath != null)
                rootPath = loadPath.replaceAll('/lib/.*$','').tokenize(':')[-1]
            else
                rootPath = System.getenv('SCHISM_BASE'); 
                
            dbDir = new File(rootPath,"databases").absolutePath
            log.info "Searching for databases in install database directory: $dbDir"
        }

        return new File(dbDir).listFiles().grep { it.name.endsWith('.db') }*.absolutePath
    }
}
