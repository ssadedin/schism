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
