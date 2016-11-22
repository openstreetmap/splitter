/*
 * Copyright (c) 2012, Gerd Petermann
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

import uk.me.parabola.splitter.Relation.Member;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Find ways and relations that will be incomplete.
 * Strategy:
 * - calculate the areas of each node, calculate and store a short that represents the combination of areas
 *    (this is done by the AreaDictionary)  
 * - a way is a problem way if its nodes are found in different combinations of areas
 * - a relation is a problem relation if its members are found in different combinations of areas
 * 
 */
class ProblemListProcessor extends AbstractMapProcessor {
	private final static int PHASE1_NODES_AND_WAYS = 1;
	private final static int PHASE2_RELS_ONLY = 2;

	private final SparseLong2ShortMapFunction coords;
	private final SparseLong2ShortMapFunction ways;
	
	private final AreaDictionaryShort areaDictionary;
	private final DataStorer dataStorer;
	private final LongArrayList problemWays = new LongArrayList(); 
	private final LongArrayList problemRels = new LongArrayList();
	private final Long2ObjectOpenHashMap<Integer> oneTileOnlyRels;

	/** each bit represents one distinct area */
	private final BitSet areaSet = new BitSet();
	
	private int phase = PHASE1_NODES_AND_WAYS;
	//	for statistics
	//private long countQuickTest = 0;
	//private long countFullTest = 0;
	private long countCoords = 0;
	private final int areaOffset;
	private final int lastAreaOffset;
	private boolean isFirstPass;
	private boolean isLastPass;
	private AreaIndex areaIndex;
	private final HashSet<String> wantedBoundaryAdminLevels = new HashSet<>();
	
	private final HashSet<String> wantedBoundaryTagValues;
	
	ProblemListProcessor(DataStorer dataStorer, int areaOffset,
			int numAreasThisPass, Long2ObjectOpenHashMap<Integer> oneTileOnlyRels,
			String[] boundaryTagList) {
		this.dataStorer = dataStorer;
		this.areaDictionary = dataStorer.getAreaDictionary();
		if (dataStorer.getUsedWays() == null){
			ways = SparseLong2ShortMap.createMap("way");
			ways.defaultReturnValue(UNASSIGNED);
			dataStorer.setUsedWays(ways);
		}
		else 
			ways = dataStorer.getUsedWays(); 
		
		this.areaIndex = dataStorer.getGrid();
		this.coords = SparseLong2ShortMap.createMap("coord");
		this.coords.defaultReturnValue(UNASSIGNED);
		this.isFirstPass = (areaOffset == 0);
		this.areaOffset = areaOffset;
		this.lastAreaOffset = areaOffset + numAreasThisPass - 1;
		this.isLastPass = (areaOffset + numAreasThisPass == dataStorer.getNumOfAreas());
		this.oneTileOnlyRels = oneTileOnlyRels;
		if (boundaryTagList != null && boundaryTagList.length > 0)
			wantedBoundaryTagValues = new HashSet<>(Arrays.asList(boundaryTagList));
		else 
			wantedBoundaryTagValues = null;
		setWantedAdminLevel(5);
	}
	
	public void setWantedAdminLevel(int adminLevel) {
		int min, max = 11;
		min = Math.max(2, adminLevel);
		wantedBoundaryAdminLevels.clear();
		for (int i = min; i <= max; i++){
			wantedBoundaryAdminLevels.add(Integer.toString(i));
		}
	}

	@Override
	public boolean skipTags() {
		if (phase == PHASE1_NODES_AND_WAYS)
			return true;
		return false;
	}

	@Override
	public boolean skipNodes() {
		if (phase == PHASE2_RELS_ONLY)
			return true;
		return false;
	}
	@Override
	public boolean skipWays() {
		if (phase == PHASE2_RELS_ONLY)
			return true;
		return false;
	}
	@Override
	public boolean skipRels() {
		if (phase == PHASE2_RELS_ONLY)
			return false;
		return true;
	}
		
	@Override
	public int getPhase(){
		return phase;
	}
	
