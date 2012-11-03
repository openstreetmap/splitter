package uk.me.parabola.splitter;

import it.unimi.dsi.bits.Fast;

import java.util.Arrays;
import java.util.HashMap;


/**
 * SparseLong2intMapInline implements SparseLong2intMapFunction 
 * optimized for low memory requirements and inserts in sequential order.
 *
 * A HashMap is used to address large vectors which address chunks. The HashMap 
 * is the only part that stores long values, and it will be very small as long 
 * as long as input is normal OSM data and not something with random numbers. 
 * A chunk stores up to CHUNK_SIZE values and a bit-mask. The bit-mask is used
 * to separate used and unused entries in the chunk. Thus, the chunk length 
 * depends on the number of used entries, not on the highest used entry.
 * A typical (uncompressed) chunk looks like this:
 * {m1,m2,m3,m4,v1,v1,v1,v1,v1,v1,v2,v2,v2,v2,v1,v1,v1,v1,v1,u,?,?,...}
 * m1-m4: the bit-mask
 * v1,v2: values stored in the chunk
 * u: "unassigned" value
 * ?: anything
 * 
 * After applying Run Length Encryption on this the chunk looks like this:
 * {m1,m2,m3,m4,u,6,v1,4,v2,5,v1,?,?,?}
 * The unassigned value on index 5 signals a compressed chunk.
 * 
 * An (uncompressed)  ONE_VALUE_CHUNK may look like this:
 * {m1,m2,m3,m4,v1,v1,v1,v1,v1,v1,v1,v1,v1,v1,v1,u,?,?,...}
 * This is stored without run length info in the shortest possible trunk:
 * {m1,m2,m3,m4,u,v1}
 * 
 * Fortunately, OSM data is distributed in a way that most(!) chunks contain
 * just one distinct value, so most chunks can be stored in 24 or 32 bytes
 * instead of 152 bytes for the worst case (counting also the padding bytes).
 */
public class SparseLong2IntMapInline implements SparseLong2IntMapFunction{
	static final int CHUNK_SIZE = 64; 							// 64  = 1<< 6 (last 6 bits of the key) 
	static final long TOP_ID_MASK = 0xfffffffff0000000L;  		// the part of the key that is saved in the HashMap (first 36 bits)
	static final long CHUNK_OFFSET_MASK = CHUNK_SIZE-1;  		// the part of the key that contains the offset in the chunk
	static final long OLD_CHUNK_ID_MASK = ~CHUNK_OFFSET_MASK;	// first 58 bits of a long. If this part of the key changes, a different chunk is needed
	static final long CHUNK_ID_MASK     = ~TOP_ID_MASK; 		// last 28 bits of a long

	static final long INVALID_CHUNK_ID = 1L; // must NOT be divisible by CHUNK_SIZE 
	public static final int LARGE_VECTOR_SIZE = (int)(CHUNK_ID_MASK/ CHUNK_SIZE + 1); // number of entries addressed by one topMap entry 

	static final int CHUNK_MASK_SIZE = 2;	// number of chunk elements needed to store the chunk mask
	static final int ONE_VALUE_CHUNK_SIZE = CHUNK_MASK_SIZE+2; 

	private HashMap<Long, int[][]> topMap;
	private int [] paddedLen = new int[CHUNK_SIZE+CHUNK_MASK_SIZE];

	/** What to return on unassigned indices */
	private int unassigned = UNASSIGNED;
	private long size;

	private long oldChunkId = INVALID_CHUNK_ID; 
	private int [] oldChunk = null; 
	private int [] currentChunk = new int[CHUNK_SIZE+CHUNK_MASK_SIZE]; 
	private int [] tmpWork = new int[CHUNK_SIZE+CHUNK_MASK_SIZE]; 
	private int [] RLEWork = new int[CHUNK_SIZE+CHUNK_MASK_SIZE];


	// for statistics
	private long [] countChunkLen; 
	private long expanded = 0;
	private long uncompressedLen = 0;
	private long compressedLen = 0;

	/**
	 * A map that stores pairs of (OSM) IDs and int values identifying the
	 * areas in which the object (node,way) with the ID occurs.
	 */
	SparseLong2IntMapInline() {
		clear();
	}

