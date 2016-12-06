/*
 * Copyright (c) 2009, Steve Ratcliffe
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
import uk.me.parabola.splitter.args.SplitterParams;
import uk.me.parabola.splitter.tools.Long2IntClosedMapFunction;
import uk.me.parabola.splitter.tools.SparseLong2IntMap;
import uk.me.parabola.splitter.tools.SparseLong2ShortMapFunction;
import uk.me.parabola.splitter.tools.SparseLong2ShortMapInline;
import uk.me.parabola.splitter.writer.OSMWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Splits a map into multiple areas.
 */
class SplitProcessor extends AbstractMapProcessor {
	private final OSMWriter[] writers;

	private SparseLong2ShortMapFunction coords;
	private SparseLong2IntMap ways; 	
	private final AreaDictionaryShort writerDictionary;
	private final AreaDictionaryInt multiTileDictionary;
	private final DataStorer dataStorer;
	private final Long2IntClosedMapFunction nodeWriterMap;
	private final Long2IntClosedMapFunction wayWriterMap;
	private final Long2IntClosedMapFunction relWriterMap;

	//	for statistics
	private long countQuickTest = 0;
	private long countFullTest = 0;
	private long countCoords = 0;
	private long countWays = 0;
	private final int writerOffset;
	private final int lastWriter;
	private final AreaIndex writerIndex;
	private final int maxThreads;
	private final short unassigned = Short.MIN_VALUE;

	private final InputQueueInfo[] writerInputQueues;
	protected final BlockingQueue<InputQueueInfo> toProcess;
	private final ArrayList<Thread> workerThreads;
	protected final InputQueueInfo STOP_MSG = new InputQueueInfo(null);

	private BitSet usedWriters;
	
	/**
	 * Distribute the OSM data to separate OSM files. 
	 * @param dataStorer 
	 * @param writerOffset first writer to be used
	 * @param numWritersThisPass number of writers to used
	 * @param mainOptions main program options
	 */
	SplitProcessor(DataStorer dataStorer, int writerOffset, int numWritersThisPass, SplitterParams mainOptions){
		this.dataStorer = dataStorer;
		this.writerDictionary = dataStorer.getAreaDictionary();
		this.multiTileDictionary = dataStorer.getMultiTileDictionary();
		this.writers = dataStorer.getWriters();
		this.coords = new SparseLong2ShortMapInline("coord");
		this.ways   = new SparseLong2IntMap("way");
		this.coords.defaultReturnValue(unassigned);
		this.ways.defaultReturnValue(unassigned); 		
		this.writerIndex = dataStorer.getGrid();
		this.countWays = ways.size();
		this.writerOffset = writerOffset;
		this.lastWriter = writerOffset + numWritersThisPass-1;
		this.maxThreads = mainOptions.getMaxThreads().getCount();
		this.toProcess = new ArrayBlockingQueue<>(numWritersThisPass);
		this.writerInputQueues = new InputQueueInfo[numWritersThisPass];
		for (int i = 0; i < writerInputQueues.length; i++) {
			writerInputQueues[i] = new InputQueueInfo(this.writers[i + writerOffset]);
			writers[i + writerOffset].initForWrite(); 
		}
		nodeWriterMap = dataStorer.getWriterMap(DataStorer.NODE_TYPE);
		wayWriterMap = dataStorer.getWriterMap(DataStorer.WAY_TYPE);
		relWriterMap = dataStorer.getWriterMap(DataStorer.REL_TYPE);
		usedWriters = new BitSet(); 

		int noOfWorkerThreads = Math.min(this.maxThreads - 1, numWritersThisPass);
		workerThreads = new ArrayList<>(noOfWorkerThreads);
		for (int i = 0; i < noOfWorkerThreads; i++) {
			Thread worker = new Thread(new OSMWriterWorker());
			worker.setName("worker-" + i);
			workerThreads.add(worker);
			worker.start();
		}
		
	} 

	/**
	 * Get the active writers associated to the index  
	 * @param multiTileWriterIdx
	 */
	private void setUsedWriters(int multiTileWriterIdx) {
		if (multiTileWriterIdx != AreaDictionaryInt.UNASSIGNED) {
			BitSet cl = multiTileDictionary.getBitSet(multiTileWriterIdx);
			// set only active writer bits
			for (int i = cl.nextSetBit(writerOffset); i >= 0 && i <= lastWriter; i = cl.nextSetBit(i + 1)) {
				usedWriters.set(i);
			}
		}
	}
	
	
	@Override
	public void processNode(Node n) {
		try {
			writeNode(n);
		} catch (IOException e) {
			throw new SplitFailedException("failed to write node " + n.getId(), e);
		}
	}

