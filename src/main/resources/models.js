/**
 * Data structures for Schism web interface
 */

class Breakpoints {
    
    constructor(props) {
        Object.assign(this,props)
        
        if(!this.breakpoints) {
            this.breakpoints = []
        }
        
        this.genes = {};
    }
    
    /**
     * Load breakpoints from a json format file 
     */
    load(src) {
        loadAndCall(this.dataFiles, (bps) => {
            console.log("Loaded " + bps.length + " breakpoints");
            
            let genes = window.model.genes;
            
            // add a 'gene' attribute to every exon (not sure why?)
            Object.keys(genes).forEach(g => genes[g].exons.forEach(e => e.gene = g))
            
            // augment the breakpoints.genes object with all the genes from the
            // window global genes
            
            Object.assign(this.genes, window.genes); // loaded as a side effect of loading breakpoints
                                 // may break if > 1 breakpoints loaded
            
            if(window.defaultBamFilePrefix && components.BreakpointsView)
                components.BreakpointsView.bamFilePrefix = defaultBamFilePrefix;
            
            bps.forEach( (bp,i) => bp.index = i)
            this.breakpoints = bps;
            $(this).trigger('breakpoints:change');
        })
    }
}
