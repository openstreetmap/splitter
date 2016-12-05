/*
 * Copyright (c) 2011,2012, Gerd Petermann
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

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Maps a BitSet containing the used areas to a short value.  
 * An OSM element is written to one or more areas. Every used
 * combination of areas is translated to a short.
 * @author GerdP
 *
 */
public class AreaDictionaryShort{
	private final static int DICT_START = Short.MAX_VALUE;
	private final Area[] areas; 
	private final ArrayList<BitSet> sets; 
	private final ArrayList<ShortArrayList> arrays; 
	private final int numOfAreas;
	private final HashMap<BitSet, Short> index;
	private final HashSet<Short> simpleNeighbours = new HashSet<>();
	private final int overlapAmount;
	
	/**
	 * Create a dictionary for a given array of areas
	 * @param overlapAmount 
	 * @param areas the array of areas
	 */
	AreaDictionaryShort (List<Area> areas, int overlapAmount){
		this.areas = areas.toArray(new Area[areas.size()]);
		this.overlapAmount = overlapAmount;
		this.numOfAreas = areas.size();
		sets = new ArrayList<>();
		arrays = new ArrayList<>();
		index = new HashMap<>();
		init();
	}
	
	/**
	 * initialize the dictionary with sets containing a single area.
	 */
	private void init(){
		ArrayList<Rectangle> rectangles = new ArrayList<>(numOfAreas);
		ArrayList<BitSet> areaSets = new ArrayList<>(numOfAreas);
		for (int i=0; i < numOfAreas; i++){
			BitSet b = new BitSet();
			b.set(i);
			translate(b);
			rectangles.add(Utils.area2Rectangle(areas[i], 0));
			areaSets.add(b);
		}
		findSimpleNeigbours(rectangles, areaSets);
		System.out.println("cached " + simpleNeighbours.size() + " combinations of areas that form rectangles.");
		return;
	}
	
	/**
	 * Calculate the short value for a given BitSet. The BitSet must not 
	 * contain values higher than numOfAreas.
	 * @param areaSet the BitSet 
	 * @return a short value that identifies this BitSet 
	 */
	public Short translate(final BitSet areaSet){
		Short combiIndex = index.get(areaSet);
		if (combiIndex == null){
			BitSet bnew = new BitSet();

			bnew.or(areaSet);
			ShortArrayList a = new ShortArrayList();
			for (int i = areaSet.nextSetBit(0); i >= 0; i = areaSet.nextSetBit(i + 1)) {
				a.add((short) i);
			}
			combiIndex = (short) (sets.size() - DICT_START);
			if (combiIndex == Short.MAX_VALUE){
				throw new SplitFailedException("areaDictionary is full. Try to decrease number of areas.");
			}
			sets.add(bnew);
			arrays.add(a);
			index.put(bnew, combiIndex);
		}
		return combiIndex;
	}

	/**
	 * find those areas that build rectangles when they are 
	 * added together. A way or relation that lies exactly within 
	 * such a combination cannot cross other areas. 
	 */
	private void findSimpleNeigbours(ArrayList<Rectangle> rectangles, ArrayList<BitSet> areaSets){
		ArrayList<Rectangle> newRectangles = new ArrayList<>();
		ArrayList<BitSet> newAreaSets = new ArrayList<>();
		
		for (int i = 0; i < rectangles.size(); i++){
			Rectangle r1 =  rectangles.get(i);
			for (int j = i+1; j < rectangles.size(); j++){
				Rectangle r2 =  rectangles.get(j);
				boolean isSimple = false;
				if (r1.y == r2.y && r1.height == r2.height 
						&& (r1.x == r2.getMaxX() || r2.x == r1.getMaxX())) 
					isSimple = true;
				else if (r1.x == r2.x && r1.width == r2.width 
						&& (r1.y == r2.getMaxY() || r2.y == r1.getMaxY()))
					isSimple = true;
				if (isSimple){
					BitSet simpleNeighbour = new BitSet();
					simpleNeighbour.or(areaSets.get(i));
					simpleNeighbour.or(areaSets.get(j));
					if (simpleNeighbour.cardinality() <= 10){
						Short idx = translate(simpleNeighbour);
						if (simpleNeighbours.contains(idx) == false){
							simpleNeighbours.add(idx);
							//System.out.println("simple neighbor: " + getMapIds(simpleNeighbour));
							Rectangle pair = new Rectangle(r1);
							pair.add(r2);
							newRectangles.add(pair);
							newAreaSets.add(simpleNeighbour);
						}
					}
				}
			}
		}
		if (newRectangles.isEmpty() == false){
			rectangles.addAll(newRectangles);
			areaSets.addAll(newAreaSets);
			newRectangles = null;
			newAreaSets = null;
			if (simpleNeighbours.size() < 1000)
				findSimpleNeigbours(rectangles,areaSets);
		}
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
	 * Return a list containing the area ids for the given 
	 * short value.  
	 * @param idx a short value that was returned by the translate() method
	 * @return a list containing the area ids 
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

	public int getNumOfAreas(){
		return numOfAreas;
	}

	public boolean mayCross(short areaIdx){
		if (areaIdx + DICT_START < numOfAreas)
			return false;
		if (simpleNeighbours.contains(areaIdx))
			return false;
		return true;
	}
	
	public Area getArea(int idx) {
		return areas[idx];
	}

	public Area getExtendedArea(int idx) {
		Area bounds = areas[idx];
		if (overlapAmount == 0)
			return bounds;
		return new Area(bounds.getMinLat() - overlapAmount,
				bounds.getMinLong() - overlapAmount,
				bounds.getMaxLat() + overlapAmount,
				bounds.getMaxLong() + overlapAmount);
	}

	public List<Area> getAreas() {
		return Collections.unmodifiableList(Arrays.asList(areas));
	}

	public static short translate(int singleWriterId) {
		return (short) (singleWriterId - DICT_START);
	}
}
