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
	private final HashMap<Long, Relation> relMap = new HashMap<Long, Relation>();
	//private final HashMap<Long, LongArrayList> problemWayNodes = new HashMap<Long, LongArrayList>();
	private final HashMap<Long, Area> relAreas = new HashMap<Long, Area>();
	private HashMap<Long, Long> nodeCoords = null; 
	private final SparseBitSet alreadySearchedRels = new SparseBitSet();
	private final SparseBitSet doneRels = new SparseBitSet();
	private SparseBitSet problemRels = new SparseBitSet();
	private SparseBitSet parentOnlyRels = new SparseBitSet();
	private SparseBitSet problemWays = new SparseBitSet();
	private SparseBitSet neededWays = new SparseBitSet();
	private SparseBitSet neededNodes = new SparseBitSet();
	private HashMap<Long, Area> wayAreaMap = new HashMap<Long, Area>();
	
	
	MultiTileProcessor(DataStorer dataStorer, LongArrayList problemWayList, LongArrayList problemRelList) {
		this.dataStorer = dataStorer;
		for (long id: problemWayList){
			problemWays.set(id);
			neededWays.set(id);
		}
		for (long id: problemRelList){
			problemRels.set(id);
		}
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
			if (neededNodes.get(node.getId())){
				long lat = 0xffffffffL & node.getMapLat();
				long lon = 0xffffffffL & node.getMapLon();
				long coord = (lat << 32) | lon;
				nodeCoords.put(node.getId(), coord);
				/*
				int lat2 = (int) (0xffffffff & (coord >>> 32));
				int lon2 = (int) (0xffffffff & coord);
				if (lat2 != node.getMapLat()  || lon2 != node.getMapLon()){
					long dd = 4;
				}
				*/
			}
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
			/*
			if (problemWays.get(way.getId())){
				System.out.println("way: " + way.getId() + " # ");
			}
			*/
			// save the node refs
			// problemWayNodes.put(way.getId(), way.getRefs());
			// calculate the bbox
			int numRefs = way.getRefs().size();
			boolean isClosed = numRefs > 1 &&  way.getRefs().get(0).equals(way.getRefs().get(numRefs-1));
			BitSet wayWriters;
			Area wayArea = getWayBbox(way.getId(), way.getRefs());
			wayAreaMap .put(way.getId(), wayArea);
			if (isClosed){
				wayWriters = checkBoundingBox(wayArea);
			}
			else {
				wayWriters = new BitSet();
				addWriters(wayWriters, way.getId(), way.getRefs());
			}
			if (!wayWriters.isEmpty()){
				int wayWriterIdx = dataStorer.getMultiTileWriterDictionary().translate(wayWriters);
				dataStorer.putWriterIdx(DataStorer.WAY_TYPE, way.getId(), wayWriterIdx);
				/*
				for (long id : way.getRefs()) {
					addOrMergeWriters(dataStorer.getSpecialNodeWriters(), wayWriters, wayWriterIdx, id);
				}
				 */
			}
		}
		if (pass == PASS4_WAYS_ONLY){
			// propagate the ways writers to all nodes 
			if (!neededWays.get(way.getId()))
				return;
			Integer wayWriterIdx = dataStorer.getWriterIdx(DataStorer.WAY_TYPE, way.getId());
			if (wayWriterIdx !=  null){
				BitSet writerSet = dataStorer.getMultiTileWriterDictionary().getBitSet(wayWriterIdx);
				for (long id : way.getRefs()) {
					addOrMergeWriters(DataStorer.NODE_TYPE, writerSet, wayWriterIdx, id);
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
		        Map.Entry<Long,Relation> pairs = (Map.Entry<Long,Relation>)it.next();
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
			System.out.println("starting to collect coordinates for " + neededNodes.cardinality() + " special nodes ");
			nodeCoords = new HashMap<Long, Long>(neededNodes.cardinality());
			return false;
		}
		
		if (pass == PASS3_NODES_AND_WAYS){
			neededNodes = null;
			
			calcWritersOfRelWaysAndNodes();
			// return memory to GC
			nodeCoords = null;
			calcWritersOfRels();
			mergeRelMemWriters();
			orWritersOfRelMembers();
			
			problemRels = null;
			problemWays = null;
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
			BitSet relWriters = null;
			alreadySearchedRels.clear();
			if (rel.isMultiPolygon){
				getSubRelAreas(rel, 0);
				Area relArea = relAreas.get(rel.getId());
				relWriters = checkBoundingBox(relArea);
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
			Area relArea = null;
			boolean isNotComplete = false;
			BitSet writerSet = new BitSet(); 
			for (Member mem: rel.getMembers()){
				long memId = mem.getRef();
				Area memArea = null;
				boolean memFound = false;
				if (mem.getType().equals("node")){
					Long coord = nodeCoords.get(memId);
					if (coord != null){
						int lat = (int) (0xffffffff & (coord >>> 32));
						int lon = (int) (0xffffffff & coord);
						addWriters(writerSet, lat, lon);
						memFound = true;
					}
				}
				else if (mem.getType().equals("way")){
					Integer idx = dataStorer.getWriterIdx(DataStorer.WAY_TYPE, memId);
					if (idx != null){
						writerSet.or(dataStorer.getMultiTileWriterDictionary().getBitSet(idx));
					}
					memArea = wayAreaMap.get(memId);
					if (memArea != null)
						memFound = true;
				}
				else if (mem.getType().equals("relation"))
					continue; // handled later
				if (!memFound) {
					isNotComplete = true;
					continue;
				}
				if (memArea == null)
					continue;
				
				if (relArea == null)
					relArea = new Area(memArea.getMinLat(), memArea.getMinLong(),
							memArea.getMaxLat(), memArea.getMaxLong());
				else 
					relArea = relArea.add(memArea);

			}
			
			relAreas.put(rel.getId(), relArea);
			if (!writerSet.isEmpty()){
				int idx = dataStorer.getMultiTileWriterDictionary().translate(writerSet);
				dataStorer.putWriterIdx(DataStorer.REL_TYPE, rel.getId(), idx);
				if (isNotComplete && parentOnlyRels.get(rel.getId()) == false)
					System.out.println("Sorry, data for relation " + rel.getId() + " is incomplete");
			}
		}

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
	private void getSubRelAreas(Relation rel, int depth){
		alreadySearchedRels.set(rel.getId());
		if (doneRels.get(rel.getId()))
			return ;
		if (depth > 15){
			System.out.println("getSubRelWriters reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		Area relArea = relAreas.get(rel.getId());
		boolean changed = false;
		for (Member mem : rel.getMembers()) {
			long memId = mem.getRef();
			
			if (mem.getType().equals("relation")) {
				if (alreadySearchedRels.get(memId)){
					System.out.println("loop in relation: " + rel.getId() + " (depth:" +  depth + ") subrel: " + memId );
				}
				else {
					// recursive search
					Relation subRel = relMap.get(memId);
					if (subRel != null){
						getSubRelAreas(subRel, depth+1);
						Area memArea = relAreas.get(mem.getRef());
						if (memArea == null)
							System.out.println("sorry, no nodes found for relation : " + mem.getRef() + " which is a sub-rel of " + rel.getId());
						else {
							if (relArea == null)
								relArea = new Area(memArea.getMinLat(), memArea.getMinLong(),
										memArea.getMaxLat(), memArea.getMaxLong());
							else
								relArea = relArea.add(memArea);
							changed = true;
						}
					}
				}
			} 
		}
		if (relArea == null)
			System.out.println("sorry, no nodes found for relation : " + rel.getId());
		else if (changed)
			relAreas.put(rel.getId(), relArea);
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
					System.out.println("loop in relation: " + rel.getId() + " (depth:" +  depth + ") subrel: " + memId );
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
			System.out.println("SparseBitSet multiTileRels " + problemRels.cardinality() + " (" + problemRels.bytes() + "  bytes)");
		if (neededWays != null)
			System.out.println("SparseBitSet neededWays " + neededWays.cardinality() + " (" + neededWays.bytes() + "  bytes)");
		if (neededNodes != null)
			System.out.println("SparseBitSet neededNodes " + neededNodes.cardinality() + " (" + neededNodes.bytes() + "  bytes)");
		System.out.println("Number of stored relations: " + relMap.size());
		if (pass == PASS4_WAYS_ONLY)
			dataStorer.stats();
	}

	/**
	 * Find all writer areas that intersect with a given bounding box. 
	 * @param polygonArea the bounding box 
	 * @return the set of writers
	 */
	private BitSet checkBoundingBox(Area polygonArea){
		BitSet writerSet = new BitSet();
		if (polygonArea != null){
			Rectangle polygonBbox = Utils.area2Rectangle(polygonArea, 0); 
			OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters();
			for (int i = 0; i < writers.length; i++) {
				Rectangle writerBbox = writers[i].getBBox();
				if (writerBbox.intersects(polygonBbox))
					writerSet.set(i);
			}
		}
		return writerSet;
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
	
	private void addWriters(BitSet writerSet, int mapLat, int mapLon){
		WriterGridResult writerCandidates = dataStorer.getGrid().get(mapLat,mapLon);
		if (writerCandidates == null)  
			return;
		
		OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters(); 
		for (int i = 0; i < writerCandidates.l.size(); i++) {
			int n = writerCandidates.l.get(i);
			OSMWriter w = writers[n];
			boolean found;
			if (writerCandidates.testNeeded){
				found = w.coordsBelongToThisArea(mapLat, mapLon);
			}
			else{ 
				found = true;
			}
			if (found) {
				writerSet.set(n);
			}
		}
		return ;
	}
	
	private void addWritersOfCrossedTiles(BitSet writerSet, Point p1,Point p2){
		OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters();
		Rectangle lineBbox = new Rectangle(p1); 
		lineBbox.add(p2);

		for (int i = 0; i < writers.length; i++) {
			OSMWriter w = writers[i];
			if (w.getBounds().contains(p1.y, p1.x))
				writerSet.set(i);
			else if (w.getBounds().contains(p2.y, p2.x))
				writerSet.set(i);
			else {
				Rectangle writerBbox = writers[i].getBBox();
				if (writerBbox.intersects(lineBbox) == false && writerBbox.intersectsLine(p1.x,p1.y,p2.x,p2.y)){
					long dd = 4;
				}
					
				if (writerBbox.intersects(lineBbox)){
					if (writerBbox.intersectsLine(p1.x,p1.y,p2.x,p2.y))
						writerSet.set(i);
				}
			}
		}
	}
	
	private void addWriters (BitSet writerSet, long wayId, LongArrayList wayRefs){
		int numRefs = wayRefs.size();
		int foundNodes = 0; 
		
		Point p1 = null,p2 = null;
		for (int i = 0; i<numRefs; i++) {
			long id = wayRefs.getLong(i);
			Long coord = nodeCoords.get(id);
			if (coord != null){
				foundNodes++;
				int lat = (int) (0xffffffff & (coord >>> 32));
				int lon = (int) (0xffffffff & coord);
				addWriters(writerSet, lat, lon);
			}
		}
		if (writerSet.cardinality() > 1){
			// TODO: add simple check if the set of writer areas forms a rectangle
			// if yes, no need to check for crossing lines
			for (int i = 0; i<numRefs; i++) {
				long id = wayRefs.getLong(i);
				Long coord = nodeCoords.get(id);
				if (coord != null){
					int lat = (int) (0xffffffff & (coord >>> 32));
					int lon = (int) (0xffffffff & coord);
					if (i > 0){
						p1 = p2;
					}
					p2 = new Point(lon,lat);

					if (p1 != null){
						addWritersOfCrossedTiles(writerSet, p1, p2);
					}
				}
			}
		}
		if (foundNodes < numRefs)
			System.out.println("Sorry, way " + wayId + " is missing " +  (numRefs-foundNodes) + " nodes.");
	}
	
	private Area getWayBbox (long wayId, LongArrayList wayRefs){
		// calculate the bbox
		int minLat = Integer.MAX_VALUE,minLon = Integer.MAX_VALUE;
		int maxLat = Integer.MIN_VALUE,maxLon = Integer.MIN_VALUE;
		int numRefs = wayRefs.size();
		for (int i = 0; i<numRefs; i++) {
			long id = wayRefs.getLong(i);
			Long coord = nodeCoords.get(id);
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
		
		return new Area(minLat, minLon, maxLat, maxLon);
	}
	
	private void markProblemMembers() {
		doneRels.clear();
		for (Relation rel: relMap.values()){
			if (!problemRels.get(rel.getId()))
				continue;
			alreadySearchedRels.clear();
			MarkNeededMembers(rel, 0);
		}
	}

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


