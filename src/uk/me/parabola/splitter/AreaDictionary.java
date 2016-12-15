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

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Maps a BitSet containing the used areas to an int value.  
 * An OSM element is written to one or more areas. Every used
 * combination of areas is translated to an int.
 * @author GerdP
 *
 */
public class AreaDictionary {
	private static final int DICT_START = Short.MAX_VALUE; 
	private final Area[] areas; 
	private final ArrayList<BitSet> sets; 
	private final ArrayList<IntArrayList> arrays; 
	private final int numOfAreas;
	private final HashMap<BitSet, Integer> index;
	private final HashSet<BitSet> simpleNeighbours = new HashSet<>();
	private final int overlapAmount;
	
	/**
	 * Create a dictionary for a given array of areas.
	 * @param overlapAmount 
	 * @param areas the array of areas
	 */
	AreaDictionary(List<Area> areas, int overlapAmount){
		this.areas = areas.toArray(new Area[areas.size()]);
		this.overlapAmount = overlapAmount;
		this.numOfAreas = areas.size();
		sets = new ArrayList<>();
		arrays = new ArrayList<>();
		index = new HashMap<>();
		init();
	}
	
	/**
	 * Initialize the dictionary with sets containing a single area.
	 */
	private void init() {
		ArrayList<Rectangle> rectangles = new ArrayList<>(numOfAreas);
		ArrayList<BitSet> areaSets = new ArrayList<>(numOfAreas);
		for (int i = 0; i < numOfAreas; i++) {
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
	 * Calculate the int value for a given BitSet. The BitSet must not 
	 * contain values higher than numOfAreas.
	 * @param areaSet the BitSet 
	 * @return an Integer value that identifies this BitSet, never null 
	 */
	public Integer translate(final BitSet areaSet) {
		Integer combiIndex = index.get(areaSet);
		if (combiIndex == null) {
			BitSet bnew = new BitSet();

			bnew.or(areaSet);
			IntArrayList a = new IntArrayList();
			for (int i = areaSet.nextSetBit(0); i >= 0; i = areaSet.nextSetBit(i + 1)) {
				a.add(i);
			}
			combiIndex = (sets.size() - DICT_START);
			if (combiIndex == Integer.MAX_VALUE) {
				throw new SplitFailedException("areaDictionary is full. Try to decrease number of areas.");
			}
			sets.add(bnew);
			arrays.add(a);
			index.put(bnew, combiIndex);
		}
		return combiIndex;
	}

	/**
	 * Find those areas that build rectangles when they are 
	 * added together. A way or relation that lies exactly within 
	 * such a combination cannot cross other areas. 
	 * @param rectangles 
	 * @param areaSets
	 */
	private void findSimpleNeigbours(ArrayList<Rectangle> rectangles, ArrayList<BitSet> areaSets){
		ArrayList<Rectangle> newRectangles = new ArrayList<>();
		ArrayList<BitSet> newAreaSets = new ArrayList<>();
		
		for (int i = 0; i < rectangles.size(); i++) {
			Rectangle r1 = rectangles.get(i);
			for (int j = i + 1; j < rectangles.size(); j++) {
				Rectangle r2 = rectangles.get(j);
				boolean isSimple = false;
				if (r1.y == r2.y && r1.height == r2.height && (r1.x == r2.getMaxX() || r2.x == r1.getMaxX()))
					isSimple = true;
				else if (r1.x == r2.x && r1.width == r2.width && (r1.y == r2.getMaxY() || r2.y == r1.getMaxY()))
					isSimple = true;
				if (isSimple) {
					BitSet simpleNeighbour = new BitSet();
					simpleNeighbour.or(areaSets.get(i));
					simpleNeighbour.or(areaSets.get(j));
					if (simpleNeighbour.cardinality() <= 10 && !simpleNeighbours.contains(simpleNeighbour)) {
						simpleNeighbours.add(simpleNeighbour);
						// System.out.println("simple neighbor: " +
						// getMapIds(simpleNeighbour));
						Rectangle pair = new Rectangle(r1);
						pair.add(r2);
						newRectangles.add(pair);
						newAreaSets.add(simpleNeighbour);
					}
				}
			}
		}
		if (!newRectangles.isEmpty()) {
			rectangles.addAll(newRectangles);
			areaSets.addAll(newAreaSets);
			newRectangles = null;
			newAreaSets = null;
			if (simpleNeighbours.size() < 1000)
				findSimpleNeigbours(rectangles, areaSets);
		}
	}
	
	/**
	 * Return the BitSet that is related to the int value.
	 * The caller must make sure that the index is valid.
	 * @param idx a value that was returned by the translate() method.  
	 * @return the BitSet
	 */
	public BitSet getBitSet(final int idx) {
		return sets.get(DICT_START + idx);
	}
	
	/**
	 * Return a list containing the area ids for the given int value.  
	 * @param idx a value that was returned by the translate() method.  
	 * @return a list containing the area ids 
	 */
	public IntArrayList getList(final int idx) {
		return arrays.get(DICT_START + idx);
	}
	
	/**
	 * return the number of sets in this dictionary 
	 * @return the number of sets in this dictionary
	 */
	public int size() {
		return sets.size();
	}

	public int getNumOfAreas() {
		return numOfAreas;
	}

	public boolean mayCross(BitSet areaSet) {
		return simpleNeighbours.contains(areaSet) == false;
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

	public static int translate(int singleWriterId) {
		return (singleWriterId - DICT_START);
	}
}
