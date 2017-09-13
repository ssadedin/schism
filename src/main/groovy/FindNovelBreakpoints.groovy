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

import java.awt.AttributeValue
import java.beans.beancontext.BeanContextServiceProviderBeanInfo
import java.nio.file.Files
import java.text.NumberFormat;
import java.util.concurrent.atomic.DoubleAdder
import java.util.logging.Level
import java.util.stream.Stream

import org.apache.commons.math3.stat.descriptive.SummaryStatistics

import graxxia.IntegerStats
import graxxia.Stats

import java.util.stream.SortedOps.AbstractDoubleSortingSink

import groovy.json.JsonOutput
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Log;
import groovyx.gpars.GParsPool
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;;
import trie.TrieNode
import trie.TrieQuery

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
    int maxPartnersForOutput = 40
    int maxPartnersToJoin = 7
    
    // Statistics
    int total = 0
    int nonFiltered = 0
    int tooCommon = 0
    int partnered = 0
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
    
    void processBreakpoint(BreakpointMessage msg) {
        ++total
          
        int freq = isFiltered(msg)
        if(freq < 0)
            return
            
        boolean verbose = false
        try {
            Long bpId = XPos.computePos(msg.chr, msg.pos)
            
            BreakpointInfo bpInfo = new BreakpointInfo(id: bpId, chr: msg.chr, pos: msg.pos, sampleCount: freq)
    
            // Check if any other breakpoint ties to this one
            bpInfo.add(msg)
            
            if(bpInfo.pos == 40285945 || bpInfo.pos == 23812754) {
                println "Debug breakpoint: $bpInfo"
                verbose = true
            }
            
            
            // Index this breakpoint
            if(reference) {
                String [] reference = bpInfo.queryReference(reference, softClipSize)
                
                // If opposite side of breakpoint is entirely homopolymer sequence 
                // then ignore this breakpoint - while such breakpoints could be real, 
                // in practice they are nearly always sequencing or mapping artefacts caused by the
                // homopolymer run
                if(isAllSameBase(reference[1]) || isAllSameBase(reference[0])) {
                    log.info "Breakpoint $bpId is adjacent to homopolymer sequence: ignoring due to low complexity / unknown reference"
                    return
                }
                
                if(hasAdjacentNBases(reference)) {
                    log.info "Breakpoint $bpId is adjacent to unknown sequence: ignoring"
                    return
                }

                indexBreakpoint(bpInfo, reference, verbose)
            }
            ++nonFiltered 
            breakpoints.add(bpInfo)
        }
        catch(Exception e) {
            ++errorCount
            log.warning "Failed to add breakpoint at $msg.chr:$msg.pos: " + e
            if(errorCount < 3)
                log.log(Level.SEVERE, "Failed to add breakpoint at $msg.chr:$msg.pos: ", e)
        }
    }
    
    @CompileStatic
    private static boolean hasAdjacentNBases(String [] bases) {
        return bases[0].endsWith('NNN') || bases[1].startsWith('NNN')
    }
    
    @CompileStatic
    private static boolean isAllSameBase(String bases) {
        final char firstBase = bases.charAt(0)
        final int numBases = bases.size()
        for(int i=1; i<numBases; ++i) {
            if(bases.charAt(i) != firstBase)
                return false
        }
        return true
    }
    
    @CompileStatic
    void indexBreakpoint(BreakpointInfo bpInfo, String [] reference, boolean verbose) {
        String opposingReference = reference[0]
        
        if(verbose)
            log.info "Indexing opposing reference for $bpInfo.id: " + opposingReference
            
        BreakpointSampleInfo obs = bpInfo.observations[0]
        int offset = 1
        int start = 0
        
        if(obs.direction == SoftClipDirection.REVERSE) {
            start = opposingReference.size() - indexLength - partnerIndexBases + 1;
        }
        
        int end = partnerIndexBases+1
        
        for(int i=start; i!=end; i+=offset) {
            String indexKey = opposingReference.substring(i, i+indexLength)
            if(verbose)
                log.info "Adding indexed ref seq " + indexKey
            breakpointPartners[indexKey] = bpInfo
        }
        
        if(verbose) {
            log.info "index forward $bpInfo.id: " + opposingReference.take(softClipSize)
            log.info "index reverse $bpInfo.id: " + (String)opposingReference.take(softClipSize).reverse()
        }
        
        breakpointSequenceIndex.add((String)opposingReference.take(softClipSize), bpInfo)
        breakpointSequenceIndex.add((String)opposingReference.take(softClipSize).reverse(), bpInfo)
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
    
    Set<String> commonPartners = new HashSet(1000)
    
    @CompileStatic
    void partnerBreakpoints() {
        List<BreakpointInfo> outputBreakpoints = new ArrayList(breakpoints.size())
        for(BreakpointInfo bp in breakpoints) {
            
            boolean verbose = false
//            if(bp.pos == 15460789 || bp.pos == 15461865) {
//                println "Debug breakpoint: $bp with bases: " + bp.observations[0].bases
//                verbose = true
//                trie.TrieNode.verbose = true
//            }

            // if there is a consensus among the soft clip, check if there is a partner sequence
            // inthe database
            BreakpointInfo partner = null
            BreakpointSampleInfo bpsi = bp.observations[0]
            if(bpsi.consensusScore > 10.0) { // a bit arbitrary
               
                // Note we search for 1 bp less than the soft clip size because we are allowing for
                // a single bp deletion. If our query has a deletion, then our query is effectively 
                // one base longer which won't be in the prefix trie. This would force the trie to 
                // cost it as an insertion which we do not want to do
                String forwardSequence = (String)bpsi.bases.take(softClipSize-1)
                String reverseSequence = (String)bpsi.bases.reverse().take(softClipSize-1)
                String reverseComplement = FASTA.reverseComplement(bpsi.bases).take(softClipSize-1)
                
                if(forwardSequence in commonPartners) {
                    log.info "$bp has Recurrent common partner sequence $forwardSequence"
                    continue
                }
                
                if(verbose)
                     log.info "Searching forward for $forwardSequence (bp=$bp.id)"
                List<TrieQuery<BreakpointInfo>> forwardPartners = breakpointSequenceIndex.query(forwardSequence, 1,1,1,5)
                
                if(verbose)
                     log.info "Searching reverse for $reverseSequence (bp=$bp.id)"
                List<TrieQuery<BreakpointInfo>> reversePartners = breakpointSequenceIndex.query(reverseSequence, 1,1,1,5)
                
                List<TrieQuery<BreakpointInfo>> reverseComplementPartners = breakpointSequenceIndex.query(reverseComplement, 1,1,1,5) 
                if(verbose)
                     log.info "Searching reverse complement: $reverseComplement (bp=$bp.id) finds " + reverseComplementPartners.size() + " with costs " + 
                              reverseComplementPartners.collect { TrieQuery q -> q.cost(TrieNode.DEFAULT_COSTS) }.join(',')
                              
                List<TrieQuery> allPartners = forwardPartners + reversePartners + reverseComplementPartners
                
                List<TrieQuery<BreakpointInfo>> partnerQueries = (allPartners).grep { TrieQuery q ->
                    q.cost(TrieNode.DEFAULT_COSTS) < 5.0
                }
                
                if(partnerQueries.size()>this.maxPartnersToJoin) {
                    log.info "Too many partners (${partnerQueries.size()}) to link $bp based on $forwardSequence"
                    commonPartners.add(forwardSequence)
                    commonPartners.add(reverseSequence)
                    commonPartners.add(reverseComplement)
                }
                else {
                    
                    partnerQueries = partnerQueries.sort { TrieQuery q ->
                        q.cost(TrieNode.DEFAULT_COSTS)
                    }
                    
                    if(partnerQueries.size()>0) {
                        
                        int lowestCost = (int) partnerQueries[0].cost(TrieNode.DEFAULT_COSTS)
                        
                        partnerQueries = partnerQueries.takeWhile { TrieQuery q ->
                            (int)q.cost(TrieNode.DEFAULT_COSTS) == lowestCost
                        }
                        
                        
                        List<BreakpointInfo> partners = (List<BreakpointInfo>)partnerQueries.collect { TrieQuery<BreakpointInfo> q ->
                            q.result
                        }.flatten()
                         .grep { BreakpointInfo p -> bp.id != p.id }
                        
                        log.info "Found ${partners.size()} equal partners for $bp starting with: ${partners.take(2).join(',')}" 
                        
                        // If any partner already points back to us, partner with them
                        BreakpointInfo recipricalPartner = partners.find { BreakpointInfo p -> p.observations[0].partner?.id == bp.id }
                        if(recipricalPartner) {
                            log.info "Partnered $bp.id to reciprical breakpoint $recipricalPartner"
                            bpsi.partner = recipricalPartner
                        }
                        else  {
                            // Of all the partners with equal score on the same chromosome, take the closest
                            bpsi.partner = partners.grep { BreakpointInfo p -> p.chr == bp.chr }?.min { BreakpointInfo partnerBp ->
                                Math.abs(partnerBp.id - bp.id)
                            }
                            
                            if(bpsi.partner) {
                                log.info "Partnered $bp.id to closest on same chromosome: $bpsi.partner"
                            }
                            else {
                                log.info "Partnered $bp.id to minimum cost match on other chr: $bpsi.partner"
                                bpsi.partner = partners[0]
                            }
                        }
                        
                        if(bpsi.partner)
                            ++partnered
                    }
                }
            }
            trie.TrieNode.verbose = false
            
//            if(partners!=null && partners.size()> maxPartnersForOutput) {
//                log.info "$bp has too many partners: ignoring"
//                ++tooPromiscuous
//            }
//            else
//            {
                outputBreakpoints.add(bp)
//            }
              this.breakpoints = outputBreakpoints;
        }
    }
    
    /**
     * Run the analysis for the configured BAM file over the given regions.
     */
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
        
        this.phase = "discover"
        this.runOverRegions(includedRegions)
        
        this.send "phase:extend"
       
        while(phase != "extend") {
            Thread.sleep(100)
        }
        
        log.info "Partnering ${breakpoints.size()} breakpoints ..."
        this.partnerBreakpoints()
        
        Regions extendedRegions = this.findExtendedRegions(new Regions(includedRegions))
        log.info "Processing ${extendedRegions.numberOfRanges} extended regions (${extendedRegions.size()}bp)"
        runOverRegions(extendedRegions)
        
        this.send "end"
        this.join()
    }
    
    void runOverRegions(Iterable<Region> includedRegions) {
        for(region in includedRegions) {
            def bpe = new BreakpointExtractor(bam, allowMultiSample: options.multi)
            bpe.breakpointListener = this
            if(options.adapter) 
                bpe.filter.setAdapterSequence(options.adapter)
            bpe.run(region)
        }
    }
    
    /**
     * Identify regions that are of interest based on linkage to discovered breakpoints
     * 
     * @return
     */
    Regions findExtendedRegions(Regions existingRegions) {
        Regions result = new Regions()
        
        for(BreakpointInfo bp in breakpoints) {
            
            // does the breakpoint have multiple reads linking to an unexplored region?
            Regions bpMates = new Regions()
            for(BreakpointSampleInfo obs in bp.observations) {
                for(Long xpos in obs.mateXPos) {
                    Region r = XPos.parsePos(xpos)
                    r.from -= 150
                    r.to += 150
                    bpMates.addRegion(r)
                }
            }
            
            Regions mateCoverage = bpMates.coverage()
            
            // Add all the regions where at least mates
            // of breakpoint reads overlap
            mateCoverage.grep { it.extra > 2 }.each { Region r ->
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
            o 'Output file (BED format)', longOpt: 'output', args: 1, required: false
            html 'Create HTML report in given directory', args:1, required: false
            genome 'Specify genome build (if not specified, determined automatically)', args:1, required:false
            n 'Number of threads to use', args:1, required:false
            localBamPath 'Prefix to path to BAM files, to enable loading in the HTML interface via IGV', args:1, required: false
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
        
        int concurrency = opts.n ? opts.n.toInteger() : 1
        
        
        SAM bam = new SAM(bamFilePaths[0])
        
        List<FindNovelBreakpoints> fnbs
        BreakpointDatabaseSet databaseSet = getBreakpointDatabases(opts, bam, dbFiles)
        try {
            fnbs = GParsPool.withPool(concurrency) {
                bamFilePaths.collectParallel { String bamFilePath ->
                    analyseSingleBam(opts, bamFilePath, databaseSet)
                }
            }
        }
        finally {
            databaseSet.close()
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
        
        log.info "Analyzing ${bamFilePath}"
        
        SAM bam = new SAM(bamFilePath)
        RefGenes refGene = getRefGene(opts, bam)
        Regions regions = resolveRegions(refGene, bam, opts)
        if(regions.numberOfRanges == 0)
            regions = null
            
        FindNovelBreakpoints fnb = new FindNovelBreakpoints(opts, bamFilePath, databaseSet).start()
		try {
            fnb.reference = resolveReference(opts)
	        fnb.refGene = refGene
	        fnb.run(regions)
		}
		catch(Exception e) {
			fnb.close()
            throw e
		}
        return fnb
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
        log.info "Partnered breakpoints: " + partnered
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
            String loadPath = FindNovelBreakpoints.class.classLoader.getResource("SAM.class").path
            String rootPath = loadPath.replaceAll('/lib/.*$','').tokenize(':')[-1]
            dbDir = new File(rootPath,"databases").absolutePath
            log.info "Searching for databases in install database directory: $dbDir"
        }

        return new File(dbDir).listFiles().grep { it.name.endsWith('.db') }*.absolutePath
    }
}