	/**
	 * Get the value from a (compressed) chunk
	 * @param array: 
	 * @param index: 
	 * @return
	 */
	private int chunkGet(int[] array, int index) {
		//assert index+MASK_SIZE < array.length;
		// if the first value is unassigned we have a compressed chunk
		if (array[CHUNK_MASK_SIZE] == unassigned){
			if (array.length == ONE_VALUE_CHUNK_SIZE) {
				//this is a one-value-chunk
				return array[CHUNK_MASK_SIZE+1];
			}
			else 
			{
			int len;
			int x = index;
			for (int j=CHUNK_MASK_SIZE+1; j < array.length; j+=2){
				len =  array[j];
				x -= len;
				if (x < 0) return array[j+1];
			}
			return unassigned; // should not happen
		}
		}
		else
			return array[index + CHUNK_MASK_SIZE];
	}


	/**
	 * Count how many of the lowest X bits in mask are set
	 * 
	 * @return
	 */
	private int countUnder(long mask, int lowest) {
		return Fast.count(mask & ((1L << lowest) - 1));
	}

	/**
	 * retrieve (compressed) chunk and expand it into the work buffer
	 * @param array
	 */
	private void fillCurrentChunk(int [] array) {
		long mask = extractMask(array);
		long elementmask = 0;

		++expanded;
		Arrays.fill(currentChunk, unassigned);
		if (array[CHUNK_MASK_SIZE] == unassigned){
			int opos = 0;
			if (array.length == ONE_VALUE_CHUNK_SIZE) {
				// decode one-value-chunk
				int val = array[CHUNK_MASK_SIZE+1];
				elementmask = 1;
				for (opos = 0; opos<CHUNK_SIZE; opos++){
					if ((mask & elementmask) != 0)
						currentChunk[opos] = val;
					elementmask <<= 1;
				}
			}
			else {
				// decode RLE-compressed chunk with multiple values
			int ipos = CHUNK_MASK_SIZE+1;
			int len = array[ipos++];
			int val = array[ipos++];
			while (len > 0){
					while (len > 0 && opos < currentChunk.length){
					if ((mask & 1L << opos) != 0){ 
						currentChunk[opos] = val; 
						--len;
					}
					++opos;
				}
				if (ipos+1 < array.length){
					len = array[ipos++];
					val = array[ipos++];
				}
				else len = -1;
			}
		}
		}
		else {
			// decode uncompressed chunk
			int ipos = CHUNK_MASK_SIZE;
			elementmask = 1;
			for (int opos=0; opos < CHUNK_SIZE; opos++) {
				if ((mask & elementmask) != 0) 
					currentChunk[opos] = array[ipos++];
				elementmask <<= 1;
			}
		}
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
		int opos = CHUNK_MASK_SIZE + 1;
        for (int i = CHUNK_MASK_SIZE; i < maxlen; i++) {
            int runLength = 1;
            while (i+1 < maxlen && tmpWork[i] == tmpWork[i+1]) {
                runLength++;
                i++;
            }
            if (opos+2 >= tmpWork.length) 
            	return -1;
            RLEWork[opos++] = runLength;
            RLEWork[opos++] = tmpWork[i]; 
        }
		if (opos > ONE_VALUE_CHUNK_SIZE+1){
			// cosmetic: fill unused entries with the unassigned value
			// in fact we only need to set opos to the padded len
			// but it eases debugging when garbage is cleaned up.
			while (opos < maxlen && opos < paddedLen[opos])
				RLEWork[opos++] = unassigned;
		}
		else {
			// special case: the chunk contains only one distinct value
			// we can store this in a length-6 chunk because we don't need
			// the length counter
			RLEWork[CHUNK_MASK_SIZE+1] = RLEWork[CHUNK_MASK_SIZE+2];
			opos = ONE_VALUE_CHUNK_SIZE;
		}

        if (opos < maxlen){
			RLEWork[CHUNK_MASK_SIZE] = unassigned; // signal a compressed record
        	return opos;
        }
        else 
        	return -1;
	}
	/**
	 * Try to compress the chunk in currentChunk and store it in the chunk vector.
	 */
	private void saveCurrentChunk(){
		long mask = 0;
		int RLELen = -1;
		int opos = CHUNK_MASK_SIZE;
		long elementMask = 1L;
		int [] chunkToSave;
		for (int j=0; j < CHUNK_SIZE; j++){
			if (currentChunk[j] != unassigned) {
				mask |= elementMask;
				tmpWork[opos++] = currentChunk[j];
			}
			elementMask <<= 1;
		}
		// good chunk length is a value that gives (12+2*opos) % 8 == 0
		// that means opos values 6,10,14,..,66, are nice
		int saveOpos = opos;
		while (opos < tmpWork.length && opos < paddedLen[opos])
			tmpWork[opos++] = unassigned;
		uncompressedLen += opos;
		if (opos > ONE_VALUE_CHUNK_SIZE)
			RLELen =  chunkCompressRLE(saveOpos);
		if (RLELen > 0){
			chunkToSave = RLEWork;
			opos = RLELen;
		}
		else
			chunkToSave = tmpWork;
		compressedLen += opos;
		oldChunk = new int[opos];
		System.arraycopy(chunkToSave, CHUNK_MASK_SIZE, oldChunk, CHUNK_MASK_SIZE, opos-CHUNK_MASK_SIZE);
		storeMask(mask, oldChunk);
		putChunk(oldChunkId,oldChunk);
		++countChunkLen[oldChunk.length];
	}

