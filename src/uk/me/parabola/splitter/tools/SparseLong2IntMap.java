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

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.splitter.Utils;

import java.util.Arrays;



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
 * A typical (uncompressed) chunk looks like this:
 * v1,v1,v1,v1,v1,v1,v2,v2,v2,v2,v1,v1,v1,v1,v1,u,?,?,...}
 * v1,v2: values stored in the chunk
 * u: "unassigned" value
 * ?: anything
 *
 * After applying Run Length Encryption on this the chunk looks like this:
 * {u,6,v1,4,v2,5,v1,?,?,?}
 * The unassigned value on index 0 signals a compressed chunk.
 *
 * An (uncompressed) ONE_VALUE_CHUNK may look like this:
 * {v1,v1,v1,v1,v1,v1,v1,v1,v1,v1,v1,u,?,?,...}
 * This is stored without run length info in the shortest possible trunk:
 * {v1}
 *
 * Fortunately, OSM data is distributed in a way that most(!) chunks contain
 * just one distinct value.

 * Since we have OSM ids with 64 bits, we have to divide the key into 3 parts:
 * 37 bits for the value that is stored in the HashMap.
 * 21 bits for the chunkId (this gives the required length of a large vector)
 * 6 bits for the position in the chunk
 *
 * The chunkId identifies the position of a 32-bit value (stored in the large vector).
 * A chunk is stored in a chunkStore which is a 3-dimensional array.
 * We group chunks of equally length together in stores of 64 entries.
 * To find the right position of a new chunk, we need three values: x,y, and z.
 * x is the length of the chunk (the number of required ints) (1-64, we store the value decremented by 1 to have 0-63)
 * y is the position of the store (0-1048575), we store a value incremented by 1 to ensure a non-zero value for used chunks
 * z is the position of the chunk within the store. (0-63)
 * The maximum values for these three values are chosen so that we can place them
 * together into one int (32 bits).
 */

public class SparseLong2IntMap {
	private static final long CHUNK_ID_MASK = 0x7ffffffL; 		// the part of the key that is not saved in the top HashMap
	private static final long TOP_ID_MASK = ~CHUNK_ID_MASK;  	// the part of the key that is saved in the top HashMap
	private static final int TOP_ID_SHIFT = Long.numberOfTrailingZeros(TOP_ID_MASK);

	private static final int CHUNK_SIZE = 64; 							// 64  = 1<< 6 (last 6 bits of the key)
	/** number of entries addressed by one topMap entry */
	private static final int LARGE_VECTOR_SIZE = (int)(CHUNK_ID_MASK/ CHUNK_SIZE + 1); 

	private static final int CHUNK_STORE_BITS_FOR_Z = 6;
	private static final int CHUNK_STORE_BITS_FOR_Y = 20; 
	private static final int CHUNK_STORE_BITS_FOR_X = 6;
	
	private static final int CHUNK_STORE_ELEMS = 1 << CHUNK_STORE_BITS_FOR_X;
	private static final int CHUNK_STORE_X_MASK = (1 << CHUNK_STORE_BITS_FOR_X) - 1;
	private static final int CHUNK_STORE_Y_MASK = (1 << CHUNK_STORE_BITS_FOR_Y) - 1;
	private static final int CHUNK_STORE_Z_MASK = (1 << CHUNK_STORE_BITS_FOR_Z) - 1;
	private static final int CHUNK_STORE_Y_SHIFT = CHUNK_STORE_BITS_FOR_X;
	private static final int CHUNK_STORE_Z_SHIFT = CHUNK_STORE_BITS_FOR_X + CHUNK_STORE_BITS_FOR_Y;

	private static final int MAX_Y_VAL = CHUNK_STORE_Y_MASK -1; // we don't use the first y entry
	private static final long CHUNK_OFFSET_MASK = CHUNK_SIZE-1;  		// the part of the key that contains the offset in the chunk
	private static final long OLD_CHUNK_ID_MASK = ~CHUNK_OFFSET_MASK;	// first 58 bits of a long. If this part of the key changes, a different chunk is needed

	private static final long INVALID_CHUNK_ID = 1L; // must NOT be divisible by CHUNK_SIZE

	private static final int ONE_VALUE_CHUNK_SIZE = 1;

	/** What to return on unassigned indices */
	private int unassigned = Integer.MIN_VALUE;
	private long size;

	private long currentChunkId = INVALID_CHUNK_ID;
	private int [] currentChunk = new int[CHUNK_SIZE];  // stores the values in the real position
	private int [] tmpWork = new int[CHUNK_SIZE];  // a chunk after applying the "mask encoding"
	private int [] RLEWork = new int[CHUNK_SIZE];  // for the RLE-compressed chunk


