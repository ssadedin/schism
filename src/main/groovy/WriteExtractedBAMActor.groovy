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

import gngs.SAM
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
