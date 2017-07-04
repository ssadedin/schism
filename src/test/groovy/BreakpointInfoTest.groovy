import static org.junit.Assert.*

import org.junit.Test

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
}
