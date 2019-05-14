package schism.domain

import groovy.transform.ToString

@ToString
class LoadOperation {
    
    static enum LoadState {
        WAITING,
        LOADING,
        COMPLETE
    }
    
    final static PHASES = [
        0 : 'Pending',
        1 : 'Loading Breakpoints',
        2 : 'Loading Observations',
        3 : 'Complete'
    ]
    
    static constraints = {
        pedigreeFile nullable: true
    }
    
    Cohort cohort
    
    String file
    
    String pedigreeFile
    
    /**
     * Number of breakpoints in total
     */
    int breakpointCount
    
    /**
     * The total number of items processed for the current phase
     */
    int processed
    
    /**
     * The total number of items for the current phase
     */
    int total
    
    /**
     * The current phase of the load operation
     */
    int phase
    
    /**
     * 
     */
    LoadState state 
}
