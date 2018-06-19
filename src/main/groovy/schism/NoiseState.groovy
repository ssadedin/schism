package schism

import gngs.ReadWindow
import gngs.Region
import gngs.Regions
import groovy.transform.CompileStatic
import groovy.util.logging.Log

/**
 * Tracks the number of distinct positions at which soft clipping is occurring within a window,
 * and reports a noise state when this value crosses a given threshold.
 * 
 * @author Simon Sadedin
 */
@CompileStatic
@Log
class NoiseState {
    
    Region over = null
    
    Regions noisyRegions = new Regions()
    
    int softClipNoiseThreshold = 200
    
    int maxBreakpointsInWindow = 100
       
    int start = -1
    
    int end = -1
    
    /**
     * Updates the noise state based on the given stats and window, and returns
     * the new noise state.
     *
     * @param windowStats
     * @param window
     * @return
     */
    boolean update(WindowStatistics windowStats, ReadWindow window) {
        if(this.isNoisy(windowStats,window)) {
            noisy(window.pos)
            return true
        }
        else {
            clean()
            return false
        }
    }
    
    /**
     * Begin noisy state
     */
    void noisy(int pos) {
        if(start < 0)
            start = pos
        end = pos
    }
    
    void clean() {
        if(start >= 0) {
            if(end<0)
                end = start+1
            noisyRegions.addRegion(new Region(over.chr, start..end))
            start = -1
            end = -1
        }
    }
    
    int bpInWindow = 0
    
    /**
     * Return true if the region of the window is considered "noisy".
     *
     * Noise is measured by
     *
     * <li> total number of reads containing soft clipping within the window
     * <li> toal number of positions that have reads containing soft clipping
     */
    @CompileStatic
    boolean isNoisy(WindowStatistics windowStats, ReadWindow window) {
      if(windowStats.softClipped > this.softClipNoiseThreshold) {
          return true
      }
      else {
          return windowStats.positionsClipped > maxBreakpointsInWindow
      }
    }
    
    String toString() {
        return "NoiseState: breakPoints: $bpInWindow"
    }
}