	@Override
	public void processNode(Node node) {
		if (phase == PHASE2_RELS_ONLY)
			return;
		int countAreas = 0;
		short lastUsedArea = UNASSIGNED;
		short areaIdx = UNASSIGNED;
		AreaGridResult areaCandidates = areaIndex.get(node);
		if (areaCandidates == null) 
			return;
		
		if (areaCandidates.l.size() > 1)
			areaSet.clear();
		for (int i = 0; i < areaCandidates.l.size(); i++) {
			int n = areaCandidates.l.getShort(i);
			if (n < areaOffset || n > lastAreaOffset)
				continue;

			boolean found;
			if (areaCandidates.testNeeded){
				found = dataStorer.getArea(n).contains(node);
				//++countFullTest;
			}
			else{ 
				found = true;
				//++countQuickTest;
			}
			if (found) {
				areaSet.set(n);
				++countAreas;
				lastUsedArea = (short) n;
			}
		}
		if (countAreas > 0){
			if (countAreas > 1)
				areaIdx = areaDictionary.translate(areaSet);
			else  
				areaIdx = (short) (lastUsedArea  - AreaDictionaryShort.DICT_START); // no need to do lookup in the dictionary 
			coords.put(node.getId(), areaIdx);
			++countCoords;
			if (countCoords % 10000000 == 0){
				System.out.println("coord MAP occupancy: " + Utils.format(countCoords) + ", number of area dictionary entries: " + areaDictionary.size() + " of " + ((1<<16) - 1));
				coords.stats(0);
			}
		}
	}
	
	@Override
	public void processWay(Way way) {
		if (phase == PHASE2_RELS_ONLY)
			return;
		boolean maybeChanged = false;
		int oldclIndex = UNASSIGNED;
		short wayAreaIdx; 
		areaSet.clear();
		//for (long id: way.getRefs()){
		int refs = way.getRefs().size();
		for (int i = 0; i < refs; i++){
			long id = way.getRefs().getLong(i);
			// Get the list of areas that the way is in. 
			short clIdx = coords.get(id);
			if (clIdx == UNASSIGNED){
				continue;
			}
			if (oldclIndex != clIdx){
				BitSet cl = areaDictionary.getBitSet(clIdx);
				areaSet.or(cl);
				oldclIndex = clIdx;
				maybeChanged = true;
			}
		}
		
		if (!isFirstPass && maybeChanged || isLastPass){
			wayAreaIdx = ways.get(way.getId());
			if (wayAreaIdx != UNASSIGNED)
				areaSet.or(areaDictionary.getBitSet(wayAreaIdx));
		}
		
		if (isLastPass){
			if (checkIfMultipleAreas(areaSet)){
				problemWays.add(way.getId());
			}
		}
		if (maybeChanged && areaSet.isEmpty() == false){
			wayAreaIdx = areaDictionary.translate(areaSet);
			ways.put(way.getId(), wayAreaIdx);
		}
	}
	// default exclude list for boundary tag
	private final static HashSet<String> unwantedBoundaryTagValues = new HashSet<>(
			Arrays.asList("administrative", "postal_code", "political"));

