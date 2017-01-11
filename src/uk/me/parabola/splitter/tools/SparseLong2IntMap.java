/*
 * Copyright (c) 2016, Gerd Petermann
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

import java.nio.ByteBuffer;
import java.util.Arrays;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntBidirectionalIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.splitter.Utils;

/**
 * Intended usage: Store many pairs of OSM id and an int which represents the position.
 * Optimized for low memory requirements and inserts in sequential order.
 * Don't use this for a rather small number of pairs.
 *
 * Inspired by SparseInt2ShortMapInline.
 *
 * A HashMap is used to address large vectors which address chunks. The HashMap
 * is the only part that stores long values, and it will be very small as long
 * as long as input is normal OSM data and not something with random numbers.
 * A chunk stores up to CHUNK_SIZE values. A separately stored bit-mask is used
 * to separate used and unused entries in the chunk. Thus, the chunk length
 * depends on the number of used entries, not on the highest used entry.
 * Such a "masked encoded" entry may look like this
 * v1,v1,v1,v1,v1,v1,v2,v2,v2,v2,v1,v1,v1,v1,v1,u,?,?,...}
 * v1,v2: values stored in the chunk
 * u: "unassigned" value
 * ?: anything
 *
 * After applying Run Length Encryption on this the chunk looks like this:
 * {v1,6,v2,4,v1,5,?,?,?}
 *
 * Fortunately, OSM data is distributed in a way that a lot of chunks contain
 * just one distinct value. 
 *
 * Since we have OSM ids with 64 bits, we have to divide the key into 3 parts:
 * 37 bits for the value that is stored in the HashMap.
 * 21 bits for the chunkId (this gives the required length of a large vector)
 * 6 bits for the position in the chunk
 *
 * The chunkId identifies the position of a 32-bit value (stored in the large vector).
 * A chunk is stored in a chunkStore which is a 3-dimensional array.
 * We group chunks of equally length together in stores of 64 entries.
 * To find the right position of a new chunk, we need three values: x,y, and z.
 * x is the length of the chunk (the number of required bytes) (1-256, we store the value decremented by 1 to have 0-255)
 * y is the position of the store (0-1048575), we store a value incremented by 1 to ensure a non-zero value for used chunks
 * z is the position of the chunk within the store. (0-15)
 * The maximum values for these three values are chosen so that we can place them
 * together into one int (32 bits).
 */

public final class SparseLong2IntMap {
	private static final boolean SELF_TEST = false;
	private static final int CHUNK_SIZE = 64; 					// 64  = 1<< 6 (last 6 bits of the key)
	private static final int MAX_BYTES_FOR_VAL = Integer.BYTES;
	private static final int MAX_STORED_BYTES_FOR_CHUNK = CHUNK_SIZE * MAX_BYTES_FOR_VAL;
	private static final int CHUNK_STORE_BITS_FOR_X = Integer.SIZE - Integer.numberOfLeadingZeros(MAX_STORED_BYTES_FOR_CHUNK-1); // values 1 .. 256 are stored as 0..255
	private static final int CHUNK_STORE_BITS_FOR_Z = 8; // must not be higher than 8
	private static final int CHUNK_STORE_BITS_FOR_Y = Integer.SIZE - (CHUNK_STORE_BITS_FOR_X + CHUNK_STORE_BITS_FOR_Z);  	
	private static final int CHUNK_STORE_ELEMS = 1 << CHUNK_STORE_BITS_FOR_Z;
	private static final int CHUNK_STORE_X_MASK = (1 << CHUNK_STORE_BITS_FOR_X) - 1;
	private static final int CHUNK_STORE_Y_MASK = (1 << CHUNK_STORE_BITS_FOR_Y) - 1;
	private static final int CHUNK_STORE_Z_MASK = (1 << CHUNK_STORE_BITS_FOR_Z) - 1;
	private static final int CHUNK_STORE_Y_SHIFT = CHUNK_STORE_BITS_FOR_X;
	private static final int CHUNK_STORE_Z_SHIFT = CHUNK_STORE_BITS_FOR_X + CHUNK_STORE_BITS_FOR_Y;

	private static final int BYTES_FOR_MASK = 8;

	/** Number of entries addressed by one topMap entry. */
	private static final int TOP_ID_SHIFT = 27; // must be below 32, smaller values give smaller LARGE_VECTOR_SIZEs and more entries in the top HashMap 
	/** the part of the key that is not saved in the top HashMap. */
	private static final long CHUNK_ID_MASK = (1L << (TOP_ID_SHIFT)) - 1;
 
	/** Number of entries addressed by one topMap entry. */
	private static final int LARGE_VECTOR_SIZE = (int) (CHUNK_ID_MASK / CHUNK_SIZE + 1);
	private static final int MAX_Y_VAL = LARGE_VECTOR_SIZE / CHUNK_STORE_ELEMS + 1;
	/** The part of the key that contains the offset in the chunk. */
	private static final long CHUNK_OFFSET_MASK = CHUNK_SIZE - 1;		
	/** First 58 bits of a long. If this part of the key changes, a different chunk is needed. */
	private static final long OLD_CHUNK_ID_MASK = ~CHUNK_OFFSET_MASK;	