	@Override
	public boolean containsKey(long key) {
		long chunkId = key & OLD_CHUNK_ID_MASK;
		if (oldChunkId != chunkId && oldChunkId != INVALID_CHUNK_ID){
			saveCurrentChunk();
		}
		int chunkoffset = (int) (key & CHUNK_OFFSET_MASK);

		if (oldChunkId == chunkId)
			 return currentChunk[chunkoffset] != unassigned;
		oldChunkId = INVALID_CHUNK_ID;
		int [] chunk = getChunk(key);
		if (chunk == null) 
			return false;
		long chunkmask = extractMask(chunk);
		long elementmask = 1L << chunkoffset;
		return (chunkmask & elementmask) != 0; 
	}


	@Override
	public int put(long key, int val) {
		long chunkId = key & OLD_CHUNK_ID_MASK;
		if (val == unassigned) {
			throw new IllegalArgumentException("Cannot store the value that is reserved as being unassigned. val=" + val);
		}
		int chunkoffset = (int) (key & CHUNK_OFFSET_MASK);
		int out;
		if (oldChunkId == chunkId){
			out = currentChunk[chunkoffset];
			currentChunk[chunkoffset] = val;
			if (out == unassigned)
				size++;
			return out;
		}
		if (oldChunkId != INVALID_CHUNK_ID)
			saveCurrentChunk();

		int [] chunk = getChunk(key);
		if (chunk == null){
			Arrays.fill(currentChunk, unassigned);
		}
		else {
			// this is the worst case: we have to modify
			// a chunk that was already saved
			fillCurrentChunk(chunk);
			--countChunkLen[chunk.length];
		}
		out = currentChunk[chunkoffset];
		oldChunkId = chunkId;
		currentChunk[chunkoffset] = val;
		if (out == unassigned)
			size++;
		return out;
	}


	@Override
	public int get(long key) {
		long chunkId = key & OLD_CHUNK_ID_MASK;
		if (oldChunkId != chunkId && oldChunkId != INVALID_CHUNK_ID){
			saveCurrentChunk();
		}
		int chunkoffset = (int) (key & CHUNK_OFFSET_MASK);

		if (oldChunkId == chunkId)
			 return currentChunk[chunkoffset];
		oldChunkId = INVALID_CHUNK_ID;
		int [] chunk = getChunk(key);
		if (chunk == null) 
			return unassigned;
		long chunkmask = extractMask(chunk);
		long elementmask = 1L << chunkoffset;
		int out;
		if ((chunkmask & elementmask) == 0) {
			out = unassigned;
		} else {
			out = chunkGet(chunk, countUnder(chunkmask, chunkoffset));
		}
		return out;
	}

	@Override
	public void clear() {
		System.out.println(this.getClass().getSimpleName() + ": Allocating three-tier structure to save area info (HashMap->vector->chunkvector)");
		// good chunk length is a value that gives (12+ 4 * chunk.length) % 8 == 0
		// that means chunk.length values 5,7,9..,65 are nice
		for (int i=0; i<paddedLen.length; i++){
			int plen = i;
			while ((12+4*plen) % 8 != 0) plen++;
			paddedLen[i] = Math.min(plen, CHUNK_SIZE+CHUNK_MASK_SIZE);
		}
		topMap = new HashMap<Long, int[][]>();
		countChunkLen = new long[CHUNK_SIZE + CHUNK_MASK_SIZE + 1 ]; // used for statistics
		size = 0;
		uncompressedLen = 0;
		compressedLen = 0;
		expanded = 0;
	}

