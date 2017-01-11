/*
 * Copyright (C) 2008 Steve Ratcliffe
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
 * Author: Steve Ratcliffe
 * Create date: 11-Jan-2009
 */
package func;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.After;
import org.junit.Before;

import func.lib.Args;
import func.lib.TestUtils;

/**
 * Base class for tests with some useful routines.  It ensures that created
 * files are deleted before the test starts.
 *
 * @author Steve Ratcliffe
 */
public class Base {
	@Before
	public void baseSetup() {
		TestUtils.deleteOutputFiles();
	}

	@After
	public void baseTeardown() {
		TestUtils.closeFiles();
	}
}
