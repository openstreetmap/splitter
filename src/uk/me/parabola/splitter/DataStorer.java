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

import java.util.HashMap;

/**
 * Stores data that is needed in different passes of the program.
 * @author GerdP
 *
 */
public class DataStorer{
	public final static int DICT_START = -1 * (Short.MIN_VALUE + 1); 
	private final int numOfWriters;
	
	private HashMap<Long,Integer> relWriters = new HashMap<Long, Integer>();
	private HashMap<Long,Integer> wayWriters = new HashMap<Long, Integer>();
	private HashMap<Long,Integer> nodeWriters = new HashMap<Long, Integer>();

	private final WriterDictionaryShort writerDictionary;
	private final WriterDictionaryInt multiTileWriterDictionary;
	private final WriterGrid grid;
	
	/** 
	 * Create a dictionary for a given number of writers
	 * @param numOfWriters the number of writers that are used
	 */
	DataStorer (OSMWriter [] writers){
		this.numOfWriters = writers.length;
		this.writerDictionary = new WriterDictionaryShort(writers);
		this.multiTileWriterDictionary = new WriterDictionaryInt(writers);
		this.grid = new WriterGrid(writerDictionary);
	}

	public int getNumOfWriters(){
		return numOfWriters;
	}
	
	
	public WriterDictionaryShort getWriterDictionary() {
		return writerDictionary;
	}


	public HashMap<Long, Integer> getProblemWayWriters() {
		return wayWriters;
	}

	public HashMap<Long,Integer> getRelWriters() {
		return relWriters;
	}


	public void stats(){
		System.out.println("HashMap<Long,Integer> relWriters  : " + relWriters.size());
		System.out.println("HashMap<Long,Integer> wayWriters  : " + wayWriters.size());
		System.out.println("HashMap<Long,Integer> nodeWriters : " + nodeWriters.size());
	}

	public WriterGrid getGrid() {
		return grid;
	}

	public HashMap<Long,Integer> getSpecialNodeWriters() {
		return nodeWriters;
	}

	public WriterDictionaryInt getMultiTileWriterDictionary() {
		return multiTileWriterDictionary;
	}
}