	private static final long INVALID_CHUNK_ID = 1L; // must NOT be divisible by CHUNK_SIZE

	/** What to return on unassigned keys. */
	private int unassigned = Integer.MIN_VALUE;
	private long size;
	private long modCount;
	private long oldModCount;

	private long currentChunkId;
	private Mem currentMem;
	private final int [] currentChunk = new int[CHUNK_SIZE]; // stores the values in the real position
	private final int [] testChunk = new int[CHUNK_SIZE]; // for internal test 
	private final int [] maskedChunk = new int[CHUNK_SIZE]; // a chunk after applying the "mask encoding"
	private final int[] tmpChunk = new int[CHUNK_SIZE * 2]; // used for tests of compression methods
	private static final int MAX_BYTES_FOR_RLE_CHUNK = CHUNK_SIZE * (Integer.BYTES + 1);
	private final ByteBuffer bufEncoded = ByteBuffer.allocate(MAX_BYTES_FOR_RLE_CHUNK); // for the RLE-compressed chunk
	
	// bit masks for the flag byte
	private static final int FLAG1_USED_BYTES_MASK = 0x03; // number of bytes - 1 
	private static final int FLAG1_RUNLEN_MASK = 0x1C; // number of bits for run length values 
	private static final int FLAG1_DICTIONARY = 0x20; // if set a dictionary follows the flag bytes
	private static final int FLAG1_COMP_METHOD_BITS = 0x40; // rest of vals are "bit" encoded 
	private static final int FLAG1_COMP_METHOD_RLE = 0x80; // values are run length encoded

	private static final int FLAG2_BITS_FOR_VALS = 0x1f;
	private static final int FLAG2_ALL_POSITIVE = 0x20;
	private static final int FLAG2_ALL_NEGATIVE = 0x40;
	private static final int FLAG2_DICT_SIZE_IS_2 = 0x80;	
	private static final int FLAG_BITS_FOR_DICT_SIZE = Integer.SIZE - Integer.numberOfLeadingZeros(CHUNK_SIZE-1);
	/** a chunk that is stored with a length between 1 and 3 has no flag byte and is always a single value chunk. */  
	private static final int SINGLE_VAL_CHUNK_LEN_NO_FLAG = 3;  
	
	long[] useMethods = new long[10]; 
	int method; 
	// for statistics
	private final String dataDesc;
	
	private int currentChunkXVal;
	private int currentChunkVectorIndex;

	private Long2ObjectOpenHashMap<Mem> topMap;

	static final long MAX_MEM = Runtime.getRuntime().maxMemory() / 1024 / 1024;
	static final int POINTER_SIZE = (MAX_MEM < 32768) ? 4 : 8; // presuming that compressedOOps is enabled
	
	private Integer bias1; // used for initial delta encoding
	private final BitWriter bitWriter = new BitWriter(1000);
	

	/**
	 * A map that stores pairs of (OSM) IDs and int values identifying the
	 * areas in which the object with the ID occurs. 
	 * @param dataDesc
	 */
	public SparseLong2IntMap(String dataDesc) {
		// sanity check to make sure that we can store enough chunks with the same length
		// If this test fails it is not possible to store the same value for all ids 
		long reserve = ((1L << CHUNK_STORE_BITS_FOR_Y) - 1) * CHUNK_SIZE - LARGE_VECTOR_SIZE;
		assert reserve > 0: "Bad combination of constants";
		this.dataDesc = dataDesc;
		System.out.println(dataDesc + " Map: uses " + this.getClass().getSimpleName());
		clear();
	}

	/**
	 * Helper class to manage memory for chunks.
	 * @author Gerd Petermann
	 *
	 */
	static class Mem {
		final long topId;
		long estimatedBytes; // estimate value for the allocated bytes
		final int[] largeVector;
		byte[][][] chunkStore;
		final int[] freePosInStore;
		/**  maps chunks that can be reused. */
		Int2ObjectOpenHashMap<IntArrayList> reusableChunks;
		
		public Mem(long topID) {
			this.topId = topID;
			largeVector = new int[LARGE_VECTOR_SIZE];
			chunkStore = new byte[MAX_STORED_BYTES_FOR_CHUNK][][];
			freePosInStore = new int[MAX_STORED_BYTES_FOR_CHUNK];
			reusableChunks = new Int2ObjectOpenHashMap<>(0);
			estimatedBytes = LARGE_VECTOR_SIZE * Integer.BYTES 
					+ (MAX_STORED_BYTES_FOR_CHUNK) * (8 + 1 * Integer.BYTES) + 3 * (24 + 16) + 190; 
		}

		public void grow(int x) {
			int oldCapacity = chunkStore[x].length;
	        int newCapacity = oldCapacity < 1024 ? oldCapacity * 2 : oldCapacity + (oldCapacity >> 1);
	        if (newCapacity >= MAX_Y_VAL) 
	            newCapacity = MAX_Y_VAL;
	        if (newCapacity <= oldCapacity)
	        	return;
			resize(x, newCapacity);
		}

		private void resize(int x, int newCapacity) {
			int oldCapacity = chunkStore[x].length;
			if (newCapacity < oldCapacity)
				assert chunkStore[x][newCapacity] == null;
	        chunkStore[x] = Arrays.copyOf(chunkStore[x], newCapacity);
	        // bytes for pointers seem to depends on the capacity ?
	        estimatedBytes += (newCapacity - oldCapacity) *  8; // pointer-pointer  
		}

