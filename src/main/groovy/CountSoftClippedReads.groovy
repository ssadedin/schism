import groovy.util.logging.Log
import groovyx.gpars.GParsPool;;


class SampleReadClipInfo {
   
    Map<Integer,List<Region>> reads
    
    Map<Integer,Integer> counts
}

@Log
class CountSoftClippedReads {
    
    List<File> bedFiles = []
    
    Map<String, BED> beds 
    
    int concurrency = 2
    
    int minDepth = 2
    
    CountSoftClippedReads(List<File> bedFiles) {
        this.bedFiles = bedFiles
    }
    
    void load() {
        
        log.info "Loading ${bedFiles.size()} BED files using $concurrency threads"
        
        GParsPool.withPool(this.concurrency) {
            
            this.beds = bedFiles.collectParallel { 
                
                Map reads = readSoftClips(it.absolutePath) 
                
                Map counts = reads.collectEntries { [it.key, it.value.size() ] }
                
                // the sample id is read as the 4th column of the BED file,
                // which appears as the "extra" field in each range included
                String sampleId = reads.iterator().next().value[0].extra
                
                [ sampleId, new SampleReadClipInfo(reads:reads,counts:counts) ]
            }.collectEntries()
        }
    }
    
    Map<Region,List> readSoftClips(String file, int min_depth=2) {
        
          BED soft_clipped = new BED(file, withExtra:true).load()
        
          // find only regions where at least 2 reads coincide with the same soft clipped boundary in 1 sample
          Map<Integer,List<Region>> grouped = 
              soft_clipped.groupBy { it.from }.grep { it.value.size()>=minDepth }.collectEntries()
        
          log.info "Found ${grouped.size()} unique sites where more than 1 soft clip starts in same position"
          
          return grouped
    }
    
    static void main(String [] args) {
        
        println " CountSoftClippedReads ".center(80, "=")
        println ""
        
        Cli cli = new Cli()
        cli.with {
            bed "Input BED file with ID field containing sample id", args:Cli.UNLIMITED, required:true
            mindp "Minimum number of reads with same soft clip", args:1
            n "Concurrency", args:1
        }
        
        def opts = cli.parse(args)
        if(!opts) 
            System.exit(1)
            
            
        Utils.configureSimpleLogging()
            
        CountSoftClippedReads cscr = new CountSoftClippedReads(opts.beds.collect { new File(it) })
        if(opts.concurrency) {
            log.info "Using concurrency $opts.concurrency"
            cscr.concurrency = opts.concurrency.toInteger()
        }
        
        if(opts.mindp) {
            cscr.minDepth = opts.mindp.toInteger()
        }
        
        cscr.load()
    }
}
