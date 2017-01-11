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

import uk.me.parabola.splitter.Relation.Member;
import uk.me.parabola.splitter.tools.Long2IntClosedMap;
import uk.me.parabola.splitter.tools.Long2IntClosedMapFunction;
import uk.me.parabola.splitter.tools.OSMId2ObjectMap;
import uk.me.parabola.splitter.tools.SparseBitSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Analyzes elements that should be written to multiple tiles 
 * to find out what details are needed in each tile.
 */
class MultiTileProcessor extends AbstractMapProcessor {
	private final static int PHASE1_RELS_ONLY = 1;
	private final static int PHASE2_WAYS_ONLY = 2;
	private final static int PHASE3_NODES_AND_WAYS = 3;
	private final static int PHASE4_WAYS_ONLY = 4;
	
	private final boolean addParentRels = false;
	private final static byte MEM_NODE_TYPE = 1;
	private final static byte MEM_WAY_TYPE  = 2;
	private final static byte MEM_REL_TYPE  = 3;
	private final static byte MEM_INVALID_TYPE = -1;
	private final static int PROBLEM_WIDTH = Utils.toMapUnit(180.0);
	protected final static String[] NAME_TAGS = {"name","name:en","int_name","note"};
	private final static String NOT_SORTED_MSG = "Maybe the IDs are not sorted. This is not supported with keep-complete=true or --problem-list";
	
	private int phase = PHASE1_RELS_ONLY;
	private final DataStorer dataStorer;
	private final AreaDictionary areaDictionary;
	private Long2ObjectLinkedOpenHashMap<MTRelation> relMap = new Long2ObjectLinkedOpenHashMap<>();
	private Long2IntClosedMapFunction nodeWriterMap;
	private Long2IntClosedMapFunction wayWriterMap;
	private Long2IntClosedMapFunction relWriterMap;
	private int [] nodeLons;
	private int [] nodeLats;
	private SparseBitSet problemRels = new SparseBitSet();
	private SparseBitSet neededWays = new SparseBitSet();
	private SparseBitSet neededNodes = new SparseBitSet();
	private OSMId2ObjectMap<Rectangle> wayBboxMap = new OSMId2ObjectMap<>();
	private SparseBitSet mpWays = new SparseBitSet();
	private OSMId2ObjectMap<JoinedWay> mpWayEndNodesMap = new OSMId2ObjectMap<>();
	/** each bit represents one area/tile */
	private final AreaSet workWriterSet = new AreaSet();
	private long lastCoordId = Long.MIN_VALUE;
	
	private int foundWays;
	private int neededNodesCount; 
	private int neededWaysCount; 
	private int neededMpWaysCount; 
	private int visitId;
	

	MultiTileProcessor(DataStorer dataStorer, LongArrayList problemWayList, LongArrayList problemRelList) {
		this.dataStorer = dataStorer;
		this.areaDictionary = dataStorer.getAreaDictionary();
		for (long id: problemWayList){
			neededWays.set(id);
		}
		for (long id: problemRelList){
			problemRels.set(id);
		}
		// we allocate this once to avoid massive resizing with large number of tiles
		neededMpWaysCount = mpWays.cardinality();
		if (problemRelList.isEmpty()) {
			phase = PHASE2_WAYS_ONLY;
		}
		return;
	}

	@Override
	public boolean skipTags() {
		if (phase == PHASE1_RELS_ONLY)
			return false;
		return true;
	}

	@Override
	public boolean skipNodes() {
		if (phase == PHASE3_NODES_AND_WAYS)
			return false;
		return true;
	}
	@Override
	public boolean skipWays() {
		if (phase == PHASE1_RELS_ONLY)
			return true;
		return false;
	}
	@Override
	public boolean skipRels() {
		if (phase == PHASE1_RELS_ONLY && problemRels.cardinality() > 0)
			return false;
		return true;
	}

	@Override
	public int getPhase() {
		return phase;
	}
	
	@Override
	public void processNode(Node node) {
		if (phase == PHASE3_NODES_AND_WAYS){
			if (neededNodes.get(node.getId())){
				storeCoord(node);
				// return memory to GC
				neededNodes.clear(node.getId());
			}
		}
	}

