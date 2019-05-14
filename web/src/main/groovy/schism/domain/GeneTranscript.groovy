package schism.domain

class GeneTranscript {
    
    static COLUMN_NAMES= [ "num", "tx", "chr", "strand", "tx_start", "tx_end", "cds_start", "cds_end", "exons", "starts", "ends", 
                           "u1","gene", "cdsStartStat","cdsEndStat","exonFrames"]

    String transcript
    
    String chr
    
    String strand 
    
    int txStart
    
    int txEnd
    
    int cdsStart
    
    int cdsEnd
    
    int numExons
    
    Map details
    
}
