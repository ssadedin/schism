import groovy.transform.CompileStatic
import groovy.util.logging.Log;
import htsjdk.samtools.CigarElement;
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
class ExtractInterestingReads3 {
    
    /**
     * Some reads have soft clipping because they contain adapter sequence. We exclude any read having
     * 7 bases or more of adapter at each end.
     */
    static final String DEFAULT_ADAPTER = "AGATCGGAAGAG"
    
    String ADAPTER_SEED = DEFAULT_ADAPTER.substring(0,5)
    
    String ADAPTER_SEED_COMPLEMENT = FASTA.reverseComplement(ADAPTER_SEED)
    
    static final int READ_WINDOW_SIZE = 500
    
    /**
     * Moving window containing all reads within READ_WINDOW_SIZE bp of the current position
     */
    List<List<SAMRecord>> window = []
    
    @CompileStatic
    void run(SAM bam, Region region, File outputFile, int minSoftClippedBases, int maxInsertSize) {
        
        if(bam.samples.unique().size()>1)
            throw new IllegalArgumentException("This tool only supports single-sample bam files")
        
        String sampleId = bam.samples[0]
        
        int halfWindowSize = (int)(READ_WINDOW_SIZE / (int)2)
        
        TreeMap<Integer, Integer> softClipCounts = new TreeMap()
        
        bam.withWriter(outputFile.absolutePath, true) { SAMFileWriter w ->
            
            int countInteresting = 0
            int countAnomalous = 0
            int countChimeric = 0
            int countLowQual = 0
            int countAdapter = 0
            
            // Running count of how many soft clipped reads were observed in the window
            int windowSoftClips = 0
            
            ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true)
            counter.extra = {
                "Interesting Regions Found: $countInteresting, anomalous=$countAnomalous, chimeric=$countChimeric, lowqual=$countLowQual, adapter=$countAdapter"
            }
            
            String regionChr = region.chr.replaceAll('^chr','')
            SAMRecordIterator iter = bam.newReader().query(region.chr.replaceAll('^chr',''), region.from,region.to, false)
            while(iter.hasNext()) {
                
                SAMRecord read = iter.next()
                
                counter.count()
                
                boolean isInteresting = read.cigar.cigarElements.any { CigarElement e -> 
                    e.operator == htsjdk.samtools.CigarOperator.S && 
                        e.length > minSoftClippedBases
                }
                    
                if(!isInteresting)
                    continue
                    
                // For now, ignore chimeric reads
                if(read.referenceIndex != read.mateReferenceIndex) {
                    ++countChimeric
                    continue
                }
                    
                    
                int r1Start = read.alignmentStart
                int r2Start = read.mateAlignmentStart
                if(read.isSecondaryOrSupplementary() || !read.properPairFlag || (Math.abs(r2Start - read.alignmentEnd) > maxInsertSize)) {
                    ++countAnomalous
                    continue
                }
                    
                if(Arrays.asList(read.baseQualities).count { ((int)it) < 20 } > 20) {
                    ++countLowQual
                    continue
                }
                    
                // When both reads in pair are aligned to nearly the same position then we 
                // have a possibility of small fragment containing adapter contamination
                if(Math.abs(read.mateAlignmentStart - read.alignmentStart) < 10) {
                    if(read.readString.indexOf(ADAPTER_SEED) >=0 || (read.readString.indexOf(ADAPTER_SEED_COMPLEMENT)>=0)) {
                        ++countAdapter
                        continue
                    }
                }
                
                softClipCounts[read.alignmentStart] = (softClipCounts[read.alignmentStart]?:0) + 1
                windowSoftClips += 1
                
                // Decrement the soft clip counts from outside the window
                Integer trailingEdge = read.alignmentStart - READ_WINDOW_SIZE
                while(!softClipCounts.isEmpty() && softClipCounts.firstKey()<trailingEdge) {
                    windowSoftClips -= softClipCounts.pollFirstEntry().value
                } 
                                    
                ++countInteresting
                    
                w.addAlignment(read)
            }
            counter.end()
        }
    }
    
    public static void main(String [] args) {
        
        Cli cli = new Cli(usage:"ExtractInterestingReads <opts>")
        cli.with {
            bam 'BAM file to extract from', args:1, required:true
            'L' 'Region to extract from', args:1, required:true
            o 'The output BED file containing regions with interesting reads', args:1, required:true
            'size' 'The minimum number of soft clipped reads to qualify a read for inclusion', args:1
            'ins' 'The maximum insert size of reads to qualify a read for inclusion', args:1
        }
        
        def opts = cli.parse(args)
        if(!opts) {
            System.exit(1)
        }
        
        Region region = new Region(opts.L)
        
        SAM sam = new SAM(opts.bam)
        
        int minSoftClipped = opts['size'] ? opts['size'].toInteger() : 10
        
        int maxInsertSize = opts['ins'] ? opts['ins'].toInteger() : 900
        
        new ExtractInterestingReads3().run(sam, region, new File(opts.o), minSoftClipped, maxInsertSize)
    } 
}