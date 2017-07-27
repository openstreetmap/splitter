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

import java.util.Arrays;

/**
 * A class to write the bitstream. Based on code in mkgmap.
 *
 * @author Steve Ratcliffe
 * @author Gerd Petermann
 */
public class BitWriter {
	// Choose so that chunks will not fill it.

	// The byte buffer and its current length (allocated length)
	private byte[] buf;  // The buffer
	private int bufsize;  // The allocated size

	/** The number of bits already used in the current byte of the buffer. */
	private int usedBits;
	/** The index of the current byte of the buffer. */
	private int index;
	private static final int BUFSIZE_INC = 50;
	private static final int INITIAL_BUF_SIZE = 20;  
	
	public BitWriter(int sizeInBytes) {
		bufsize = sizeInBytes;
		buf = new byte[bufsize];
	}

	public BitWriter() {
		this(INITIAL_BUF_SIZE);
	}

	public void clear() {
		Arrays.fill(buf, (byte) 0);
		index = 0;
		usedBits = 0;
	}

	/**
	 * Put exactly one bit into the buffer.
	 *
	 * @param b The bottom bit of the integer is set at the current bit position.
	 */
	private void put1(int b) {
		ensureSize(index + 1);

		// Get the remaining bits into the byte.
		int rem = usedBits;

		// Or it in, we are assuming that the position is never turned back.
		buf[index] |= (b & 0x1) << rem;
		usedBits++;
		if (usedBits == 8) {
			index++;
			usedBits = 0;
		}
	}
	
	public void put1(boolean b) {
		put1(b ? 1 : 0);
	}

	/**
	 * Put a number of bits into the buffer, growing it if necessary.
	 *
	 * @param bval The bits to add, the lowest <b>n</b> bits will be added to
	 * the buffer.
	 * @param nb The number of bits.
	 */
	public void putn(int bval, int nb) {
		assert nb >= 1 && nb <= 32;
		int val = nb < 32 ? bval & ((1<<nb) - 1) : bval;
		int n = nb;

		ensureSize(index + (usedBits + n + 7) / 8);
		
		int rem = usedBits;
			
		// Get each affected byte and set bits into it until we are done.
		while (n > 0) {
			buf[index] |= ((val << rem) & 0xff);

			// Account for change so far
			int nput = 8 - rem;
			if (nput > n)
				nput = n;
			usedBits += nput;
			if (usedBits >= 8) {
				index++;
				usedBits = 0;
			}
			// Shift down in preparation for next byte.
			val >>>= nput;
			rem = 0;
			n -= nput;
		}
	}
	
	/**
	 * Write a signed value. Caller must make sure that absolute value fits into 
	 * the given number of bits
	 */

	public void sputn(final int bval, final int nb) {
		assert nb > 1 && nb <= 32;
		int top = 1 << (nb - 1);
		if (bval < 0) {
			assert -bval <  top || top < 0;  
			int v = (top + bval) | top; 
			putn(v, nb);
		} else {
			assert bval < top || top < 0;
			putn(bval, nb);
		}
	}
	
	/**
	 * Write a signed value. If the value doesn't fit into nb bits, write one or more 1 << (nb-1)  
	 * as a flag for extended range.
	 */

	public void sputn2(final int bval, final int nb) {
		assert nb > 1 && nb <= 32;
		int top = 1 << (nb - 1);
		int mask = top - 1;
		int val = Math.abs(bval);
		
		if (bval == Integer.MIN_VALUE) {
			// catch special case : Math.abs(Integer.MIN_VALUE) returns Integer.MIN_VALUE
			putn(top, nb);
			val = Math.abs(val - mask);
		}
		assert val >= 0;
		while (val > mask) {
			putn(top, nb);
			val -= mask;
		}
		if (bval < 0) {
			putn((top - val) | top, nb);
		} else {
			putn(val, nb);
		}
	}

	public byte[] getBytes() {
		return buf;
	}

	public int getBitPosition() {
		return index * 8 + usedBits;
	}
	
	/**
	 * Get the number of bytes actually used to hold the bit stream. This therefore can be and usually
	 * is less than the length of the buffer returned by {@link #getBytes()}.
	 * @return Number of bytes required to hold the output.
	 */
	public int getLength() {
		if (usedBits == 0)
			return index;
		return index + 1;
	}

	/**
	 * Set everything up so that the given size can be accommodated.
	 * The buffer is re-sized if necessary.
	 *
	 * @param newlen The new length of the bit buffer in bytes.
	 */
	private void ensureSize(int newlen) {
		if (newlen >= bufsize)
			reallocBuffer();
	}

	/**
	 * Reallocate the byte buffer.
	 */
	private void reallocBuffer() {
		bufsize += BUFSIZE_INC;
		buf = Arrays.copyOf(buf, bufsize);
	}
}
