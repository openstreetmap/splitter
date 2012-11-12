/*
 * Copyright (c) 2011,2012.
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
package uk.me.parabola.splitter;

import it.unimi.dsi.Util;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Stores data that is needed in different passes of the program.
 * @author GerdP
 *
 */
public class DataStorer{
	public final static int NODE_TYPE  = 0;
	public final static int WAY_TYPE   = 1;
	public final static int REL_TYPE   = 2;
	private final int numOfWriters;
	private final WriterMapper[] maps = new WriterMapper[3];
	private final String[] mapNames = {"node", "way", "rel"};
	private final WriterDictionaryShort writerDictionary;
	private final WriterDictionaryInt multiTileWriterDictionary;
	private final WriterIndex writerIndex;
	private SparseLong2ShortMapFunction usedWays = null;
	private final HashMap<Long,Integer> usedRels = new HashMap<Long, Integer>();
	private boolean idsAreNotSorted;
	private boolean savedToFiles = false;

	/** 
	 * Create a dictionary for a given number of writers
	 * @param numOfWriters the number of writers that are used
	 */
	DataStorer (OSMWriter [] writers){
		this.numOfWriters = writers.length;
		this.writerDictionary = new WriterDictionaryShort(writers);
		this.multiTileWriterDictionary = new WriterDictionaryInt(writers);
		for (int i = 0; i< maps.length; i++){
			maps[i] = new WriterMapper(mapNames[i]);
		}
		this.writerIndex = new WriterGrid(writerDictionary);
		return;
	}

	public int getNumOfWriters(){
		return numOfWriters;
	}

	public WriterDictionaryShort getWriterDictionary() {
		return writerDictionary;
	}


	public void stats(){
		for (WriterMapper map: maps)
			map.stats();
	}

	public WriterIndex getGrid() {
		return writerIndex;
	}

	public WriterDictionaryInt getMultiTileWriterDictionary() {
		return multiTileWriterDictionary;
	}

	public SparseLong2ShortMapFunction getUsedWays() {
		return usedWays;
	}

	public HashMap<Long, Integer> getUsedRels() {
		return usedRels;
	}

	public Integer putWriterIdx (int mapType, long id, int idx){
		if (savedToFiles){
			System.out.println("Fatal error: tried to update fixed map");
			System.exit(-1);
		}
		return maps[mapType].put(id,idx);
	}

	public Integer getWriterIdx (int mapType, long id){
		if (savedToFiles)
			return maps[mapType].getSeq(id);
		return maps[mapType].getRandom(id);
	}

	/**
	 * This indicates that all data is collected and only 
	 * sequential read access 
	 * @param fileOutputDir
	 * @throws IOException
	 */
	public void setReadOnly(File fileOutputDir) throws IOException{
		if (idsAreNotSorted)
			return; // can't use files to save heap
		if (maps[NODE_TYPE].size() <= 100000)
			return;
		System.out.println("Writing maps to temp files... ");
		long start = System.currentTimeMillis();
		for (int i = 0; i< maps.length; i++){
			maps[i].writeMapToFile(fileOutputDir);
		}
		savedToFiles = true;
		System.out.println("Writing temp files took " + (System.currentTimeMillis()-start) + " ms");
	}

	public void restart() throws IOException{
		if (!savedToFiles)
			return;
		for (WriterMapper map: maps)
			map.close();
	}

	public void finish(){
		if (!savedToFiles)
			return;
		for (WriterMapper map: maps)
			map.finish();

	}
	/**
	 * 
	 * @author GerdP
	 *
	 */
	private class WriterMapper{
		String name;
		File tmpFile;
		Map<Long,Integer> map;
		DataInputStream dis;
		long currentKey;
		Integer currentVal;

		public WriterMapper(String name) {
			this.name = name;
			this.map = new HashMap<Long, Integer>();
			currentKey = Long.MIN_VALUE;
		}
		public int size() {
			return map.size();
		}
		public void finish() {
			if (tmpFile != null && tmpFile.exists()){
				try {
					close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				tmpFile.delete();
			}
		}

		public void stats (){
			System.out.println(map.getClass().getSimpleName() + "<Long,Integer> " + name + "-Writers  : " + Util.format(map.size()));
		}

		public Integer put(long key, int value){
			return map.put(key, value);
		}

		public  Integer getRandom(long id){
			return map.get(id);
		}

		public Integer getSeq(long id){
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
				return null;
			}
			return currentVal;

		}
		
		/**
		 * Write content of a map to a temporary file, making sure that data is sorted by the keys. 
		 * @param fileOutputDir
		 * @throws IOException
		 */
		private void writeMapToFile(File fileOutputDir) throws IOException{
			for (int i = 0; i < 2; i++){
				tmpFile = File.createTempFile(name, null, fileOutputDir);
				tmpFile.deleteOnExit();
				FileOutputStream fos = new FileOutputStream(tmpFile);
				BufferedOutputStream stream = new BufferedOutputStream(fos);
				DataOutputStream dos = new DataOutputStream(stream);
				long lastKey = Long.MIN_VALUE;
				Iterator<Map.Entry<Long,Integer>> iter = map.entrySet().iterator();
				while(iter.hasNext()) {
					Map.Entry<Long,Integer> pair = iter.next();
					long key = pair.getKey();
					assert i == 0  | lastKey < key;
					lastKey = key;
					Integer val = pair.getValue();
					if (val != null){
						dos.writeLong(key);
						dos.writeInt(val);
					}
				}
				// write sentinel
				dos.writeLong(Long.MAX_VALUE);
				dos.writeInt(Integer.MAX_VALUE);
				dos.close();
				open();
				if (map instanceof SortedMap)
					break;
				map = new TreeMap<Long, Integer>();
				do{
					readPair();
					map.put(currentKey, currentVal);
				} while (currentKey != Long.MAX_VALUE);
				finish();
			}
			map = null;
		}

		void open() throws IOException{
			FileInputStream fis = new FileInputStream(tmpFile);
			BufferedInputStream stream = new BufferedInputStream(fis);
			dis = new DataInputStream(stream);
		}

		void readPair() {
			try {
				currentKey = dis.readLong();
				currentVal = dis.readInt();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

		void close() throws IOException{
			currentKey = Long.MIN_VALUE;
			dis.close();
		}
	}
	public void setUsedWays(SparseLong2ShortMapFunction ways) {
		usedWays = ways;
	}

	public boolean isIdsAreNotSorted() {
		return idsAreNotSorted;
	}

	public void setIdsAreNotSorted(boolean idsAreNotSorted) {
		this.idsAreNotSorted = idsAreNotSorted;
	}

}