		public void startStore(int x) {
			chunkStore[x] = new byte[2][];
			estimatedBytes += 24 + 2 * 8; // pointer-pointer
		}
	}
	
	/**
	 * Helper class to store the various positions in the multi-tier data structure.
	 * @author Gerd Petermann
	 *
	 */
	private static class MemPos{
		final int x,y,z;
		final Mem mem;
		final int largeVectorIndex;
		
		MemPos(Mem mem, int largeVectorIndex, int x, int y, int z) {
			this.mem = mem;
			this.largeVectorIndex = largeVectorIndex;
			this.x = x;
			this.y = y;
			this.z = z;
		}
		
		public ByteBuffer getInBuf() {
			int chunkLenWithMask = x + 1 + BYTES_FOR_MASK;
			int startPos = z * chunkLenWithMask + 1;
			return ByteBuffer.wrap(mem.chunkStore[x][y], startPos, chunkLenWithMask);
		}
	}
	
	/**
	 * Count how many of the lowest X bits in mask are set.
	 *
	 * @return how many of the lowest X bits in mask are set.
	 */
	private static int countUnder(final long mask, final int lowest) {
		return Long.bitCount(mask & ((1L << lowest) - 1));
	}
 
	/**
	 * Put an int value into the byte buffer using the given number of bytes. 
	 * @param buf the buffer
	 * @param val the int value to store
	 * @param bytesToUse the number of bytes to use
	 */
	static void putVal(final ByteBuffer buf, final int val, final int bytesToUse) {
		switch (bytesToUse) {
		case 1:
			assert val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE : val + " of out Byte range";
			buf.put((byte) val);
			break;
		case 2:
			buf.putShort((short) val);
			break;
		case 3: // put3
			buf.put((byte) (val & 0xff));
			buf.putShort((short) (val >> 8));
			break;
		default:
			buf.putInt(val);
			break;
		}
	}

	/**
	 * Read an int value from the byte buffer using the given number of bytes.
	 * @param buf the byte buffer
	 * @param bytesToUse number of bytes (1 - 4)
	 * @return the integer value
	 */
	static int getVal(final ByteBuffer buf, final int bytesToUse) {
		switch (bytesToUse) {
		case 1:
			return buf.get();
		case 2:
			return buf.getShort();
		case 3:
			byte b1 = buf.get();
			short s = buf.getShort();
			return (b1 & 0xff) | (s << 8);
		default:
			return buf.getInt();
		}
	}

	/**
	 * calculate the number of bits needed to store the value as a signed number.
	 * @param val the value to store
	 * @return the number of bits needed to store the value as a signed number
	 */
	private static int bitsNeeded(int val) {
		return Long.SIZE - Long.numberOfLeadingZeros(Math.abs(val)) + 1;
	}
  
	private Mem getMem (long key) {
		long topID = (key >> TOP_ID_SHIFT);
		if (currentMem == null || currentMem.topId != topID) {
			currentMem = topMap.get(topID);
		}
		return currentMem;
	}
	