	// for statistics
	private final String dataDesc;
	private long expanded = 0;
	private long uncompressedLen = 0;
	private long compressedLen = 0;
	private int storedLengthOfCurrentChunk = 0;
	private int currentChunkIdInStore = 0;

	private Long2ObjectOpenHashMap<Mem> topMap;

	final static long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
	final static int pointerSize = (maxMem < 32768) ? 4 : 8; // presuming that compressedOOps is enabled

	/**
	 * Helper class to manage memory for chunks
	 * @author Gerd Petermann
	 *
	 */
	static class Mem {
		long estimatedBytes; // estimate the allocated bytes
		final int[] largeVector;
		int[][][] chunkStore;
		long[][][] maskStore;
		final int[] freePosInStore;
		final int[] countChunkLen;
		/**  maps chunks that can be reused */
		Int2ObjectOpenHashMap<IntArrayList> reusableChunks;
		
		public Mem() {
			largeVector = new int[LARGE_VECTOR_SIZE];
			chunkStore = new int[CHUNK_SIZE + 1][][];
			maskStore = new long[CHUNK_SIZE + 1][][];
			freePosInStore = new int[CHUNK_SIZE + 1];
			countChunkLen = new int[CHUNK_SIZE + 1]; // used for statistics
			reusableChunks = new Int2ObjectOpenHashMap<>(0);
			estimatedBytes = LARGE_VECTOR_SIZE * Integer.BYTES + (CHUNK_SIZE + 1) * (2 * 8 + 2 * Integer.BYTES) + 5 * (24 + 16) + 190 + 8; 
		}

		public void grow(int x) {
			int oldCapacity = chunkStore[x].length;
	        int newCapacity = oldCapacity * 2;
	        if (newCapacity >= MAX_Y_VAL) 
	            newCapacity = MAX_Y_VAL;
	        if (newCapacity <= oldCapacity)
	        	return;
	        chunkStore[x] = Arrays.copyOf(chunkStore[x], newCapacity);
	        maskStore[x] = Arrays.copyOf(maskStore[x], newCapacity);
	        estimatedBytes += (newCapacity - oldCapacity) * (pointerSize * 2); 
		}

		public void startChunk(int x) {
			chunkStore[x] = new int[2][];
			maskStore[x] = new long[2][];
			estimatedBytes += 2 * (24 + 2 * (pointerSize * 2));
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
		clear();
	}

	/**
	 * Count how many of the lowest X bits in mask are set
	 *
	 * @return
	 */
	private static int countUnder(long mask, int lowest) {
		return Long.bitCount(mask & ((1L << lowest) - 1));
	}

	/**
	 * Try to use Run Length Encoding to compress the chunk stored in tmpWork. In most
	 * cases this works very well because chunks often have only one
	 * or two distinct values.
	 * @param maxlen: number of elements in the chunk.
	 * @return -1 if compression doesn't save space, else the number of elements in the
	 * compressed chunk stored in buffer RLEWork.
	 */
	private int chunkCompressRLE (int maxlen){
		int opos =  1;
		for (int i = 0; i < maxlen; i++) {
			int runLength = 1;
			while (i+1 < maxlen && tmpWork[i] == tmpWork[i+1]) {
				runLength++;
				i++;
			}
			if (opos+2 >= tmpWork.length)
				return -1; // compressed record is not shorter
			RLEWork[opos++] = runLength;
			RLEWork[opos++] = tmpWork[i];
		}
		if (opos == 3){
			// special case: the chunk contains only one distinct value
			// we can store this in a length-1 chunk because we don't need
			// the length counter nor the compression flag
			RLEWork[0] = RLEWork[2];
			return ONE_VALUE_CHUNK_SIZE;
		}

		if (opos < maxlen){
			RLEWork[0] = unassigned; // signal a normal compressed record
			return opos;
		}
		return -1;
	}

	/**
	 * Try to compress the data in currentChunk and store the result in the chunkStore.
	 */
	private void saveCurrentChunk(){
		long mask = 0;
		int RLELen = -1;
		int opos = 0;
		long elementMask = 1L;
		int [] chunkToSave;
		// move used entries to the beginning
		for (int j=0; j < CHUNK_SIZE; j++){
			if (currentChunk[j] != unassigned) {
				mask |= elementMask;
				tmpWork[opos++] = currentChunk[j];
			}
			elementMask <<= 1;
		}
		uncompressedLen += opos;
		if (opos > ONE_VALUE_CHUNK_SIZE)
			RLELen =  chunkCompressRLE(opos);
		if (RLELen > 0){
			chunkToSave = RLEWork;
			opos = RLELen;
		}
		else
			chunkToSave = tmpWork;
		compressedLen += opos;
		putChunk(currentChunkId, chunkToSave, opos, mask);
	}

	public boolean containsKey(long key) {
		return get(key) != unassigned;
	}


