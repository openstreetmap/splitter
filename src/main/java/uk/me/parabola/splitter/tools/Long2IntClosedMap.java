/*
 * Copyright (C) 2012, Gerd Petermann
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

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import uk.me.parabola.splitter.SplitFailedException;
import uk.me.parabola.splitter.Utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Stores long/int pairs. 
 * Requires less heap space compared to a HashMap while updates are allowed, and almost no
 * heap when sequential access is used. This is NOT a general purpose class.
 * 
 * @author GerdP 
 */
public class Long2IntClosedMap implements Long2IntClosedMapFunction{
	private static final long LOW_ID_MASK = 0x3fffffffL; // 30 bits
	private static final long TOP_ID_MASK = ~LOW_ID_MASK;
	private static final int TOP_ID_SHIFT = Long.numberOfTrailingZeros(TOP_ID_MASK);  
	
	private File tmpFile;
	private final String name;
	private LongArrayList index;	// stores the higher 34 bits of the key which doesn't change frequently
	private IntArrayList bounds;
	// stores the lower 30 bits of the long value
	private int [] keys;
	private int [] vals;
	private final int maxSize;
	private final int unassigned; 
	private int size;
	private long currentKey = Long.MIN_VALUE;
	private long oldTopId = Long.MIN_VALUE;
	private int currentVal;
	private DataInputStream dis;
	
	
	public Long2IntClosedMap(String name, int maxSize, int unassigned) {
		this.name = name;
		this.maxSize = maxSize;
		index = new LongArrayList();
		bounds = new IntArrayList();
		keys = new int[maxSize];
		this.unassigned = unassigned;
	}

	@Override
	public int add(long key, int val) {
		if (key == 0 || key == Long.MAX_VALUE){
			throw new IllegalArgumentException("Error: Cannot store " + name + " id " + key + ", this value is reserved.");
		}
		if (keys == null){
			throw new IllegalArgumentException(name + ": Add on read-only map requested");
		}
		if (size > 0 && currentKey >= key)
			throw new IllegalArgumentException("New " + name + " id " + key + " is not higher than last id " + currentKey);
		if (size+1 > maxSize)
			throw new IllegalArgumentException(name + " Map is full.");
		long topId = key >> TOP_ID_SHIFT;
		if (topId != oldTopId){
			index.add(topId);
			bounds.add(size);
			oldTopId = topId;
		}
		keys[size] = (int)(key & LOW_ID_MASK);
		if (val != unassigned) {
			if (vals == null)
				allocVals();

			vals[size] = val;
		}
		currentKey = key;
		size++;
		return size-1;
	}

	@Override
	public void switchToSeqAccess(File directory) throws IOException {
		tmpFile = File.createTempFile(name,null,directory);
		tmpFile.deleteOnExit();
		try (FileOutputStream fos = new FileOutputStream(tmpFile);
				BufferedOutputStream stream = new BufferedOutputStream(fos);
				DataOutputStream dos = new DataOutputStream(stream)) {
			long lastKey = Long.MIN_VALUE;
			if (vals != null) {
				for (int indexPos = 0; indexPos < index.size(); indexPos++){
					long topId = index.getLong(indexPos);
					int lowerBound = bounds.getInt(indexPos);
					int upperBound = size;
					if (indexPos+1 < index.size())
						upperBound = bounds.getInt(indexPos+1);
					long topVal = topId << TOP_ID_SHIFT;
					for (int i = lowerBound; i <  upperBound; i++){
						long key = topVal | (keys[i] & LOW_ID_MASK);

						int val = vals[i];
						assert i == 0  || lastKey < key;
						lastKey = key;
						if (val != unassigned){
							dos.writeLong(key);
							dos.writeInt(val);
						}
					}
				}
			}
			// write sentinel
			dos.writeLong(Long.MAX_VALUE);
			dos.writeInt(Integer.MAX_VALUE);
			keys = null;
			vals = null;
			index = null;
			bounds = null;
			currentKey = Long.MIN_VALUE;
			System.out.println("Wrote " + size + " " + name + " pairs to " + tmpFile.getAbsolutePath());
		}
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
		if (vals == null)
			return unassigned;
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
		long topId = key >> TOP_ID_SHIFT;
		int indexPos = Arrays.binarySearch(index.toLongArray(),0,index.size(),topId);
		if (indexPos < 0)
			return -1;
		int lowerBound = bounds.getInt(indexPos);
		int upperBound = size;
		if (bounds.size() > indexPos+1)
			upperBound = bounds.getInt(indexPos+1);
		int lowId = (int)(key & LOW_ID_MASK);
		int pos = Arrays.binarySearch(keys,lowerBound,upperBound, lowId);
		return pos;
	}

	@Override
	public int getSeq(long id){
		if (currentKey == Long.MIN_VALUE){
			dis = null;
			readPair();
		}
		while(id > currentKey)
			readPair();
		if (id < currentKey || id == Long.MAX_VALUE){
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
		} catch (IOException e){
			System.out.println(e);
			throw new SplitFailedException("Failed to read from temp file " + tmpFile);
		}
	}

	private void open() throws FileNotFoundException{
		FileInputStream fis = new FileInputStream(tmpFile);
		BufferedInputStream stream = new BufferedInputStream(fis);
		dis = new DataInputStream(stream);
	}

	@Override
	public void finish() {
		if (tmpFile != null && tmpFile.exists()){
			close();
			tmpFile.delete();
			System.out.println("temporary file " + tmpFile.getAbsolutePath() + " was deleted");
		}
	}

	@Override
	public void close() {
		currentKey = Long.MIN_VALUE;
		currentVal = unassigned;
		if (dis != null)
			try {
				dis.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	}

	@Override
	public int replace(long key, int val) {
		if (keys == null){
			throw new IllegalArgumentException("replace on read-only map requested");
		}
		int pos = getKeyPos(key);
		if (pos < 0)
			throw new IllegalArgumentException("replace on unknown key requested");
		if (vals == null)
			allocVals();
		int oldVal = vals[pos];
		vals[pos] = val;
		return oldVal;
	}

	@Override
	public void stats(String prefix) {
		System.out.println(prefix + name + "WriterMap contains " + Utils.format(size) + " pairs.");
	}
	
	private void allocVals() {
		vals = new int[maxSize];
		Arrays.fill(vals, unassigned);
	}
}


