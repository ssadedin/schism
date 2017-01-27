import java.util.Collection;

import org.broadinstitute.gatk.engine.walkers.ReadPairWalker;

import htsjdk.samtools.SAMRecord;

public class JavaInterestingReads extends ReadPairWalker<String, Integer> {

    public static void main(String[] args) {
        // TODO Auto-generated method stub

    }

    @Override
    public String map(Collection<SAMRecord> arg0) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Integer reduce(String arg0, Integer arg1) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Integer reduceInit() {
        // TODO Auto-generated method stub
        return null;
    }

}