	/**
	 * Try to use Run Length Encoding (RLE) to compress the "mask-encoded" chunk. In most
	 * cases this works very well because chunks often have only one or two distinct values.
	 * The values and run length fields are each written with a fixed number of bits.  
	 * 
	 * @param numVals number of elements in the chunk, content of {@code maskedChunk} after that is undefined.
	 * @param minVal smallest value in maskedChunk 
	 * @param maxVal highest value in maskedChunk 
	 * 
	 */
	private void chunkCompress(int numVals, int minVal, int maxVal) {
		int flag1 = FLAG1_COMP_METHOD_BITS;
		int opos = 0;
		int maxRunLen = 0;
		int numCounts = 0;
		Int2IntLinkedOpenHashMap dict = new Int2IntLinkedOpenHashMap(32, Hash.VERY_FAST_LOAD_FACTOR);
		dict.defaultReturnValue(-1);

		for (int i = 0; i < numVals; i++) {
			int runLength = 1;
			while (i + 1 < numVals && maskedChunk[i] == maskedChunk[i + 1]) {
				runLength++;
				i++;
			}
			numCounts++;
			int v = maskedChunk[i];
			if (dict.get(v) == dict.defaultReturnValue())
				dict.put(v, dict.size());
			tmpChunk[opos++] = v;
			tmpChunk[opos++] = runLength;
			if (maxRunLen < runLength)
				maxRunLen = runLength;
		}
		// the first value is used as a bias because it is likely that this will bring min/max values closer to 0 
		int bias2 = maskedChunk[0];
		int bits = Math.max(bitsNeeded(minVal - bias2), bitsNeeded(maxVal - bias2));
		int sign = getSign(minVal - bias2, maxVal - bias2);
		// try to find out if compression will help
		int bitsForRLE = bitsNeeded(maxRunLen-1) - 1; // we always have positive values and we store the len decremented by 1
		int bitsForVal = bits - Math.abs(sign);
		int bitsForPos = bitsNeeded(dict.size() - 1) - 1;
		int bitsForDictFlag = dict.size() > 2 ? FLAG_BITS_FOR_DICT_SIZE : 0;
		int bitsForDict = bitsForDictFlag + (dict.size() - 1) * bitsForVal;
		int len1 = toBytes((numVals - 1) * bitsForVal);
		int len2 = toBytes(bitsForRLE + (numCounts - 1) * (bitsForRLE + bitsForVal));
		int len3 = toBytes(bitsForDict + (numVals - 1) * bitsForPos);
		int len4 = toBytes(bitsForDict + bitsForRLE + (numCounts - 1) * (bitsForRLE + (dict.size() > 2 ? bitsForPos : 0)));
		boolean useRLE = numCounts < 5 && maxRunLen > 1 && (Math.min(len2, len4) < Math.min(len1, len3));
		boolean useDict = (useRLE) ? len2 > len4 : len1 > len3;
		if (useRLE & useDict)
			method = 3;
		else if (useRLE & !useDict)
			method = 4;
		else if (!useRLE & useDict)
			method = 5;
		else if (!useRLE & !useDict)
			method = 6;
		
//		System.out.println(len1 + " " + len2 + " " + len3 + " " + len4 + " " + useDict + " " + useRLE + " "  + dict.size() + " " + numCounts);
//		if (useRLE && numVals / 2 < numCounts) {
//			long dd = 4;
//		}
		bitWriter.clear();
		if (useDict) {
			flag1 |= FLAG1_DICTIONARY;
			if (dict.size() > 2) 
				bitWriter.putn(dict.size() - 1, FLAG_BITS_FOR_DICT_SIZE);
			IntBidirectionalIterator iter = dict.keySet().iterator();
			iter.next();
			while (iter.hasNext()) {
				storeVal(iter.nextInt() - bias2, bits, sign);
			}
		}

		if (useRLE) {
			flag1 |= FLAG1_COMP_METHOD_RLE;
			flag1 |= ((bitsForRLE << 2) & FLAG1_RUNLEN_MASK) ;
			boolean writeIndex = useDict & (dict.size() > 2);
			int pos = 1; // first val is written with different method
			
			bitWriter.putn(tmpChunk[pos++] - 1, bitsForRLE);
			while (pos < opos) {
				int v = tmpChunk[pos++];
				if (!useDict)
					storeVal(v - bias2, bits, sign);
				else {
					if (writeIndex) {
						int idx = dict.get(v);
						bitWriter.putn(idx, bitsForPos);
					}
				}
				bitWriter.putn(tmpChunk[pos++] - 1, bitsForRLE);
			}
		} else {
			for (int i = 1; i < numVals; i++) { // first val is written with different method
				if (useDict) {
					int v = maskedChunk[i];
					bitWriter.putn(dict.get(v), bitsForPos);
				} else {
					storeVal(maskedChunk[i] - bias2, bits, sign);
				}
			}
		}
		int bytesForBias = 0;
		bytesForBias = bytesNeeded(bias2, bias2);
		flag1 |= (bytesForBias - 1) & FLAG1_USED_BYTES_MASK;
		int bwLen = bitWriter.getLength();
		if (SELF_TEST) {
			if (useRLE && useDict && len4 != bwLen)
				assert false : "len4 " + bwLen + " <> " + len4;
			if (!useRLE && useDict && len3 != bwLen)
				assert false : "len3 " + bwLen + " <> " + len3;
			if (useRLE && !useDict && len2 != bwLen)
				assert false : "len2 " + bwLen + " <> " + len2;
			if (!useRLE && !useDict && len1 != bwLen)
				assert false : "len1 " + bwLen + " <> " + len1;
		}
		int len = 1 + 1 + bitWriter.getLength() + bytesForBias;
		if (len < MAX_STORED_BYTES_FOR_CHUNK) {
			bufEncoded.put((byte) flag1);
			int flag2 = (bits - 1) & FLAG2_BITS_FOR_VALS; // number of bits for the delta encoded values
			if (sign > 0)
				flag2 |= FLAG2_ALL_POSITIVE;
			else if (sign < 0)
				flag2 |= FLAG2_ALL_NEGATIVE;
			if (dict.size() == 2)
				flag2 |= FLAG2_DICT_SIZE_IS_2;
			bufEncoded.put((byte) flag2); 
			putVal(bufEncoded, bias2, bytesForBias);

			bufEncoded.put(bitWriter.getBytes(), 0, bitWriter.getLength());
		} else {
			method = 7;
			// no flag byte for worst case 
			for (int i = 0; i < numVals; i++){
				putVal(bufEncoded, currentChunk[i], 4);
			}
		}
		return;
	}
	
	/**
	 * calculate the number of bytes consumed by given a number of bits
	 * @param nBits the number of bits
	 * @return the number of bytes needed to store the bits
	 */
	private static int toBytes(int nBits) {
		return (nBits + 7) / 8;
	}

	private void storeVal(int val, int nb, int sign) {
		if (sign == 0)
			bitWriter.sputn(val, nb);
		else if (sign == 1){
			bitWriter.putn(val, nb-1);
		} else
			bitWriter.putn(-val, nb-1);
	}

	private static int readVal(BitReader br, int bits, int sign) {
		if (sign == 0)
		  return br.sget(bits);
		else if (sign > 0)
			return br.get(bits-1);
		return -br.get(bits-1);
	}