	@Override
	public void processWay(Way way) {
		if (phase == PHASE2_WAYS_ONLY){
			if (!neededWays.get(way.getId()))
				return;
			for (long id : way.getRefs()) {
				neededNodes.set(id);
			}
			if (mpWays.get(way.getId())){
				mpWays.clear(way.getId());
				
				int numRefs = way.getRefs().size();
				if (numRefs >= 2){
					JoinedWay joinedWay = new JoinedWay(way.getRefs().getLong(0), way.getRefs().getLong(numRefs-1));
					mpWayEndNodesMap.put(way.getId(), joinedWay);
					
				}
			}
			foundWays++;
		}
		else if (phase == PHASE3_NODES_AND_WAYS){
			if (!neededWays.get(way.getId()))
				return;
			// calculate the bbox
			int numRefs = way.getRefs().size();
			boolean isClosed = numRefs > 1 &&  way.getRefs().get(0).equals(way.getRefs().get(numRefs-1));
			workWriterSet.clear();
			Rectangle wayBbox = getWayBbox(way.getId(), way.getRefs());
			if (wayBbox == null)
				return;
			wayBboxMap.put(way.getId(), wayBbox);
			if (isClosed){
				checkBoundingBox(workWriterSet, wayBbox);
			}
			else {
				addWritersOfWay(workWriterSet, wayBbox, way.getId(), way.getRefs());
			}
			int wayWriterIdx;
			if (workWriterSet.isEmpty())
				wayWriterIdx = UNASSIGNED;
			else 
				wayWriterIdx = areaDictionary.translate(workWriterSet);
			
			try{
				wayWriterMap.add(way.getId(), wayWriterIdx);
			}catch (IllegalArgumentException e){
				System.err.println(e.getMessage());
				throw new SplitFailedException(NOT_SORTED_MSG);
			}

		}
		else if (phase == PHASE4_WAYS_ONLY){
			// propagate the ways writers to all nodes 
			if (!neededWays.get(way.getId()))
				return;
			int wayWriterIdx = wayWriterMap.getRandom(way.getId());
			if (wayWriterIdx !=  UNASSIGNED){
				AreaSet wayWriterSet = areaDictionary.getSet(wayWriterIdx);
				for (long id : way.getRefs()) {
					addOrMergeWriters(nodeWriterMap, wayWriterSet, wayWriterIdx, id);
				}
			}
		}
	}

	@Override
	public void processRelation(Relation rel) {
		// TODO: we store all relations here, no matter how many are needed. Another approach would be to store 
		// the rels in the problem list and read again until all sub rels of these problem rels are found or
		// known as missing. This can require many more read passes for relations, but can help if this phase
		// starts to be a memory bottleneck.
		if (phase == PHASE1_RELS_ONLY){
			MTRelation myRel = new MTRelation(rel);
			relMap.put(myRel.getId(), myRel);
		}
	}

	@Override
	public boolean endMap() {
		if (phase == PHASE1_RELS_ONLY){
			stats("Finished collecting relations.");
			Utils.printMem();
			System.out.println("starting to resolve relations containing problem relations ...");
			// add all ways and nodes of problem rels so that we collect the coordinates
			markProblemMembers();
			if (addParentRels){
				// we want to see the parent rels, but not all children of all parents
				markParentRels();
			}
			// free memory for rels that are not causing any trouble
			relMap.long2ObjectEntrySet().removeIf(e -> !problemRels.get(e.getLongKey()));
			problemRels = null;
			// reallocate to the needed size
			relMap = new Long2ObjectLinkedOpenHashMap<>(relMap);
			
			//System.out.println("Finished adding parents and members of problem relations to problem lists.");
			System.out.println("Finished adding members of problem relations to problem lists.");
			stats("starting to collect ids of needed way nodes ...");
			neededMpWaysCount = mpWays.cardinality();
			neededWaysCount = neededWays.cardinality();
			++phase;
		}
		else if (phase == PHASE2_WAYS_ONLY){
			stats("Finished collecting problem ways.");
			neededNodesCount = neededNodes.cardinality();
			// critical part: we have to allocate possibly large arrays here
			nodeWriterMap = new Long2IntClosedMap("node", neededNodesCount, UNASSIGNED);
			wayWriterMap = new Long2IntClosedMap("way", foundWays, UNASSIGNED);
			dataStorer.setWriterMap(DataStorer.NODE_TYPE, nodeWriterMap);
			dataStorer.setWriterMap(DataStorer.WAY_TYPE, wayWriterMap);
			nodeLons = new int[neededNodesCount];
			nodeLats = new int[neededNodesCount];

			System.out.println("Found " + Utils.format(foundWays) + " of " + Utils.format(neededWaysCount) + " needed ways.");
			System.out.println("Found " + Utils.format(mpWayEndNodesMap.size()) + " of " + Utils.format(neededMpWaysCount) + " needed multipolygon ways.");
			stats("Starting to collect coordinates for " + Utils.format(neededNodesCount) + " needed nodes.");
			Utils.printMem();
			++phase;
		}
		else if (phase == PHASE3_NODES_AND_WAYS){
			System.out.println("Found " + Utils.format(nodeWriterMap.size()) + " of " + Utils.format(neededNodesCount) + " needed nodes.");
			Utils.printMem();
			mpWays = null;
			neededNodes = null;
			System.out.println("Calculating tiles for problem relations...");
			calcWritersOfRelWaysAndNodes();
			// return coordinate memory to GC
			nodeLats = null;
			nodeLons = null;
			
			calcWritersOfMultiPolygonRels();
			mergeRelMemWriters();
			propagateWritersOfRelsToMembers();

			mpWayEndNodesMap.clear();
			wayBboxMap = null;
			relWriterMap = new Long2IntClosedMap("rel", relMap.size(), UNASSIGNED);
			
			for (Entry<MTRelation> entry : relMap.long2ObjectEntrySet()){
				int val = entry.getValue().getMultiTileWriterIndex();
				if (val != UNASSIGNED){
					try{
						relWriterMap.add(entry.getLongKey(), val);
					}catch (IllegalArgumentException e){
						System.err.println(e);
						throw new SplitFailedException(NOT_SORTED_MSG); 
					}
				}
			}
			relMap = null;
			dataStorer.setWriterMap(DataStorer.REL_TYPE, relWriterMap);
			stats("Making sure that needed way nodes of relations are written to the correct tiles...");
			++phase;
		}
		else if (phase == PHASE4_WAYS_ONLY){
			stats("Finished processing problem lists.");
			return true; 
		}
		return false; // not done yet
	}

