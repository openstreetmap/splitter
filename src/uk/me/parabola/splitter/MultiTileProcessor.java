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

import uk.me.parabola.splitter.Element.Tag;
import uk.me.parabola.splitter.Relation.Member;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.awt.geom.Rectangle2D;
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

	private int pass = PASS1_RELS_ONLY;
	private final DataStorer dataStorer;
	private final HashMap<Long, Relation> relMap = new HashMap<Long, Relation>();
	private final HashMap<Long, LongArrayList> problemWayNodes = new HashMap<Long, LongArrayList>();
	private final HashMap<Long, Area> wayAreas = new HashMap<Long, Area>();
	private final HashMap<Long, Area> relAreas = new HashMap<Long, Area>();
	private HashMap<Long, Long> nodeCoords = new HashMap<Long, Long>(); 
	private final SparseBitSet alreadySearchedRels = new SparseBitSet();
	private final SparseBitSet doneRels = new SparseBitSet();
	private SparseBitSet problemRels = new SparseBitSet();
	private SparseBitSet problemWays = new SparseBitSet();
	private SparseBitSet neededWays = new SparseBitSet();
	private SparseBitSet neededNodes = new SparseBitSet();
	
	
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
			// save the nodes
			problemWayNodes.put(way.getId(), way.getRefs());
			// calculate the bbox
			int minLat = Integer.MAX_VALUE,minLon = Integer.MAX_VALUE;
			int maxLat = Integer.MIN_VALUE,maxLon = Integer.MIN_VALUE;
			for (long id : way.getRefs()) {
				Long coord = nodeCoords.get(id);
				if (coord != null){
					int lat = (int) (0xffffffff & (coord >>> 32));
					int lon = (int) (0xffffffff & coord);
					if (lat < minLat) minLat = lat;
					if (lat > maxLat) maxLat = lat;
					if (lon < minLon) minLon = lon;
					if (lon > maxLon) maxLon = lon;
				}
				else 
					System.out.println("sorry, node " + id + " for needed way " + way.getId() + " is missing ");
			}
			if (maxLon == Integer.MIN_VALUE|| maxLat == Integer.MIN_VALUE){
				System.out.println("sorry, no nodes found for needed way " + way.getId());
				return;
			}
			Area wayArea = new Area(minLat, minLon, maxLat, maxLon);
			wayAreas.put(way.getId(), wayArea);
			if (problemWays.get(way.getId())){
				BitSet wayWriters = checkBoundingBox(wayArea);
				if (!wayWriters.isEmpty()){
					int wayWriterIdx = dataStorer.getMultiTileWriterDictionary().translate(wayWriters);
					dataStorer.getProblemWayWriters().put(way.getId(), wayWriterIdx);
					for (long id : way.getRefs()) {
						addOrMergeWriters(dataStorer.getSpecialNodeWriters(), wayWriters, wayWriterIdx, id);
					}
				}
			}
		}
	}
	
	@Override
	public void processRelation(Relation rel) {
		if (pass == PASS1_RELS_ONLY){
			// store full info
			relMap.put(rel.getId(), rel);
			/*
			if (problemRels.get(rel.getId()))
				return;
			
			if (testIfProblemPolygon(rel.tagsIterator())){
				if (rel.getMembers().size() > 1){
					System.out.println("adding as problem rel: " + rel.getId() + " # ferry ");
					problemRels.set(rel.getId());
				}

			}
			*/
		}
		
	}
	
	@Override
	public boolean endMap() {
		if (pass == PASS1_RELS_ONLY){
			stats("endMap start");
			System.out.println("starting to resolve sub-relations of problem relations");
			doneRels.clear();
			for (Relation rel: relMap.values()){
				if (!problemRels.get(rel.getId()))
					continue;
				alreadySearchedRels.clear();
				markSubRels(rel, 0);
			}
			// free memory for rels that are not causing trouble
			Iterator<Entry<Long, Relation>> it = relMap.entrySet().iterator();
		    while (it.hasNext()) {
		        Map.Entry<Long,Relation> pairs = (Map.Entry<Long,Relation>)it.next();
		        if (!problemRels.get(pairs.getKey())){
		        	it.remove(); // avoids a ConcurrentModificationException
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
			return false;
		}
		
		if (pass == PASS3_NODES_AND_WAYS){
			for (Relation rel: relMap.values()){
				// calculate the bbox of the relation using the node and way members
				Area relArea = null;
				boolean isNotComplete = false;
				for (Member mem: rel.getMembers()){
					Area memArea = null;
					if (mem.getType().equals("node")){
						// TODO: probably we should not add nodes to the area,
						// instead we should or-combine the writes 
						Long coord = nodeCoords.get(mem.getRef());
						if (coord != null){
							int lat = (int) (0xffffffff & (coord >>> 32));
							int lon = (int) (0xffffffff & coord);
							memArea = new Area(lat,lon,lat,lon);
						}
					}
					else if (mem.getType().equals("way"))
						memArea = wayAreas.get(mem.getRef());
					else if (mem.getType().equals("relation"))
						continue; // handled later
					if (memArea == null){
						isNotComplete = true;
						continue;
					}
					if (isNotComplete)
						System.out.println("Sorry, data for relation " + rel.getId() + " is incomplete");

					if (relArea == null)
						relArea = new Area(memArea.getMinLat(), memArea.getMinLong(),
								memArea.getMaxLat(), memArea.getMaxLong());
					else 
						relArea = relArea.add(memArea);

				}
				relAreas.put(rel.getId(), relArea);

			}
			// return memory to GC
			nodeCoords = null;

			// recurse thru sub relations
			doneRels.clear();
			for (Relation rel: relMap.values()){
				alreadySearchedRels.clear();
				getSubRelWriters(rel, 0);
				assert doneRels.get(rel.getId());
				// now we know the bounding box of the relation, so we can calculate the tiles
				// this is far away from being precise, but very fast
				Area relArea = relAreas.get(rel.getId());
				BitSet relWriters = checkBoundingBox(relArea);
				if (!relWriters.isEmpty()){
					int writerIdx = dataStorer.getMultiTileWriterDictionary().translate(relWriters);
					dataStorer.getRelWriters().put(rel.getId(), writerIdx);
				}
			}
			// or combine the writers of sub-relations with the parent relation 
			doneRels.clear();
			for (Relation rel: relMap.values()){
				alreadySearchedRels.clear();
				orSubRelWriters(rel, 0);
			}
			// make sure that the ways and nodes of the problem relations are written to all needed tiles
			for (Relation rel: relMap.values()){
				if (!problemRels.get(rel.getId()))
					continue;

				int relWriterIdx = dataStorer.getRelWriters().get(rel.getId());
				BitSet relWriters =  dataStorer.getMultiTileWriterDictionary().getBitSet(relWriterIdx);
				for (Member mem: rel.getMembers()){
					if (mem.getType().equals("way")){
						addOrMergeWriters(dataStorer.getProblemWayWriters(), relWriters, relWriterIdx, mem.getRef());
						LongArrayList nodes = problemWayNodes.get(mem.getRef());
						if (nodes != null){
							for (long nodeId: nodes){
								addOrMergeWriters(dataStorer.getSpecialNodeWriters(), relWriters, relWriterIdx, nodeId);
							}
						}
						
					}
					else if (mem.getType().equals("node")){
						addOrMergeWriters(dataStorer.getSpecialNodeWriters(), relWriters, relWriterIdx, mem.getRef());
					}
				}
			}
			stats("endMap Pass3 end");
			++pass;
		}
		return true;
	}

	/**
	 * If a relation contains relations, mark all members of it as problem cases. The routine calls 
	 * itself recursively when the sub relation contains sub relations. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void markSubRels(Relation rel, int depth){
		
		alreadySearchedRels.set(rel.getId());
		if (doneRels.get(rel.getId()))
			return ;
		if (depth > 15){
			System.out.println("markSubRels reached max. depth: " + rel.getId() + " " +  depth);
			return ;
		}
		for (Member mem : rel.getMembers()) {
			// String role = mem.getRole();
			long memId = mem.getRef();
			if (mem.getType().equals("way"))
				neededWays.set(memId);
			else if (mem.getType().equals("node"))
				neededNodes.set(memId);
			else if (mem.getType().equals("relation")) {
				if (alreadySearchedRels.get(memId)){
					System.out.println("loop in relation: " + rel.getId() + " " +  depth + " " + memId );
				}
				else {
					// recursive search
					Relation subRel = relMap.get(memId);
					if (subRel != null){
						problemRels.set(memId);
						markSubRels(subRel, depth+1);
					}
				}
			} 
		}
		doneRels.set(rel.getId());
	}


	/**
	 * If a relation contains relations, mark all members of it as problem cases. The routine calls 
	 * itself recursively when the sub relation contains sub relations. 
	 * @param rel the relation 
	 * @param depth used to detect loops 
	 * @return
	 */
	private void getSubRelWriters(Relation rel, int depth){
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
					System.out.println("loop in relation: " + rel.getId() + " " +  depth + " " + memId );
				}
				else {
					// recursive search
					Relation subRel = relMap.get(memId);
					if (subRel != null){
						getSubRelWriters(subRel, depth+1);
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
		Integer relWriterIdx = dataStorer.getRelWriters().get(rel.getId());
		BitSet relWriters = new BitSet();
		if (relWriterIdx != null)
		   relWriters.or(dataStorer.getMultiTileWriterDictionary().getBitSet(relWriterIdx));
		
		boolean changed = false;
		for (Member mem : rel.getMembers()) {
			long memId = mem.getRef();
			
			if (mem.getType().equals("relation")) {
				if (alreadySearchedRels.get(memId)){
					System.out.println("loop in relation: " + rel.getId() + " " +  depth + " " + memId );
				}
				else {
					// recursive search
					Relation subRel = relMap.get(memId);
					if (subRel != null){
						orSubRelWriters(subRel, depth+1);
						Integer memWriterIdx = dataStorer.getRelWriters().get(memId);
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
			dataStorer.getRelWriters().put(rel.getId(), relWriterIdx);
		}
		doneRels.set(rel.getId());
	}


	/**
	 * Report some numbers regarding memory usage 
	 * @param msg
	 */
	private void stats(String msg){
		System.out.println("Stats for MultiTileProcessor pass " + pass + " " + msg);
		if (pass == PASS3_NODES_AND_WAYS)
			dataStorer.stats();
		System.out.println("SparseBitSet multiTileRels " + problemRels.cardinality() + " (" + problemRels.bytes() + "  bytes)");
		System.out.println("SparseBitSet neededWays " + neededWays.cardinality() + " (" + neededWays.bytes() + "  bytes)");
		System.out.println("SparseBitSet neededNodes " + neededNodes.cardinality() + " (" + neededNodes.bytes() + "  bytes)");
		System.out.println("Number of stored relations: " + relMap.size());
	}

	/**
	 * Find all writer areas that intersect with a given bounding box. 
	 * @param area the bounding box 
	 * @return the set of writers
	 */
	private BitSet checkBoundingBox(Area area){
		BitSet res = new BitSet();
		if (area != null){
			Rectangle2D bbox = new Rectangle2D.Float(area.getMinLong(), area.getMinLat(),
					area.getWidth(), area.getHeight());
			OSMWriter[] writers = dataStorer.getWriterDictionary().getWriters();
			for (int i = 0; i < writers.length; i++) {
				Area wArea = writers[i].getBounds(); // bounds or extendedBounds? 
				Rectangle2D testBox = new Rectangle2D.Float(wArea.getMinLong(),wArea.getMinLat(),  
						wArea.getWidth(), wArea.getHeight());
				if (bbox.intersects(testBox))
					res.set(i);
			}
		}
		return res;
	}

	/**
	 * Merge the writers of a parent object with the writes of the child, 
	 * add or update the entry in the Map
	 * @param childWriterMap
	 * @param parentWriters
	 * @param parentWriterIdx
	 * @param childId
	 */
	private void addOrMergeWriters(Map<Long, Integer> childWriterMap,
			BitSet parentWriters, int parentWriterIdx, long childId) {
		
		Integer childWriterIdx = childWriterMap.get(childId);
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
		childWriterMap.put(childId, childWriterIdx);
	}
	
	private boolean testIfProblemPolygon(Iterator<Tag> tags){
		boolean isMulti = false;
		boolean isLake = false;
		boolean isFerry = false;
		
		while(tags.hasNext()) {
			Element.Tag t = tags.next();
			if ("type".equals(t.key) && "multipolygon".equals(t.value))
				isMulti = true;
			
			if ("natural".equals(t.key)&& "water".equals(t.value))
				isLake= true;
			if ("route".equals(t.key)&& "ferry".equals(t.value))
				isFerry= true;
		}
		
		return (isFerry);
	}
}


