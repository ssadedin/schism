import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.SAMFileHeader
import htsjdk.samtools.SAMFileWriter
import htsjdk.samtools.SAMFileWriterFactory
import htsjdk.samtools.SAMRecord;;

class WriteExtractedBAMActor extends DefaultActor {
    
    SAM bam
    
    File outputFile
    
    SAMFileWriter w 
    
    int countWritten = 0
    
    String debugRead = null // "HJYFJCCXX160204:8:2106:27275:50463"
    
    WriteExtractedBAMActor(SAM bam, File outputFile)  {
        this.bam = bam
        this.outputFile = outputFile
        SAMFileWriterFactory f = new SAMFileWriterFactory()
        SAMFileHeader header = bam.samFileReader.fileHeader
        w = f.makeBAMWriter(header, false, outputFile)
    }
    
    void act() {
        loop {
            react { msg ->
                if(msg instanceof BreakpointMessage) {
                    processReads(msg.reads)
                }
                else 
                if(msg == "end") {
                    println "Closing bam file $outputFile after writing $countWritten reads"
                    w.close()
                    SAM.index(outputFile)
                    
                    terminate()
                }
            }
        }
    }
    
    void processReads(List<SAMRecord> reads) {
        for(SAMRecord read in reads) {
            
            if(debugRead && read.readName == debugRead) {
                println "Writing read $read"
            }
            
            ++countWritten
            w.addAlignment(read)
        }
    }
}
