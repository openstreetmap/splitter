package func;

import func.lib.Outputs;
import func.lib.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A basic check of various arguments that can be passed in.
 *
 * @author Gerd Petermann
 */
public class ArgsTest {

	@BeforeEach
	public void baseSetup() {
		TestUtils.deleteOutputFiles();
	}

	@AfterEach
	public void baseTeardown() {
		TestUtils.closeFiles();
	}

	@Test
	public void testHelp() {
		Outputs outputs = TestUtils.run("--help");
		outputs.checkNoError();
	}
}
