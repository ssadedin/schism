import com.xlson.groovycsv.PropertyMapper;

class LabelVelvetGraph {
    
    OptionAccessor options
    
    void labelGraph() {
        
        List<Map> matches = new TSV(options.b, columnNames: ['chr','start','end','id','score','ident']).collect { line ->
            [ 
                region: new Region(line.chr, line.start..line.end), 
                id:  line.id.replaceAll('_length.*$','').replaceAll('^NODE_','').toInteger(),
                score: line.score,
                ident: line.ident
            ]
        }
        
//        println "Read: " + matches*.region*.toString().join('\n') +'\n'
        
        // Group by node, then sort each node by score
        Map<String,List<Map>> nodes = matches.groupBy { it.id }
        
        // Find the best nodes, order by genomic region
        RegionComparator comparator = new RegionComparator()
        List<Map> bestNodes = nodes.collect { entry -> entry.value.max { it.score } }
                                   .sort { n1,n2 -> comparator.compare(n1.region, n2.region) }
                                   
        int labelIndex = 0
        String chr = null
        println "Best Matches:\n" + bestNodes.collect {  n ->
            if(n.region.chr != chr)
                labelIndex = 0
            chr = n.region.chr
            n.label = 'chr' + chr.replaceAll('^chr','') + '_' + labelIndex
            labelIndex++
            [n.id, n.region.toString(), n.ident, n.label].join('\t')
        }.join('\n') + '\n'
    
        
        new File(options.o).withWriter { w ->
            w.println("node_id,custom_label")
            w.println(
                bestNodes.collect { n ->
                    [n.id, n.label].join(',')
                }.join('\n') + '\n'
            )
        }
        
        new File(options.bed).withWriter { w ->
            w.println(
                bestNodes.collect { n ->
                    ['chr' + n.region.chr.replaceAll('^chr',''), n.region.from+1, n.region.to-1, n.label].join('\t')
                }.join('\n') + '\n'        
            )
        }
    }
    
    static void main(String [] args) {
        
        Cli cli = new Cli(usage:"LabelVelvetGraph <options>")
        
        cli.with {
            'g' 'Graph output by Velvet', longOpt: 'graph', required:true, args:1
            'b' 'Blat output (scores.tsv file) for contigs', longOpt: 'blat', required: true, args:1
            'o' 'Output Bandage file in CSV format', longOpt: 'output', args:1, required: true
            'bed' 'Output BED file to load in IGV', required: true, args:1
        }
        
        def opts = cli.parse(args)
        if(!opts) 
            System.exit(1)
        
        println """
        LabelVelvetGraph
        ----------------
        
        Input Graph: $opts.g
        Input Blat: $opts.b
        Output: $opts.o
        """.stripIndent()
        
        new LabelVelvetGraph(options:opts).labelGraph()
    }
}
