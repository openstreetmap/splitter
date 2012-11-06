/*
 * Copyright (c) 2012.
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

import it.unimi.dsi.Util;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.awt.Rectangle;
import java.util.BitSet;

/**
 * Find ways and relations that will be incomplete.
 * Strategy:
 * - calculate the writers of each node, calculate and store a short that represents the combination of writers 
 *    (this is done by the WriterDictionary)  
 * - a way is incomplete (in at least one tile) if its nodes are written to different combinations of writers
 * - a relation is incomplete (in at least one tile) if its members are written to different combinations of writers
 * 
 * TODO: find out how to handle nodes that are not written to any tile (ways and rels referring to them are problem cases)
 * TODO: find an appropriate data structure to store the needed info even if planet is used as input and split-file is used to
 * specify a few areas.
 */
class ProblemListProcessor extends AbstractMapProcessor {
	private final OSMWriter[] writers;

	private SparseLong2ShortMapFunction coords;
	private SparseLong2ShortMapFunction ways;
	
	private final WriterDictionaryShort writerDictionary;
	private final DataStorer dataStorer;
	private LongArrayList problemWays; 
	private LongArrayList problemRels;
	private BitSet writerSet;
	
	//	for statistics
	//private long countQuickTest = 0;
	//private long countFullTest = 0;
	private long countCoords = 0;
	private boolean isFirstPass;
	private boolean isLastPass;
	private WriterGrid grid;

	private Rectangle realWriterBbox;
	
	ProblemListProcessor(DataStorer dataStorer,
			int writerOffset, int numWritersThisPass, LongArrayList problemWays, LongArrayList problemRels) {
		this.dataStorer = dataStorer;
		this.writerDictionary = dataStorer.getWriterDictionary();
		if (dataStorer.getUsedWays() == null){
			ways = new SparseLong2ShortMapInline();
			ways.defaultReturnValue(UNASSIGNED);
			dataStorer.setUsedWays(ways);
		}
		else 
			ways = dataStorer.getUsedWays(); 
		this.writers = writerDictionary.getWriters();
		//this.ways = dataStorer.getWays();
		
		writerSet = new BitSet(writerDictionary.getNumOfWriters());
		this.grid = dataStorer.getGrid();
		this.coords = new SparseLong2ShortMapInline();
		this.coords.defaultReturnValue(UNASSIGNED);
		this.isFirstPass = (writerOffset == 0);
		this.isLastPass = (writerOffset + numWritersThisPass == writers.length);
		this.problemWays = problemWays;
		this.problemRels = problemRels;
		this.realWriterBbox = Utils.area2Rectangle(grid.getBounds(), 0);
	}
	
	
	@Override
	public boolean skipTags() {
		return true;
	}

	@Override
	public void processNode(Node node) {
		int countWriters = 0;
		short lastUsedWriter = UNASSIGNED;
		short writerIdx = UNASSIGNED;
		WriterGridResult writerCandidates = grid.getWithOuter(node);
		if (writerCandidates == null) 
			return;
		
		if (writerCandidates.l.size() > 1)
			writerSet.clear();
		for (int i = 0; i < writerCandidates.l.size(); i++) {
			int n = writerCandidates.l.get(i);
			boolean found;
			if (writerCandidates.testNeeded){
				OSMWriter w = writers[n];
				found = w.nodeBelongsToThisArea(node);
				//++countFullTest;
			}
			else{ 
				found = true;
				//++countQuickTest;
			}
			if (found) {
				writerSet.set(n);
				++countWriters;
				lastUsedWriter = (short) n;
			}
		}
		if (countWriters > 0){
			if (countWriters > 1)
				writerIdx = writerDictionary.translate(writerSet);
			else  
				writerIdx = (short) (lastUsedWriter  - WriterDictionaryShort.DICT_START); // no need to do lookup in the dictionary 
			coords.put(node.getId(), writerIdx);
			++countCoords;
			if (countCoords % 1000000 == 0){
				System.out.println("MAP occupancy: " + Utils.format(countCoords) + ", number of area dictionary entries: " + writerDictionary.size() + " of " + ((1<<16) - 1));
				coords.stats(0);
			}
		}
	}
	