	@Override
	public void processWay(Way w) {
		usedWriters.clear();
		int multiTileWriterIdx = (wayWriterMap != null) ? wayWriterMap.getSeq(w.getId()): AreaDictionaryInt.UNASSIGNED;
		if (multiTileWriterIdx != AreaDictionaryInt.UNASSIGNED){
			setUsedWriters(multiTileWriterIdx);
		}
		else{
			short oldclIndex = unassigned;
			for (long id : w.getRefs()) {
				// Get the list of areas that the way is in. 
				short clIdx = coords.get(id);
				if (clIdx != unassigned){
					if (oldclIndex != clIdx){ 
						BitSet cl = writerDictionary.getBitSet(clIdx);
						usedWriters.or(cl);
						if (wayWriterMap != null){
							// we can stop here because all other nodes
							// will be in the same tile
							break;
						}
						oldclIndex = clIdx;
					}
				}
			}
		}
		if (!usedWriters.isEmpty()){
			// store these areas in ways map
			ways.put(w.getId(), multiTileDictionary.translate(usedWriters));
			++countWays;
			if (countWays % 1000000 == 0){
				System.out.println("  Number of stored tile combinations in multiTileDictionary: " + Utils.format(multiTileDictionary.size()));
				ways.stats(0);
			}
			try {
				writeWay(w);
			} catch (IOException e) {
				throw new SplitFailedException("failed to write way " + w.getId(), e);
			}
		}
	}

	@Override
	public void processRelation(Relation rel) {
		usedWriters.clear();
		Integer singleTileWriterIdx = dataStorer.getOneTileOnlyRels(rel.getId());
		if (singleTileWriterIdx != null){
			if (singleTileWriterIdx == AreaDictionaryInt.UNASSIGNED) {
			    // we know that the relation is outside of all real areas 
				return;
			}
			// relation is within an area that is overlapped by the writer areas
			setUsedWriters(singleTileWriterIdx);
		} else {
			int multiTileWriterIdx = (relWriterMap != null) ? relWriterMap.getSeq(rel.getId())
					: AreaDictionaryInt.UNASSIGNED;
			if (multiTileWriterIdx != AreaDictionaryInt.UNASSIGNED) {
				setUsedWriters(multiTileWriterIdx);
			} else{
				short oldclIndex = unassigned;
				int oldwlIndex = unassigned;
				for (Member mem : rel.getMembers()) {
					// String role = mem.getRole();
					long id = mem.getRef();
					if (mem.getType().equals("node")) {
						short clIdx = coords.get(id);

						if (clIdx != unassigned){
							if (oldclIndex != clIdx){ 
								BitSet wl = writerDictionary.getBitSet(clIdx);
								usedWriters.or(wl);
							}
							oldclIndex = clIdx;
						}
					} else if (mem.getType().equals("way")) {
						int wlIdx = ways.get(id);

						if (wlIdx != unassigned){
							if (oldwlIndex != wlIdx){ 
								BitSet wl = multiTileDictionary.getBitSet(wlIdx);
								usedWriters.or(wl);
							}
							oldwlIndex = wlIdx;
						}
					}
				}
			}
		}
		try {
			writeRelation(rel);
		} catch (IOException e) {
			throw new SplitFailedException("failed to write relation " + rel.getId(), e);
		}
	}
	@Override
	public boolean endMap() {
		coords.stats(0);
		ways.stats(0);
		Utils.printMem();
		System.out.println("Full Node tests:  " + Utils.format(countFullTest));
		System.out.println("Quick Node tests: " + Utils.format(countQuickTest)); 		
		coords = null;
		ways = null;

		for (int i = 0; i < writerInputQueues.length; i++) {
			try {
				writerInputQueues[i].stop();
			} catch (InterruptedException e) {
				throw new SplitFailedException(
						"Failed to add the stop element for worker thread " + i,
						e);
			}
		}
		try {
			if (maxThreads > 1)
				toProcess.put(STOP_MSG);// Magic flag used to indicate that all data is done.

		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}

		for (Thread workerThread : workerThreads) {
			try {
				workerThread.join();
			} catch (InterruptedException e) {
				throw new SplitFailedException("Failed to join for thread "
						+ workerThread.getName(), e);
			}
		}
		for (int i=writerOffset; i<= lastWriter; i++) {
			writers[i].finishWrite();
		}
		return true; 		
	}

