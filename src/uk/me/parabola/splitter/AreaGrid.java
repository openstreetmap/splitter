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

import java.util.BitSet;

/**
 * A grid that covers the area covered by all areas. Each grid element contains 
 * information about the tiles that are intersecting the grid element and whether 
 * the grid element lies completely within such a tile area.
 * This is used to minimize the needed tests when analyzing coordinates of node coordinates.
 * @author GerdP
 *
 */
public class AreaGrid implements AreaIndex{
	private final Grid grid;
	protected final AreaGridResult r;
	protected final AreaDictionary areaDictionary;

	/**
	 * Create a grid to speed up the search of area candidates.
	 * @param areaDictionary 
	 */
	AreaGrid(AreaDictionary areaDictionary) {
		this.areaDictionary = areaDictionary;
		r = new AreaGridResult();
		grid = new Grid(null, null);
	}

	public Area getBounds(){
		return grid.getBounds();
	}

	public AreaGridResult get (final Node n){
		return grid.get(n.getMapLat(),n.getMapLon());
	}

	public AreaGridResult get (int lat, int lon){
		return grid.get(lat, lon);
	}

	private class Grid {
		private final static int TOP_GRID_DIM_LON = 512; 
		private final static int TOP_GRID_DIM_LAT = 512;
		private final static int SUB_GRID_DIM_LON = 32; 
		private final static int SUB_GRID_DIM_LAT = 32;
		private static final int MIN_GRID_LAT = 2048;
		private static final int MIN_GRID_LON = 2048;
		private static final int MAX_TESTS = 10; 
		private int gridDivLon, gridDivLat;
		private int gridMinLat, gridMinLon; 
		// bounds of the complete grid
		private Area bounds = null;
		private int[][] indexGrid;
		private BitSet[] testGrid;
		private Grid[][] subGrid = null; 
		private final int maxCompares;
		private int usedSubGridElems = 0;
		private final int gridDimLon;
		private final int gridDimLat;

		public Grid(AreaSet usedAreas, Area bounds) {
			// each element contains an index to the areaDictionary or unassigned
			if (usedAreas == null){
				gridDimLon = TOP_GRID_DIM_LON;
				gridDimLat = TOP_GRID_DIM_LAT;
			}
			else{
				gridDimLon = SUB_GRID_DIM_LON;
				gridDimLat = SUB_GRID_DIM_LAT;
			}
			indexGrid = new int[gridDimLon + 1][gridDimLat + 1];
			// is true for an element if the list of areas needs to be tested
			testGrid = new BitSet[gridDimLon + 1];
			for (int lon = 0; lon < testGrid.length; lon++) {
				testGrid[lon] = new BitSet(gridDimLat + 1);
			}
			this.bounds = bounds;
			maxCompares = fillGrid(usedAreas);
		}
		
		public Area getBounds() {
			return bounds;
		}
		
