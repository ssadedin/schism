import static org.junit.Assert.*

import org.junit.Test

import gngs.FASTA
import gngs.XPos
import schism.BreakpointInfo
import schism.BreakpointSampleInfo
import schism.SoftClipDirection

class BreakpointInfoTest {

    @Test
    void testMergeBreakpointInfo() {
        
        BreakpointSampleInfo sampleInfo1 = new BreakpointSampleInfo()
        sampleInfo1.with {
            direction = SoftClipDirection.FORWARD
            startClips = 4
            obs = 4
        }
        
        BreakpointSampleInfo sampleInfo2 = new BreakpointSampleInfo()
        sampleInfo2.with {
            direction = SoftClipDirection.FORWARD
            startClips = 8
            obs = 7
        } 
        
        Long bpId = XPos.computePos('X', 1)
        
        BreakpointInfo bpInfo1 = new BreakpointInfo(
            id: bpId,
            obs: 4,
            sampleCount: 1,
            observations: [sampleInfo1]
        )
        
        BreakpointInfo bpInfo2 = new BreakpointInfo(
            id: XPos.computePos('X', 1),
            obs: 7,
            sampleCount: 1,
            observations: [sampleInfo2]
        ) 
        
        bpInfo1.add(bpInfo2)
        
        assert bpInfo1.id == bpId
        assert bpInfo1.sampleCount == 2
        assert bpInfo1.observations.size() == 2
        assert bpInfo1.obs == 11
    }
    
    @Test
    void testReferenceSequence() {
        
        Long bpId = XPos.computePos('X', 200)
        
        BreakpointSampleInfo sampleInfo1 = new BreakpointSampleInfo()
        sampleInfo1.with {
            direction = SoftClipDirection.FORWARD
            startClips = 4
            obs = 4
        }
        
        BreakpointInfo bpInfo1 = new BreakpointInfo(
            id: bpId,
            chr: 'X',
            pos: 100,
            obs: 4,
            sampleCount: 1,
            observations: [sampleInfo1]
        )
        
        FASTA fasta = [
            basesAt: { String chr, long start, long end ->
                "AAAAAAAATTTTTTTT"
            }
        ] as FASTA
        
        def (opp, over) = bpInfo1.queryReference(fasta, 8)
        assert opp == "TTTTTTTT"
        assert over == "AAAAAAAA"

        
    }
}
