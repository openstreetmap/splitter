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

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;
/**
 * 
 */
public class CustomCollectionsTest {

	//@Test(expectedExceptions = IllegalArgumentException.class)
	//public void testInit() {
	//	new IntObjMap<String>(123, 0.5f);
	//}

	@Test
	public void testLong2IntMap() {
		testMap(new Long2IntClosedMap("test", 10000, -1));
	}

	private static void testMap(Long2IntClosedMapFunction map) {
		int val;
		for (int i = 1; i < 1000; i++) {
			int j = map.add((long) i * 10, i);
			assertEquals(i - 1, j);
			assertEquals(i, map.size());
		}

		for (int i = 1; i < 1000; i++) {
			int pos = map.getKeyPos(i * 10);
			assertEquals(pos + 1, i);
		}

		for (int i = 1; i < 1000; i++) {
			val = map.getRandom(i * 10);
			assertEquals(val, i);
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
			assertEquals("random access on sequential-only map requested", e.getMessage());
		}
		val = map.getSeq(5);
		assertEquals(-1, val);
		val = map.getSeq(10);
		assertEquals(1, val);
		val = map.getSeq(19);
		assertEquals(-1, val);
		val = map.getSeq(30);
		assertEquals(3, val);

		map.finish();
	}

	private static void testVals(SparseLong2IntMap map, long idOffset, List<Integer> vals) {
		map.clear();
		map.put(1, -12000);
		long key = 128;
		for (int val : vals) {
			map.put(idOffset + key++, val);
		}
		map.put(1, 0); // trigger saving of chunk
		key = 128;
		for (int val : vals) {
			assertEquals("values " + vals.toString(), val, map.get(idOffset + key++));
		}
		map.clear();
	}