	private static int getSign(int v1, int v2) {
		assert v1 != v2;
		if (v1 < 0) {
			return (v2 <= 0) ? -1 : 0;
		} else if (v1 > 0) {
			return v2 >= 0 ? 1: 0;
		} else {
			//v1 == 0
			return v2 < 0 ? -1 : 1;
		}
	}

	/**
	 * Try to compress the data in currentChunk and store the result in the chunkStore. 
	 */
	private void saveCurrentChunk() {
		if (currentChunkId == INVALID_CHUNK_ID || modCount == oldModCount)
			return;
		// step 1: mask encoding
		long mask = 0;
		int simpleLen = 0;
		long elementMask = 1L;
		if (bias1 == null) {
			bias1 = findBias1(); // very simple heuristics 
		}
		int maxVal = Integer.MIN_VALUE;
		int minVal = Integer.MAX_VALUE;
		for (int i = 0; i < CHUNK_SIZE; i++) {
			if (currentChunk[i] != unassigned) {
				int v = currentChunk[i] - bias1; // apply bias 
				if (minVal > v)
					minVal = v;
				if (maxVal < v)
					maxVal = v;
				maskedChunk[simpleLen++] = v;
				mask |= elementMask;
			}
			elementMask <<= 1;
		}
		bufEncoded.clear();
		bufEncoded.putLong(mask);
		method = 0;
		if (minVal == maxVal) {
			method = 1;
			// nice: single value chunk 
			int bytesFor1st = bytesNeeded(minVal, maxVal);
			if (bytesFor1st > SINGLE_VAL_CHUNK_LEN_NO_FLAG) {
				method = 2;
				bufEncoded.put((byte) (bytesFor1st - 1)); // flag byte
			}
			putVal(bufEncoded, maskedChunk[0], bytesFor1st);
		} else {
			chunkCompress(simpleLen, minVal, maxVal);
			assert bufEncoded.position() > SINGLE_VAL_CHUNK_LEN_NO_FLAG;
		}
		++useMethods[method];
		bufEncoded.flip();
		putChunk();
		if (SELF_TEST) {
			Arrays.fill(testChunk, unassigned);
			decodeStoredChunk(getMemPos(currentChunkId), testChunk, -1);
			for (int i = 0; i < CHUNK_SIZE; i++) {
				if (testChunk[i] != currentChunk[i]) {
					assert false : "current chunk id=" + currentChunkId + " key=" + (currentChunkId + i)
							+ " doesn't match " + testChunk[i] + "<>" + currentChunk[i]; 
				}
			}
		}
	}

	/**
	 * Calculate the number of bytes needed to encode values in the given range.
	 * @param minVal smallest value
	 * @param maxVal highest value
	 * @return number of needed bytes
	 */
	static int bytesNeeded (long minVal, long maxVal) {
		if (minVal >= Byte.MIN_VALUE && maxVal <= Byte.MAX_VALUE) {
			return Byte.BYTES;
		} else if (minVal >= Short.MIN_VALUE && maxVal <= Short.MAX_VALUE) {
			return Short.BYTES;
		} else if (minVal >= -0x00800000 && maxVal <= 0x7fffff) {
			return 3;
		} 
		return Integer.BYTES;
	}
	
	private int findBias1() {
		int minVal = Integer.MAX_VALUE;
		int maxVal = Integer.MIN_VALUE;
		for (int i = 0; i < CHUNK_SIZE; i++) {
			if (currentChunk[i] != unassigned) {
				if (minVal > currentChunk[i])
					minVal = currentChunk[i];
				if (maxVal < currentChunk[i])
					maxVal = currentChunk[i];
			}
		}
		int avg = minVal + (maxVal-minVal) / 2;
		if (avg < 0 && avg - Integer.MIN_VALUE < Byte.MAX_VALUE)
			return Integer.MIN_VALUE + Byte.MAX_VALUE;
		if (avg > 0 && Integer.MAX_VALUE - avg < Byte.MAX_VALUE)
			return Integer.MAX_VALUE - Byte.MAX_VALUE;
		return avg;
	}

	public boolean containsKey(long key) {
		return get(key) != unassigned;
	}

	public int put(long key, int val) {
		if (val == unassigned) {
			throw new IllegalArgumentException("Cannot store the value that is reserved as being unassigned. val=" + val);
		}
		long chunkId = key & OLD_CHUNK_ID_MASK;
		if (currentChunkId != chunkId){
			// we need a different chunk
			replaceCurrentChunk(key);
		}
		int chunkoffset = (int) (key & CHUNK_OFFSET_MASK);
		int out = currentChunk[chunkoffset];
		currentChunk[chunkoffset] = val;
		if (out == unassigned) 
			size++;
		if (out != val)
			modCount++;
		return out;
	}

