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

import java.util.BitSet;

/**
 * A grid that covers the area covered by all writers. Each grid element contains 
 * information about the tiles that are intersecting the grid element and whether 
 * the grid element lies completely within such a tile area.
 * This is used to minimize the needed tests when analyzing coordinates of node coordinates. 
 * @author GerdP
 *
 */
public class WriterGrid{
	private final static int gridDimLon = 512; 
	private final static int gridDimLat = 512; 
	private int gridDivLon, gridDivLat;
	private int gridMinLat, gridMinLon; 
	// bounds of the complete grid
	private Area bounds;
	private short [][] grid;
	private boolean [][] gridTest;
	private final WriterGridResult r;
	private final WriterDictionaryShort writerDictionary;

	WriterGrid(WriterDictionaryShort writerDictionary){
		this.writerDictionary = writerDictionary;  
		r = new WriterGridResult();
		long start = System.currentTimeMillis();
		makeWriterGrid();
		System.out.println("Grid was created in " + (System.currentTimeMillis() - start) + " ms");
	}

	/**
	 * Create the grid and fill each element
	 */
	private void makeWriterGrid() {
		int gridStepLon, gridStepLat;
		int minLat = Integer.MAX_VALUE, maxLat = Integer.MIN_VALUE;
		int minLon = Integer.MAX_VALUE, maxLon = Integer.MIN_VALUE;
		OSMWriter[] writers = writerDictionary.getWriters();
		// calculate grid area
		for (OSMWriter w : writers){
			if (w.getExtendedBounds().getMinLat() < minLat)
				minLat = w.getExtendedBounds().getMinLat();
			if (w.getExtendedBounds().getMinLong() < minLon)
				minLon = w.getExtendedBounds().getMinLong();
			if (w.getExtendedBounds().getMaxLat() > maxLat)
				maxLat = w.getExtendedBounds().getMaxLat();
			if (w.getExtendedBounds().getMaxLong() > maxLon)
				maxLon = w.getExtendedBounds().getMaxLong();
		}

		// save these results for later use
		gridMinLon = minLon;
		gridMinLat = minLat;
		bounds = new Area(minLat, minLon, maxLat, maxLon);

		// calculate the grid element size
		int diffLon = maxLon - minLon;
		int diffLat = maxLat - minLat;
		gridDivLon = Math.round((diffLon / gridDimLon + 0.5f) );
		gridDivLat = Math.round((diffLat / gridDimLat + 0.5f));

		gridStepLon = Math.round(((diffLon) / gridDimLon) + 0.5f);
		gridStepLat = Math.round(((diffLat) / gridDimLat) + 0.5f);
		assert gridStepLon * gridDimLon >= diffLon : "gridStepLon is too small";
		assert gridStepLat * gridDimLat >= diffLat : "gridStepLat is too small";
		grid = new short[gridDimLon + 1][gridDimLat + 1];
		gridTest = new boolean[gridDimLon + 1][gridDimLat + 1];

		int maxWriterSearch = 0;
		BitSet writerSet = new BitSet(); 

		// start identifying the writer areas that intersect each grid tile
		for (int lon = 0; lon <= gridDimLon; lon++) {
			int testMinLon = gridMinLon + gridStepLon * lon;
			for (int lat = 0; lat <= gridDimLat; lat++) {
				int testMinLat = gridMinLat + gridStepLat * lat;
				writerSet.clear();
				int len = 0;

				for (int j = 0; j < writerDictionary.getNumOfWriters(); j++) {
					OSMWriter w = writers[j];
					// find grid areas that intersect with the writers' area
					int tminLat = Math.max(testMinLat, w.getExtendedBounds().getMinLat());
					int tminLon = Math.max(testMinLon, w.getExtendedBounds().getMinLong());
					int tmaxLat = Math.min(testMinLat + gridStepLat,w.getExtendedBounds().getMaxLat());
					int tmaxLon = Math.min(testMinLon + gridStepLon,w.getExtendedBounds().getMaxLong());
					if (tminLat <= tmaxLat && tminLon <= tmaxLon) {
						// yes, they intersect
						len++;
						writerSet.set(j);
						if (!w.getExtendedBounds().contains(testMinLat, testMinLon)|| !w.getExtendedBounds().contains(testMinLat+ gridStepLat, testMinLon+ gridStepLon)){
							// grid area is completely within writer area 
							gridTest[lon][lat] = true;
						}
					}
				}
				maxWriterSearch = Math.max(maxWriterSearch, len);
				if (len >  0)
					grid[lon][lat] = writerDictionary.translate(writerSet);
				else 
					grid[lon][lat] = AbstractMapProcessor.UNASSIGNED;
			}
		}

		System.out.println("Grid [" + gridDimLon + "][" + gridDimLat + "] for grid area " + bounds + 
				" requires max. " + maxWriterSearch + " checks for each node.");
	}

	/**
	 * For a given node, return the list of writers that may contain it 
	 * @param node the node
	 * @return a reference to an {@link WriterGridResult} instance that contains 
	 * the list of candidates and a boolean that shows whether this list
	 * has to be verified or not. 
	 */
	public WriterGridResult get(final Node node){
		int nMapLon = node.getMapLon();
		int nMapLat = node.getMapLat();
		if (!bounds.contains(nMapLat, nMapLon)) 
			return null;
		int gridLonIdx = (nMapLon - gridMinLon ) / gridDivLon; 
		int gridLatIdx = (nMapLat - gridMinLat ) / gridDivLat;

		// get list of writer candidates from grid
		short idx = grid[gridLonIdx][gridLatIdx];
		if (idx == AbstractMapProcessor.UNASSIGNED) 
			return null;
		r.testNeeded = gridTest[gridLonIdx][gridLatIdx];
		r.l = writerDictionary.getList(idx);
		return r; 		
	}
}
