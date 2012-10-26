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

import it.unimi.dsi.fastutil.shorts.ShortArrayList;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;

/**
 * Maps a BitSet containing the used writers to a short value.  
 * An OSM element is written to one or more writers. Every used
 * combination of writers is translated to a short.
 * @author GerdP
 *
 */
public class WriterDictionaryShort{
	public final static int DICT_START = -1 * (Short.MIN_VALUE + 1);
	private OSMWriter[] writers;
	private final ArrayList<BitSet> sets; 
	private final ArrayList<ShortArrayList> arrays; 
	private final int numOfWriters;
	private final HashMap<BitSet, Short> index;
	
	/** 
	 * Create a dictionary for a given array of writers
	 * @param writers the array of writers
	 */
	WriterDictionaryShort (OSMWriter [] writers){
		this.writers = writers;
		this.numOfWriters = writers.length;
		sets = new ArrayList<BitSet>();
		arrays = new ArrayList<ShortArrayList>();
		index = new HashMap<BitSet, Short>();
		init();
	}
	
	/**
	 * initialize the dictionary with sets containing a single writer.
	 */
	private void init(){
		ArrayList<Area> rectangles = new ArrayList<Area>(numOfWriters);
		ArrayList<BitSet> writerSets = new ArrayList<BitSet>(numOfWriters);
		for (int i=0; i < numOfWriters; i++){
			BitSet b = new BitSet();
			b.set(i);
			translate(b);
			rectangles.add(writers[i].getBounds());
			writerSets.add(b);
		}
	}
	
	/**
	 * Calculate the short value for a given BitSet. The BitSet must not 
	 * contain values higher than numOfWriters.
	 * @param writerSet the BitSet 
	 * @return a short value that identifies this BitSet 
	 */
	public short translate(final BitSet writerSet){
		Short combiIndex = index.get(writerSet);
		if (combiIndex == null){
			BitSet bnew = new BitSet();

			bnew.or(writerSet);
			ShortArrayList a = new ShortArrayList();
			for (int i = writerSet.nextSetBit(0); i >= 0; i = writerSet.nextSetBit(i + 1)) {
				a.add((short) i);
			}
			combiIndex = (short) (sets.size() - DICT_START);
			if (combiIndex == Short.MAX_VALUE){
				throw new RuntimeException("writerDictionary is full. Decrease --max-areas value");
			}
			sets.add(bnew);
			arrays.add(a);
			index.put(bnew, combiIndex);
		}
		return combiIndex;
	}

	/**
	 * Return the BitSet that is related to the short value.
	 * The caller must make sure that the short is valid.
	 * @param idx a short value that was returned by the translate() 
	 * method.  
	 * @return the BitSet
	 */
	public BitSet getBitSet (final short idx){
		return sets.get(idx + DICT_START);
	}
	
	/**
	 * Return a list containing the writer ids for the given 
	 * short value.  
	 * @param idx a short value that was returned by the translate()
	 * @return a list containing the writer ids 
	 */
	public ShortArrayList getList (final short idx){
		return arrays.get(DICT_START + idx);
	}
	
	/**
	 * return the number of sets in this dictionary 
	 * @return the number of sets in this dictionary
	 */
	public int size(){
		return sets.size();
	}

	public int getNumOfWriters(){
		return numOfWriters;
	}

	public OSMWriter[] getWriters(){
		return writers;
	}
	
	public boolean isMultiTile(short writerIdx){
		if (writerIdx + DICT_START < numOfWriters)
			return false;
		return true;
	}
	
	
	public String getMapIds(BitSet writerSet){
		StringBuilder sb = new StringBuilder("{");
		for (int k = 0;k<numOfWriters;k++){
			if (writerSet.get(k)) {
				sb.append(writers[k].getMapId());
				sb.append(", ");
			}
		}
		return sb.substring(0, sb.length()-2) + "}";
		
	}

	public int getWriterNum(short writerIdx) {
		int writerId = writerIdx + DICT_START;
		if (writerId < numOfWriters)
			return writerId;
		return -1; // multiple writers
	}
}