	/**
	 * Either decode the encoded chunk data into target or extract a single value. 
	 * @param mp the MemPos instance with information about the store
	 * @param targetChunk if not null, data will be decoded into this buffer
	 * @param chunkOffset gives the wanted element (targetChunk must be null)
	 * @return the extracted value or unassigned 
	 */
	private int decodeStoredChunk (MemPos mp, int[] targetChunk, int chunkOffset) {
		ByteBuffer inBuf = mp.getInBuf();
		long chunkMask = inBuf.getLong();
		if (targetChunk == null) {
			long elementmask = 1L << chunkOffset;
			if ((chunkMask & elementmask) == 0)
				return unassigned; // not in chunk
			// the map contains the key, decode it
		}
		int chunkLenNoMask = inBuf.remaining();
		
		int flag = 0;
		int bytesToUse = Integer.BYTES; // assume worst case
		if (chunkLenNoMask == MAX_STORED_BYTES_FOR_CHUNK) {
			// special case: no flag is written if we have the max. size
			// all values are written with 4 bytes and without bias 
			if (targetChunk == null) {
				inBuf.position(inBuf.position() + chunkOffset * bytesToUse);
				return getVal(inBuf, bytesToUse);
			}
			for (int i = 0; i < CHUNK_SIZE; i++) {
				targetChunk[i] = getVal(inBuf, bytesToUse);
			}
			return unassigned;
		} else if (chunkLenNoMask <= SINGLE_VAL_CHUNK_LEN_NO_FLAG) {
			bytesToUse = chunkLenNoMask;
		} else {
			flag = inBuf.get();
			if ((flag & FLAG1_COMP_METHOD_BITS) != 0) {
				inBuf.position(inBuf.position() - 1);
				int val = decodeBits(chunkMask, targetChunk, chunkOffset, inBuf);
				return val;
			}
			bytesToUse = (flag & FLAG1_USED_BYTES_MASK) + 1;	
		}
		int start = bias1 + getVal(inBuf, bytesToUse);
		boolean isSingleValueChunk = (chunkLenNoMask <= SINGLE_VAL_CHUNK_LEN_NO_FLAG || chunkLenNoMask == 1 + bytesToUse);
		assert isSingleValueChunk;
		if (targetChunk == null) {
			return start;
		}
		maskedChunk[0] = start;
		updateTargetChunk(targetChunk, chunkMask, isSingleValueChunk);
		return unassigned; 
	}

	
	private void updateTargetChunk(int[] targetChunk, long chunkMask, boolean singleValueChunk) {
		if (targetChunk == null)
			return;
		int j = 0;
		int opos = 0;
		while (chunkMask != 0) {
			if ((chunkMask & 1L) != 0) {
				targetChunk[opos] = maskedChunk[j];
				if (!singleValueChunk)
					j++;
			}
			opos++;
			chunkMask >>>= 1;
		}
	}

	/**
	 * Decode a stored chunk written with the {@link BitWriter}.
	 * @param mp
	 * @param targetChunk
	 * @param chunkOffset
	 * @param inBuf
	 * @return
	 */
	private int decodeBits(long chunkMask, int[] targetChunk, int chunkOffset, ByteBuffer inBuf) {
		int flag1 = inBuf.get();
		assert (flag1 & FLAG1_COMP_METHOD_BITS) != 0;
		int index = CHUNK_SIZE + 1; 
		if (targetChunk == null) {
			// we only want to retrieve one value for the index
			index = countUnder(chunkMask, chunkOffset); 
		}
		boolean useDict = (flag1 & FLAG1_DICTIONARY) != 0;
		int flag2 = inBuf.get();
		int bits = (flag2 & FLAG2_BITS_FOR_VALS) + 1;
		int sign = 0;
		if ((flag2 & FLAG2_ALL_POSITIVE) != 0)
			sign = 1;
		else if ((flag2 & FLAG2_ALL_NEGATIVE) != 0)
			sign = -1;
		boolean dictSizeIs2 = (flag2 & FLAG2_DICT_SIZE_IS_2) != 0;

		assert bits >= 1;
		BitReader br;
		int bias = bias1;
		int val;
		// read first value
		int bytesFor1st = (flag1 & FLAG1_USED_BYTES_MASK) + 1;
		val = getVal(inBuf, bytesFor1st) + bias;
		bias = val;
		br = new BitReader(inBuf.array(), inBuf.position());
		if (index == 0)
			return val;
		int dictSize = dictSizeIs2 ? 2: 1;
		if (useDict) {
			if (!dictSizeIs2)
				dictSize = br.get(FLAG_BITS_FOR_DICT_SIZE) + 1;
		}
		int[] dict = new int[dictSize];
		if (useDict) {
			dict[0] = val;
			for (int i = 1; i < dictSize; i++) {
				dict[i] = readVal(br, bits, sign) + bias;
			}
		}
		boolean useRLE = (flag1 & FLAG1_COMP_METHOD_RLE) != 0;
		int bitsForPos = bitsNeeded(dictSize - 1) - 1; 
		
		if (targetChunk == null && !useRLE) {
			// shortcut: we can calculate the position of the value in the bit stream
			if (useDict) {
				br.skip((index-1) * bitsForPos);
				int dictPos = br.get(bitsForPos);
				return dict[dictPos]; 
			}
			// unlikely 
			int bitsToUse = bits - Math.abs(sign); 
			br.skip((index-1) * bitsToUse);
			return readVal(br, bits, sign) + bias;
		}
		int runLength;
		int bitsForRLE = useRLE ? (flag1 & FLAG1_RUNLEN_MASK) >> 2 : 0;
		int mPos = 0;
		int dictPos = 0;
		int nVals = 0;
		int n = Long.bitCount(chunkMask);
		boolean readIndex = dictSize > 2 || !useRLE;
		while (true) {
			if (useRLE) {
				runLength = br.get(bitsForRLE) + 1;
				nVals += runLength;
			} else
				nVals++;
			if (index < nVals)
				return val;
			if (targetChunk != null) {
				do {
					maskedChunk[mPos++] = val;
				} while (mPos < nVals);
			}
			if (nVals >= n)
				break;
			if (useDict) {
				dictPos = readIndex ? br.get(bitsForPos) : (dictPos == 0) ? 1 : 0;
				;
				val = dict[dictPos];
			} else {
				val = readVal(br, bits, sign) + bias;
			}
		}
		updateTargetChunk(targetChunk, chunkMask, false);
		return unassigned; 
	}

