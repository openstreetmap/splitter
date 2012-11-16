/*
 * Copyright (C) 2012.
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

import it.unimi.dsi.Util;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Analyzes elements that should be written to multiple tiles 
 * to find out what details are needed in each tile.
 */
class MultiTileProcessor extends AbstractMapProcessor {
	private final int PASS1_RELS_ONLY = 1;
	private final int PASS2_WAYS_ONLY = 2;
	private final int PASS3_NODES_AND_WAYS = 3;
	private final int PASS4_WAYS_ONLY = 4;

	private int pass = PASS1_RELS_ONLY;
	private final DataStorer dataStorer;
	private final WriterDictionaryInt multiTileDictionary;
	private Map<Long, Relation> relMap = new LinkedHashMap<Long, Relation>();
	private Long2IntClosedMapFunction nodeWriterMap;
	private Long2IntClosedMapFunction wayWriterMap;
	private Long2IntClosedMapFunction relWriterMap;
	private HashMap<Long, Rectangle> relBboxes = new HashMap<Long, Rectangle>();
	private int [] nodeLons;
	private int [] nodeLats;
	private SparseBitSet problemRels = new SparseBitSet();
	private SparseBitSet neededWays = new SparseBitSet();
	private SparseBitSet neededNodes = new SparseBitSet();
	private Map<Long, Rectangle> wayBboxMap = new HashMap<Long, Rectangle>();
	private final BitSet workWriterSet;
	private long lastCoordId = Long.MIN_VALUE;
	private int foundWays;
	private int visitId = 0;

	MultiTileProcessor(DataStorer dataStorer, LongArrayList problemWayList, LongArrayList problemRelList) {
		this.dataStorer = dataStorer;
		multiTileDictionary = dataStorer.getMultiTileWriterDictionary();
		for (long id: problemWayList){
			neededWays.set(id);
		}
		for (long id: problemRelList){
			problemRels.set(id);
		}
		// we allocate this once to avoid massive resizing with large number of tiles
		workWriterSet = new BitSet();
		return;
	}

	@Override
	public boolean skipTags() {
		if (pass == PASS1_RELS_ONLY)
			return false;
		return true;
	}

	@Override
	public boolean skipNodes() {
		if (pass == PASS3_NODES_AND_WAYS)
			return false;
		return true;
	}
	@Override
	public boolean skipWays() {
		if (pass == PASS1_RELS_ONLY)
			return true;
		return false;
	}
	@Override
	public boolean skipRels() {
		if (pass == PASS1_RELS_ONLY)
			return false;
		return true;
	}

	@Override
	public void processNode(Node node) {
		if (pass == PASS3_NODES_AND_WAYS){
			if (neededNodes.get(node.getId()))
				storeCoord(node);
		}
	}

	@Override
	public void processWay(Way way) {
		if (pass == PASS2_WAYS_ONLY){
			if (!neededWays.get(way.getId()))
				return;
			for (long id : way.getRefs()) {
				neededNodes.set(id);
			}
			foundWays++;
		}
		else if (pass == PASS3_NODES_AND_WAYS){
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
			if (workWriterSet.isEmpty())
				wayWriterMap.add(way.getId(), WriterDictionaryInt.UNASSIGNED);
			else {
				int wayWriterIdx = multiTileDictionary.translate(workWriterSet);
				wayWriterMap.add(way.getId(), wayWriterIdx);
			}
		}
		else if (pass == PASS4_WAYS_ONLY){
			// propagate the ways writers to all nodes 
			if (!neededWays.get(way.getId()))
				return;
			int wayWriterIdx = wayWriterMap.getRandom(way.getId());
			if (wayWriterIdx !=  WriterDictionaryInt.UNASSIGNED){
				BitSet wayWriterSet = multiTileDictionary.getBitSet(wayWriterIdx);
				for (long id : way.getRefs()) {
					addOrMergeWriters(nodeWriterMap, wayWriterSet, wayWriterIdx, id);
				}
			}
		}
	}

	@Override
	public void processRelation(Relation rel) {
		if (pass == PASS1_RELS_ONLY){
			Iterator<Element.Tag> tags = rel.tagsIterator();
			while(tags.hasNext()) {
				Element.Tag t = tags.next();
				if ("type".equals(t.key) && "multipolygon".equals(t.value) || "boundary".equals(t.value)){
					rel.markAsMultiPolygon();
					break;
				}
			}
			// return tags to GC
			rel.clearTags();
			relMap.put(rel.getId(), rel);
		}
	}

