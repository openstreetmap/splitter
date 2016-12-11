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
 package uk.me.parabola.splitter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;

/**
 * Maps a BitSet containing the used areas to an integer value.  
 * An OSM element is written to one or more areas. Every used
 * combination of areas is translated to an integer.
 * Use this dictionary if you expect many different area combinations,
 * e.g. for relations and their members.
 * @author Gerd Petermann
 *
 */
public class AreaDictionaryInt{
	private final ArrayList<BitSet> sets; 
	private final int numOfAreas;
	private final HashMap<BitSet, Integer> index;
	
	/** 
	 * Create a dictionary for a given array of areas
	 * @param num the number of areas that are used
	 */
	AreaDictionaryInt (int num){
		this.numOfAreas = num;
		sets = new ArrayList<>();
		index = new HashMap<>();
		init();
	}
	
	/**
	 * initialize the dictionary with sets containing a single area.
	 */
	private void init(){
		ArrayList<BitSet> areaSets = new ArrayList<>(numOfAreas);
		for (int i = 0; i < numOfAreas; i++) {
			BitSet b = new BitSet();
			b.set(i);
			translate(b);
			areaSets.add(b);
		}
	}
	
	
	/**
	 * Calculate the integer value for a given BitSet. The BitSet must not 
	 * contain values higher than numOfAreas.
	 * @param areaSet the BitSet 
	 * @return an int value that identifies this BitSet 
	 */
	public Integer translate(final BitSet areaSet){
		Integer combiIndex = index.get(areaSet);
		if (combiIndex == null){
			BitSet bnew = new BitSet();

			bnew.or(areaSet);
			combiIndex = sets.size();
			sets.add(bnew);
			index.put(bnew, combiIndex);
		}
		return combiIndex;
	}

	/**
	 * Return the BitSet that is related to the int value.
	 * The caller must make sure that the idx is valid.
	 * @param idx an int value that was returned by the translate() 
	 * method.  
	 * @return the BitSet
	 */
	public BitSet getBitSet (final int idx){
		return sets.get(idx);
	}
	
	/**
	 * return the number of sets in this dictionary 
	 * @return the number of sets in this dictionary
	 */
	public int size(){
		return sets.size();
	}
}
