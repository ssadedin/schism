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

import gngs.ProgressCounter
import gngs.ReadWindow
import gngs.Region
import gngs.Regions
import gngs.SAM
import groovy.transform.CompileStatic
import groovy.util.logging.Log
import groovyx.gpars.GParsPool;
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.CigarElement
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileWriter
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SAMRecordIterator;

@CompileStatic
@Log
class NoiseState {
    
    Region over = null
    
    Regions noisyRegions = new Regions()
    
    int softClipNoiseThreshold = 200 
    
    int maxBreakpointsInWindow = 100
       
    int start = -1
    
    int end = -1
    
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
          true
      }
      else {
          int readsAtPos = window.window[window.pos]?.size()?:0
          int bpInWindow = window.window.count { it.value.size() > 1 } - readsAtPos
          return bpInWindow > maxBreakpointsInWindow
      }
    }
}

@Log
class WindowStatistics {
    
    int halfWindowSize = 0
    
    WindowStatistics(int windowSize) {
        this.halfWindowSize = windowSize / 2
    }
    
    int softClipped = 0
    
    int startPosition = 0
    
    @CompileStatic
    void update(ReadWindow readWindow) {
        
        int pos = readWindow.pos
        
        TreeMap<Integer, List<SAMRecord>> window = readWindow.window
        
        int before = softClipped
        
        // Add the soft clipped reads from the leading edge, subtract from trailing edge
        int trailingEdge = pos - halfWindowSize + 2
        List<SAMRecord> trailingReads = window[trailingEdge]
        if(trailingEdge > startPosition && trailingReads) {
            softClipped -= trailingReads.size()
//            log.info "$pos: Removing ${trailingReads*.readName} from noise"
        }
                
        int leadingEdge = pos + halfWindowSize -1
        List<SAMRecord> leadingReads = window[leadingEdge]
        if(leadingEdge>startPosition && leadingReads) {
//            log.info "$pos: Adding ${leadingReads*.readName} from noise"
            softClipped += leadingReads.size()
        }
        
//        String debugRead = "H23LJCCXX150122:2:1123:10967:46367"
            
        if(before != softClipped)
//        log.info "Noise at $pos ($trailingEdge - $leadingEdge) = $softClipped -${trailingReads?.size()} +${leadingReads?.size()} debugRead at " + 
//            ((window.find { e -> e.value.find { it.readName == debugRead } != null }?.key?:0) - trailingEdge)
            
//        if(pos < startPosition && softClipped<0)
//            softClipped = 0

        if(softClipped<0)
            softClipped=0
        assert softClipped >= 0
    }
}

@Log
class BreakpointExtractor {
    
    static final int READ_WINDOW_SIZE = 500
    
    /**
     * Optional log file to write log of action to (for debugging)
     */
    PrintStream filterLog = null
    
    /**
     * Moving window containing all reads within READ_WINDOW_SIZE bp of the current position
     */
    List<List<SAMRecord>> window = []
    
    NoiseState noiseState = null
    
    WindowStatistics windowStats = new WindowStatistics(READ_WINDOW_SIZE)
    
    // ----------  Statistics accumulated during run

    int countNoisy = 0
    long startTimeMs = 0
    long endTimeMs = 0
    int softClipNoiseThreshold = 200
    
    int minBQ = 12
    int maxBreakpointsInWindow = 10
    
    int countInteresting = 0
    
    boolean verbose = false
    
    /**
     * If set to true, only a single uniquely positioned read will be reported for multiple
     * reads are aligned to identical positions 
     */
    boolean dedupeReads = true
    
    String debugRead = null
    
    int debugPosition = -1
    
    SAM bam = null
    
    BreakpointReadFilter filter = new BreakpointReadFilter()
    
    DefaultActor breakpointListener = null
    
    /**
     * Buffer of reads that were observed previously in window that
     * contain soft clips at end.
     */
    TreeMap<Integer, List<SAMRecord>> aheadCache = new TreeMap()
    
    String sampleId = null
    
    BreakpointExtractor(Map options=[:],SAM bam) {
        this(options,bam,bam.samples[0])
    }
    
    BreakpointExtractor(Map options=[:],SAM bam, String sampleId) {
        if((bam.samples.unique().size()>1) && !options.allowMultiSample)
            throw new IllegalArgumentException("This tool only supports single-sample bam files")
        
        this.bam = bam
        this.sampleId = sampleId
    }
    
    /**
     * Run with retries
     * 
     * @param region
     * @param retries
     */
    void run(Region region, int retries) {
        int retryCount = 0
        
        while(true) {
            try {
                run(region)
                return
            }
            catch(Throwable e) {
                if(++retryCount > retries)
                    throw e
                log.info "Retry $retryCount of $retries: " + e.toString()
                Thread.sleep(retryCount * 5000)
                try {
                    println "ls returns: " + "ls $bam.samFile.absolutePath".execute().text
                    this.bam = new SAM(this.bam.samFile.absolutePath)
                }
                catch(Throwable t) {
                   // ignore 
                }
            }
        }
    }