	/**
	 * Use the various bit masks to extract the position of the chunk in the store.
	 * @param key the key for which we want the chunk
	 * @return the filled MemPos instance or null if the chunk is not in the store.
	 */
	private MemPos getMemPos(long key) {
		Mem mem = getMem(key);
		if (mem == null)
			return null;
		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;

		int idx = mem.largeVector[chunkid];  // performance bottleneck: produces many cache misses
		if (idx == 0)
			return null;
		int x = idx & CHUNK_STORE_X_MASK;
		int y = (idx >> CHUNK_STORE_Y_SHIFT) & CHUNK_STORE_Y_MASK;
		y--; // we store the y value incremented by 1
		assert y < LARGE_VECTOR_SIZE;
		int z = (idx >> CHUNK_STORE_Z_SHIFT) & CHUNK_STORE_Z_MASK;
		return new MemPos(mem, idx, x, y, z);
	}
	
	/**
	 * Check if we already have a chunk for the given key. If no,
	 * fill currentChunk with default value, else with the saved
	 * chunk.
	 * @param key the key for which we need the current chunk
	 */
	private void replaceCurrentChunk(long key) {
		saveCurrentChunk();
		Arrays.fill(currentChunk, unassigned);
		oldModCount = modCount;
		currentChunkId = key & OLD_CHUNK_ID_MASK; 
		currentChunkXVal = -1;
		currentChunkVectorIndex = 0;
		MemPos mp = getMemPos(key);
		if (mp == null)
			return;

		currentChunkVectorIndex = mp.largeVectorIndex;
		currentChunkXVal = mp.x;
		decodeStoredChunk(mp, currentChunk, -1);
	}

	
	/**
	 * Returns the value to which the given key is mapped or the {@code unassigned} value.
	 * @param key the key
	 * @return the value to which the given key is mapped or the {@code unassigned} value
	 */
	public int get(long key){
		long chunkId = key & OLD_CHUNK_ID_MASK;
		int chunkoffset = (int) (key & CHUNK_OFFSET_MASK);

		if (currentChunkId == chunkId) {
			return currentChunk[chunkoffset];
		}
		MemPos mp = getMemPos(key);
		if (mp == null)
			return unassigned;

		return decodeStoredChunk(mp, null, chunkoffset);
	}

	public void clear() {
		topMap = new Long2ObjectOpenHashMap<>(Hash.DEFAULT_INITIAL_SIZE, Hash.VERY_FAST_LOAD_FACTOR);
		
		Arrays.fill(currentChunk, 0);
		Arrays.fill(maskedChunk, 0);
		currentChunkXVal = -1;
		currentChunkVectorIndex = 0;
		currentChunkId = INVALID_CHUNK_ID;
		currentMem = null;
		bias1 = null;
		size = 0;
		// test();
	}

	public long size() {
		return size;
	}

	public int defaultReturnValue() {
		return unassigned;
	}

	public void defaultReturnValue(int arg0) {
		unassigned = arg0;
	}