	@Override
	public long size() {
		return size;
	}

	@Override
	public int defaultReturnValue() {
		return unassigned;
	}

	@Override
	public void defaultReturnValue(int arg0) {
		unassigned = arg0;
	}

	private int[] getChunk (long key){
		long topID = key & TOP_ID_MASK;
		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;
		int[][]  t = topMap.get(topID);
		if (t == null)
			return null;
		return t[chunkid];
	}

	private void putChunk (long key, int[] chunk) {
		long topID = key & TOP_ID_MASK;
		int chunkid = (int) (key & CHUNK_ID_MASK) / CHUNK_SIZE;
		int[][]  largeVector = topMap.get(topID);
		if (largeVector == null){
			largeVector = new int[LARGE_VECTOR_SIZE][];
			topMap.put(topID, largeVector);
		}
		largeVector[chunkid] = chunk;
	}

	/**
	 *  Store the mask value (a long) in the 1st MASK_SIZE chunk elements.
	 * @param mask
	 * @param chunk
	 */
	private void storeMask (long mask, int[] chunk) {
		// store chunkmask in chunk
		long tmp = mask;
		chunk[0] = (int) (tmp & 0xffffffffL);
		tmp >>= 32;
		chunk[1] = (int) (tmp & 0xffffffffL);
	}
	/** 
	 * Extract the (long) mask value from the 1st MASK_SIZE chunk elements.
	 * @param chunk
	 * @return the mask
	 */
	private long extractMask(int [] chunk){
		long mask = 0;
		mask |= (chunk[1] & 0xffffffffL);
		mask <<= 32;
		mask |= (chunk[0] & 0xffffffffL);
		return mask;
	}

	static final int APPROX_BYTES_FOR_ONE_HASH_ENTRY = 36; // 1.5 * (16 bytes for the Long object, 4 bytes for key ref + 4 bytes for value ref)
	static final int VECTOR_OVERHEAD = 16; // 8 bytes for object, 4 bytes for length, 4 bytes for padding

	@Override
	public void stats(int msgLevel) {
		long usedChunks = 0;
		long pctusage = 0;
		int i;
		if (msgLevel > 0)
			System.out.println("Number of stored ids: " + Utils.format(size()));
		for (i=5; i <=CHUNK_SIZE + CHUNK_MASK_SIZE; i+=2) {
			usedChunks += countChunkLen[i];
			long bytes = countChunkLen[i] * (12+i*4); // no padding in our chunks 
			if (msgLevel > 0) { 
				System.out.println("Length-" + i + " chunks: " + Utils.format(countChunkLen[i]) + " (Bytes: " + Utils.format(bytes) + ")");
			}
		}
		i = CHUNK_SIZE+CHUNK_MASK_SIZE;
		usedChunks += countChunkLen[i];
		if (msgLevel > 0) { 
			System.out.println("Length-" + i + " chunks: " + Utils.format(countChunkLen[i]) + " (Bytes: " + Utils.format(countChunkLen[i] * (VECTOR_OVERHEAD+i*4)) + ")");
		}
		if (msgLevel > 0 & uncompressedLen > 0){
			System.out.print("RLE compresion info: compressed / uncompressed size / ratio: " + 
					Utils.format(compressedLen) + " / "+ 
					Utils.format(uncompressedLen) + " / "+
					Utils.format(Math.round(100-(float) (compressedLen*100/uncompressedLen))) + "%");
			if (expanded > 0 )
				System.out.print(", times fully expanded: " + Utils.format(expanded));
			System.out.println();
		}
		pctusage = Math.min(100, 1 + Math.round((float)usedChunks*100/(topMap.size()*LARGE_VECTOR_SIZE))) ;
		System.out.println("Map details: HashMap -> " + topMap.size() + " vectors for "+ Utils.format(usedChunks) + " chunks(vector usage < " + pctusage + "%)");
	}
}

