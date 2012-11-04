/*
 * Copyright (c) 2009.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package uk.me.parabola.splitter;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 
 */
public class TestCustomCollections {

	//@Test(expectedExceptions = IllegalArgumentException.class)
	//public void testInit() {
	//	new IntObjMap<String>(123, 0.5f);
	//}

	@Test
	public void testLongShortMap() {
		testMap(new SparseLong2ShortMapInline(), 0L);
		testMap(new SparseLong2ShortMapInline(), -10000L);
		testMap(new SparseLong2ShortMapInline(), 1L << 35);
		testMap(new SparseLong2ShortMapInline(), -1L << 35);
	}

	private void testMap(SparseLong2ShortMapInline map, long idOffset) {
		map.defaultReturnValue((short) Short.MIN_VALUE);
   
		for (short i = 1; i < 1000; i++) {
			int j = map.put(idOffset + i, i);
			Assert.assertEquals(j, Short.MIN_VALUE);
			Assert.assertEquals(map.size(), i);
		}

		for (short i = 1; i < 1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			Assert.assertEquals(b, true);
		}

    
		for (short i = 1; i < 1000; i++) {
			Assert.assertEquals(map.get(idOffset + i), i);
		}

    // random read access 
		for (short i = 1; i < 1000; i++) {
        short key = (short) Math.max(1, (Math.random() * 1000));
			Assert.assertEquals(map.get(idOffset + key), key);
		}

		for (short i = 1000; i < 2000; i++) {
			Assert.assertEquals(map.get(idOffset + i), Short.MIN_VALUE);
		}
		for (short i = 1000; i < 2000; i++) {
			boolean b = map.containsKey(idOffset + i);
			Assert.assertEquals(b, false);
		}
		for (short i = 1000; i < 1200; i++) {
			short j = map.put(idOffset + i, (short) 333);
			Assert.assertEquals(j, Short.MIN_VALUE);
			Assert.assertEquals(map.size(), i);
		}
    // random read access 2 
		for (int i = 1; i < 1000; i++) {
        int key = 1000 + (short) (Math.random() * 200);
			Assert.assertEquals(map.get(idOffset + key), 333);
		}


		for (short i = -2000; i < -1000; i++) {
			Assert.assertEquals(map.get(idOffset + i), Short.MIN_VALUE);
		}
		for (short i = -2000; i < -1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			Assert.assertEquals(b, false);
		}

		Assert.assertEquals(map.get(idOffset + 123456), Short.MIN_VALUE);
		map.put(idOffset + 123456, (short) 999);
		Assert.assertEquals(map.get(idOffset + 123456), 999);
		map.put(idOffset + 123456, (short) 888);
		Assert.assertEquals(map.get(idOffset + 123456), 888);
   
		Assert.assertEquals(map.get(idOffset - 123456), Short.MIN_VALUE);
		map.put(idOffset - 123456, (short) 999);
		Assert.assertEquals(map.get(idOffset - 123456), 999);
		map.put(idOffset - 123456, (short) 888);
		Assert.assertEquals(map.get(idOffset - 123456), 888);
   
	}
	@Test
	public void testLongIntMap() {
		testMap(new SparseLong2IntMapInline(), 0L);
		testMap(new SparseLong2IntMapInline(), -10000L);
		testMap(new SparseLong2IntMapInline(), 1L << 35);
		testMap(new SparseLong2IntMapInline(), -1L << 35);
	}

	private void testMap(SparseLong2IntMapInline map, long idOffset) {
		map.defaultReturnValue((int) Integer.MIN_VALUE);
   
		for (int i = 1; i < 1000; i++) {
			int j = map.put(idOffset + i, i);
			Assert.assertEquals(j, Integer.MIN_VALUE);
			Assert.assertEquals(map.size(), i);
		}

		for (int i = 1; i < 1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			Assert.assertEquals(b, true);
		}

		for (int i = 1; i < 1000; i++) {
			Assert.assertEquals(map.get(idOffset + i), i);
		}

    // random read access 1 
		for (int i = 1; i < 1000; i++) {
        int key = (int) Math.max(1,Math.random() * 1000);
			Assert.assertEquals(map.get(idOffset + key), key);
		}

		for (int i = 1000; i < 2000; i++) {
			Assert.assertEquals(map.get(idOffset + i), Integer.MIN_VALUE);
		}
		for (int i = 1000; i < 2000; i++) {
			boolean b = map.containsKey(idOffset + i);
			Assert.assertEquals(b, false);
		}
		for (int i = 1000; i < 1200; i++) {
			int j = map.put(idOffset + i, 333);
			Assert.assertEquals(j, Integer.MIN_VALUE);
			Assert.assertEquals(map.size(), i);
		}
    // random read access 2 
		for (int i = 1; i < 1000; i++) {
        int key = 1000 + (short) (Math.random() * 200);
			Assert.assertEquals(map.get(idOffset + key), 333);
		}

		for (int i = -2000; i < -1000; i++) {
			Assert.assertEquals(map.get(idOffset + i), Integer.MIN_VALUE);
		}
		for (int i = -2000; i < -1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			Assert.assertEquals(b, false);
		}

		Assert.assertEquals(map.get(idOffset + 123456), Integer.MIN_VALUE);
		map.put(idOffset + 123456, (int) 999);
		Assert.assertEquals(map.get(idOffset + 123456), 999);
		map.put(idOffset + 123456, (int) 888);
		Assert.assertEquals(map.get(idOffset + 123456), 888);
   
		Assert.assertEquals(map.get(idOffset - 123456), Integer.MIN_VALUE);
		map.put(idOffset - 123456, (int) 999);
		Assert.assertEquals(map.get(idOffset - 123456), 999);
		map.put(idOffset - 123456, (int) 888);
		Assert.assertEquals(map.get(idOffset - 123456), 888);
   
	}
}