	public int put(long key, int val) {
		long chunkId = key & OLD_CHUNK_ID_MASK;
		if (val == unassigned) {
			throw new IllegalArgumentException("Cannot store the value that is reserved as being unassigned. val=" + val);
		}
		int chunkoffset = (int) (key & CHUNK_OFFSET_MASK);
		int out;
		if (currentChunkId == chunkId){
			out = currentChunk[chunkoffset];
			currentChunk[chunkoffset] = val;
			if (out == unassigned)
				size++;
			return out;
		}

		if (currentChunkId != INVALID_CHUNK_ID){
			// we need a different chunk
			saveCurrentChunk();
		}

		fillCurrentChunk(key);
		out = currentChunk[chunkoffset];
		currentChunkId = chunkId;
		currentChunk[chunkoffset] = val;
		if (out == unassigned)
			size++;

		return out;
	}


	/**
	 * Check if we already have a chunk for the given key. If no,
	 * fill currentChunk with default value, else with the saved
	 * chunk.
	 * @param key
	 */
	private void fillCurrentChunk(long key) {
		Arrays.fill(currentChunk, unassigned);
		storedLengthOfCurrentChunk = 0;
		currentChunkIdInStore = 0;
		long topID = key >> TOP_ID_SHIFT;
		Mem mem = topMap.get(topID);
		if (mem == null)
			return;
		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;

		int idx = mem.largeVector[chunkid];
		if (idx == 0)
			return;
		currentChunkIdInStore = idx;
		int x = idx & CHUNK_STORE_X_MASK;
		int y = (idx >> CHUNK_STORE_Y_SHIFT) & CHUNK_STORE_Y_MASK;
		y--; // we store the y value incremented by 1
		int chunkLen = x +  1;
		int [] store = mem.chunkStore[x][y];
		int z = (idx >> CHUNK_STORE_Z_SHIFT) & CHUNK_STORE_Z_MASK;

		long chunkMask = mem.maskStore[x][y][z];
		long elementmask = 0;

		++expanded;
		storedLengthOfCurrentChunk = x;
		int startPos = z * chunkLen + 1;
		boolean isCompressed = (chunkLen == ONE_VALUE_CHUNK_SIZE || store[startPos] == unassigned);
		if (isCompressed){
			int opos = 0;
			if (chunkLen == ONE_VALUE_CHUNK_SIZE) {
				// decode one-value-chunk
				int val = store[startPos];
				elementmask = 1;
				for (opos = 0; opos<CHUNK_SIZE; opos++){
					if ((chunkMask & elementmask) != 0)
						currentChunk[opos] = val;
					elementmask <<= 1;
				}
			}
			else {
				// decode RLE-compressed chunk with multiple values
				int ipos = startPos + 1;
				int len = store[ipos++];
				int val = store[ipos++];
				while (len > 0){
					while (len > 0 && opos < currentChunk.length){
						if ((chunkMask & 1L << opos) != 0){
							currentChunk[opos] = val;
							--len;
						}
						++opos;
					}
					if (ipos+1 < startPos + chunkLen){
						len = store[ipos++];
						val = store[ipos++];
					}
					else len = -1;
				}
			}
		}
		else {
			// decode uncompressed chunk
			int ipos = startPos;
			elementmask = 1;
			for (int opos=0; opos < CHUNK_SIZE; opos++) {
				if ((chunkMask & elementmask) != 0)
					currentChunk[opos] = store[ipos++];
				elementmask <<= 1;
			}
		}
	}

	public int get(long key){
		long chunkId = key & OLD_CHUNK_ID_MASK;
		int chunkoffset = (int) (key & CHUNK_OFFSET_MASK);

		if (currentChunkId == chunkId)
			return currentChunk[chunkoffset];

		long topID = key >> TOP_ID_SHIFT;
		Mem mem = topMap.get(topID);
		if (mem == null)
			return unassigned;
		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;

		int idx = mem.largeVector[chunkid];
		if (idx == 0)
			return unassigned;
		int x = idx & CHUNK_STORE_X_MASK;
		int y = (idx >> CHUNK_STORE_Y_SHIFT) & CHUNK_STORE_Y_MASK;
		y--; // we store the y value incremented by 1

		int chunkLen = x + 1;
		int[] store = mem.chunkStore[x][y];
		int z = (idx >> CHUNK_STORE_Z_SHIFT) & CHUNK_STORE_Z_MASK;

		long chunkMask = mem.maskStore[x][y][z];

		long elementmask = 1L << chunkoffset;
		if ((chunkMask & elementmask) == 0)
			return unassigned; // not in chunk
		int startOfChunk = z * chunkLen + 1;
		// the map contains the key, extract the value
		int firstAfterMask = store[startOfChunk];
		if (chunkLen == ONE_VALUE_CHUNK_SIZE)
			return firstAfterMask;
		int index = countUnder(chunkMask, chunkoffset);
		if (firstAfterMask == unassigned) {
			// extract from compressed chunk
			int len;
			for (int j = 1; j < chunkLen; j += 2) {
				len = store[j + startOfChunk];
				index -= len;
				if (index < 0)
					return store[j + startOfChunk + 1];
			}
			return unassigned; // should not happen
		}
		// extract from uncompressed chunk
		return store[index + startOfChunk];
	}

