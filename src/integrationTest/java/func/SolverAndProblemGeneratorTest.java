package func;

import func.lib.Args;
import func.lib.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import uk.me.parabola.splitter.Main;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static org.junit.Assert.*;

// get src="http://www.mkgmap.org.uk/testinput/osm/alaska-2016-12-27.osm.pbf"
// dest="src/test/resources/in/osm/alaska-2016-12-27.osm.pbf" usetimestamp="true"
// ignoreerrors="true"
// get src="http://www.mkgmap.org.uk/testinput/osm/hamburg-2016-12-26.osm.pbf"
// dest="src/test/resources/in/osm/hamburg-2016-12-26.osm.pbf" usetimestamp="true"
// ignoreerrors="true"


/**
 * Compare file sizes with expected results. A very basic check that the size of the output files has not changed.
 * This can be used to make sure that a change that is not expected to change the output does not do so.
 * <p>
 * The sizes will have to be always changed when the output does change though.
 */
class SolverAndProblemGeneratorTest {

    @BeforeEach
    void baseSetup() {
        TestUtils.deleteOutputFiles();
    }

    @AfterEach
    void baseTeardown() {
        TestUtils.closeFiles();
    }

    @Test
    @Disabled("Failing, will be fixed when project is restructured")
    void testHamburg() throws Exception {
        runSplitter(Args.expectedHamburg, "--stop-after=gen-problem-list", Args.HAMBURG);
    }

    @Test
    @Disabled("Failing, will be fixed when project is restructured")
    void testAlaska() throws Exception {
        runSplitter(Args.expectedAlaska, "--stop-after=gen-problem-list", Args.ALASKA);
    }

    @Test
    @Disabled("Failing, will be fixed when project is restructured")
    public void testAlaskaOverlap() throws Exception {
        runSplitter(Args.expectedAlaskaOverlap, "--stop-after=split", "--keep-complete=false", Args.ALASKA);
    }

    @Test
    @Disabled("Failing, will be fixed when project is restructured")
    /* verifies that --max-areas has no effect on the output */
    public void testAlaskaMaxAreas7() throws Exception {
        runSplitter(Args.expectedAlaska, "--stop-after=gen-problem-list", "--max-areas=5", Args.ALASKA);
    }


    private static void runSplitter(Map<String, Integer> expected, String... optArgs) throws IOException {
        List<String> argsList = new ArrayList<>(Arrays.asList(Args.MAIN_ARGS));
        argsList.addAll(Arrays.asList(optArgs));

        Main.mainNoSystemExit(argsList.toArray(new String[0]));

        for (Entry<String, Integer> entry : expected.entrySet()) {
            String f = entry.getKey();
            long expectedSize = entry.getValue();
            assertTrue("no " + f + " generated", new File(f).exists());
            List<String> lines = Files.readAllLines(Paths.get(f, ""));
            long realSize = 0;

            for (String l : lines) {
                realSize += l.length();
            }

            assertEquals(f + " has wrong size", expectedSize, realSize);
        }
    }

    @Test
    public void testNoSuchFile() {
        Main.mainNoSystemExit("no-such-file-xyz.osm");
        assertFalse("no file generated", new File(Args.DEF_AREAS_LIST).exists());
    }

}