		/**
		 * Create the grid and fill each element
		 * @param usedAreas 
		 * @return maximum number of area tests needed for any returned GridResult 
		 */
		private int fillGrid(AreaSet usedAreas) {
			int gridStepLon, gridStepLat;
			if (bounds == null){
				// calculate grid area
				Area tmpBounds = null;
				for (int i = 0; i < areaDictionary.getNumOfAreas(); i++) {
					Area extBounds = areaDictionary.getExtendedArea(i);
					if (usedAreas == null || usedAreas.get(i))
						tmpBounds = (tmpBounds ==null) ? extBounds : tmpBounds.add(extBounds);
				}
				if (tmpBounds == null)
					return 0;
				// create new Area to make sure that we don't update the existing area
				bounds = new Area(tmpBounds.getMinLat() , tmpBounds.getMinLong(), tmpBounds.getMaxLat(), tmpBounds.getMaxLong());
			}
			// save these results for later use
			gridMinLon = bounds.getMinLong();
			gridMinLat = bounds.getMinLat();
			// calculate the grid element size
			int gridWidth = bounds.getWidth();
			int gridHeight = bounds.getHeight();
			gridDivLon = Math.round((gridWidth / gridDimLon + 0.5f) );
			gridDivLat = Math.round((gridHeight / gridDimLat + 0.5f));
			gridStepLon = Math.round(((gridWidth) / gridDimLon) + 0.5f);
			gridStepLat = Math.round(((gridHeight) / gridDimLat) + 0.5f);
			assert gridStepLon * gridDimLon >= gridWidth : "gridStepLon is too small";
			assert gridStepLat * gridDimLat >= gridHeight : "gridStepLat is too small";

			int maxAreaSearch = 0;
			AreaSet[][] gridAreas = new AreaSet[gridDimLon+1][gridDimLat+1];

			for (int j = 0; j < areaDictionary.getNumOfAreas(); j++) {
				Area extBounds = areaDictionary.getExtendedArea(j); 
				if (!(usedAreas == null || usedAreas.get(j)))
					continue;
				int minLonArea = extBounds.getMinLong();
				int maxLonArea = extBounds.getMaxLong();
				int minLatArea = extBounds.getMinLat();
				int maxLatArea = extBounds.getMaxLat();
				int startLon = Math.max(0,(minLonArea- gridMinLon ) / gridDivLon);
				int endLon = Math.min(gridDimLon,(maxLonArea - gridMinLon ) / gridDivLon);
				int startLat = Math.max(0,(minLatArea- gridMinLat ) / gridDivLat);
				int endLat = Math.min(gridDimLat,(maxLatArea - gridMinLat ) / gridDivLat);
				// add this area to all grid elements that intersect with it
				for (int lon = startLon; lon <= endLon; lon++) {
					int testMinLon = gridMinLon + gridStepLon * lon;
					for (int lat = startLat; lat <= endLat; lat++) {
						int testMinLat = gridMinLat + gridStepLat * lat;
						if (gridAreas[lon][lat]== null)
							gridAreas[lon][lat] = new AreaSet();
						// add this area
						gridAreas[lon][lat].set(j);
						if (!extBounds.contains(testMinLat, testMinLon)
								|| !extBounds.contains(testMinLat+ gridStepLat, testMinLon+ gridStepLon)){
							// grid area is not completely within area 
							testGrid[lon].set(lat);
						}
					}
				}
			}
			for (int lon = 0; lon <= gridDimLon; lon++) {
				for (int lat = 0; lat <= gridDimLat; lat++) {
					AreaSet areaSet = (gridAreas[lon][lat]);
					if (areaSet == null)
						indexGrid[lon][lat] = AbstractMapProcessor.UNASSIGNED;
					else {
						areaSet.lock();
						if (testGrid[lon].get(lat)){
							int numTests = areaSet.cardinality();
							if (numTests  >  MAX_TESTS){ 
								if (gridStepLat > MIN_GRID_LAT && gridStepLon > MIN_GRID_LON){
									Area gridPart = new Area(gridMinLat + gridStepLat * lat, gridMinLon + gridStepLon * lon,
											gridMinLat + gridStepLat * (lat+1),
											gridMinLon + gridStepLon * (lon+1));
									// late allocation 
									if (subGrid == null)
										subGrid = new Grid [gridDimLon + 1][gridDimLat + 1];
									usedSubGridElems++;

									subGrid[lon][lat] = new Grid(areaSet, gridPart);
									numTests = subGrid[lon][lat].getMaxCompares() + 1;
									maxAreaSearch = Math.max(maxAreaSearch, numTests);
									continue;
								}
							}
							maxAreaSearch = Math.max(maxAreaSearch, numTests);
						}
						indexGrid[lon][lat] = areaDictionary.translate(areaSet);
					}
				}
			}
			System.out.println("AreaGridTree [" + gridDimLon + "][" + gridDimLat + "] for grid area " + bounds + 
					" requires max. " + maxAreaSearch + " checks for each node (" + usedSubGridElems + " sub grid(s))" );
			return maxAreaSearch;
			
		}
		
		/**
		 * The highest number of required tests 
		 * @return
		 */
		private int getMaxCompares() {
			return maxCompares;
		}
		/**
		 * For a given node, return the list of areas that may contain it 
		 * @param node the node
		 * @return a reference to an {@link AreaGridResult} instance that contains 
		 * the list of candidates and a boolean that shows whether this list
		 * has to be verified or not. 
		 */
		public AreaGridResult get(final int lat, final int lon){
			if (!bounds.contains(lat, lon)) 
				return null;
			int gridLonIdx = (lon - gridMinLon ) / gridDivLon; 
			int gridLatIdx = (lat - gridMinLat ) / gridDivLat;

			if (subGrid != null){
				Grid sub = subGrid[gridLonIdx][gridLatIdx];
				if (sub != null){
					// get list of area candidates from sub grid
					return sub.get(lat, lon);
				}
			}
			// get list of area candidates from grid
			int idx = indexGrid[gridLonIdx][gridLatIdx];
			if (idx == AbstractMapProcessor.UNASSIGNED) 
				return null;
			r.testNeeded = testGrid[gridLonIdx].get(gridLatIdx);
			r.set = areaDictionary.getSet(idx);
			return r; 		
		}
	}
}
