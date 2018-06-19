package schism

import gngs.FASTA
import gngs.PrefixTrie
import groovy.transform.CompileStatic
import groovy.util.logging.Log
import trie.TrieNode
import trie.TrieQuery

/**
 * Matches up genomic breakpoints from within a known set, based on 
 * complementary overlapping adjacent sequence.
 * <p>
 * For example:
 * <pre>
 * 
 *  TAGATCatagcatg ->            <- tagatcATAGCATG
 *        ^                               ^
 *       bp1                             bp2
 *       
 * </pre>
 * Here you can see that the soft clipped bases at one end match the reference
 * at the other. This allows us to infer a likely structural variant where the 
 * two breakpoints are in fact one in the sample.
 * <p>
 * To recognise this pattern, we need to index the reference sequence on each side of
 * each breakpoint, and check if it matches the soft clipped bases next to another
 * breakpoints. There are a range of complications to this:
 * <p>
 *  <li>Depending on the type of SV, we might actually observe reversed or reverse
 *      complemented bases in the soft clipping
 *  <li>Some breakpoints will be adjacent to very common sequence, and in these cases
 *      there will be very many partners. In these scenarios we would like to implement
 *      some heuristics to identify the most likely partner from the many possible.
 *  <li>There could be read errors or actual polymorphisms in the soft clipped bases. So
 *      in doing the matching we would like to allow for mismatches and indels - without
 *      allowing for too much flexibility which would open up too many possibilities.
 * 
 * @author Simon Sadedin
 */
@Log
class BreakpointConnector {
    
    int softClipSize = 15
    
    int maxPartnersForOutput = 40
    
    int maxPartnersToJoin = 7
    
    int partnerIndexBases = 5
    
    int partnered = 0
    
    Set<String> commonPartners = new HashSet(1000)
    
    List<BreakpointInfo> breakpoints = []
    
    PrefixTrie<BreakpointInfo> breakpointSequenceIndex = new PrefixTrie()
    
    @CompileStatic
    void indexBreakpoint(BreakpointInfo bpInfo, String [] reference, boolean verbose) {
        
        this.breakpoints.add(bpInfo)
        
        String opposingReference = reference[0]
        
        if(verbose)
            log.info "Indexing opposing reference for $bpInfo.id: " + opposingReference
            
        BreakpointSampleInfo obs = bpInfo.observations[0]
        int offset = 1
        int start = 0
        int end = partnerIndexBases+1
       
        if(verbose) {
            log.info "index forward $bpInfo.id: " + opposingReference.take(softClipSize)
            log.info "index reverse $bpInfo.id: " + (String)opposingReference.take(softClipSize).reverse()
        }
        
        breakpointSequenceIndex.add((String)opposingReference.take(softClipSize), bpInfo)
        breakpointSequenceIndex.add((String)opposingReference.take(softClipSize).reverse(), bpInfo)
    } 
    
