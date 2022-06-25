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
package schism

import gngs.AcknowledgeableMessage
import gngs.ProgressCounter
import gngs.ReadWindow
import gngs.Region
import gngs.Regions
import gngs.RegulatingActor
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
import java.util.logging.Logger

class BreakpointExtractor {
    
    final static Logger log = Logger.getLogger('BreakpointExtractor')
    
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
    
    String debugRead = "H3G5FCCXY170923:8:1118:28321:6987"
    
    int debugPosition = 101791685
    
    SAM bam = null
    
    BreakpointReadFilter filter = new BreakpointReadFilter()
    
    RegulatingActor breakpointListener = null
    
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
            
            if(reads?.any { it.readName == debugRead }) {
                log.info "DEBUG READ AT POS: " + pos
            }
            
            if(noiseState.update(windowStats, readWindow)) {
                if(pos == debugPosition) {
                    log.info "Debug position $pos is excluded by noise: " + noiseState + " window = " + readWindow
                    log.info "Excluding reads are: "
                    readWindow.window.grep { Map.Entry e -> ((List)e.value).size()>1}.collect { entry ->
                        Map.Entry e = (Map.Entry)entry
                        List<SAMRecord> noiseReads = (List<SAMRecord>)e.value
                        log.info "Reads: " + noiseReads*.readName
                    }
                }
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
                    (List<SAMRecord>)(localReads + priorReads)
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
                def bpMessage = new BreakpointMessage(chr: region.chr, pos:pos, reads: readsToSend, sample:sampleId)
                breakpointListener.sendTo(new AcknowledgeableMessage(bpMessage, breakpointListener.pendingMessageCount))
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
