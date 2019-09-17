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

package uk.me.parabola.splitter.tools;

import static org.junit.Assert.assertEquals;
import uk.me.parabola.splitter.tools.SparseBitSet;
import org.junit.Test;

/**
 * Unit tests for the sparse BitSet implementation
 */
public class SparseBitSetTest {
	private final int NUM = 10000;
	private final long[] POS = { 1, 63, 64, 65, 4711, 12345654321L };

	@Test
	public void testSparseBitSetSequential() {
		SparseBitSet sparseSet = new SparseBitSet();
		for (long i = 1; i < NUM; i++) {
			assertEquals("get(" + i + ")", false, sparseSet.get(i));
		}
		for (long i = 1; i < NUM; i++) {
			sparseSet.set(i);
			assertEquals("get(" + i + ")", true, sparseSet.get(i));
		}
		assertEquals("cardinality() returns wrong value", NUM - 1, sparseSet.cardinality());
		for (long i = 1; i < NUM; i++) {
			sparseSet.clear(i);
			assertEquals("get(" + i + ")", false, sparseSet.get(i));
			assertEquals("cardinality() returns wrong value", NUM - i - 1, sparseSet.cardinality());
		}

	}

	@Test
	public void testSparseBitSetRandom() {
		SparseBitSet sparseSet = new SparseBitSet();
		for (long i : POS) {
			sparseSet.set(i);
			assertEquals("get(" + i + ")", true, sparseSet.get(i));
			assertEquals("cardinality() returns wrong value", 1, sparseSet.cardinality());
			sparseSet.clear(i);
			assertEquals("get(" + i + ")", false, sparseSet.get(i));
			assertEquals("cardinality() returns wrong value", 0, sparseSet.cardinality());
		}

	}
}