	/**
	 * Mark all members of given problem relations as problem cases. 
	 */
	private void markProblemMembers() {
		ArrayList<MTRelation> visited = new ArrayList<>();
		for (MTRelation rel: relMap.values()){
			if (!problemRels.get(rel.getId()))
				continue;
			incVisitID();
			visited.clear();
			MarkNeededMembers(rel, 0, visited);
			assert visited.size() == 0;
		}
	}

	/**
	 * Mark the ways and nodes of a relation as problem cases. If the relation 
	 * contains sub relations, the routine calls itself recursively. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @param visited 
	 * @return
	 */
	private void MarkNeededMembers(MTRelation rel, int depth, ArrayList<MTRelation> visited){
		if (rel.getLastVisitId() == visitId)
			return;
		rel.setLastVisitId(visitId);
		if (depth > 15){
			System.out.println("MarkNeededMembers reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		for (int i = 0; i < rel.numMembers; i++){
			long memId = rel.memRefs[i];
			byte memType = rel.memTypes[i];
			if (memType == MEM_WAY_TYPE){
				neededWays.set(memId);
				if (rel.isMultiPolygon())
					mpWays.set(memId);
			}
			else if (memType == MEM_NODE_TYPE)
				neededNodes.set(memId);
			else if (memType == MEM_REL_TYPE){
				MTRelation subRel = relMap.get(memId);
				if (subRel == null)
					continue;
				if (subRel.getLastVisitId() == visitId)
					loopAction(rel, subRel, visited);
				else {
					problemRels.set(memId);
					visited.add(subRel);
					MarkNeededMembers(subRel, depth+1, visited);
					visited.remove(visited.size()-1);
				}
			} 
		}
	}

	/**
	 * Mark the parents of problem relations as problem relations.
	 */
	private void markParentRels(){
		while (true){
			boolean changed = false;
			for (MTRelation rel: relMap.values()){
				if (rel.hasRelMembers() == false || problemRels.get(rel.getId()))
					continue;
				for (int i = 0; i < rel.numMembers; i++){
					long memId = rel.memRefs[i];
					if (rel.memTypes[i] == MEM_REL_TYPE){
						if (problemRels.get(memId)){
							problemRels.set(rel.getId());
							rel.setAddedAsParent();
							System.out.println("Adding parent of problem rel "+ memId + " to problem list: " + rel.getId());
							changed = true;
							break;
						}
					} 
				}
			}
			if (!changed)
				return;
		}
	}
	
	/**
	 * Calculate the writers for each relation based on the 
	 * nodes and ways. 
	 */
	private void calcWritersOfRelWaysAndNodes() {
		for (MTRelation rel: relMap.values()){
			if (false == (rel.hasWayMembers() ||  rel.hasNodeMembers()) )
				continue;
			
			AreaSet writerSet = new AreaSet();
			for (int i = 0; i < rel.numMembers; i++){
				long memId = rel.memRefs[i];
				boolean memFound = false;
				if (rel.memTypes[i] == MEM_NODE_TYPE){
					int pos = nodeWriterMap.getKeyPos(memId);
					if (pos >= 0){
						addWritersOfPoint(writerSet, nodeLats[pos], nodeLons[pos]);
						memFound = true;
					}
				}
				else if (rel.memTypes[i] == MEM_WAY_TYPE){
					int idx = wayWriterMap.getRandom(memId);
					if (idx != UNASSIGNED){
						writerSet.or(areaDictionary.getSet(idx));
						memFound = true;
					}
					if (wayBboxMap.get(memId) != null)
						memFound = true;
				}
				else if (rel.memTypes[i] == MEM_REL_TYPE)
					continue; // handled later
				if (!memFound) {
					rel.setNotComplete();
					continue;
				}
			}	
			if (!writerSet.isEmpty()){
				int idx = areaDictionary.translate(writerSet);
				rel.setMultiTileWriterIndex(idx);
			}
		}
	
	}
	/**
	 * Multipolygon relations should describe one or more closed polygons.
	 * We calculate the writers for each of the polygons. 
	 */
	private void calcWritersOfMultiPolygonRels() {
		// recurse thru sub relations
		ArrayList<MTRelation> visited = new ArrayList<>();
		
		for (MTRelation rel: relMap.values()){
			AreaSet relWriters = new AreaSet();
			if (rel.isMultiPolygon()){
				if (rel.hasRelMembers()){
					incVisitID();
					visited.clear();
					orSubRelWriters(rel, 0, visited);
				}
				checkSpecialMP(relWriters, rel);
				if (!relWriters.isEmpty()){
					int writerIdx = areaDictionary.translate(relWriters);
					rel.setMultiTileWriterIndex(writerIdx);
					int touchedTiles = relWriters.cardinality();
					if (touchedTiles > dataStorer.getNumOfAreas() / 2 && dataStorer.getNumOfAreas() > 10){
						System.out.println("Warning: rel " + rel.getId() + " touches " + touchedTiles + " tiles.");
					}
				}
			}
		}
	}

	/**
	 * Or-combine all writers of the members of a relation 
	 */
	private void mergeRelMemWriters() {
		// or combine the writers of sub-relations with the parent relation 
		ArrayList<MTRelation> visited = new ArrayList<>();
		for (MTRelation rel: relMap.values()){
			incVisitID();
			visited.clear();
			orSubRelWriters(rel, 0, visited);
		}
	}

	/**
	 * Make sure that all the elements of a relation are written to the same tiles as the relation info itself.
	 */
	private void propagateWritersOfRelsToMembers() {
		// make sure that the ways and nodes of the problem relations are written to all needed tiles
		for (MTRelation rel: relMap.values()){
			if (rel.wasAddedAsParent())
				continue;
			int relWriterIdx = rel.getMultiTileWriterIndex();
			if (relWriterIdx == UNASSIGNED)
				continue;
			AreaSet relWriters =  areaDictionary.getSet(relWriterIdx);
			for (int i = 0; i < rel.numMembers; i++){
				long memId = rel.memRefs[i];
				switch (rel.memTypes[i]){
				case MEM_WAY_TYPE:
					addOrMergeWriters(wayWriterMap, relWriters, relWriterIdx, memId);
					break;
				case MEM_NODE_TYPE:
					addOrMergeWriters(nodeWriterMap, relWriters, relWriterIdx, memId);
					break;
					default:
				}
			}
		}

	}

	/**
	 * Store the coordinates of a node in the most appropriate data structure.
	 * @param node
	 */
	private void storeCoord(Node node) {
		long id = node.getId();
		if (lastCoordId >= id){
			System.err.println("Error: Node ids are not sorted. Use e.g. osmosis to sort the input data.");
			System.err.println("This is not supported with keep-complete=true or --problem-list"); 
			throw new SplitFailedException("Node ids are not sorted");
		}
		int nodePos = -1;
		try{
			nodePos = nodeWriterMap.add(id, UNASSIGNED);
		}catch (IllegalArgumentException e){
			System.err.println(e.getMessage());
			throw new SplitFailedException(NOT_SORTED_MSG);
		}
				
		nodeLons[nodePos ] = node.getMapLon();
		nodeLats[nodePos] = node.getMapLat();
		lastCoordId = id;
	}

	/**
	 * If a relation contains relations, or-combine the writers of the sub-
	 * relation with the writes of the parent relation . The routine calls 
	 * itself recursively when the sub relation contains sub relations. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void orSubRelWriters(MTRelation rel, int depth, ArrayList<MTRelation> visited ){
		if (rel.getLastVisitId() == visitId)
			return;
		rel.setLastVisitId(visitId);
		if (depth > 15){
			System.out.println("orSubRelWriters reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		AreaSet relWriters = new AreaSet();
		int relWriterIdx = rel.getMultiTileWriterIndex();
		if (relWriterIdx != UNASSIGNED)
			relWriters.or(areaDictionary.getSet(relWriterIdx));

		boolean changed = false;
		for (int i = 0; i < rel.numMembers; i++){
			long memId = rel.memRefs[i];
			if (rel.memTypes[i] == MEM_REL_TYPE){
				MTRelation subRel = relMap.get(memId);
				if (subRel == null)
					continue;
				if (subRel.getLastVisitId() == visitId)
					loopAction(rel, subRel, visited);
				else {
					visited.add(rel);
					orSubRelWriters(subRel, depth+1, visited);
					visited.remove(visited.size()-1);
					int memWriterIdx = subRel.getMultiTileWriterIndex();
					if (memWriterIdx == UNASSIGNED || memWriterIdx == relWriterIdx){
						continue;
					}
					AreaSet memWriters = areaDictionary.getSet(memWriterIdx);
					int oldSize = relWriters.cardinality();
					relWriters.or(memWriters);
					if (oldSize != relWriters.cardinality())
						changed = true;
				}
			}
		}
		if (changed){
			rel.setMultiTileWriterIndex(areaDictionary.translate(relWriters));
		}
	}

	/**
	 * Report some numbers regarding memory usage 
	 * @param msg
	 */
	private void stats(String msg){
		System.out.println("Stats for " + getClass().getSimpleName() + " pass " + phase);
		if (problemRels != null)
			System.out.println("  " + problemRels.getClass().getSimpleName() + " problemRels contains now " + Utils.format(problemRels.cardinality()) + " Ids.");
		if (neededWays != null)
			System.out.println("  " + neededWays.getClass().getSimpleName() + " neededWays contains now " + Utils.format(neededWays.cardinality())+ " Ids.");
		if (mpWays != null)
			System.out.println("  " + mpWays.getClass().getSimpleName() + " mpWays contains now " + Utils.format(mpWays.cardinality())+ " Ids.");
		if (neededNodes != null)
			System.out.println("  " + neededNodes.getClass().getSimpleName() + " neededNodes contains now " + Utils.format(neededNodes.cardinality())+ " Ids.");
		if (relMap != null)
			System.out.println("  Number of stored relations: " + Utils.format(relMap.size()));
		System.out.println("  Number of stored tile combinations in multiTileDictionary: " + Utils.format(areaDictionary.size()));
		if (phase == PHASE4_WAYS_ONLY)
			dataStorer.stats("  ");
		System.out.println("Status: " + msg);
			
	}

	/**
	 * Find all writer areas that intersect with a given bounding box. 
	 * @param writerSet an already allocate AreaSet which may be modified
	 * @param polygonBbox the bounding box 
	 * @return true if any writer bbox intersects the polygon bbox
	 */
	private boolean checkBoundingBox(AreaSet writerSet, Rectangle polygonBbox){
		boolean foundIntersection = false;
		if (polygonBbox != null){
			for (int i = 0; i < dataStorer.getNumOfAreas(); i++) {
				Rectangle writerBbox = Utils.area2Rectangle(dataStorer.getArea(i), 1);
				if (writerBbox.intersects(polygonBbox)){
					writerSet.set(i);
					foundIntersection = true;
				}
			}
		}
		return foundIntersection;
	}

	/**
	 * Merge the writers of a parent object with the writes of the child, 
	 * add or update the entry in the Map
	 * @param map
	 * @param parentWriters
	 * @param parentWriterIdx
	 * @param childId
	 */
	private void addOrMergeWriters(Long2IntClosedMapFunction map, AreaSet parentWriters, int parentWriterIdx, long childId) {
		int pos = map.getKeyPos(childId);
		if (pos < 0)
			return;
		int childWriterIdx = map.getRandom(childId);
		if (childWriterIdx != UNASSIGNED){
			// we have already calculated writers for this child
			if (parentWriterIdx == childWriterIdx)
				return;
			// we have to merge (without changing the stored BitSets!)
			AreaSet childWriters = areaDictionary.getSet(childWriterIdx);
			AreaSet mergedWriters = new AreaSet(parentWriters); 
			mergedWriters.or(childWriters);
			childWriterIdx = areaDictionary.translate(mergedWriters);
		}
		else
			childWriterIdx = parentWriterIdx;
		map.replace(childId, childWriterIdx);
	}

	/**
	 * Calculate the writers for a given point specified by coordinates.
	 * Set the corresponding bit in the AreaSet.
	 * @param writerSet an already allocate AreaSet which may be modified
	 * @param mapLat latitude value 
	 * @param mapLon longitude value
	 * @return true if a writer was found
	 */
	private boolean addWritersOfPoint(AreaSet writerSet, int mapLat, int mapLon){
		AreaGridResult writerCandidates = dataStorer.getGrid().get(mapLat,mapLon);
		if (writerCandidates == null)  
			return false;

		boolean foundWriter = false;
		for (int n : writerCandidates.set) {
			Area extbbox = dataStorer.getExtendedArea(n);
			boolean found = (writerCandidates.testNeeded) ? extbbox.contains(mapLat, mapLon) : true;
			foundWriter |= found;
			if (found) 
				writerSet.set(n);
		}
		return foundWriter;
	}

	/**
	 * Find tiles that are crossed by a line specified by two points. 
	 * @param writerSet an already allocate AreaSet which may be modified
	 * @param possibleWriters a AreaSet that contains the writers to be checked
	 * @param p1 first point of line
	 * @param p2 second point of line
	 */
	private void addWritersOfCrossedTiles(AreaSet writerSet, final AreaSet possibleWriters, final Point p1,final Point p2){
		for (int i : possibleWriters) {
			Rectangle writerBbox = Utils.area2Rectangle(dataStorer.getArea(i), 1);
			if (writerBbox.intersectsLine(p1.x,p1.y,p2.x,p2.y))
				writerSet.set(i);
		}
	}

	/**
	 * Calculate all writer areas that are crossed or directly "touched" by a way. 
	 * @param writerSet an already allocate AreaSet which may be modified
	 * @param wayBbox 
	 * @param wayId the id that identifies the way
	 * @param wayRefs list with the node references
	 */
	private void addWritersOfWay (AreaSet writerSet, Rectangle wayBbox, long wayId, LongArrayList wayRefs){
		int numRefs = wayRefs.size();
		int foundNodes = 0; 
		boolean needsCrossTileCheck = false;

		Point p1 = null,p2 = null;
		for (int i = 0; i<numRefs; i++) {
			long id = wayRefs.getLong(i);
			int pos = nodeWriterMap.getKeyPos(id);
			if (pos >= 0){
				foundNodes++;
				boolean hasWriters = addWritersOfPoint(writerSet, nodeLats[pos], nodeLons[pos]);
				if (!hasWriters)
					needsCrossTileCheck = true;
			}
		}
		if (foundNodes < numRefs)
			System.out.println("Sorry, way " + wayId + " is missing " +  (numRefs-foundNodes) + " node(s).");
		if (needsCrossTileCheck == false){
			int numWriters = writerSet.cardinality();

			if (numWriters == 0) 
				needsCrossTileCheck = true; 
			else if (numWriters > 1){
				if (dataStorer.getAreaDictionary().mayCross(writerSet))
					needsCrossTileCheck = true;
			}
		}
		if (needsCrossTileCheck){
			AreaSet possibleWriters = new AreaSet();
			checkBoundingBox(possibleWriters ,wayBbox);
			// the way did cross a border tile
			for (int i = 0; i<numRefs; i++) {
				long id = wayRefs.getLong(i);
				int pos = nodeWriterMap.getKeyPos(id);
				if (pos >= 0){
					if (i > 0){
						p1 = p2;
					}
					p2 = new Point(nodeLons[pos],nodeLats[pos]);

					if (p1 != null){
						addWritersOfCrossedTiles(writerSet, possibleWriters, p1, p2);
					}
				}
			}
		}
	}

	/**
	 * Calculate the bbox of the way.
	 * @param wayId the id that identifies the way
	 * @param wayRefs the list of node references
	 * @return a new Area object or null if no node is known
	 */
	private Rectangle getWayBbox (long wayId, LongArrayList wayRefs){
		// calculate the bbox
		int minLat = Integer.MAX_VALUE,minLon = Integer.MAX_VALUE;
		int maxLat = Integer.MIN_VALUE,maxLon = Integer.MIN_VALUE;
		int numRefs = wayRefs.size();
		for (int i = 0; i<numRefs; i++) {
			long id = wayRefs.getLong(i);
			int pos = nodeWriterMap.getKeyPos(id);
			if (pos >= 0){
				int lat = nodeLats[pos];
				int lon = nodeLons[pos];
				if (lat < minLat) minLat = lat;
				if (lat > maxLat) maxLat = lat;
				if (lon < minLon) minLon = lon;
				if (lon > maxLon) maxLon = lon;
			}
		}
		if (maxLon == Integer.MIN_VALUE|| maxLat == Integer.MIN_VALUE){
			System.out.println("Sorry, no nodes found for needed way " + wayId);
			return null;
		}

		return new Rectangle(minLon, minLat, Math.max(1, maxLon-minLon), Math.max(1,maxLat-minLat));
	}

	/**
	 * Increment the loop detection ID. If the maximum value is reached, 
	 * reset all IDs and start again.
	 */
	private void incVisitID() {
		if (visitId == Integer.MAX_VALUE){
			// unlikely
			visitId = 0;
			for (Entry<MTRelation> entry : relMap.long2ObjectEntrySet()){
				entry.getValue().setLastVisitId(visitId);
			}
		}
		visitId++;
	}

	/*
	 * Report a loop in a relation 
	 */
	static void loopAction(MTRelation rel, MTRelation subRel, ArrayList<MTRelation> visited){
		if (subRel.isOnLoop())
			return; // don't complain again
		if (rel.getId() == subRel.getId()){
			System.out.println("Loop in relation " + rel.getId() +  ": Contains itself as sub relation.");
			rel.markOnLoop();
		}
		else if (visited.contains(rel)){
			subRel.markOnLoop();
			StringBuilder sb = new StringBuilder("Loop in relation " + subRel.getId() + ". Loop contains relation(s): "); 
			for (MTRelation r: visited){
				sb.append(r.getId());
				sb.append(' ');
				r.markOnLoop();
			}
			System.out.println(sb);
		} 
		else {
			System.out.println("Duplicate sub relation in relation " + rel.getId() +  ". Already looked at member " + subRel.getId() + "." );
		}
	}

	/**
	 * Handle multipolygon relations that have too large bboxes.  
	 * TODO: handle polygons that cross the 180/-180 border
	 * @param relWriters
	 * @param rel
	 */
	private void checkSpecialMP(AreaSet relWriters, MTRelation rel) {
		long[] joinedWays = null;
		List<Long> wayMembers = new LinkedList<>();
		LongArrayList polygonWays = new LongArrayList();
		for (int i = 0; i < rel.numMembers; i++){
			long memId = rel.memRefs[i];
			if (rel.memTypes[i] == MEM_WAY_TYPE && "inner".equals(rel.memRoles[i]) == false){
				wayMembers.add(memId);
			}
		}
		boolean complainedAboutSize = false;
		Rectangle mpBbox = null;
		boolean hasMissingWays = false;
		while (wayMembers.size() > 0){
			polygonWays.clear();
			mpBbox = null;
			boolean closed = false;
			while (true){
				boolean changed = false;
				for (int i = wayMembers.size()-1; i >= 0; i--){
					boolean added = false;
					long memId = wayMembers.get(i);
					JoinedWay mpWay = mpWayEndNodesMap.get(memId);
					if (mpWay == null){
						wayMembers.remove(i);
						hasMissingWays = true;
						continue;
					}
					long mpWayStart = mpWay.startNode;
					long mpWayEnd = mpWay.endNode;
					added = true;
					if (joinedWays == null){
						joinedWays = new long[2];
						joinedWays[0] = mpWayStart;
						joinedWays[1] = mpWayEnd; 
					}
					else if (joinedWays[0] == mpWayStart){
						joinedWays[0] = mpWayEnd;
					}
					else if (joinedWays[0] == mpWayEnd){
						joinedWays[0] = mpWayStart;
					}
					else if (joinedWays[1] == mpWayStart){
						joinedWays[1] = mpWayEnd;
					}
					else if (joinedWays[1] == mpWayEnd){
						joinedWays[1] = mpWayStart;
					}
					else 
						added = false;
					if (added){
						changed = true;
						wayMembers.remove(i);
						polygonWays.add(memId);
						int pos = wayWriterMap.getKeyPos(memId);
						if (pos < 0)
							continue;
						Rectangle wayBbox = wayBboxMap.get(memId);
						if (wayBbox == null)
							continue;
						if (wayBbox.x < 0 && wayBbox.getMaxX() > 0 && wayBbox.width >= PROBLEM_WIDTH){
							System.out.println("way crosses -180/180: " + memId);
						}
						if (mpBbox == null)
							mpBbox = new Rectangle(wayBbox);
						else 
							mpBbox.add(wayBbox);
						
						if (mpBbox.x < 0 && mpBbox.getMaxX() > 0 && mpBbox.width >= PROBLEM_WIDTH){
							if (complainedAboutSize == false){
								System.out.println("rel crosses -180/180: " + rel.getId());
								complainedAboutSize = true;
							}
						}

					}
					if (joinedWays[0] == joinedWays[1]){
						closed = true;
						break;
					}
				}
				if (!changed || closed){
					break;
				}
			}
			if (mpBbox != null){
				
				// found closed polygon or nothing more to add
				boolean isRelevant = checkBoundingBox(relWriters, mpBbox);
				if (isRelevant & hasMissingWays)
					System.out.println("Warning: Incomplete multipolygon relation " + rel.getId() + " (" + rel.getName() + "): using bbox of " + 
							(closed ? "closed":"unclosed") + " polygon to calc tiles, ways: " + polygonWays);
				mpBbox = null;
			} 
			joinedWays = null;
		}
		return;
	}

	/**
	 * Stores the IDs of the end nodes of a way
	 * @author GerdP
	 *
	 */
	class JoinedWay{
		long startNode, endNode;
		public JoinedWay(long startNode, long endNode) {
			this.startNode = startNode;
			this.endNode = endNode;
		}

	}
	
	/**
	 * A helper class that just contains all information about relation that we need  
	 * in the MultiTileProcessor.
	 * @author GerdP
	 *
	 */
	private class MTRelation {

		private final static short IS_MP     = 0x01; 
		private final static short ON_LOOP   = 0x02; 
		private final static short HAS_NODES = 0x04; 
		private final static short HAS_WAYS  = 0x08; 
		private final static short HAS_RELS  = 0x10; 
		private final static short IS_JUST_PARENT = 0x20; 
		private final static short IS_NOT_COMPLETE = 0x40; 

		private final long id;
		protected final byte[] memTypes;
		protected final String[] memRoles;
		protected final long[] memRefs;
		protected final int numMembers;
		private final String name;
		
		private int multiTileWriterIndex = UNASSIGNED;
		private int lastVisitId;
		private short flags; 	// flags for the MultiTileProcessor
		
		public MTRelation(Relation rel){
			numMembers  = rel.getMembers().size();
			memTypes = new byte[numMembers];
			memRoles = new String[numMembers];
			memRefs = new long[numMembers];
			id = rel.getId();
			for (int i = 0; i<numMembers; i++){
				Member mem = rel.getMembers().get(i);
				memRefs[i] = mem.getRef(); 
				memRoles[i] = mem.getRole().intern();
				if ("node".equals(mem.getType())){
					memTypes[i] = MEM_NODE_TYPE;
					flags |= HAS_NODES;
				}
				else if ("way".equals(mem.getType())){
					memTypes[i] = MEM_WAY_TYPE;
					flags |= HAS_WAYS;
				} 
				else if ("relation".equals(mem.getType())){
					memTypes[i] = MEM_REL_TYPE;
					flags |= HAS_RELS;
				}
				else
					memTypes[i] = MEM_INVALID_TYPE;
				
			}

			String type = rel.getTag("type");
			if ("multipolygon".equals(type) || "boundary".equals(type))
				markAsMultiPolygon();
			
			String goodNameCandidate = null;
			String nameCandidate = null;
			String zipCode = null;
			Iterator<Element.Tag> tags = rel.tagsIterator();
			while(tags.hasNext()) {
				Element.Tag t = tags.next();
				for (String nameTag: NAME_TAGS){
					if (nameTag.equals(t.key)){
						goodNameCandidate = t.value;
						break;
					}
				}
				if (goodNameCandidate != null)
					break;
				if (t.key.contains("name"))
					nameCandidate = t.value;
				else if ("postal_code".equals(t.key))
					zipCode = t.value;
			}
			if (goodNameCandidate != null)
				name = goodNameCandidate;
			else if (nameCandidate != null) 
				name = nameCandidate;
			else if (zipCode != null) 
				name = "postal_code=" + zipCode;
			else 
				name = "?";
		}
		
		public long getId() {
			return id;
		}
		public boolean isOnLoop() {
			return (flags & ON_LOOP) != 0; 
		}

		public void markOnLoop() {
			this.flags |= ON_LOOP;
		}

		public int getMultiTileWriterIndex() {
			return multiTileWriterIndex;
		}

		public void setMultiTileWriterIndex(int multiTileWriterIndex) {
			this.multiTileWriterIndex = multiTileWriterIndex;
		}

		public boolean hasNodeMembers() {
			return (flags & HAS_NODES) != 0;
		}
		public boolean hasWayMembers() {
			return (flags & HAS_WAYS) != 0;
		}
		public boolean hasRelMembers() {
			return (flags & HAS_RELS) != 0;
		}

		public boolean wasAddedAsParent() {
			return (flags & IS_JUST_PARENT) != 0;
		}

		public void setAddedAsParent() {
			this.flags |= IS_JUST_PARENT;
		}

		public boolean isNotComplete() {
			return (flags & IS_NOT_COMPLETE) != 0;
		}

		public void setNotComplete() {
			this.flags |= IS_NOT_COMPLETE;
		}
		public boolean isMultiPolygon() {
			return (flags & IS_MP) != 0; 
		}

		public void markAsMultiPolygon() {
			this.flags |= IS_MP;
		}

		public int getLastVisitId() {
			return lastVisitId;
		}

		public void setLastVisitId(int visitId) {
			this.lastVisitId = visitId;
		}
		
		public String getName(){
			return name;
		}

		@Override
		public String toString(){
			return "r" + id + " " + name + " subrels:" + hasRelMembers() + " incomplete:" + isNotComplete();
		}
	}
}


