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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
	private final int maxRealWriter;
	private final WriterMapper[] maps = new WriterMapper[3];
	private final String[] mapNames = {"node", "way", "rel"};
	private final WriterDictionaryShort writerDictionary;
	private final WriterDictionaryInt multiTileWriterDictionary;
	private final WriterGrid grid;
	private SparseLong2ShortMapFunction usedWays = null;
	private final HashMap<Long,Integer> usedRels = new HashMap<Long, Integer>();

	/** 
	 * Create a dictionary for a given number of writers
	 * @param numOfWriters the number of writers that are used
	 */
	DataStorer (OSMWriter [] writers){
		this.numOfWriters = writers.length;
		int i = -1;
		for (OSMWriter w: writers){
			if (w.getMapId() >= 0)
				i++; 
			else 
				break;
		}
		maxRealWriter = i;
		this.writerDictionary = new WriterDictionaryShort(writers);
		this.multiTileWriterDictionary = new WriterDictionaryInt(writers);
		this.grid = new WriterGrid(writerDictionary);
		for (i = 0; i< maps.length; i++){
			maps[i] = new WriterMapper(mapNames[i]);
		}
	}

	public int getNumOfWriters(){
		return numOfWriters;
	}
	
	public int getMaxRealWriter(){
		return maxRealWriter;
	}
	
	
	public WriterDictionaryShort getWriterDictionary() {
		return writerDictionary;
	}


	public void stats(){
		for (WriterMapper map: maps)
			map.stats();
	}

	public WriterGrid getGrid() {
		return grid;
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
		return maps[mapType].put(id,idx);
	}
	public Integer getWriterIdx (int mapType, long id){
		return maps[mapType].getRandom(id);
	}
	public Integer getWriterIdxSeq (int mapType, long id){
		return maps[mapType].getSeq(id);
	}
	
	/**
	 * This indicates that all data is collected and only 
	 * sequential read access 
	 * @param fileOutputDir
	 * @throws IOException
	 */
	public void setReadOnly(File fileOutputDir) throws IOException{
		for (int i = 0; i< maps.length; i++){
			maps[i].writeMapToFile(fileOutputDir);
		}
	}
	
	public void restart() throws IOException{
		for (WriterMapper map: maps)
			map.close();
	}
	
	public void finish(){
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
			this.map = new TreeMap<Long, Integer>();
			currentKey = Long.MIN_VALUE;
		}
		public void finish() {
			if (tmpFile != null && tmpFile.exists()){
				try {
					close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
						
				tmpFile.delete();
			}
		}
		
		void stats (){
			System.out.println("TreeMap<Long,Integer> " + name + "-Writers  : " + Util.format(map.size()));
		}

		Integer put(long key, int value){
			return map.put(key, value);
		}
		
		Integer getRandom(long id){
			return map.get(id);
		}
		
		Integer getSeq(long id){
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
		private void writeMapToFile(File fileOutputDir) throws IOException{
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
				assert lastKey < key;
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

}
