/*
 * Copyright (c) 2009, Chris Miller
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

package uk.me.parabola.splitter.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import uk.me.parabola.splitter.tools.Long2IntClosedMap;
import uk.me.parabola.splitter.tools.Long2IntClosedMapFunction;
import uk.me.parabola.splitter.tools.SparseLong2IntMap;

/**
 * 
 */
public class TestCustomCollections {

	//@Test(expectedExceptions = IllegalArgumentException.class)
	//public void testInit() {
	//	new IntObjMap<String>(123, 0.5f);
	//}

	@Test
	public static void testLong2IntMap() {
		testMap(new Long2IntClosedMap("test", 10000, -1));
	}

	private static void testMap(Long2IntClosedMapFunction map) {
		int val;
		for (int i = 1; i < 1000; i++) {
			int j = map.add((long)i*10, i);
			Assert.assertEquals(j, i-1);
			Assert.assertEquals(map.size(), i);
		}

		for (int i = 1; i < 1000; i++) {
			int pos = map.getKeyPos(i*10);
			Assert.assertEquals(i, pos+1);
		}

		for (int i = 1; i < 1000; i++) {
			val = map.getRandom(i*10);
			Assert.assertEquals(i, val);
		}

		try {
			map.switchToSeqAccess(null);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try{
			val = map.getRandom(5);
		} catch (IllegalArgumentException e){
			Assert.assertEquals(e.getMessage(), "random access on sequential-only map requested");
		}
		val = map.getSeq(5);
		Assert.assertEquals(val,-1);
		val = map.getSeq(10);
		Assert.assertEquals(val,1);
		val = map.getSeq(19);
		Assert.assertEquals(val,-1);
		val = map.getSeq(30);
		Assert.assertEquals(val,3);

		map.finish();
	}

	private static void testVals(SparseLong2IntMap map, long idOffset, List<Integer> vals) {
		map.clear();
		map.put(1, 0);
		long key = 128;
		for (int val : vals) {
			map.put(idOffset + key++, val);
		}
		map.put(1, 0); // trigger saving of chunk
		key = 128;
		for (int val : vals) {
			Assert.assertEquals(map.get(idOffset + key++), val);
		}
		map.clear();
	}

	@Test
	public static void testSparseLong2IntMap() {
		ByteBuffer buf = ByteBuffer.allocate(4);
		for (int i = 0; i < 32; i++) {
			int val = 1 << i;
			do {
				for (int j = 1; j <= 4; j++) {
					int bytesToUse = j;
					if (bytesToUse == 1 && val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE
							|| bytesToUse == 2 && val >= Short.MIN_VALUE && val <= Short.MAX_VALUE
							|| bytesToUse == 3 && val >= -0x800000 && val <= 0x7fffff) {
						buf.clear();
						SparseLong2IntMap.putVal(buf, val, bytesToUse);
						buf.flip();
						Assert.assertEquals(val, SparseLong2IntMap.getVal(buf, bytesToUse));
					}
				}
				val = ~val;
			} while (val < 0);
		}

		testMap(0L);
		testMap(-10000L);
		testMap(1L << 35);
		testMap(-1L << 35);
	}

	private static void testMap(long idOffset) {
		SparseLong2IntMap map = new SparseLong2IntMap("test");
		map.defaultReturnValue(Integer.MIN_VALUE);

		// special patterns
		testVals(map, idOffset, Arrays.asList(1)); // single value chunk with 1 byte value
		testVals(map, idOffset, Arrays.asList(1000)); // single value chunk with 2 byte value
		testVals(map, idOffset, Arrays.asList(33000)); // single value chunk with 3 byte value
		testVals(map, idOffset, Arrays.asList(1<<25)); // single value chunk with 4 byte value
		testVals(map, idOffset, Arrays.asList(856, 856, 844, 844, 646, 646, 646, 646, 646, 646));
		testVals(map, idOffset, Arrays.asList(260*256, 31*256, 31*256, 24*256));
		testVals(map, idOffset, Arrays.asList(260, 31, 31, 24));
		testVals(map, idOffset, Arrays.asList(137, 110, 114, 128, 309, 114));
		testVals(map, idOffset, Arrays.asList(254, 12, 12, 12, 12));
		testVals(map, idOffset, Arrays.asList(254, 254, 12, 12));
		testVals(map, idOffset, Arrays.asList(254, 12, 13));
		testVals(map, idOffset, Arrays.asList(1000, 800, 700, 820));
		testVals(map, idOffset, Arrays.asList(1000, 1000, 700));
		testVals(map, idOffset, Arrays.asList(-32519, 255, -32519));
		testVals(map, idOffset, Arrays.asList(-1, 1, 200, 1));
		testVals(map, idOffset, Arrays.asList(Integer.MIN_VALUE + 1, 1234));
		testVals(map, idOffset, Arrays.asList(Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1, 1234));
		testVals(map, idOffset, Arrays.asList(Integer.MIN_VALUE + 1, 1234, Integer.MIN_VALUE + 1));

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

		// random read access 
		for (int i = 1; i < 1000; i++) {
			int key = (int) Math.max(1, (Math.random() * 1000));
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
		Assert.assertEquals(map.get(idOffset + 1010), 333);
		for (int i = 1; i < 1000; i++) {
			int key = 1000 + (int) (Math.random() * 200);
			
			Assert.assertEquals(map.get(idOffset + key), 333);
		}


		for (int i = -2000; i < -1000; i++) {
			Assert.assertEquals(map.get(idOffset + i), Integer.MIN_VALUE);
		}
		for (int i = -2000; i < -1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			Assert.assertEquals(b, false);
		}
		long mapSize = map.size();
		// seq. update existing records 
		for (int i = 1; i < 1000; i++) {
			int j = map.put(idOffset + i, i+333);
			Assert.assertEquals(j, i);
			Assert.assertEquals(map.size(), mapSize);
		}
		// random read access 3, update existing entries 
		for (int i = 1; i < 1000; i++) {
			int j = map.put(idOffset + i, i+555);
			Assert.assertEquals(true, j == i+333 | j == i+555);
			Assert.assertEquals(map.size(), mapSize);
		}
				
		Assert.assertEquals(map.get(idOffset + 123456), Integer.MIN_VALUE);
		map.put(idOffset + 123456,  999);
		Assert.assertEquals(map.get(idOffset + 123456), 999);
		map.put(idOffset + 123456,  888);
		Assert.assertEquals(map.get(idOffset + 123456), 888);

		Assert.assertEquals(map.get(idOffset - 123456), Integer.MIN_VALUE);
		map.put(idOffset - 123456,  999);
		Assert.assertEquals(map.get(idOffset - 123456), 999);
		map.put(idOffset - 123456,  888);
		Assert.assertEquals(map.get(idOffset - 123456), 888);
		map.put(idOffset + 3008,  888);
		map.put(idOffset + 3009,  888);
		map.put(idOffset + 3010,  876);
		map.put(idOffset + 3011,  876);
		map.put(idOffset + 3012,  678);
		map.put(idOffset + 3013,  678);
		map.put(idOffset + 3014,  678);
		map.put(idOffset + 3015,  678);
		map.put(idOffset + 3016,  678);
		map.put(idOffset + 3017,  678);
		map.put(idOffset + 4000,  888);
		map.put(idOffset + 4001,  888);
		map.put(idOffset + 4002,  876);
		map.put(idOffset + 4003,  876);
		// update the first one
		map.put(idOffset + 3008,  889);
		// update the 2nd one
		map.put(idOffset + 4000,  889);
		// add a very different key
		map.put(idOffset + 5000,  889);
		map.put(idOffset + 5001,  222);
		Assert.assertEquals(map.get(idOffset + 3008), 889);
		Assert.assertEquals(map.get(idOffset + 3009), 888);
		Assert.assertEquals(map.get(idOffset + 3010), 876);
		Assert.assertEquals(map.get(idOffset + 3011), 876);
		Assert.assertEquals(map.get(idOffset + 3012), 678);
		Assert.assertEquals(map.get(idOffset + 3013), 678);
		Assert.assertEquals(map.get(idOffset + 3014), 678);
		Assert.assertEquals(map.get(idOffset + 4000), 889);
		Assert.assertEquals(map.get(idOffset + 4001), 888);
		Assert.assertEquals(map.get(idOffset + 4002), 876);
		Assert.assertEquals(map.get(idOffset + 4003), 876);
		Assert.assertEquals(map.get(idOffset + 5000), 889);
		Assert.assertEquals(map.get(idOffset + 5001), 222);
		
		map.clear();
		// special pattern 1
		Assert.assertEquals(map.put(idOffset + 1, 0), Integer.MIN_VALUE);
		Assert.assertEquals(map.put(idOffset + 65, -1), Integer.MIN_VALUE);
		Assert.assertEquals(map.get(idOffset + 999), Integer.MIN_VALUE);
		Assert.assertEquals(map.get(idOffset + 1), 0);
		Assert.assertEquals(map.get(idOffset + 65), -1);

		// special pattern 2
		map.clear();
		
		

		
		map.clear();
		// larger values
		for (int i = 100_000; i < 110_000; i++) {
			map.put(idOffset + i, i);
		}
		for (int i = 100_000; i < 110_000; i++) {
			Assert.assertEquals(map.get(idOffset + i), i);
		}
		Random random = new Random(101);
		Map<Long,Integer> ref = new HashMap<>();
		// special cases long chunks (all 64 values used and random
		for (int i = 0; i < 1000; i++) {
			map.put(idOffset + i, random.nextInt(Integer.MAX_VALUE));
		}
//		map.stats(0);
		ref.entrySet().forEach(e -> {
			int val = map.get(e.getKey());
			Assert.assertEquals(map.get(e.getValue()), val);
		});
		
		
		ref.clear();
		map.clear();
		for (int i = 0; i < 100_00; i++) {
			long id = Math.round((1L << 29) * random.nextDouble());
			int val = (-1 * (1 << 20) + (int) Math.round((1 << 20) * random.nextDouble()));
			map.put(idOffset + id, val);
			ref.put(idOffset + id, val);
		}
//		map.stats(0);
		ref.entrySet().forEach(e -> {
			long id = e.getKey(); 
			int val = map.get(id);
			Assert.assertEquals(map.get(id), val);
		});
	}
}
