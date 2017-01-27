import groovy.transform.CompileStatic
import groovy.util.logging.Log
import groovyx.gpars.GParsPool;
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileWriter
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SAMRecordIterator;
    
/**
 * Scans a BAM file for regions where high quality reads have large numbers
 * of bases soft clipped. These are then written to a BED file for further
 * downstream processing.
 * 
 * @author simon
 */
@Log
class ExtractInterestingReads {
    
    public static void main(String [] args) {
        
        Cli cli = new Cli(usage:"ExtractInterestingReads <opts>")
        cli.with {
            bam 'BAM file to extract from', args:Cli.UNLIMITED
            'L' 'Region to extract from', args:1, required:true
            o 'The output BAM file containing interesting reads', args:Cli.UNLIMITED
            'size' 'The minimum number of soft clipped bases to qualify a read for inclusion', args:1
            'ins' 'The maximum insert size of reads to qualify a read for inclusion', args:1
            'nt' 'The noise filter threshold: ignore regions where more than this number of soft clipped reads observed in a 500bp window', args:1
            'minbq' 'The score below which bases are counted as low quality', args:1
            'minm' 'The mininum number of non-soft-clipped bases that must be preset in a read with clipped ends', args:1
            stats 'Write statistics to the given file', args:1
            log 'Write verbose details to log file', args:1
            v   'Write verbose details to standard output'
        }
        
        def opts = cli.parse(args)
        if(!opts) {
            System.exit(1)
        }
        
        if(!opts.bam || !opts.bams || opts.bams.size() == 0) 
            throw new IllegalArgumentException("No BAM files specified with -bam. Please specify at least one BAM file to process")
        
        if(opts.bams.size() != opts.os.size()) 
            throw new IllegalArgumentException("Different number of BAM files to output files specified. Please specify the same number of -bam arguments as -o.")
            
        Region region = new Region(opts.L)
        
        
        List<BreakpointExtractor> bpes = [opts.bams,opts.os].transpose().collect { bamFile,outputFileName ->
            SAM bam = new SAM(bamFile)
            File outputFile = new File(outputFileName)
            BreakpointExtractor bpe = new BreakpointExtractor(bam)
            bpe.breakpointListener = new WriteExtractedBAMActor(bam, outputFile)
            bpe.breakpointListener.start()
            setOptions(bpe, opts)
            return bpe
        }
        
        println "Created ${bpes.size()} parallel instances to process reads"
        
        GParsPool.withPool(opts.bams.size()) { 
            bpes.eachParallel { BreakpointExtractor bpe ->
                bpe.run(region)
            }
        }
        
        bpes*.breakpointListener*.send("end")
        
        bpes*.breakpointListener*.join()
        
        writeStats(opts, bpes)
        
        Thread.sleep(1000)
        
        System.exit(0)
    }

    /**
     * If specified in options, write out a file of statistics about what happened during the run
     * 
     */
    private static void writeStats(OptionAccessor opts, List<ExtractInterestingReads> eirs) {
        if(opts.stats) {
            List statColumns = [
                "countInteresting", "countAnomalous", "countChimeric", "countLowQual", "countAdapter", "countContam", "countNoisy"
            ]
            new File(opts.stats).withWriter { w ->
                w.println((["sample"] + statColumns*.replaceAll('^count','') + ["Seconds"]).join('\t'))
                
                for(ExtractInterestingReads eir in eirs) {
                    SAM sam = eir.bam
                    w.println(([sam.samples[0]] + statColumns.collect { eir.getProperty(it) } + [(eir.endTimeMs - eir.startTimeMs)/1000]).join('\t'))
                }
            }
        }
    }

    /**
     * Extract options from the given options object and set on analysis
     * 
     * @param eir
     * @param opts
     * @return
     */
    private static setOptions(BreakpointExtractor bpe, OptionAccessor opts) {
        if(opts.v) {
            bpe.verbose = true
            bpe.filterLog = System.out
            bpe.filter.filterLog = System.out
        }
        else
        if(opts.log) {
            bpe.verbose = true
            def filterLog = new PrintStream(new File(opts.log))
            bpe.filterLog = filterLog
            bpe.filter.filterLog = filterLog
        }

        if(opts.ins) {
            bpe.filter.maxInsertSize = opts['ins'].toInteger()
        }

        if(opts['minbq'])
            bpe.minBQ = opts['minbq'].toInteger()

        if(opts['minm'])
            bpe.filter.minNonScBases = opts['minm'].toInteger()

        if(opts['size'])
            bpe.filter.minSoftClippedBases = opts['size'].toInteger()

        if(opts['nt'])
            bpe.softClipNoiseThreshold = opts['nt'].toInteger()
    }
}