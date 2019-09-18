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
 * Create date: 10-Jan-2009
 */
package func.lib;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import uk.me.parabola.splitter.Main;

/**
 * Useful routines to use during the functional tests.
 * 
 * @author Steve Ratcliffe
 * @author Gerd Petermann
 */
public class TestUtils {
	private static final List<String> files = new ArrayList<>();
	private static final Deque<Closeable> open = new ArrayDeque<>();

	static {
		files.add(Args.DEF_AREAS_KML);
		files.add(Args.DEF_AREAS_LIST);
		files.add(Args.DEF_AREAS_POLY);
		files.add(Args.DEF_PROBLEM_LIST);
		files.add(Args.DEF_DENSITIES);
		files.add(Args.DEF_TEMPLATE);

		Runnable r = TestUtils::deleteOutputFiles;
		Thread t = new Thread(r);
		Runtime.getRuntime().addShutdownHook(t);
	}

	/**
	 * Delete output files that were created by the tests.
	 * Used to clean up before/after a test.
	 */
	public static void deleteOutputFiles() {
		for (String fname : files) {
			File f = new File(fname);

			if (f.exists())
				assertTrue("delete existing file: " + f.getName(), f.delete());
		}
	}

	public static void closeFiles() {
		while (!open.isEmpty()) {
			try {
				open.remove().close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Run with a single argument.  The standard arguments are added first.
	 * @param arg The argument.
	 */
	public static Outputs run(String arg) {
		return run(new String[] {arg});
	}

	/**
	 * Run with the given args.  Some standard arguments are added first.
	 *
	 * To run without the standard args, use runRaw().
	 * @param in The arguments to use.
	 */
	public static Outputs run(String ... in) {
		List<String> args = new ArrayList<>(Arrays.asList(in));

		OutputStream outsink = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(outsink);

		OutputStream errsink = new ByteArrayOutputStream();
		PrintStream err = new PrintStream(errsink);

		PrintStream origout = System.out;
		PrintStream origerr = System.err;

		try {
			System.setOut(out);
			System.setErr(err);
			Main.mainNoSystemExit(args.toArray(new String[args.size()]));
		} finally {
			out.close();
			err.close();
			System.setOut(origout);
			System.setErr(origerr);
		}

		return new Outputs(outsink.toString(), errsink.toString());
	}
}
