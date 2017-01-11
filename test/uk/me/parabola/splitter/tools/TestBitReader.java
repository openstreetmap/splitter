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
 */
package uk.me.parabola.splitter.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class TestBitReader {

	/**
	 * Very simple test that the bit reader is working.
	 * @author Steve Ratcliffe for mkgmap
	 * @author Gerd Petermann
	 */
	@Test
	public void testGetBits() {
		// Add your code here
		BitReader br = new BitReader(new byte[]{
				(byte) 0xf1, 0x73, (byte) 0xc2, 0x5
		});

		assertTrue("first bit", br.get1());
		assertEquals("five bits", 0x18, br.get(5));
		assertEquals("four bits", 0xf, br.get(4));
		assertEquals("sixteen bits", 0x709c, br.get(16));
	}

	@Test
	public void testSpecialNegative() {
		BitReader br = new BitReader(new byte[]{0x24, 0xb});

		int s = br.sget2(3);
		assertEquals(-12, s);
	}
	@Test
	public void testSpecialNegative2() {
		BitReader br = new BitReader(new byte[]{0x2c, 0x0});

		int s = br.sget2(3);
		assertEquals(-6, s);
	}

	@Test
	public void testSpecialPositive() {
		BitReader br = new BitReader(new byte[]{(byte) 0xa4, 0});

		int s = br.sget2(3);
		assertEquals(8, s);
	}
	
	@Test
	public void testWriteReadSingleBit() {
		BitWriter bw = new BitWriter();
		final int testVal  = 1231212311;
		int n = 0;
		
		int v = testVal;
		while (v > 0) {
			bw.put1(v % 2 != 0);
			v >>= 1;
			n++;
		}
		assertEquals(n, bw.getBitPosition());
		BitReader br = new BitReader(bw.getBytes());
		v = testVal;
		while (n-- > 0) {
			boolean b = br.get1();
			assertEquals(v % 2 != 0, b);
			v >>= 1;
		}
	}

	@Test
	public void testDynAlloc() {
		BitWriter bw = new BitWriter(10);
		int n = 0;
		int bits = 9;
		for (int i = 0; i < 100; i++) {
			bw.putn(i, bits);
			n += bits;
		}
		assertEquals(n, bw.getBitPosition());
		for (int i = 0; i < 100; i++) {
			bw.put1(i % 3 == 0);
			n += 1;
		}
		assertEquals(n, bw.getBitPosition());
	}

	@Test
	public void testWriteReadSigned() {
		for (int n = 2; n <= 32; n++) {
			testWriteReadSigned(n);
		}
	}

	private static void testWriteReadSigned(int nbits) {
		int[] TEST_VALS = { Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -40, -1, 0, 1, 40, Integer.MAX_VALUE - 1, Integer.MAX_VALUE };
		for (int i = 0; i < TEST_VALS.length; i++) {
			BitWriter bw = new BitWriter();
			int v = TEST_VALS[i];
			if (nbits < 30 && (v < -1000 || v > 1000))
				continue;
			bw.sputn2(v,nbits);
			boolean checkSimple = false;
			if ((1l << (nbits-1)) > Math.abs((long) v) || nbits == 32) {
				bw.sputn(v, nbits);
				checkSimple = true;
			}
			BitReader br = new BitReader(bw.getBytes());

			int s = br.sget2(nbits);
			assertEquals("number of bits:" + nbits, v, s);
			if (checkSimple) {
				int s2 = br.sget(nbits);
				assertEquals("number of bits:" + nbits, v, s2);
				
			}
		}
	}
	
	@Test
	public void testWriteReadUnsigned() {
		for (int n = 1; n <= 32; n++) {
			testWriteReadUnsigned(n);
		}
	}

	private static void testWriteReadUnsigned(int nbits) {
		int[] TEST_VALS = { 0, 1, 40, Integer.MAX_VALUE - 1, Integer.MAX_VALUE };
		for (int i = 0; i < TEST_VALS.length; i++) {
			BitWriter bw = new BitWriter();
			int v = TEST_VALS[i] & (1 << nbits) - 1;
			bw.putn(v, nbits);
			BitReader br = new BitReader(bw.getBytes());

			int s = br.get(nbits);
			assertEquals("number of bits:" + nbits, v, s);
		}
	}
	
	@Test
	public void positionedRead() {
		BitReader br = new BitReader(new byte[] { (byte) 0xf1, 0x73, (byte) 0xc2, 0x5 });

		br.position(10);
		assertEquals("sixteen bits at pos 10", 0x709c, br.get(16));
		
	}

	@Test
	public void positionedReadWithOffset() {
		BitReader br = new BitReader(new byte[] {0, (byte) 0xf1, 0x73, (byte) 0xc2, 0x5}, 1);

		int pos = 10;
		br.position(pos);
		assertEquals("sixteen bits at pos " + pos, 0x709c, br.get(16));
		br.skip(-16);
		assertEquals("sixteen bits at pos " + pos, 0x709c, br.get(16));
		br.skip(-2);
		br.skip(-15);
		br.skip(1);
		assertEquals("sixteen bits at pos " + pos, 0x709c, br.get(16));
	}
}
