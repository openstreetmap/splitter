package uk.me.parabola.splitter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;


/**
 * Stores long/short pairs. 
 * TODO: reduce number of allocated bytes for the keys. We use longs, but most OSM IDs
 *  use not more than 31 bits.
 * 
 */
class Long2IntClosedMap implements Long2IntClosedMapFunction{
	private File tmpFile;
	private final String name;
	private long [] keys;
	private int [] vals;
	private final int maxSize;
	private final int unassigned; 
	private int size;
	private long currentKey;
	private int currentVal;
	private DataInputStream dis;
	
	
	public Long2IntClosedMap(String name, int maxSize, int unassigned) {
		this.name = name;
		this.maxSize = maxSize;
		keys = new long[maxSize];
		vals = new int[maxSize];
		this.unassigned = unassigned;
	}

	@Override
	public int add(long key, int val) {
		if (keys == null){
			throw new IllegalArgumentException("add on read-only map requested");
		}
		if (size > 0 && keys[size-1] > key)
			throw new IllegalArgumentException("New key is not higher than last key");
		if (size+1 > maxSize)
			throw new IllegalArgumentException(name + " map is full.");
		keys[size] = key;
		vals[size] = val;
		size++;
		return size-1;
	}

	@Override
	public void switchToSeqAccess(File directory) throws IOException {
		tmpFile = File.createTempFile(name,null,directory);
		tmpFile.deleteOnExit();
		FileOutputStream fos = new FileOutputStream(tmpFile);
		BufferedOutputStream stream = new BufferedOutputStream(fos);
		DataOutputStream dos = new DataOutputStream(stream);
		long lastKey = Long.MIN_VALUE;
		for (int i = 0; i <  size; i++){
			long key = keys[i];
			int val = vals[i];
			assert i == 0  | lastKey < key;
			lastKey = key;
			if (val != unassigned){
				dos.writeLong(key);
				dos.writeInt(val);
			}
		}
		// write sentinel
		dos.writeLong(Long.MAX_VALUE);
		dos.writeInt(Integer.MAX_VALUE);
		dos.close();
		keys = null;
		vals = null;
		System.out.println("Wrote " + size + " " + name + " pairs to " + tmpFile.getAbsolutePath());
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
	public  int getRandom(long key){
		int pos = getKeyPos(key);
		if (pos >= 0)
			return vals[pos];
		return unassigned;
	}

	@Override
	public int getKeyPos(long key) {
		if (keys == null){
			throw new IllegalArgumentException("random access on sequential-only map requested");
		}
		
		int pos = Arrays.binarySearch(keys,0,size, key);
		return pos;
	}

	@Override
	public int getSeq(long id){
		if (currentKey == Long.MIN_VALUE){
			try{
				open();
			} catch (IOException e) {
				// TODO: handle exception
			}
			readPair();
		}
		while(id > currentKey)
			readPair();
		if (id < currentKey){
			return unassigned;
		}
		return currentVal;

	}

	private void readPair() {
		try {
			if (dis == null)
				open();
			currentKey = dis.readLong();
			currentVal = dis.readInt();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	void open() throws IOException{
		FileInputStream fis = new FileInputStream(tmpFile);
		BufferedInputStream stream = new BufferedInputStream(fis);
		dis = new DataInputStream(stream);
	}

	@Override
	public void finish() {
		if (tmpFile != null && tmpFile.exists()){
			try {
				close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			tmpFile.delete();
			System.out.println("temporary file " + tmpFile.getAbsolutePath() + " was deleted");
		}
	}

	@Override
	public void close() throws IOException {
		currentKey = Long.MIN_VALUE;
		currentVal = unassigned;
		if (dis != null)
			dis.close();
	}

	@Override
	public int replace(long key, int val) {
		if (keys == null){
			throw new IllegalArgumentException("replace on read-only map requested");
		}
		int pos = getKeyPos(key);
		if (pos < 0)
			throw new IllegalArgumentException("replace on unknown key requested");
		int oldVal = vals[pos];
		vals[pos] = val;
		return oldVal;
	}

	@Override
	public void stats() {
		System.out.println(name + "WriterMap contains " + Utils.format(size) + " keys");
		
	}
}