	public void clear() {
		System.out.println(dataDesc + " Map: uses " + this.getClass().getSimpleName());
		topMap = new Long2ObjectOpenHashMap<>();
		size = 0;
		uncompressedLen = 0;
		compressedLen = 0;
		expanded = 0;
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

	/**
	 * Find the place were a chunk has to be stored and copy the content
	 * to this place.
	 * @param key the (OSM) id
	 * @param chunk  the chunk
	 * @param len the number of used bytes in the chunk
	 */
	private void putChunk(long key, int[] chunk, int len, long mask) {
		long topID = key >> TOP_ID_SHIFT;
		Mem mem = topMap.get(topID);
		if (mem == null) {
			mem = new Mem();
			topMap.put(topID, mem);
		}

		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;
		int x = len - 1;
		if (storedLengthOfCurrentChunk > 0) {
			// this is a rewrite, add the previously used chunk to the reusable list
			IntArrayList reusableChunk = mem.reusableChunks.get(storedLengthOfCurrentChunk);
			if (reusableChunk == null) {
				reusableChunk = new IntArrayList(8);
				mem.reusableChunks.put(storedLengthOfCurrentChunk, reusableChunk);
				mem.estimatedBytes += 8 * Integer.BYTES + 24 + Integer.BYTES + pointerSize + 16; // for the IntArrayList instance 
				mem.estimatedBytes += 20; // estimate for the hash map entry
			}
			reusableChunk.add(currentChunkIdInStore);
		}
		if (mem.chunkStore[x] == null) {
			mem.startChunk(x);
		}
		IntArrayList reusableChunk = mem.reusableChunks.get(x);
		int y, z;
		int[] store;
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
				mem.chunkStore[x][y] = new int[len * (CHUNK_STORE_ELEMS) + 2];
				mem.estimatedBytes += 24 + (len * (CHUNK_STORE_ELEMS) + 2) * Integer.BYTES; 
				mem.maskStore[x][y] = new long[CHUNK_STORE_ELEMS];
				mem.estimatedBytes += 24 + CHUNK_STORE_ELEMS * Long.BYTES;
			}
			store = mem.chunkStore[x][y];
			z = store[0]++;
			++mem.countChunkLen[len];
		}

		mem.maskStore[x][y][z] = mask;
		y++; // we store the y value incremented by 1

		if (len > 1)
			System.arraycopy(chunk, 0, store, z * len + 1, len);
		else
			store[z * len + 1] = chunk[0];
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
	 * calculate and print performance values regarding memory
	 */
	public void stats(int msgLevel) {
		long totalBytes = CHUNK_SIZE * Integer.BYTES;
		long totalChunks = 1; // current chunk
			
		if (size() == 0){
			System.out.println(dataDesc + " Map is empty");
			return;
		}
		for (Mem mem : topMap.values()) {
			for (int x = 1; x <= CHUNK_SIZE; x++) {
				totalChunks += mem.countChunkLen[x];
			}
			totalBytes += mem.estimatedBytes;
		}

		float bytesPerKey = (size()==0) ? 0: (float)(totalBytes*100 / size()) / 100;
		System.out.println(dataDesc + " Map: Number of stored long/int pairs: " + Utils.format(size()) + " require ca. " +
				bytesPerKey + " bytes per pair. " +
				totalChunks + " chunks are used, the avg. number of values in one "+CHUNK_SIZE+"-chunk is " +
				((totalChunks==0) ? 0 :(size() / totalChunks)) + ".");
		System.out.println(dataDesc + " Map details: bytes " + Utils.format(totalBytes) + ", including " +
				topMap.size() + " arrays with " + LARGE_VECTOR_SIZE * Integer.BYTES/1024/1024 + " MB");  
		System.out.println();
		if (msgLevel > 0 & uncompressedLen > 0){
			System.out.print(dataDesc + " RLE compression info: compressed / uncompressed size / ratio: " + 
					Utils.format(compressedLen) + " / "+
					Utils.format(uncompressedLen) + " / "+
					Utils.format(Math.round(100-(float) (compressedLen*100/uncompressedLen))) + "%");
			if (expanded > 0 )
				System.out.print(", times fully expanded: " + Utils.format(expanded));
			System.out.println();
		}
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



