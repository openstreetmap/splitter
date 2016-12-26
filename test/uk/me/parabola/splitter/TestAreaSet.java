/*
 * Copyright (C) 2012, Gerd Petermann
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package uk.me.parabola.splitter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the sparse BitSet implementation
 */
public class TestAreaSet {
	private final int NUM = 10000;
	private final int[] POS = { 1, 63, 64, 65, 4711, 78231};

	public void allTests() {
		testAreaSetRandom();
		testAreaSetSequential();
	}

	@Test
	public void testAreaSetSequential() {
		AreaSet set = new AreaSet();
		for (int i = 1; i < NUM; i++) {
			assertEquals("get(" + i + ")", false, set.get(i));
		}
		for (int i = 1; i < NUM; i++) {
			set.set(i);
			assertEquals("get(" + i + ")", true, set.get(i));
		}
		assertEquals("cardinality() returns wrong value", NUM - 1, set.cardinality());
		for (int i = 1; i < NUM; i++) {
			set.clear(i);
			assertEquals("get(" + i + ")", false, set.get(i));
			assertEquals("cardinality() returns wrong value", NUM - i - 1, set.cardinality());
		}

	}

	@Test
	public void testAreaSetRandom() {
		AreaSet set = new AreaSet();
		for (int i : POS) {
			set.set(i);
			assertEquals("get(" + i + ")", true, set.get(i));
			assertEquals("cardinality() returns wrong value", 1, set.cardinality());
			set.clear(i);
			assertEquals("get(" + i + ")", false, set.get(i));
			assertEquals("cardinality() returns wrong value", 0, set.cardinality());
		}
	}
}
