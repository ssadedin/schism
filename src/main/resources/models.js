/**
 * Data structures for Schism web interface
 */

class Breakpoints {
    
    constructor(props) {
        Object.assign(this,props)
        
        if(!this.breakpoints) {
            this.breakpoints = []
        }
    }
    
    /**
     * Load breakpoints from a json format file 
     */
    load(src) {
        loadAndCall(this.dataFiles, (bps) => {
            console.log("Loaded " + bps.length + " breakpoints");
            this.breakpoints = bps;
            $(this).trigger('breakpoints:change');
        })
    }
}