	@Override
	public boolean endMap() {
		if (pass == PASS1_RELS_ONLY){
			stats("endMap start");
			System.out.println("starting to resolve relations containing problem relations ...");
			// add all ways and nodes of problem rels so that we collect the coordinates
			markProblemMembers();
			// we want to see the parent rels, but not all children of all parents 
			markParentRels();
			// free memory for rels that are not causing any trouble
			Iterator<Entry<Long, Relation>> it = relMap.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Long,Relation> pairs = it.next();
				if (!problemRels.get(pairs.getKey())){
					it.remove(); 
				}
			}
			stats("endMap Pass1 end");
			System.out.println("starting to collect ids of needed way nodes ...");
			++pass;
		}
		else if (pass == PASS2_WAYS_ONLY){
			stats("endMap Pass2 end");
			++pass;
			System.out.println("Found " + foundWays + " needed ways");
			System.out.println("Starting to collect coordinates for " + Utils.format(neededNodes.cardinality()) + " special nodes ");
			// critical part: we have to allocate possibly large arrays here
			nodeWriterMap = new Long2IntClosedMap("node", neededNodes.cardinality(), WriterDictionaryInt.UNASSIGNED);
			wayWriterMap = new Long2IntClosedMap("way", foundWays, WriterDictionaryInt.UNASSIGNED);
			dataStorer.setWriterMap(DataStorer.NODE_TYPE, nodeWriterMap);
			dataStorer.setWriterMap(DataStorer.WAY_TYPE, wayWriterMap);
			nodeLons = new int[neededNodes.cardinality()];
			nodeLats = new int[neededNodes.cardinality()];
		}
		else if (pass == PASS3_NODES_AND_WAYS){
			neededNodes = null;
			System.out.println("Found " + nodeWriterMap.size() + " needed nodes");
			calcWritersOfRelWaysAndNodes();
			// return coordinate memory to GC
			nodeLats = null;
			nodeLons = null;
			
			calcWritersOfMultiPolygonRels();
			mergeRelMemWriters();
			propagateWritersOfRelsToMembers();

			problemRels = null;
			//problemWays = null;
			//parentOnlyRels = null;
			wayBboxMap = null;
			relBboxes = null;
			relWriterMap = new Long2IntClosedMap("rel", relMap.size(), WriterDictionaryInt.UNASSIGNED);
			for (Map.Entry<Long, Relation> entry: relMap.entrySet()) {
				int val = entry.getValue().getMultiTileWriterIndex();
				if (val != WriterDictionaryInt.UNASSIGNED)
					relWriterMap.add(entry.getKey(), val);
			}
			relMap = null;
			dataStorer.setWriterMap(DataStorer.REL_TYPE, relWriterMap);
			stats("endMap Pass3 end");
			++pass;
			return false;
		}
		else if (pass == PASS4_WAYS_ONLY){
			stats("endMap Pass4 end");
			return true; 
		}
		return false; // not done yet
	}

	/**
	 * Mark all members of given problem relations as problem cases. 
	 */
	private void markProblemMembers() {
		LongArrayList visited = new LongArrayList();
		for (Relation rel: relMap.values()){
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
	private void MarkNeededMembers(Relation rel, int depth, LongArrayList visited){
		if (rel.getVisitId() == visitId)
			return;
		rel.setVisitId(visitId);
		if (depth > 15){
			System.out.println("MarkNeededMembers reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		for (Member mem : rel.getMembers()) {
			long memId = mem.getRef();
			if (mem.getType().equals("way")){
				neededWays.set(memId);
			}
			else if (mem.getType().equals("node"))
				neededNodes.set(memId);
			else if (mem.getType().equals("relation")) {
				Relation subRel = relMap.get(memId);
				if (subRel != null && subRel.getVisitId() != visitId){
					problemRels.set(memId);
					visited.add(memId);
					MarkNeededMembers(subRel, depth+1, visited);
					visited.remove(visited.size()-1);
				}
				else
					loopAction(rel, memId, visited);
			} 
		}
	}

	/**
	 * Mark the parents of problem relations as problem relations.
	 */
	private void markParentRels(){
		while (true){
			boolean changed = false;
			for (Relation rel: relMap.values()){
				if (rel.hasRelMembers() == false || problemRels.get(rel.getId()))
					continue;
				for (Member mem : rel.getMembers()) {
					long memId = mem.getRef();
					if (mem.getType().equals("relation")) {
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

	private void calcWritersOfRelWaysAndNodes() {
		for (Relation rel: relMap.values()){
			if (!rel.hasWayMembers() &&  !rel.hasNodeMembers())
				continue;
			Rectangle relBbox = null;
			boolean isNotComplete = false;
			BitSet writerSet = new BitSet(); 
			for (Member mem: rel.getMembers()){
				long memId = mem.getRef();
				Rectangle memBbox = null;
				boolean memFound = false;
				if (mem.getType().equals("node")){
					int pos = nodeWriterMap.getKeyPos(memId);
					if (pos >= 0){
						addWritersOfPoint(writerSet, nodeLats[pos], nodeLons[pos]);
						memFound = true;
					}
				}
				else if (mem.getType().equals("way")){
					int idx = wayWriterMap.getRandom(memId);
					if (idx != WriterDictionaryInt.UNASSIGNED){
						writerSet.or(multiTileDictionary.getBitSet(idx));
						memFound = true;
					}
					memBbox = wayBboxMap.get(memId);
					if (memBbox != null)
						memFound = true;
				}
				else if (mem.getType().equals("relation"))
					continue; // handled later
				if (!memFound) {
					isNotComplete = true;
					continue;
				}
				if (memBbox == null)
					continue;
	
				if (relBbox == null)
					relBbox = new Rectangle(memBbox);
				else 
					relBbox.add(memBbox);
	
			}
	
			if (relBbox != null)
				relBboxes.put(rel.getId(), relBbox);
			if (!writerSet.isEmpty()){
				int idx = multiTileDictionary.translate(writerSet);
				rel.setMultiTileWriterIndex(idx);
				if (isNotComplete && rel.wasAddedAsParent() == false)
					System.out.println("Sorry, data for relation " + rel.getId() + " is incomplete");
			}
		}
	
	}

	private void calcWritersOfMultiPolygonRels() {
		// recurse thru sub relations
		LongArrayList visited = new LongArrayList();
		for (Relation rel: relMap.values()){
			BitSet relWriters = new BitSet();
			if (rel.isMultiPolygon()){
				incVisitID();
				visited.clear();
				getSubRelBboxes(rel, 0, visited);
				Rectangle relBbox = relBboxes.get(rel.getId());
				checkBoundingBox(relWriters, relBbox);
				// now we know the bounding box of the relation, so we can calculate the tiles
				// this is far away from being precise, but very fast
				if (!relWriters.isEmpty()){
					int writerIdx = multiTileDictionary.translate(relWriters);
					rel.setMultiTileWriterIndex(writerIdx);
				}
			}
		}
	}

	/**
	 * or-combine all writers of the members of a relation 
	 */
	private void mergeRelMemWriters() {
		// or combine the writers of sub-relations with the parent relation 
		LongArrayList visited = new LongArrayList();
		for (Relation rel: relMap.values()){
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
		for (Relation rel: relMap.values()){
			if (rel.wasAddedAsParent())
				continue;
			int relWriterIdx = rel.getMultiTileWriterIndex();
			if (relWriterIdx == WriterDictionaryInt.UNASSIGNED)
				continue;
			BitSet relWriters =  multiTileDictionary.getBitSet(relWriterIdx);
			for (Member mem: rel.getMembers()){
				if (mem.getType().equals("way")){
					addOrMergeWriters(wayWriterMap, relWriters, relWriterIdx, mem.getRef());
				}
				else if (mem.getType().equals("node")){
					addOrMergeWriters(nodeWriterMap, relWriters, relWriterIdx, mem.getRef());
				}
			}
		}

	}

	/**
	 * If a relation contains relations, collect the bounding boxes of the sub rels. The routine calls 
	 * itself recursively when the sub relation contains sub relations. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void getSubRelBboxes(Relation rel, int depth, LongArrayList visited){
		if (rel.getVisitId() == visitId)
			return;
		rel.setVisitId(visitId);
		if (depth > 15){
			System.out.println("getSubRelBboxes reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		Rectangle relBbox = relBboxes.get(rel.getId());
		boolean changed = false;
		for (Member mem : rel.getMembers()) {
			long memId = mem.getRef();
	
			if (mem.getType().equals("relation")) {
				Relation subRel = relMap.get(memId);
				if (subRel == null)
					continue;
				if (subRel.getVisitId() == visitId)
					loopAction(rel, memId, visited);
				else {
					visited.add(memId);
					getSubRelBboxes(subRel, depth+1, visited);
					visited.remove(visited.size()-1);
					Rectangle memBbox = relBboxes.get(mem.getRef());
					if (memBbox != null){
						if (relBbox == null)
							relBbox = new Rectangle(memBbox);
						else
							relBbox.add(memBbox);
						changed = true;
					}
				}
			} 
		}
		if (changed)
			relBboxes.put(rel.getId(), relBbox);
	}

	/**
	 * Store the coordinates of a node in the most appropriate data structure.
	 * We try to use an array list, if input is not sorted, we switch to a HashMap 
	 * @param node
	 */
	private void storeCoord(Node node) {
		// store two ints in one long to save memory
		int lat = node.getMapLat();
		int lon = node.getMapLon();

		long id = node.getId();
		if (lastCoordId >= id){
			throw new IllegalArgumentException ("Error: Node ids are not sorted. Use e.g. osmosis to sort the input data.");
		}
		int nodePos = nodeWriterMap.add(id, WriterDictionaryInt.UNASSIGNED);
		lastCoordId = id;
		nodeLons[nodePos ] = lon;
		nodeLats[nodePos] = lat;
	}

	/**
	 * If a relation contains relations, or-combine the writers of the sub-
	 * relation with the writes of the parent relation . The routine calls 
	 * itself recursively when the sub relation contains sub relations. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void orSubRelWriters(Relation rel, int depth, LongArrayList visited ){
		if (rel.getVisitId() == visitId)
			return;
		rel.setVisitId(visitId);
		if (depth > 15){
			System.out.println("orSubRelWriters reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		BitSet relWriters = new BitSet();
		int relWriterIdx = rel.getMultiTileWriterIndex();
		if (relWriterIdx != WriterDictionaryInt.UNASSIGNED)
			relWriters.or(multiTileDictionary.getBitSet(relWriterIdx));

		boolean changed = false;
		for (Member mem : rel.getMembers()) {
			long memId = mem.getRef();

			if (mem.getType().equals("relation")) {
				Relation subRel = relMap.get(memId);
				if (subRel == null)
					continue;
				if (subRel.getVisitId() == visitId)
					loopAction(rel, memId, visited);
				else {
					visited.add(rel.getId());
					orSubRelWriters(subRel, depth+1, visited);
					visited.remove(visited.size()-1);
					int memWriterIdx = subRel.getMultiTileWriterIndex();
					if (memWriterIdx == WriterDictionaryInt.UNASSIGNED || memWriterIdx == relWriterIdx){
						continue;
					}
					BitSet memWriters = multiTileDictionary.getBitSet(memWriterIdx);
					BitSet test = new BitSet();
					test.or(memWriters);
					test.andNot(relWriters);
					if (test.isEmpty() == false){
						relWriters.or(memWriters);
						changed = true;
					}
				}
			}
		}
		if (changed){
			relWriterIdx = multiTileDictionary.translate(relWriters);
			rel.setMultiTileWriterIndex(relWriterIdx);
		}
	}


	/**
	 * Report some numbers regarding memory usage 
	 * @param msg
	 */
	private void stats(String msg){
		System.out.println("Stats for MultiTileProcessor pass " + pass + " " + msg);
		if (problemRels != null)
			System.out.println("SparseBitSet problemRels " + problemRels.cardinality() + " (" + problemRels.bytes() + "  bytes)");
		if (neededWays != null)
			System.out.println("SparseBitSet neededWays " + neededWays.cardinality() + " (" + neededWays.bytes() + "  bytes)");
		if (neededNodes != null)
			System.out.println("SparseBitSet neededNodes " + neededNodes.cardinality() + " (" + neededNodes.bytes() + "  bytes)");
		if (relMap != null)
			System.out.println("Number of stored relations: " + relMap.size());
		System.out.println("Number of stored combis in big dictionary: " + Util.format(multiTileDictionary.size()));
		if (pass == PASS4_WAYS_ONLY)
			dataStorer.stats();
			
	}

	/**
	 * Find all writer areas that intersect with a given bounding box. 
	 * @param writerSet an already allocate BitSet which may be modified
	 * @param polygonBbox the bounding box 
	 */
	private void checkBoundingBox(BitSet writerSet, Rectangle polygonBbox){
		if (polygonBbox != null){
			OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters();
			for (int i = 0; i < writers.length; i++) {
				Rectangle writerBbox = writers[i].getBBox();
				if (writerBbox.intersects(polygonBbox))
					writerSet.set(i);
			}
		}
	}

	/**
	 * Merge the writers of a parent object with the writes of the child, 
	 * add or update the entry in the Map
	 * @param map
	 * @param parentWriters
	 * @param parentWriterIdx
	 * @param childId
	 */
	private void addOrMergeWriters(Long2IntClosedMapFunction map, BitSet parentWriters, int parentWriterIdx, long childId) {
		int pos = map.getKeyPos(childId);
		if (pos < 0)
			return;
		int childWriterIdx = map.getRandom(childId);
		if (childWriterIdx != WriterDictionaryInt.UNASSIGNED){
			// we have already calculated writers for this child
			if (parentWriterIdx == childWriterIdx)
				return;
			// we have to merge (without changing the stored BitSets!)
			BitSet childWriters = multiTileDictionary.getBitSet(childWriterIdx);
			BitSet mergedWriters = new BitSet(); 
			mergedWriters.or(childWriters);
			mergedWriters.or(parentWriters);
			childWriterIdx = multiTileDictionary.translate(mergedWriters);
		}
		else
			childWriterIdx = parentWriterIdx;
		map.replace(childId, childWriterIdx);
	}

	/**
	 * Calculate the writers for a given point specified by coordinates.
	 * Set the corresponding bit in the BitSet.
	 * @param writerSet an already allocate BitSet which may be modified
	 * @param mapLat latitude value 
	 * @param mapLon longitude value
	 * @return true if a writer was found
	 */
	private boolean addWritersOfPoint(BitSet writerSet, int mapLat, int mapLon){
		WriterGridResult writerCandidates = dataStorer.getGrid().get(mapLat,mapLon);
		if (writerCandidates == null)  
			return false;

		OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters();
		boolean foundWriter = false;
		for (int i = 0; i < writerCandidates.l.size(); i++) {
			int n = writerCandidates.l.get(i);
			OSMWriter w = writers[n];
			boolean found = (writerCandidates.testNeeded) ? w.coordsBelongToThisArea(mapLat, mapLon) : true;
			foundWriter |= found;
			if (found) 
				writerSet.set(n);
		}
		return foundWriter;
	}

	/**
	 * Find tiles that are crossed by a line specified by two points. 
	 * @param writerSet an already allocate BitSet which may be modified
	 * @param possibleWriters a BitSet that contains the writers to be checked
	 * @param p1 first point of line
	 * @param p2 second point of line
	 */
	private void addWritersOfCrossedTiles(BitSet writerSet, final BitSet possibleWriters, final Point p1,final Point p2){
		OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters();

		for (int i = possibleWriters.nextSetBit(0); i >= 0; i = possibleWriters.nextSetBit(i+1)){
			Rectangle writerBbox = writers[i].getBBox();
			if (writerBbox.intersectsLine(p1.x,p1.y,p2.x,p2.y))
				writerSet.set(i);
		}
	}

	/**
	 * Calculate all writer areas that are crossed or directly "touched" by a way. 
	 * @param writerSet an already allocate BitSet which may be modified
	 * @param wayBbox 
	 * @param wayId the id that identifies the way
	 * @param wayRefs list with the node references
	 */
	private void addWritersOfWay (BitSet writerSet, Rectangle wayBbox, long wayId, LongArrayList wayRefs){
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
				short idx = dataStorer.getWriterDictionary().translate(writerSet);
				if (dataStorer.getWriterDictionary().mayCross(idx))
					needsCrossTileCheck = true;
			}
		}
		if (needsCrossTileCheck){
			BitSet possibleWriters = new BitSet();
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
			System.out.println("sorry, no nodes found for needed way " + wayId);
			return null;
		}

		return new Rectangle(minLon, minLat, maxLon-minLon, maxLat-minLat);
	}

	private void incVisitID() {
		if (visitId == Integer.MAX_VALUE){
			// unlikely
			visitId = 0;
			for (Map.Entry<Long, Relation> entry : relMap.entrySet()){
				entry.getValue().setVisitId(visitId);
			}
		}
		visitId++;
	}

	void loopAction(Relation rel, long memId, LongArrayList visited){
			if (rel.isOnLoop() == false && visited.contains(memId)){
				System.out.println("Loop in relation. Members of the loop: " + visited.toString());
				rel.markOnLoop();
			}
	}
}


