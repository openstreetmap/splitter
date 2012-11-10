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

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
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
	private HashMap<Long, Relation> relMap = new HashMap<Long, Relation>();
	//private final HashMap<Long, LongArrayList> problemWayNodes = new HashMap<Long, LongArrayList>();
	private HashMap<Long, Rectangle> relBboxes = new HashMap<Long, Rectangle>();
	private HashMap<Long, Long> nodeCoords = null; 
	LongArrayList nodeCoordIds = null; 
	LongArrayList nodeCoordVals = null; 
	private final SparseBitSet alreadySearchedRels = new SparseBitSet();
	private final SparseBitSet doneRels = new SparseBitSet();
	private SparseBitSet problemRels = new SparseBitSet();
	private SparseBitSet parentOnlyRels = new SparseBitSet();
	private SparseBitSet problemWays = new SparseBitSet();
	private SparseBitSet neededWays = new SparseBitSet();
	private SparseBitSet neededNodes = new SparseBitSet();
	private Map<Long, Rectangle> wayBboxMap = new HashMap<Long, Rectangle>();
	private final BitSet workWriterSet;
	private long lastCoordId = Long.MIN_VALUE;


	MultiTileProcessor(DataStorer dataStorer, LongArrayList problemWayList, LongArrayList problemRelList) {
		this.dataStorer = dataStorer;
		for (long id: problemWayList){
			problemWays.set(id);
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
		}
		if (pass == PASS3_NODES_AND_WAYS){
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
			if (!workWriterSet.isEmpty()){
				int wayWriterIdx = dataStorer.getMultiTileWriterDictionary().translate(workWriterSet);
				dataStorer.putWriterIdx(DataStorer.WAY_TYPE, way.getId(), wayWriterIdx);
			}
		}
		if (pass == PASS4_WAYS_ONLY){
			// propagate the ways writers to all nodes 
			if (!neededWays.get(way.getId()))
				return;
			Integer wayWriterIdx = dataStorer.getWriterIdx(DataStorer.WAY_TYPE, way.getId());
			if (wayWriterIdx !=  null){
				BitSet wayWriterSet = dataStorer.getMultiTileWriterDictionary().getBitSet(wayWriterIdx);
				for (long id : way.getRefs()) {
					addOrMergeWriters(DataStorer.NODE_TYPE, wayWriterSet, wayWriterIdx, id);
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
					rel.setMultiPolygon(true);
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
			System.out.println("starting to resolve relations containing problem relations");
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
			++pass;
			return false;
		}
		if (pass == PASS2_WAYS_ONLY){
			stats("endMap Pass2 end");
			++pass;
			System.out.println("starting to collect coordinates for " + Utils.format(neededNodes.cardinality()) + " special nodes ");

			nodeCoordIds = new LongArrayList(neededNodes.cardinality());
			nodeCoordVals = new LongArrayList(neededNodes.cardinality());
			return false;
		}

		if (pass == PASS3_NODES_AND_WAYS){
			neededNodes = null;

			calcWritersOfRelWaysAndNodes();
			// return memory to GC
			nodeCoords = null;
			nodeCoordIds = null;
			nodeCoordVals = null;
			calcWritersOfRels();
			mergeRelMemWriters();
			orWritersOfRelMembers();

			problemRels = null;
			problemWays = null;
			parentOnlyRels = null;
			relMap = null;
			wayBboxMap = null;
			relBboxes = null;
			stats("endMap Pass3 end");
			++pass;
			return false;
		}
		if (pass == PASS4_WAYS_ONLY){
			stats("endMap Pass4 end");
			++pass;
		}
		return true;
	}

	/**
	 * Makes sure that all the elements of a relation are written to the same tiles as the relation info itself.
	 */
	private void orWritersOfRelMembers() {
		// make sure that the ways and nodes of the problem relations are written to all needed tiles
		for (Relation rel: relMap.values()){
			Integer relWriterIdx = dataStorer.getWriterIdx(DataStorer.REL_TYPE, rel.getId());
			if (relWriterIdx == null)
				continue;
			BitSet relWriters =  dataStorer.getMultiTileWriterDictionary().getBitSet(relWriterIdx);
			for (Member mem: rel.getMembers()){
				if (mem.getType().equals("way")){
					addOrMergeWriters(DataStorer.WAY_TYPE, relWriters, relWriterIdx, mem.getRef());
				}
				else if (mem.getType().equals("node")){
					addOrMergeWriters(DataStorer.NODE_TYPE, relWriters, relWriterIdx, mem.getRef());
				}
			}
		}

	}

	private void mergeRelMemWriters() {
		// or combine the writers of sub-relations with the parent relation 
		doneRels.clear();
		for (Relation rel: relMap.values()){
			alreadySearchedRels.clear();
			orSubRelWriters(rel, 0);
		}
	}

	private void calcWritersOfRels() {
		// recurse thru sub relations
		doneRels.clear();
		for (Relation rel: relMap.values()){
			BitSet relWriters = new BitSet();
			alreadySearchedRels.clear();
			if (rel.isMultiPolygon()){
				getSubRelBboxes(rel, 0);
				Rectangle relBbox = relBboxes.get(rel.getId());
				checkBoundingBox(relWriters, relBbox);
				// now we know the bounding box of the relation, so we can calculate the tiles
				// this is far away from being precise, but very fast
				if (!relWriters.isEmpty()){
					int writerIdx = dataStorer.getMultiTileWriterDictionary().translate(relWriters);
					dataStorer.putWriterIdx(DataStorer.REL_TYPE, rel.getId(), writerIdx);
				}
			}
			else{
				orSubRelWriters(rel, 0);
			}
			assert doneRels.get(rel.getId());
		}
	}

	private void calcWritersOfRelWaysAndNodes() {
		for (Relation rel: relMap.values()){
			Rectangle relBbox = null;
			boolean isNotComplete = false;
			BitSet writerSet = new BitSet(); 
			for (Member mem: rel.getMembers()){
				long memId = mem.getRef();
				Rectangle memBbox = null;
				boolean memFound = false;
				if (mem.getType().equals("node")){
					Long coord = findCoord(memId);
					if (coord != null){
						int lat = (int) (0xffffffff & (coord >>> 32));
						int lon = (int) (0xffffffff & coord);
						addWritersOfPoint(writerSet, lat, lon);
						memFound = true;
					}
				}
				else if (mem.getType().equals("way")){
					Integer idx = dataStorer.getWriterIdx(DataStorer.WAY_TYPE, memId);
					if (idx != null){
						writerSet.or(dataStorer.getMultiTileWriterDictionary().getBitSet(idx));
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

			relBboxes.put(rel.getId(), relBbox);
			if (!writerSet.isEmpty()){
				int idx = dataStorer.getMultiTileWriterDictionary().translate(writerSet);
				dataStorer.putWriterIdx(DataStorer.REL_TYPE, rel.getId(), idx);
				if (isNotComplete && parentOnlyRels.get(rel.getId()) == false)
					System.out.println("Sorry, data for relation " + rel.getId() + " is incomplete");
			}
		}

	}

	/**
	 * Store the coordinates of a node in the most appropriate data structure.
	 * We try to use an array list, if input is not sorted, we switch to a HashMap 
	 * @param node
	 */
	private void storeCoord(Node node) {
		// store two ints in one long to save memory
		long lat = 0xffffffffL & node.getMapLat();
		long lon = 0xffffffffL & node.getMapLon();
		long coord = (lat << 32) | lon;

		long id = node.getId();
		if (lastCoordId >= id && nodeCoords == null){
			System.out.println("Node ids are not sorted, switching to HashMap to store coordinates");
			nodeCoords = new HashMap<Long, Long>();
			for (int i = 0; i < nodeCoordIds.size(); i++){
				nodeCoords.put(nodeCoordIds.get(i), nodeCoordVals.get(i));
			}
			nodeCoordIds = null;
			nodeCoordVals = null;
		}
		lastCoordId = id;
		if (nodeCoords != null)
			nodeCoords.put(id, coord);
		else {
			nodeCoordIds.add(id);
			nodeCoordVals.add(coord);
		}
		/*
		int lat2 = (int) (0xffffffff & (coord >>> 32));
		int lon2 = (int) (0xffffffff & coord);
		if (lat2 != node.getMapLat()  || lon2 != node.getMapLon()){
			long dd = 4;
		}
		 */
	}

	/**
	 * Use either Map or perform binary search to find coordinates
	 * @param memId
	 * @return
	 */
	private Long findCoord(long memId) {
		if (nodeCoords != null)
			return nodeCoords.get(memId);
		int pos = Arrays.binarySearch(nodeCoordIds.elements(), 0, nodeCoordIds.size(), memId);
		if (pos >= 0)
			return nodeCoordVals.get(pos);
		else 
			return null;
	}

	/**
	 * Mark the ways and nodes of a relation as problem cases. If the relation 
	 * contains sub relations, the routine calls itself recursively. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void MarkNeededMembers(Relation rel, int depth){

		alreadySearchedRels.set(rel.getId());
		if (doneRels.get(rel.getId()))
			return ;
		if (depth > 15){
			System.out.println("MarkNeededMembers reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		for (Member mem : rel.getMembers()) {
			// String role = mem.getRole();
			long memId = mem.getRef();
			if (mem.getType().equals("way")){
				neededWays.set(memId);
				problemWays.clear(memId);
			}
			else if (mem.getType().equals("node"))
				neededNodes.set(memId);
			else if (mem.getType().equals("relation")) {
				if (alreadySearchedRels.get(memId)){
					//System.out.println("loop in relation: " + rel.getId() + " (depth:" +  depth + ") subrel: " + memId );
				}
				else {
					// recursive search
					Relation subRel = relMap.get(memId);
					if (subRel != null){
						problemRels.set(memId);
						MarkNeededMembers(subRel, depth+1);
					}
				}
			} 
		}
		doneRels.set(rel.getId());
	}


	/**
	 * If a relation contains relations, collect the areas and writers of the sub rels. The routine calls 
	 * itself recursively when the sub relation contains sub relations. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void getSubRelBboxes(Relation rel, int depth){
		alreadySearchedRels.set(rel.getId());
		if (doneRels.get(rel.getId()))
			return ;
		if (depth > 15){
			System.out.println("getSubRelWriters reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		Rectangle relBbox = relBboxes.get(rel.getId());
		boolean changed = false;
		for (Member mem : rel.getMembers()) {
			long memId = mem.getRef();

			if (mem.getType().equals("relation")) {
				if (alreadySearchedRels.get(memId)){
					if (rel.isOnLoop() == false)
						System.out.println("loop in relation: " + rel.getId() + " (depth:" +  depth + ") subrel: " + memId );
					rel.setOnLoop(true);
				}
				else {
					// recursive search
					Relation subRel = relMap.get(memId);
					if (subRel != null){
						getSubRelBboxes(subRel, depth+1);
						Rectangle memBbox = relBboxes.get(mem.getRef());
						if (memBbox == null)
							System.out.println("sorry, no nodes found for relation : " + mem.getRef() + " which is a sub-rel of " + rel.getId());
						else {
							if (relBbox == null)
								relBbox = new Rectangle(memBbox);
							else
								relBbox.add(memBbox);
							changed = true;
						}
					}
				}
			} 
		}
		if (relBbox == null)
			System.out.println("sorry, no nodes found for relation : " + rel.getId());
		else if (changed)
			relBboxes.put(rel.getId(), relBbox);
		doneRels.set(rel.getId());
	}


	/**
	 * If a relation contains relations, or-combine the writers of the sub-
	 * relation with the writes of the parent relation . The routine calls 
	 * itself recursively when the sub relation contains sub relations. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void orSubRelWriters(Relation rel, int depth){
		alreadySearchedRels.set(rel.getId());
		if (doneRels.get(rel.getId()))
			return ;
		if (depth > 15){
			System.out.println("orSubRelWriters reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		BitSet relWriters = new BitSet();
		Integer relWriterIdx = dataStorer.getWriterIdx(DataStorer.REL_TYPE, rel.getId());
		if (relWriterIdx != null)
			relWriters.or(dataStorer.getMultiTileWriterDictionary().getBitSet(relWriterIdx));

		boolean changed = false;
		for (Member mem : rel.getMembers()) {
			long memId = mem.getRef();

			if (mem.getType().equals("relation")) {
				if (alreadySearchedRels.get(memId)){
					if (rel.isOnLoop() == false)
						System.out.println("loop in relation: " + rel.getId() + " (depth:" +  depth + ") subrel: " + memId );
					rel.setOnLoop(true);
				}
				else {
					// recursive search
					Relation subRel = relMap.get(memId);
					if (subRel != null){
						orSubRelWriters(subRel, depth+1);
						Integer memWriterIdx = dataStorer.getWriterIdx(DataStorer.REL_TYPE, memId);
						if (memWriterIdx == null){
							continue;
						}
						BitSet memWriters = dataStorer.getMultiTileWriterDictionary().getBitSet(memWriterIdx);
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
		}
		if (changed){
			relWriterIdx = dataStorer.getMultiTileWriterDictionary().translate(relWriters);
			dataStorer.putWriterIdx(DataStorer.REL_TYPE, rel.getId(), relWriterIdx);
		}
		doneRels.set(rel.getId());
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
	 * @param kind
	 * @param parentWriters
	 * @param parentWriterIdx
	 * @param childId
	 */
	private void addOrMergeWriters(int kind, BitSet parentWriters, int parentWriterIdx, long childId) {
		Integer childWriterIdx = null;
		childWriterIdx = dataStorer.getWriterIdx(kind, childId);
		if (childWriterIdx != null){
			// we have already calculated writers for this child
			if (parentWriterIdx == childWriterIdx)
				return;
			// we have to merge (without changing the stored BitSets!)
			BitSet childWriters = dataStorer.getMultiTileWriterDictionary().getBitSet(childWriterIdx);
			BitSet mergedWriters = new BitSet(); 
			mergedWriters.or(childWriters);
			mergedWriters.or(parentWriters);
			childWriterIdx = dataStorer.getMultiTileWriterDictionary().translate(mergedWriters);
		}
		else
			childWriterIdx = parentWriterIdx;
		dataStorer.putWriterIdx(kind, childId, childWriterIdx);
	}

	/**
	 * Calculate the writers for a given point specified by coordinates.
	 * Set the corresponding bit in the BitSet.
	 * @param writerSet an already allocate BitSet which may be modified
	 * @param mapLat latitude value 
	 * @param mapLon longitude value
	 */
	private void addWritersOfPoint(BitSet writerSet, int mapLat, int mapLon){
		WriterGridResult writerCandidates = dataStorer.getGrid().get(mapLat,mapLon);
		if (writerCandidates == null)  
			return;

		OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters(); 
		for (int i = 0; i < writerCandidates.l.size(); i++) {
			int n = writerCandidates.l.get(i);
			OSMWriter w = writers[n];
			boolean found = (writerCandidates.testNeeded) ? w.coordsBelongToThisArea(mapLat, mapLon) : true;
			if (found) 
				writerSet.set(n);
		}
		return ;
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

		Point p1 = null,p2 = null;
		for (int i = 0; i<numRefs; i++) {
			long id = wayRefs.getLong(i);
			Long coord = findCoord(id);
			if (coord != null){
				foundNodes++;
				int lat = (int) (0xffffffff & (coord >>> 32));
				int lon = (int) (0xffffffff & coord);
				addWritersOfPoint(writerSet, lat, lon);
			}
		}
		if (writerSet.cardinality() > 1){
			short idx = dataStorer.getWriterDictionary().translate(writerSet);
			if (dataStorer.getWriterDictionary().mayCross(idx)){
				BitSet possibleWriters = new BitSet();
				checkBoundingBox(possibleWriters ,wayBbox);
				// the way did cross a border tile
				for (int i = 0; i<numRefs; i++) {
					long id = wayRefs.getLong(i);
					Long coord = findCoord(id);
					if (coord != null){
						int lat = (int) (0xffffffff & (coord >>> 32));
						int lon = (int) (0xffffffff & coord);
						if (i > 0){
							p1 = p2;
						}
						p2 = new Point(lon,lat);

						if (p1 != null){
							addWritersOfCrossedTiles(writerSet, possibleWriters, p1, p2);
						}
					}
				}
			}
		}
		if (foundNodes < numRefs)
			System.out.println("Sorry, way " + wayId + " is missing " +  (numRefs-foundNodes) + " node(s).");
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
			Long coord = findCoord(id);
			if (coord != null){
				int lat = (int) (0xffffffff & (coord >>> 32));
				int lon = (int) (0xffffffff & coord);
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

	/**
	 * Mark all members of problem relations as problem cases.
	 */
	private void markProblemMembers() {
		doneRels.clear();
		for (Relation rel: relMap.values()){
			if (!problemRels.get(rel.getId()))
				continue;
			alreadySearchedRels.clear();
			MarkNeededMembers(rel, 0);
		}
	}

	/**
	 * Mark the parents of problem relations as problem relations.
	 */
	private void markParentRels(){
		while (true){
			boolean changed = false;
			for (Relation rel: relMap.values()){
				if (problemRels.get(rel.getId()))
					continue;
				for (Member mem : rel.getMembers()) {
					// String role = mem.getRole();
					long memId = mem.getRef();
					if (mem.getType().equals("relation")) {
						if (problemRels.get(memId)){
							problemRels.set(rel.getId());
							parentOnlyRels.set(rel.getId());
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
}