    void partnerBreakpoints() {
        List<BreakpointInfo> outputBreakpoints = new ArrayList(breakpoints.size())
        for(BreakpointInfo bp in breakpoints) {
            
            boolean verbose = false
//            if(bp.pos == 15460789 || bp.pos == 15461865) {
//                println "Debug breakpoint: $bp with bases: " + bp.observations[0].bases
//                verbose = true
//                trie.TrieNode.verbose = true
//            }

            // if there is a consensus among the soft clip, check if there is a partner sequence
            // inthe database
            BreakpointInfo partner = null
            BreakpointSampleInfo bpsi = bp.observations[0]
            if(bpsi.consensusScore > 10.0) { // a bit arbitrary
               
                // Note we search for 1 bp less than the soft clip size because we are allowing for
                // a single bp deletion. If our query has a deletion, then our query is effectively 
                // one base longer which won't be in the prefix trie. This would force the trie to 
                // cost it as an insertion which we do not want to do
                String forwardSequence = (String)bpsi.bases.take(softClipSize-1)
                String reverseSequence = (String)bpsi.bases.reverse().take(softClipSize-1)
                String reverseComplement = FASTA.reverseComplement(bpsi.bases).take(softClipSize-1)
                
                if(forwardSequence in commonPartners) {
                    log.info "$bp has Recurrent common partner sequence $forwardSequence"
                    continue
                }
                
                if(verbose)
                     log.info "Searching forward for $forwardSequence (bp=$bp.id)"
                List<TrieQuery<BreakpointInfo>> forwardPartners = breakpointSequenceIndex.query(forwardSequence, 1,1,1,5)
                
                if(verbose)
                     log.info "Searching reverse for $reverseSequence (bp=$bp.id)"
                List<TrieQuery<BreakpointInfo>> reversePartners = breakpointSequenceIndex.query(reverseSequence, 1,1,1,5)
                
                List<TrieQuery<BreakpointInfo>> reverseComplementPartners = breakpointSequenceIndex.query(reverseComplement, 1,1,1,5) 
                if(verbose)
                     log.info "Searching reverse complement: $reverseComplement (bp=$bp.id) finds " + reverseComplementPartners.size() + " with costs " + 
                              reverseComplementPartners.collect { TrieQuery q -> q.cost(TrieNode.DEFAULT_COSTS) }.join(',')
                              
                List<TrieQuery> allPartners = forwardPartners + reversePartners + reverseComplementPartners
               
                List<TrieQuery<BreakpointInfo>> partnerQueries = (allPartners).grep { TrieQuery q ->
                    q.cost(TrieNode.DEFAULT_COSTS) < 5.0
                }
                
                if(partnerQueries.size()>this.maxPartnersToJoin) {
                    log.info "Too many partners (${partnerQueries.size()}) to link $bp based on $forwardSequence"
                    commonPartners.add(forwardSequence)
                    commonPartners.add(reverseSequence)
                    commonPartners.add(reverseComplement)
                }
                else {
                    
                    chooseBestPartner(bp, bpsi, partnerQueries)
                    if(bpsi.partner)
                        ++partnered
                }
            }
            trie.TrieNode.verbose = false
            
//            if(partners!=null && partners.size()> maxPartnersForOutput) {
//                log.info "$bp has too many partners: ignoring"
//                ++tooPromiscuous
//            }
//            else
//            {
                outputBreakpoints.add(bp)
//            }
              this.breakpoints = outputBreakpoints;
        }
    }
    
    /**
     * Choose the best partner from among the given list of queries for partners based
     * on overlap of breakpoint sequence.
     * 
     * @param bp
     * @param bpsi
     * @param partnerQueries
     */
    void chooseBestPartner(BreakpointInfo bp, BreakpointSampleInfo bpsi, List<TrieQuery<BreakpointInfo>> partnerQueries) {
        
        if(partnerQueries.isEmpty())
            return
        
        partnerQueries = partnerQueries.sort { TrieQuery q ->
            q.cost(TrieNode.DEFAULT_COSTS)
        }

        int lowestCost = (int) partnerQueries[0].cost(TrieNode.DEFAULT_COSTS)

        partnerQueries = partnerQueries.takeWhile { TrieQuery q ->
            (int)q.cost(TrieNode.DEFAULT_COSTS) == lowestCost
        }

        List<BreakpointInfo> partners = (List<BreakpointInfo>)partnerQueries.collect { TrieQuery<BreakpointInfo> q ->
            q.result
        }.flatten()
        .grep { BreakpointInfo p -> bp.id != p.id }
        
        log.info "Found ${partners.size()} equal partners for $bp starting with: ${partners.take(2).join(',')}"
        if(partners.isEmpty())
            return

        if(partners.size() == 1)
            bpsi.partner = partners[0]
        else
            chooseBetweenEqualCostPartners(bp, bpsi, partners)
    }
    
    /**
     * Choose the best partner from a list of partners who have equally well matching 
     * sequence at their breakpoints.
     * 
     * @param bpsi
     * @param partners
     */
    void chooseBetweenEqualCostPartners(BreakpointInfo bp, BreakpointSampleInfo bpsi, List<BreakpointInfo> partners) {
        
        // If any partner already points back to us, partner with them
        BreakpointInfo reciprocalPartner = partners.find { BreakpointInfo p -> p.observations[0].partner?.id == bp.id }
        if(reciprocalPartner) {
            log.info "Partnered $bp.id to reciprical breakpoint $reciprocalPartner"
            bpsi.partner = reciprocalPartner
        }
        else {
            // Of all the partners with equal score on the same chromosome, take the closest
            bpsi.partner = partners.grep { BreakpointInfo p -> p.chr == bp.chr }?.min { BreakpointInfo partnerBp ->
                Math.abs(partnerBp.id - bp.id)
            }
                            
            if(bpsi.partner) {
                log.info "Partnered $bp.id to closest on same chromosome: $bpsi.partner"
            }
            else {
                log.info "Partnered $bp.id to minimum cost match on other chr: " + partners[0].id
                bpsi.partner = partners[0]
            }
        }
    }
    
    
    
}