	@Override
	public void processWay(Way way) {
		int oldclIndex = UNASSIGNED;
		short wayWriterIdx; 
		//BitSet wayNodeWriterCombis = new BitSet();
		writerSet.clear();
		if (!isFirstPass){
			wayWriterIdx = ways.get(way.getId());
			if (wayWriterIdx != UNASSIGNED)
				writerSet.or(writerDictionary.getBitSet(wayWriterIdx));
		}
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
				//wayNodeWriterCombis.set(clIdx + WriterDictionaryShort.DICT_START);
				BitSet cl = writerDictionary.getBitSet(clIdx);
				writerSet.or(cl);
				oldclIndex = clIdx;
			}
		}
		if (isLastPass){
			if (checkWriters(writerSet))
				problemWays.add(way.getId());
		}
		if (writerSet.isEmpty() == false){
			wayWriterIdx = writerDictionary.translate(writerSet);
			ways.put(way.getId(), wayWriterIdx);
		}
	}
	
	@Override
	public void processRelation(Relation rel) {
		//BitSet writerSet = new BitSet();
		writerSet.clear();
		Integer relWriterIdx;
		if (!isFirstPass){
			relWriterIdx = dataStorer.getUsedRels().get(rel.getId());
			if (relWriterIdx != null)
				writerSet.or(dataStorer.getMultiTileWriterDictionary().getBitSet(relWriterIdx));
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
						BitSet wl = writerDictionary.getBitSet(clIdx);
						writerSet.or(wl);
					}
					oldclIndex = clIdx;

				}

			} else if (mem.getType().equals("way")) {
				short wlIdx = ways.get(id);

				if (wlIdx != UNASSIGNED){
					if (oldwlIndex != wlIdx){ 
						BitSet wl = writerDictionary.getBitSet(wlIdx);
						writerSet.or(wl);
					}
					oldwlIndex = wlIdx;
				}
			}
			// ignore relation here
		}
		if (writerSet.isEmpty())
			return;
		if (isLastPass){
			if (checkWriters(writerSet))
				problemRels.add(rel.getId());
			return;
		}
		
		relWriterIdx = dataStorer.getMultiTileWriterDictionary().translate(writerSet);
		dataStorer.getUsedRels().put(rel.getId(), relWriterIdx);
	}
	
	
	@Override
	public boolean endMap() {
		System.out.println("Statistics for coords map:");
		coords.stats(1);
		System.out.println("Statistics for extended ways map:");
		ways.stats(1);
		if (isLastPass){
			System.out.println("");
			System.out.println("Number of stored integers for ways: " + Util.format(dataStorer.getUsedWays().size()));
			System.out.println("Number of stored integers for rels: " + Util.format(dataStorer.getUsedRels().size()));
			System.out.println("Number of stored combis in big dictionary: " + Util.format(dataStorer.getMultiTileWriterDictionary().size()));
			System.out.println("Number of detected problem ways: " + Util.format(problemWays.size()));
			System.out.println("Number of detected problem rels: " + Util.format(problemRels.size()));
			dataStorer.getUsedWays().clear();
			dataStorer.getUsedRels().clear();
		}
		return true;
	}
	
	/** 
	 * 
	 * @param writerCombis
	 * @return true if the combination of writers can contain a problem polygon
	 */
	boolean checkWriters(BitSet writerCombis){

		if (writerCombis.cardinality() <= 1)
			return false; // only one writer: not a problem case
		Rectangle bbox = null;
		for (int i = writerSet.nextSetBit(0); i >= 0; i = writerSet.nextSetBit(i+1)){
			if (writers[i].getMapId() >= 0)  
				return true; // multiple writers with a real writer area: problem case 
			
			Rectangle writerBbox = Utils.area2Rectangle(writers[i].getBounds(), 0);
			if (bbox == null)
				bbox = writerBbox;
			else 
				bbox.add(writerBbox);
		}
		// TODO: make sure that we detect two boxes that share exactly the same line
		if (bbox.intersects(realWriterBbox))
			return true;
		return false;
	}
}