	@Test
	public void testSparseLong2IntMap() {
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
						assertEquals(SparseLong2IntMap.getVal(buf, bytesToUse), val);
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

	private static int UNASSIGNED = Integer.MIN_VALUE;
	private static void testMap(long idOffset) {
		SparseLong2IntMap map = new SparseLong2IntMap("test");
		map.defaultReturnValue(UNASSIGNED);

		// special patterns
		testVals(map, idOffset, Arrays.asList(1,2,1,1,1,2,1,1,2,1,1,2));
		testVals(map, idOffset, Arrays.asList(1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2,1,2));
		testVals(map, idOffset, Arrays.asList(66560, 7936, 7936, 6144));
		testVals(map, idOffset, Arrays.asList(Integer.MIN_VALUE + 1, 1234));
		testVals(map, idOffset, Arrays.asList(1)); // single value chunk with 1 byte value
		testVals(map, idOffset, Arrays.asList(1000)); // single value chunk with 2 byte value
		testVals(map, idOffset, Arrays.asList(33000)); // single value chunk with 3 byte value
		testVals(map, idOffset, Arrays.asList(1<<25)); // single value chunk with 4 byte value
		testVals(map, idOffset, Arrays.asList(856, 856, 844, 844, 646, 646, 646, 646, 646, 646));
		testVals(map, idOffset, Arrays.asList(260, 31, 31, 24));
		testVals(map, idOffset, Arrays.asList(137, 110, 114, 128, 309, 114));
		testVals(map, idOffset, Arrays.asList(254, 12, 12, 12, 12));
		testVals(map, idOffset, Arrays.asList(254, 254, 12, 12));
		testVals(map, idOffset, Arrays.asList(254, 12, 13));
		testVals(map, idOffset, Arrays.asList(1000, 800, 700, 820));
		testVals(map, idOffset, Arrays.asList(1000, 1000, 700));
		testVals(map, idOffset, Arrays.asList(-32519, 255, -32519));
		testVals(map, idOffset, Arrays.asList(-1, 1, 200, 1));
		testVals(map, idOffset, Arrays.asList(Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1, 1234));
		testVals(map, idOffset, Arrays.asList(Integer.MIN_VALUE + 1, 1234, Integer.MIN_VALUE + 1));

		for (int i = 1; i < 1000; i++) {
			int j = map.put(idOffset + i, i);
			assertEquals(UNASSIGNED, j);
			assertEquals(i, map.size());
		}

		for (int i = 1; i < 1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			assertEquals(true, b);
		}


		for (int i = 1; i < 1000; i++) {
			assertEquals(i, map.get(idOffset + i));
		}

		// random read access 
		for (int i = 1; i < 1000; i++) {
			int key = (int) Math.max(1, (Math.random() * 1000));
			assertEquals(key, map.get(idOffset + key));
		}

		for (int i = 1000; i < 2000; i++) {
			assertEquals(UNASSIGNED, map.get(idOffset + i));
		}
		for (int i = 1000; i < 2000; i++) {
			boolean b = map.containsKey(idOffset + i);
			assertEquals(false, b);
		}
		for (int i = 1000; i < 1200; i++) {
			int j = map.put(idOffset + i, 333);
			assertEquals(UNASSIGNED, j);
			assertEquals(i, map.size());
		}
		// random read access 2 
		assertEquals(333, map.get(idOffset + 1010));
		for (int i = 1; i < 1000; i++) {
			int key = 1000 + (int) (Math.random() * 200);
			assertEquals(333, map.get(idOffset + key));
		}

		for (int i = -2000; i < -1000; i++) {
			assertEquals(UNASSIGNED, map.get(idOffset + i));
		}
		for (int i = -2000; i < -1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			assertEquals(false, b);
		}
		long mapSize = map.size();
		// seq. update existing records 
		for (int i = 1; i < 1000; i++) {
			int j = map.put(idOffset + i, i+333);
			assertEquals(i, j);
			assertEquals(mapSize, map.size());
		}
		// random read access 3, update existing entries 
		for (int i = 1; i < 1000; i++) {
			int j = map.put(idOffset + i, i+555);
			assertEquals(true, j == i+333 | j == i+555);
			assertEquals(mapSize, map.size());
		}
				
		assertEquals(UNASSIGNED, map.get(idOffset + 123456));
		map.put(idOffset + 123456,  999);
		assertEquals(999, map.get(idOffset + 123456));
		map.put(idOffset + 123456,  888);
		assertEquals(888, map.get(idOffset + 123456));

		assertEquals(UNASSIGNED, map.get(idOffset - 123456));
		map.put(idOffset - 123456,  999);
		assertEquals(999, map.get(idOffset - 123456));
		map.put(idOffset - 123456,  888);
		assertEquals(888, map.get(idOffset - 123456));
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
		assertEquals(889, map.get(idOffset + 3008));
		assertEquals(888, map.get(idOffset + 3009));
		assertEquals(876, map.get(idOffset + 3010));
		assertEquals(876, map.get(idOffset + 3011));
		assertEquals(678, map.get(idOffset + 3012));
		assertEquals(678, map.get(idOffset + 3013));
		assertEquals(678, map.get(idOffset + 3014));
		assertEquals(889, map.get(idOffset + 4000));
		assertEquals(888, map.get(idOffset + 4001));
		assertEquals(876, map.get(idOffset + 4002));
		assertEquals(876, map.get(idOffset + 4003));
		assertEquals(889, map.get(idOffset + 5000));
		assertEquals(222, map.get(idOffset + 5001));
		
		map.clear();
		// special pattern 1
		assertEquals(UNASSIGNED, map.put(idOffset + 1, 0));
		assertEquals(UNASSIGNED, map.put(idOffset + 65, -1));
		assertEquals(UNASSIGNED, map.get(idOffset + 999));
		assertEquals(0, map.get(idOffset + 1));
		assertEquals(-1, map.get(idOffset + 65));

		map.clear();
		map.put(idOffset + 1, 22);
		map.put(idOffset + 5, 22);
		map.put(idOffset + 100, 44);
		assertEquals(22, map.put(idOffset + 5, 33));

		
		map.clear();
		// larger values
		for (int i = 100_000; i < 110_000; i++) {
			map.put(idOffset + i, i);
		}
		for (int i = 100_000; i < 110_000; i++) {
			assertEquals(i, map.get(idOffset + i));
		}
		map.clear();
		Random random = new Random(101);
		Map<Long,Integer> ref = new HashMap<>();
		// special cases long chunks (all 64 values used and random
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 1000; j++) {
				int val = random.nextInt(Integer.MAX_VALUE);
				map.put(idOffset + j, val);
				ref.put(idOffset + j, val);
			}
		}
//		map.stats(0);
		ref.entrySet().forEach(e -> {
			long id = e.getKey();
			int val = map.get(id);
			assertEquals("id=" + id, (int) e.getValue(), val);
		});
		
		
		ref.clear();
		map.clear();
		for (int i = 0; i < 10_000; i++) {
			long id = Math.round((1L << 29) * random.nextDouble());
			int val = (-1 * (1 << 20) + (int) Math.round((1 << 20) * random.nextDouble()));
			map.put(idOffset + id, val);
			ref.put(idOffset + id, val);
		}
//		map.stats(0);
		ref.entrySet().forEach(e -> {
			long id = e.getKey();
			int val = map.get(id);
			assertEquals("id=" + id, (int) e.getValue(), val);
		});
		
		// simulate split where all nodes fall into same tile
		map.clear();
		for (int i = 0; i < 1 << 27; i+=64) {
			map.put(idOffset + i,  12);
		}
		assertEquals("id=" + idOffset+ 2048, 12, map.get(idOffset + 2048));
		assertEquals("id=" + idOffset+ 2048*1024, 12, map.get(idOffset + 2048*1024));
		assertEquals("id=" + idOffset+ 2048*1024 + 1, UNASSIGNED, map.get(idOffset + 2048*1024+1));
		return;
	}
}