    @CompileStatic
    void run(Region region) {
        
        noiseState = new NoiseState(
            over:region, 
            softClipNoiseThreshold: softClipNoiseThreshold,
            maxBreakpointsInWindow: maxBreakpointsInWindow
        )
        
        int halfWindowSize = (int)(READ_WINDOW_SIZE / (int)2)
        
        this.startTimeMs = System.currentTimeMillis()
        
        boolean verbose = this.verbose
            
        // Running count of how many soft clipped reads were observed in the window
        int windowSoftClips = 0
            
        ProgressCounter counter = new ProgressCounter(withRate:true, withTime:true)
        counter.extra = {
            "Breakpoints: $countInteresting, anomalous=$filter.countAnomalous, chimeric=$filter.countChimeric, lowqual=$filter.countLowQual, adapter=$filter.countAdapter noisy=$countNoisy contam=$filter.countContam softclippedBuffer=$windowStats.softClipped"
        }
        
        windowStats.startPosition = region.from + halfWindowSize
        
        List<SAMRecord> emptyList = Collections.emptyList()
        
        String regionChr = region.chr
        String genomeBuild = bam.sniffGenomeBuild()
        if(genomeBuild?.startsWith('GRCh'))
            regionChr = regionChr.replaceAll('^chr','')
            
        bam.movingWindow(READ_WINDOW_SIZE, regionChr, region.from,region.to, { ReadWindow readWindow ->
            
            counter.count()
                
            TreeMap<Integer,List<SAMRecord>> window = readWindow.window
            int pos = readWindow.pos
                
            windowStats.update(readWindow)
                 
            List<SAMRecord> reads = window[pos]
            if(noiseState.update(windowStats, readWindow)) {
                ++countNoisy
                return
            }
            
            if(reads == null) 
                reads = emptyList
            
            List priorReads = aheadCache.remove(pos)
            Map<CigarOperator,List<SAMRecord>> readGroups = reads.groupBy { read ->
                read.cigar.cigarElements[0].operator
            }
            
            List<SAMRecord> localReads = readGroups[CigarOperator.S]?:emptyList
            
            updateAheadCache(readGroups)
                    
            List<SAMRecord> readsToSend = 
                priorReads ?
                    localReads + priorReads
                :
                    localReads
                
            if(pos == debugPosition) {
                println "Debug reads are: $readsToSend"
            }
            
            if(!readsToSend || readsToSend.isEmpty()) 
                return
               
            if(dedupeReads)
                readsToSend = dedupe(readsToSend)
            
            countInteresting += reads.size()
            if(breakpointListener != null) {
                breakpointListener.send(new BreakpointMessage(chr: region.chr, pos:pos, reads: readsToSend, sample:sampleId))
            }
            
        }, filter.&isReadInteresting) 
            
        counter.end()
        
        this.endTimeMs = System.currentTimeMillis()
    }
    
    @CompileStatic
    List<SAMRecord> dedupe(List<SAMRecord> reads) {
        Map grouped = reads.groupBy { SAMRecord read ->
            getReadStart(read) + "-" + getReadEnd(read)
        }
        
        return grouped.collect { Map.Entry<String, List<SAMRecord>> entry ->
           // Use only the first read from any group that has the same start and end position
           entry.value[0] 
        }
    }
    
    @CompileStatic
    int getReadStart(SAMRecord read) {
        List<CigarElement> cigar = read.cigar.cigarElements
        if(cigar[0].operator == CigarOperator.SOFT_CLIP) {
            // soft clipped at start - read start is alignment start - soft clip size
            return read.alignmentStart - cigar[0].length
        }
        else
            return read.alignmentStart
    }
    
    @CompileStatic
    int getReadEnd(SAMRecord read) {
        List<CigarElement> cigar = read.cigar.cigarElements
        if(cigar[-1].operator == CigarOperator.SOFT_CLIP) {
            // soft clipped at start - read start is alignment start - soft clip size
            return read.alignmentEnd + cigar[-1].length
        }
        else
            return read.alignmentEnd
    }
     
            
    @CompileStatic
    void updateAheadCache(Map<CigarOperator,List<SAMRecord>> readGroups) {
        // Add any bp discovered downstream to the read-ahead-cache
        readGroups[CigarOperator.M]?.groupBy{it.alignmentEnd}?.each { Integer pos, List<SAMRecord> records ->
//            println "Add ${records.size()} records to aheadcache at $pos"
            aheadCache.get(pos,[]).addAll(records)
        }
    }
    
    void setDebugRead(String r) {
        this.debugRead = r
        this.filter.debugRead = r
    }
}
