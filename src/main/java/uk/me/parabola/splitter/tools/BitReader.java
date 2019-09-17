/*
 * Copyright (C) 2017, Gerd Petermann
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

/**
 * Read an array as a bit stream. Based on code in mkgmap.
 *
 * @author Steve Ratcliffe
 * @author Gerd Petermann
 */
public class BitReader {
	private final byte[] buf;
	/** index of the first available byte in the buffer. */
	private final int offset; 
	/** index of the current byte in the buffer. */
	private int index; //
	/**  bit position within the current byte. */
	private int bitPosition;

	public BitReader(byte[] buf) {
		this(buf, 0);
	}

	public BitReader(byte[] buf, int start) {
		this.buf = buf;
		this.offset = start;
		reset();
		
	}

	/** reset the reader for a repeated read. */
	public void reset() {
		index = offset;
		bitPosition = 0;
	}

	/** set the reader to the given bit position. */
	public void position(int bitPos) {
		index = offset + bitPos / 8;
		bitPosition = bitPos & 0x07;
	}
	
	/** set the reader to the relative bit position. */
	public void skip(int bits) {
		position(getBitPosition() + bits);
	}
	
	/** get a single bit. */
	public boolean get1() {
		int off = bitPosition;
		byte b = buf[index];

		if (++bitPosition == 8) {
			bitPosition = 0;
			index++;
		}
		return ((b >> off) & 1) == 1;
	}

	/** get an unsigned int value using the given number of bits */
	public int get(int n) {
		if (n == 1) {
			return get1() ? 1 : 0;
		}
		int nb = n + bitPosition;
		int shift = 0;
		long work = 0;
		do {
			work |= ((long)buf[index++] & 0xff) << shift;
			shift += 8;
			nb -= 8;
		} while (nb > 0);
		
		if (nb < 0) 
			index--;
		
		int res = (int) (work >>> bitPosition);
		bitPosition = nb < 0 ? nb + 8 : 0;
		if (n < 32)
			res &= ((1 << n) - 1);
		return res;
	}

	/**
	 * Get a signed quantity.
	 *
	 * @param n The field width, including the sign bit.
	 * @return A signed number.
	 */
	public int sget(int n) {
		int res = get(n);
		if (n < 32) {
			int top = 1 << (n - 1);

			if ((res & top) != 0) {
				int mask = top - 1;
				res = ~mask | res;
			}
		}
		return res;
	}

	/**
	 * Get a signed n-bit value, treating 1 << (n-1) as a flag to read another signed n-bit value
	 * for extended range.
	 */
	public int sget2(int n) {
		assert n > 1;
		int top = 1 << (n - 1);
		int mask = top - 1;
		int base = 0;

		long res = get(n);
		while (res == top) {
			// Add to the base value, and read another
			base += mask;
			res = get(n);
		}

		// The final byte determines the sign of the result. Add or subtract the base as
		// appropriate.
		if ((res & top) == 0)
			res += base;
		else
			res = (res | ~mask) - base; // Make negative and subtract the base

		return (int) res;
	}

	public int getBitPosition() {
		return (index - offset) * 8 + bitPosition;
	}
}
