/*
 * Copyright (C) 2016
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe, Gerd Petermann
 * Create date: 2016-12-28
 */
package func;

import org.junit.Test;

import func.lib.Outputs;
import func.lib.TestUtils;

/**
 * A basic check of various arguments that can be passed in.
 *
 * @author Gerd Petermann
 */
public class ArgsTest extends Base {
	@Test
	public void testHelp() {
		Outputs outputs = TestUtils.run("--help");
		outputs.checkNoError();
	}
}