	private void putChunk() {
		Mem mem = getMem(currentChunkId);
		if (mem == null) {
			long topID = currentChunkId >> TOP_ID_SHIFT;
			mem = new Mem(topID);
			topMap.put(topID, mem);
			currentMem = mem;
		}

		int len = bufEncoded.limit();
		int x = len - (1 + BYTES_FOR_MASK); 
		if (currentChunkXVal >= 0) {
			// this is a rewrite, add the previously used chunk to the reusable list
			IntArrayList reusableChunk = mem.reusableChunks.get(currentChunkXVal);
			if (reusableChunk == null) {
				reusableChunk = new IntArrayList(8);
				mem.reusableChunks.put(currentChunkXVal, reusableChunk);
				mem.estimatedBytes += 8 * Integer.BYTES + 24 + Integer.BYTES + POINTER_SIZE + 16; // for the IntArrayList instance 
				mem.estimatedBytes += 20; // estimate for the hash map entry
			}
			reusableChunk.add(currentChunkVectorIndex);
		}
		if (mem.chunkStore[x] == null) {
			mem.startStore(x);
		}
		IntArrayList reusableChunk = mem.reusableChunks.get(x);
		int y, z;
		byte[] store;
		if (reusableChunk != null && !reusableChunk.isEmpty()) {
			int reusedIdx = reusableChunk.removeInt(reusableChunk.size() - 1);
			y = (reusedIdx >> CHUNK_STORE_Y_SHIFT) & CHUNK_STORE_Y_MASK;
			y--; // we store the y value incremented by 1
			z = (reusedIdx >> CHUNK_STORE_Z_SHIFT) & CHUNK_STORE_Z_MASK;
			store = mem.chunkStore[x][y];
		} else {
			y = ++mem.freePosInStore[x] / CHUNK_STORE_ELEMS;
			if (y >= mem.chunkStore[x].length) 
				mem.grow(x);
			if (mem.chunkStore[x][y] == null) {
				int numChunks = (len < 16) ? CHUNK_STORE_ELEMS : 8;
				mem.chunkStore[x][y] = new byte[numChunks * len + 1];
				mem.estimatedBytes += 24 + numChunks * len  + 1;
				int padding = 8 - (numChunks & 7);
				if (padding < 8)
					mem.estimatedBytes += padding;
			}
			store = mem.chunkStore[x][y];
			z = (store[0]++) & CHUNK_STORE_Z_MASK;
			if (len * (z + 1) + 1 > store.length) {
				int newNum = Math.min(CHUNK_STORE_ELEMS, z + 8);
				store = Arrays.copyOf(store, newNum * len + 1);
				mem.chunkStore[x][y] = store;
				mem.estimatedBytes += (newNum- z) * len;
			}
		}

		ByteBuffer storeBuf = ByteBuffer.wrap(store, z * len + 1, len);
		storeBuf.put(bufEncoded);
	
		// calculate the position in the large vector
		y++; // we store the y value incremented by 1
		assert x < 1 << CHUNK_STORE_BITS_FOR_X;
		assert y < 1 << CHUNK_STORE_BITS_FOR_Y;
		assert z < 1 << CHUNK_STORE_BITS_FOR_Z;
		int idx = (z & CHUNK_STORE_Z_MASK) << CHUNK_STORE_Z_SHIFT 
				| (y & CHUNK_STORE_Y_MASK) << CHUNK_STORE_Y_SHIFT
				| (x & CHUNK_STORE_X_MASK);

		assert idx != 0;
		int vectorPos = (int) (currentChunkId & CHUNK_ID_MASK) / CHUNK_SIZE;
		mem.largeVector[vectorPos] = idx;
	}

	/**
	 * calculate and print performance values regarding memory.
	 */
	public void stats(int msgLevel) {
		long totalBytes = currentChunk.length * Integer.BYTES;
		long totalChunks = 1; // current chunk
			
		if (size() == 0){
			System.out.println(dataDesc + " Map is empty");
			return;
		}
		int[] all = new int[MAX_STORED_BYTES_FOR_CHUNK];
		int memCount = 1;
		for (Mem mem : topMap.values()) {
			for (int x = 0; x < mem.freePosInStore.length; x++) {
				if (mem.freePosInStore[x] == 0)
					continue;
				all[x] += mem.freePosInStore[x];
				if (msgLevel >= 1) {
					System.out.println("mem store no: " + memCount + " len: " + (x+1) + " " + mem.freePosInStore[x]);
				}
				memCount++;
				totalChunks += mem.freePosInStore[x];
			}
			totalBytes += mem.estimatedBytes;
		}
//		if (msgLevel >= 0) {
//			for (int x = 0; x < all.length; x++) {
//				if (all[x] != 0) 
//					System.out.println("len: " + (x+1) + " " + all[x]);
//			}
//		}
		float bytesPerKey = size()==0 ? 0: (float)(totalBytes*100 / size()) / 100;
		System.out.println(dataDesc + " Map: " + Utils.format(size()) + " stored long/int pairs require ca. " +
				bytesPerKey + " bytes per pair. " +
				totalChunks + " chunks are used, the avg. number of values in one "+CHUNK_SIZE+"-chunk is " +
				(totalChunks == 0 ? 0 : (size() / totalChunks)) + ".");
		System.out.println(dataDesc + " Map details: bytes ~" + Utils.format(totalBytes/1024/1024) + " MB, including " +
				topMap.size() + " array(s) with " + LARGE_VECTOR_SIZE * Integer.BYTES/1024/1024 + " MB");  
		System.out.println(dataDesc + " Compression methods" + Arrays.toString(useMethods));
		System.out.println();
	}

	
	/*
	void  test(){
		int[] yVals = { 0, 1, 2, MAX_Y_VAL - 2, MAX_Y_VAL - 1, MAX_Y_VAL };
		for (int z = 0; z < 64; z++){
			for (int y : yVals){
				for (int x=0; x < 64; x++){
					int idx = (z & CHUNK_STORE_Z_MASK)<<CHUNK_STORE_Z_SHIFT
							| (y & CHUNK_STORE_Y_MASK)<< CHUNK_STORE_Y_SHIFT
							| (x & CHUNK_STORE_X_MASK);
					// extract
					int x2 = idx & CHUNK_STORE_X_MASK;
					int y2 = (idx >> CHUNK_STORE_Y_SHIFT) & CHUNK_STORE_Y_MASK;
					int z2 = (idx >> CHUNK_STORE_Z_SHIFT) & CHUNK_STORE_Z_MASK;
					assert x == x2;
					assert y == y2;
					assert z == z2;
				}
			}
		}
	}
	 */
}