	@Override
	public void processRelation(Relation rel) {
		if (phase == PHASE1_NODES_AND_WAYS)
			return;
		boolean useThis = false;
		boolean isMPRelType = false;
		boolean hasBoundaryTag = false;
		boolean isWantedBoundary = (wantedBoundaryTagValues == null) ? true:false;
		Iterator<Element.Tag> tags = rel.tagsIterator();
		String admin_level = null;
		while(tags.hasNext()) {
			Element.Tag t = tags.next();
			if ("type".equals(t.key)) {
				if ("restriction".equals((t.value)) || "through_route".equals((t.value)) || t.value.startsWith("restriction:"))
					useThis= true; // no need to check other tags
				else if ("multipolygon".equals((t.value))  || "boundary".equals((t.value)))
					isMPRelType= true;
				else if ("associatedStreet".equals((t.value))  || "street".equals((t.value)))
					useThis= true; // no need to check other tags
			} else if ("boundary".equals(t.key)){
				hasBoundaryTag = true;
				if (wantedBoundaryTagValues != null){
					if (wantedBoundaryTagValues.contains(t.value))
						isWantedBoundary = true;
				} else {
					if (unwantedBoundaryTagValues.contains(t.value))
						isWantedBoundary = false;
				}
			} else if ("admin_level".equals(t.key)){
				admin_level = t.value;
			}
			
			if (useThis)
				break;
		}
		if (isMPRelType && (isWantedBoundary || hasBoundaryTag == false))
			useThis = true;
		else if (isMPRelType && hasBoundaryTag  && admin_level != null){
			if (wantedBoundaryAdminLevels.contains(admin_level))
				useThis = true;
		}
		if (!useThis){
			return;
		}
		areaSet.clear();
		Integer relAreaIdx;
		if (!isFirstPass){
			relAreaIdx = dataStorer.getUsedRels().get(rel.getId());
			if (relAreaIdx != null)
				areaSet.or(dataStorer.getMultiTileDictionary().getBitSet(relAreaIdx));
		}
		short oldclIndex = UNASSIGNED;
		short oldwlIndex = UNASSIGNED;
		//System.out.println("r" + rel.getId() + " " + rel.getMembers().size());
		for (Member mem : rel.getMembers()) {
			long id = mem.getRef();
			if (mem.getType().equals("node")) {
				short clIdx = coords.get(id);

				if (clIdx != UNASSIGNED){
					if (oldclIndex != clIdx){ 
						BitSet wl = areaDictionary.getBitSet(clIdx);
						areaSet.or(wl);
					}
					oldclIndex = clIdx;

				}

			} else if (mem.getType().equals("way")) {
				short wlIdx = ways.get(id);

				if (wlIdx != UNASSIGNED){
					if (oldwlIndex != wlIdx){ 
						BitSet wl = areaDictionary.getBitSet(wlIdx);
						areaSet.or(wl);
					}
					oldwlIndex = wlIdx;
				}
			}
			// ignore relation here
		}
		if (areaSet.isEmpty())
			return;
		if (isLastPass){
			if (checkIfMultipleAreas(areaSet)){
				problemRels.add(rel.getId());
			} else {
				// the relation is only in one distinct area
				int newAreaIdx = -1;
				for (int i = areaSet.nextSetBit(0); i >= 0; i = areaSet.nextSetBit(i+1)){
					if (dataStorer.getArea(i).isPseudoArea() == false)  {
						// this should be the only area
						newAreaIdx = i;
						break;
					}
				}
				// store the info that the rel is only in one distinct area
				oneTileOnlyRels.put(rel.getId(), new Integer(newAreaIdx));
			}
			return;
		}
		
		relAreaIdx = dataStorer.getMultiTileDictionary().translate(areaSet);
		dataStorer.getUsedRels().put(rel.getId(), relAreaIdx);
	}
	
	
	@Override
	public boolean endMap() {
		if (phase == PHASE1_NODES_AND_WAYS){
			phase++;
			return false;
		}
		coords.stats(0);
		ways.stats(0);
		if (isLastPass){
			System.out.println("");
			System.out.println("  Number of stored shorts for ways: " + Utils.format(dataStorer.getUsedWays().size()));
			System.out.println("  Number of stored integers for rels: " + Utils.format(dataStorer.getUsedRels().size()));
			System.out.println("  Number of stored combis in big dictionary: " + Utils.format(dataStorer.getMultiTileDictionary().size()));
			System.out.println("  Number of detected problem ways: " + Utils.format(problemWays.size()));
			System.out.println("  Number of detected problem rels: " + Utils.format(problemRels.size()));
			Utils.printMem();
			System.out.println("");
			dataStorer.getUsedWays().clear();
			dataStorer.getUsedRels().clear();
		}
		return true;
	}
	
	/** 
	 * @param areaCombis
	 * @return true if the combination of distinct areas can contain a problem polygon
	 */
	static boolean checkIfMultipleAreas(BitSet areaCombis){
		// this returns a few false positives for those cases
		// where a way or rel crosses two pseudo-areas at a 
		// place that is far away from the real areas
		// but it is difficult to detect these cases.
		return areaCombis.cardinality() > 1;
	}

	public LongArrayList getProblemWays() {
		return problemWays;
	}
	
	public LongArrayList getProblemRels() {
		return problemRels;
	}
}
