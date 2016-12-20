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
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
	/** the part of the key that is not saved in the top HashMap. */
	private static final long CHUNK_ID_MASK = 0x7ffffffL; 		
	private static final long TOP_ID_MASK = ~CHUNK_ID_MASK;  	// the part of the key that is saved in the top HashMap
	private static final int TOP_ID_SHIFT = Long.numberOfTrailingZeros(TOP_ID_MASK);

	private static final int CHUNK_SIZE = 64; 					// 64  = 1<< 6 (last 6 bits of the key)
	/** Number of entries addressed by one topMap entry. */
	private static final int LARGE_VECTOR_SIZE = (int) (CHUNK_ID_MASK / CHUNK_SIZE + 1);
	private static final int CHUNK_STORE_BITS_FOR_Z = 5; // must fit into byte field 
	private static final int CHUNK_STORE_BITS_FOR_Y = Integer.numberOfTrailingZeros(LARGE_VECTOR_SIZE) - CHUNK_STORE_BITS_FOR_Z + 1; 
	private static final int CHUNK_STORE_BITS_FOR_X = 8; // values 1 .. 256 are stored as 0..255
	
	private static final int CHUNK_STORE_ELEMS = 1 << CHUNK_STORE_BITS_FOR_Z;
	private static final int CHUNK_STORE_X_MASK = (1 << CHUNK_STORE_BITS_FOR_X) - 1;
	private static final int CHUNK_STORE_Y_MASK = (1 << CHUNK_STORE_BITS_FOR_Y) - 1;
	private static final int CHUNK_STORE_Z_MASK = (1 << CHUNK_STORE_BITS_FOR_Z) - 1;
	private static final int CHUNK_STORE_Y_SHIFT = CHUNK_STORE_BITS_FOR_X;
	private static final int CHUNK_STORE_Z_SHIFT = CHUNK_STORE_BITS_FOR_X + CHUNK_STORE_BITS_FOR_Y;

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
	private final int [] currentChunk = new int[CHUNK_SIZE]; // stores the values in the real position
	private final int [] maskedChunk = new int[CHUNK_SIZE]; // a chunk after applying the "mask encoding"
	private final int[] tmpChunk = new int[CHUNK_SIZE * 2]; // used for tests of compression methods
	private static final int MAX_BYTES_FOR_RLE_CHUNK = CHUNK_SIZE * (Integer.BYTES + 1);
	private final ByteBuffer bufEncoded = ByteBuffer.allocate(MAX_BYTES_FOR_RLE_CHUNK); // for the RLE-compressed chunk
	private static final int MAX_STORED_BYTES_FOR_CHUNK = CHUNK_SIZE * Integer.BYTES;
	
	// bit masks for the flag byte
	private static final int FLAG_USED_BYTES_MASK = 0x03; // number of bytes - 1 
	private static final int FLAG_COMP_METHOD_DELTA = 0x10; // rest of vals are delta encoded, bias is first val
	private static final int FLAG_COMP_METHOD_RLE = 0x80; // values are run length encoded

	// for statistics
	private final String dataDesc;
	
	private int storedLengthOfCurrentChunk;
	private int currentChunkIdInStore;

	private Long2ObjectOpenHashMap<Mem> topMap;

	static final long MAX_MEM = Runtime.getRuntime().maxMemory() / 1024 / 1024;
	static final int POINTER_SIZE = (MAX_MEM < 32768) ? 4 : 8; // presuming that compressedOOps is enabled
	
	private Integer bias1; // used for initial delta encoding 

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
		long[][][] maskStore;
		final int[] freePosInStore;
		/**  maps chunks that can be reused. */
		Int2ObjectOpenHashMap<IntArrayList> reusableChunks;
		
		public Mem(long topID) {
			this.topId = topID;
			largeVector = new int[LARGE_VECTOR_SIZE];
			chunkStore = new byte[MAX_STORED_BYTES_FOR_CHUNK][][];
			maskStore = new long[MAX_STORED_BYTES_FOR_CHUNK][][];
			freePosInStore = new int[MAX_STORED_BYTES_FOR_CHUNK];
			reusableChunks = new Int2ObjectOpenHashMap<>(0);
			estimatedBytes = LARGE_VECTOR_SIZE * Integer.BYTES 
					+ (MAX_STORED_BYTES_FOR_CHUNK) * (2 * 8 + 1 * Integer.BYTES) + 4 * (24 + 16) + 190; 
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
	        maskStore[x] = Arrays.copyOf(maskStore[x], newCapacity);
	        // bytes for pointers seem to depends on the capacity ?
	        estimatedBytes += (newCapacity - oldCapacity) * (2 * 8); // pointer-pointer  
		}

		public void startStore(int x) {
			chunkStore[x] = new byte[2][];
			maskStore[x] = new long[2][];
			estimatedBytes += 2 * (24 + 2 * (8)); // pointer-pointer
		}
	}
	
	/**
	 * Helper class to store the various positions in the multi-tier data structure.
	 * @author Gerd Petermann
	 *
	 */
	private class MemPos{
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
		
		public long getMask() {
			return mem.maskStore[x][y][z];		
		}
		
		public ByteBuffer getInBuf() {
			int chunkLen = x + 1;
			int startPos = z * chunkLen + 1;
			byte[] store = mem.chunkStore[x][y];
			return ByteBuffer.wrap(store, startPos, chunkLen);
		}
	}
	
	/**
	 * A map that stores pairs of (OSM) IDs and int values identifying the
	 * areas in which the object with the ID occurs. 
	 * @param dataDesc
	 */
	public SparseLong2IntMap(String dataDesc) {
		long reserve = (1L << CHUNK_STORE_BITS_FOR_Y - 1) * CHUNK_SIZE - LARGE_VECTOR_SIZE;
		assert reserve > 0;
		this.dataDesc = dataDesc;
		System.out.println(dataDesc + " Map: uses " + this.getClass().getSimpleName());
		clear();
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
	 * Try to use Run Length Encoding (RLE) to compress the chunk stored in tmpWork. In most
	 * cases this works very well because chunks often have only one or two distinct values.
	 * The run length value is always between 1 and 64, which requires just one byte. 
	 * If the stored values don't fit into a single byte, also try delta encoding (for values 2 .. n). 
	 * 
	 * @param numVals number of elements in the chunk, content of {@code maskedChunk} after that is undefined.
	 * @param minVal smallest value in maskedChunk 
	 * @param maxVal highest value in maskedChunk 
	 * 
	 */
	private void chunkCompress(int numVals, long minVal, long maxVal) {
		assert minVal != maxVal;
		int start = maskedChunk[0];
		int bytesFor1st = calcNeededBytes(start, start);
		int bytesForRest = calcNeededBytes(minVal, maxVal);
		int flag = 0;
		int bias2 = 0;
		int prefixLen = 1; 
		
		if (bytesForRest > 1) {
			// check if all values are in a small range which allows 
			int test = testBias(minVal, maxVal, start);
			if (test < bytesForRest) {
				bytesForRest = test;
				flag |= FLAG_COMP_METHOD_DELTA;
				bias2 = start;
			}
		}
		int lenNoCompress = Math.min(MAX_STORED_BYTES_FOR_CHUNK, prefixLen + bytesFor1st + (numVals-1) * bytesForRest);
		int lenRLE = prefixLen; 
	
		int numCounts = 0;
		int opos = 0;
		for (int i = 0; i < numVals; i++) {
			int runLength = 1;
			while (i+1 < numVals && maskedChunk[i] == maskedChunk[i+1]) {
				runLength++;
				i++;
			}
			numCounts++;
			tmpChunk[opos++] = maskedChunk[i];
			tmpChunk[opos++] = runLength;
			lenRLE += (numCounts == 1 ? bytesFor1st : bytesForRest) + 1;
			if (lenRLE >= lenNoCompress) 
				break;
		}
		flag |= (bytesForRest - 1) << 2 | (bytesFor1st - 1);
		
		boolean storeFlag = true;
		if (lenRLE < lenNoCompress) {
			flag |= FLAG_COMP_METHOD_RLE;
		} else {
			// check unlikely special case to make sure that encoded len is below 256 
			// don't write flag if all values are stored with 4 bytes 
			storeFlag = (lenNoCompress < MAX_STORED_BYTES_FOR_CHUNK);
		}
		if (storeFlag) {
			bufEncoded.put((byte) flag);
		}
		int bytesToUse = bytesFor1st;
		int bias = 0;
		if (lenRLE < lenNoCompress) {
			int pos = 0;
			while (pos < opos) {
				putVal(bufEncoded, tmpChunk[pos++] - bias, bytesToUse);
				bufEncoded.put((byte) tmpChunk[pos++]); // run length
				if (pos == 2) {
					bytesToUse = bytesForRest;
					bias = bias2;
				}
			}
			assert  lenRLE == bufEncoded.position();
		} else {
			for (int i = 0; i < numVals; i++) {
				putVal(bufEncoded, maskedChunk[i] - bias, bytesToUse);
				if (i == 0) {
					bytesToUse = bytesForRest;
					bias = bias2;
				}
			}
			assert  lenNoCompress == bufEncoded.position();
		}
	
		return;
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
		if (minVal == maxVal) {
			// nice: single value chunk 
			int bytesFor1st = calcNeededBytes(minVal, maxVal);
			if (bytesFor1st > 2) {
				bufEncoded.put((byte) (bytesFor1st - 1)); // flag byte
			}
			putVal(bufEncoded, maskedChunk[0], bytesFor1st);
		} else {
			chunkCompress(simpleLen, minVal, maxVal);
		}
		bufEncoded.flip();
		putChunk(currentChunkId, bufEncoded, mask);
	}

	/**
	 * Calculate the needed bytes for the range minVal..maxVal if bias is substructed.
	 * @param minVal start of range (including)
	 * @param maxVal end of range (including)
	 * @param bias the bias value to test
	 * @return the number of needed bytes
	 */
	private static int testBias(long minVal, long maxVal, int bias) {
		long minVal2 = minVal - bias;
		long maxVal2 = maxVal - bias;
		int test = calcNeededBytes(minVal2, maxVal2);
		return test;
	}

	/**
	 * Calculate the number of bytes needed to encode values in the given range.
	 * @param minVal smallest value
	 * @param maxVal highest value
	 * @return number of needed bytes
	 */
	static int calcNeededBytes (long minVal, long maxVal) {
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
	 * @return
	 */
	private int decodeStoredChunk (MemPos mp, int[] targetChunk, int chunkOffset) {
		int chunkLen = mp.x + 1;
		ByteBuffer inBuf = mp.getInBuf();

		int flag = 0;
		int bytesToUse = Integer.BYTES; // assume worst case
		if (chunkLen == MAX_STORED_BYTES_FOR_CHUNK) {
			// special case: no flag is written if we have the max. size
		} else if (chunkLen <= 2) {
			bytesToUse = chunkLen;
		} else {
			flag = inBuf.get();
			bytesToUse = (flag & FLAG_USED_BYTES_MASK) + 1;	
		}
		int bias = bias1;
		int start = bias + getVal(inBuf, bytesToUse);
		boolean singleValueChunk = (chunkLen <= 2 || chunkLen == 1 + bytesToUse);

		if (targetChunk == null && singleValueChunk) {
			return start;
		}
		long chunkMask = mp.getMask();
		int index = CHUNK_SIZE + 1; 
		if (targetChunk == null) {
			// we only want to retrieve one value for the index
			index = countUnder(chunkMask, chunkOffset); 
			if (index == 0 )
				return start;
		}
		int mPos = 0;
		maskedChunk[mPos++] = start;
		// rest of values might be encoded with different number of bytes
		if (chunkLen != MAX_STORED_BYTES_FOR_CHUNK) {
			bytesToUse = ((flag >> 2) & FLAG_USED_BYTES_MASK) + 1; 
			if ((flag & FLAG_COMP_METHOD_DELTA) != 0) {
				bias = start;
			}
		}
		int val = start;
		if (targetChunk == null && (flag & FLAG_COMP_METHOD_RLE) == 0) {
			// use shortcut, we can calculate the position of the wanted value
			inBuf.position(inBuf.position() + (index-1) * bytesToUse);
			return val = bias + getVal(inBuf, bytesToUse); 
		}
		// loop through the values
		while (inBuf.hasRemaining()) {
			if ((flag & FLAG_COMP_METHOD_RLE) != 0) {
				int runLength = inBuf.get();
				index -= runLength - 1;
				if (index <= 0)
					return val;
				while (--runLength > 0) {
					maskedChunk[mPos++] = val;
				}
				if (!inBuf.hasRemaining())
					break;
			}
			val = bias + getVal(inBuf, bytesToUse);
			if (--index <= 0)
				return val;
			maskedChunk[mPos++] = val;
			
		}
		if (targetChunk != null) {
			int j = 0;
			int opos = 0;
			while (chunkMask != 0) {
				if ((chunkMask & 1) != 0) {
					targetChunk[opos] = maskedChunk[j];
					if (!singleValueChunk) {
						j++;
					}
				}
				opos++;
				chunkMask >>>= 1;
			}
		}
		return unassigned; 
	}

	/**
	 * Use the various bit masks to extract the position of the chunk in the store.
	 * @param key the key for which we want the chunk
	 * @return the filled MemPos instance or null if the chunk is not in the store.
	 */
	private MemPos getMemPos(long key) {
		long topID = (key >> TOP_ID_SHIFT);
		Mem mem = topMap.get(topID);
		if (mem == null)
			return null;
		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;

		int idx = mem.largeVector[chunkid];  // performance bottleneck: produces many cache misses
		if (idx == 0)
			return null;
		currentChunkIdInStore = idx;
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
		storedLengthOfCurrentChunk = 0;
		currentChunkIdInStore = 0;
		MemPos mp = getMemPos(key);
		if (mp == null)
			return;

		currentChunkIdInStore = mp.largeVectorIndex;
		storedLengthOfCurrentChunk = mp.x;
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

		long chunkMask = mp.getMask();
		long elementmask = 1L << chunkoffset;
		if ((chunkMask & elementmask) == 0)
			return unassigned; // not in chunk
		// the map contains the key, decode it
		return decodeStoredChunk(mp, null, chunkoffset);
	}

	public void clear() {
		topMap = new Long2ObjectOpenHashMap<>(Hash.DEFAULT_INITIAL_SIZE, Hash.VERY_FAST_LOAD_FACTOR);
		
		Arrays.fill(currentChunk, 0);
		Arrays.fill(maskedChunk, 0);
		storedLengthOfCurrentChunk = 0;
		currentChunkIdInStore = 0;
		currentChunkId = INVALID_CHUNK_ID;
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

	private void putChunk(long key, ByteBuffer bb, long mask) {
		long topID = key >> TOP_ID_SHIFT;
		Mem mem = topMap.get(topID);
		if (mem == null) {
			mem = new Mem(topID);
			topMap.put(topID, mem);
		}

		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;
		int len = bb.limit();
		int x = len - 1;
		if (storedLengthOfCurrentChunk > 0) {
			// this is a rewrite, add the previously used chunk to the reusable list
			IntArrayList reusableChunk = mem.reusableChunks.get(storedLengthOfCurrentChunk);
			if (reusableChunk == null) {
				reusableChunk = new IntArrayList(8);
				mem.reusableChunks.put(storedLengthOfCurrentChunk, reusableChunk);
				mem.estimatedBytes += 8 * Integer.BYTES + 24 + Integer.BYTES + POINTER_SIZE + 16; // for the IntArrayList instance 
				mem.estimatedBytes += 20; // estimate for the hash map entry
			}
			reusableChunk.add(currentChunkIdInStore);
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
				int numElems = len * CHUNK_STORE_ELEMS + 1;
				mem.chunkStore[x][y] = new byte[numElems]; 
				mem.estimatedBytes += 24 + (numElems) * Byte.BYTES;
				int padding = 8 - (numElems * Byte.BYTES % 8);
				if (padding < 8)
					mem.estimatedBytes += padding;
				mem.maskStore[x][y] = new long[CHUNK_STORE_ELEMS];
				mem.estimatedBytes += 24 + CHUNK_STORE_ELEMS * Long.BYTES;
			}
			store = mem.chunkStore[x][y];
			z = store[0]++;
		}

		mem.maskStore[x][y][z] = mask;
		ByteBuffer storeBuf = ByteBuffer.wrap(store, z * len + 1, len);
		storeBuf.put(bb);
	
		// calculate the position in the large vector
		y++; // we store the y value incremented by 1
		assert x < 1 << CHUNK_STORE_BITS_FOR_X;
		assert y < 1 << CHUNK_STORE_BITS_FOR_Y;
		assert z < 1 << CHUNK_STORE_BITS_FOR_Z;
		int idx = (z & CHUNK_STORE_Z_MASK) << CHUNK_STORE_Z_SHIFT 
				| (y & CHUNK_STORE_Y_MASK) << CHUNK_STORE_Y_SHIFT
				| (x & CHUNK_STORE_X_MASK);

		assert idx != 0;
		mem.largeVector[chunkid] = idx;
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
		float bytesPerKey = (size()==0) ? 0: (float)(totalBytes*100 / size()) / 100;
		System.out.println(dataDesc + " Map: " + Utils.format(size()) + " stored long/int pairs require ca. " +
				bytesPerKey + " bytes per pair. " +
				totalChunks + " chunks are used, the avg. number of values in one "+CHUNK_SIZE+"-chunk is " +
				((totalChunks==0) ? 0 :(size() / totalChunks)) + ".");
		System.out.println(dataDesc + " Map details: bytes ~" + Utils.format(totalBytes/1024/1024) + " MB, including " +
				topMap.size() + " array(s) with " + LARGE_VECTOR_SIZE * Integer.BYTES/1024/1024 + " MB");  
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