	private void writeNode(Node currentNode) throws IOException {
		int countWriters = 0;
		int lastUsedWriter = unassigned;
		AreaGridResult writerCandidates = writerIndex.get(currentNode);
		int multiTileWriterIdx = (nodeWriterMap != null) ? nodeWriterMap.getSeq(currentNode.getId()): AreaDictionaryInt.UNASSIGNED;

		boolean isSpecialNode = (multiTileWriterIdx != AreaDictionaryInt.UNASSIGNED);
		if (writerCandidates == null && !isSpecialNode)  {
			return;
		}
		if (isSpecialNode || writerCandidates != null && writerCandidates.l.size() > 1)
			usedWriters.clear();
		if (writerCandidates != null){
			for (int i = 0; i < writerCandidates.l.size(); i++) {
				int n = writerCandidates.l.getShort(i);
				if (n < writerOffset || n > lastWriter)
					continue;
				OSMWriter writer = writers[n];
				boolean found;
				if (writerCandidates.testNeeded){
					found = writer.getExtendedBounds().contains(currentNode);
					++countFullTest;
				}
				else{ 
					found = true;
					++countQuickTest;
				}
				if (found) {
					usedWriters.set(n);
					++countWriters;
					lastUsedWriter = n;
					if (maxThreads > 1) {
						addToWorkingQueue(n, currentNode);
					} else {
						writer.write(currentNode);
					}
				}
			}
		}
		if (isSpecialNode){
			// this node is part of a multi-tile-polygon, add it to all tiles covered by the parent 
			BitSet nodeWriters = multiTileDictionary.getBitSet(multiTileWriterIdx);
			for(int i=nodeWriters.nextSetBit(writerOffset); i>=0 && i <= lastWriter; i=nodeWriters.nextSetBit(i+1)){
				if (usedWriters.get(i) )
					continue;
				if (maxThreads > 1) {
					addToWorkingQueue(i, currentNode);
				} else {
					writers[i].write(currentNode);
				}
			}
		}
		
		if (countWriters > 0){
			short writersID;
			if (countWriters > 1)
				writersID = writerDictionary.translate(usedWriters);
			else  
				writersID = AreaDictionaryShort.translate(lastUsedWriter); // no need to do lookup in the dictionary
			coords.put(currentNode.getId(), writersID);
			++countCoords;
			if (countCoords % 10000000 == 0){
				System.out.println("coord MAP occupancy: " + Utils.format(countCoords) + ", number of area dictionary entries: " + writerDictionary.size() + " of " + ((1<<16) - 1));
				coords.stats(0);
			}
		}
	}

	private boolean seenWay;

	private void writeWay(Way currentWay) throws IOException {
		if (!seenWay) {
			seenWay = true;
			System.out.println("Writing ways " + new Date());
		}
		writeElement(currentWay, usedWriters);
	}

	private boolean seenRel;

	private void writeRelation(Relation currentRelation) throws IOException {
		if (!seenRel) {
			seenRel = true;
			System.out.println("Writing relations " + new Date());
		}
		writeElement(currentRelation, usedWriters);
	}

	private void writeElement (Element el, BitSet writersToUse) throws IOException {
		if (!writersToUse.isEmpty()) {
			for (int n = writersToUse.nextSetBit(0); n >= 0; n = writersToUse.nextSetBit(n + 1)) {
				if (maxThreads > 1) {
					addToWorkingQueue(n, el);
				} else {
					writers[n].write(el);
				}
			}
		}
	}
	
	private void addToWorkingQueue(int writerNumber, Element element) {
		try {
			writerInputQueues[writerNumber-writerOffset].put(element);
		} catch (InterruptedException e) {
			throw new SplitFailedException("Failed to add to working queue", e);
		}
	}

	private class InputQueueInfo {
		protected final OSMWriter writer;
		private ArrayList<Element> staging;
		protected final BlockingQueue<ArrayList<Element>> inputQueue;

		public InputQueueInfo(OSMWriter writer) {
			inputQueue =  new ArrayBlockingQueue<>(NO_ELEMENTS);
			this.writer = writer;
			this.staging = new ArrayList<>(STAGING_SIZE);
		}

		void put(Element e) throws InterruptedException {
			staging.add(e);
			if (staging.size() >= STAGING_SIZE)
				flush();
		}

		void flush() throws InterruptedException {
			// System.out.println("Flush");
			inputQueue.put(staging);
			staging = new ArrayList<>(STAGING_SIZE);
			toProcess.put(this);
		}

		void stop() throws InterruptedException {
			flush();
		}
	}

	public static final int NO_ELEMENTS = 3;
	final int STAGING_SIZE = 300;

	private class OSMWriterWorker implements Runnable {

		public OSMWriterWorker() {
		}

		@Override
		public void run() {
			boolean finished = false;
			while (!finished) {
				InputQueueInfo workPackage = null;
				try {
					workPackage = toProcess.take();
				} catch (InterruptedException e1) {
					e1.printStackTrace();
					continue;
				}
				if (workPackage == STOP_MSG) {
					try {
						toProcess.put(STOP_MSG); // Re-inject it so that other
						// threads know that we're
						// exiting.
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					finished = true;
				} else {
					synchronized (workPackage) {
						while (!workPackage.inputQueue.isEmpty()) {
							ArrayList<Element> elements = null;
							try {
								elements = workPackage.inputQueue.poll();
								for (Element element : elements) {
									workPackage.writer.write(element);
								}
							} catch (IOException e) {
								throw new SplitFailedException("Thread "
										+ Thread.currentThread().getName()
										+ " failed to write element ", e);
							}
						}
					}

				}
			}
			System.out.println("Thread " + Thread.currentThread().getName()
					+ " has finished");
		}
	}

}
