/*
 * Copyright (c) 2009.
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

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

/**
 * Splits a density map into multiple areas, none of which
 * exceed the desired threshold.
 *
 * @author GerdP
 */
public class SplittableDensityArea {
	private static final int MAX_LAT_DEGREES = 85;
	private static final int MAX_LON_DEGREES = 90;
	public static final int MAX_SINGLE_POLYGON_VERTICES = 40;
	private static final int MAX_LOOPS = 100;	// number of loops to find better solution for one rectangular area
	private static final int AXIS_HOR = 0; 
	private static final int AXIS_VERT = 1; 
	private static final double NICE_MAX_ASPECT_RATIO = 4;
	private static final double VERY_NICE_FILL_RATIO = 0.93;
	
	private double maxAspectRatio;
	private long minNodes;
	private final int searchLimit;

	private final DensityMap allDensities;
	
	private int spread = 0;
	private double[] aspectRatioFactor;
	int minAspectRatioFactorPos = Integer.MAX_VALUE;
	
	private static final int[] SPREAD_VALUES = { 0, 7, 14, 28};  // empirically found
	
	private static final int MAX_SPREAD = SPREAD_VALUES[SPREAD_VALUES.length-1];
	
	private boolean beQuiet = false;
	private long maxNodes;
	private final int shift;
	
	private HashSet<Tile> knownBad;
	private LinkedHashMap<Tile, Integer> incomplete;
	private long countBad;
	private boolean searchAll = false;
	private int [][]yxMap;
	private int [][]xyMap;
	private final int maxTileHeight;
	private final int maxTileWidth;
	
	private HashMap<Tile,Solution> goodSolutions;
	private double goodRatio; 
	private boolean trimShape;
	private boolean trimTiles;
	private boolean allowEmptyPart = false;
	private int currMapId;
	
	private static enum sides {TOP,RIGHT,BOTTOM,LEFT}
	
	public SplittableDensityArea(DensityMap densities, int searchLimit) {
		this.shift = densities.getShift();
		this.searchLimit = searchLimit;
		maxTileHeight = Utils.toMapUnit(MAX_LAT_DEGREES) / (1 << shift);
		maxTileWidth = Utils.toMapUnit(MAX_LON_DEGREES) / (1 << shift);
		allDensities = densities;
	}
	public void setMapId(int mapId) {
		currMapId = mapId;
	}

	public void setMaxNodes(long maxNodes) {
		this.maxNodes = maxNodes;
	}


	public void setTrim(boolean trim) {
		this.trimShape = trim;
	}

	public boolean hasData(){
		return allDensities != null && allDensities.getNodeCount() > 0;
	}

	/**
	 * @return the area that this splittable area represents
	 */ 	
	public Area getBounds() {
		return allDensities.getBounds();
	}

	/**
	 * @param maxNodes the maximum number of nodes per area
	 * @return a list of areas, each containing no more than {@code maxNodes} nodes.
	 * Each area returned must be aligned to the appropriate overview map resolution.
	 */ 	
	private List<Area> split() {
		if (allDensities == null || allDensities.getNodeCount() == 0)
			return Collections.emptyList();
		prepare(null);
		Tile startTile = new Tile(0,0,allDensities.getWidth(),allDensities.getHeight(), allDensities.getNodeCount());
		
		Solution fullSolution = new Solution(spread);
		Solution startSolution = solveRectangularArea(startTile);
		
		if (startSolution != null && startSolution.isNice())
			return startSolution.getAreas(null);

		if (!beQuiet)
			System.out.println("Split was not yet succesfull. Trying to remove large empty areas...");
		List<Tile> startTiles = checkForEmptyClusters(0, startTile, true);
		if (startTiles.size() == 1){
			Tile tile = startTiles.get(0);
			if (tile.equals(startTile)){
				// don't try again to find a solution
				if (startSolution == null)
					return Collections.emptyList();
				return startSolution.getAreas(null);
			}
		}			
		if (!beQuiet)
			System.out.println("Trying again with " + startTiles.size() + " trimmed partition(s), also allowing big empty parts.");
		allowEmptyPart = true;
		for (Tile tile: startTiles){
			if (!beQuiet)
				System.out.println("Solving partition " + tile.toString());
			Solution solution = solveRectangularArea(tile);
			if (solution != null && solution.isEmpty() == false)
				fullSolution.merge(solution, 0);
			else {
				if (!beQuiet)
					System.out.println("Warning: No solution found for partition " + tile.toString());
			}
		}
		System.out.println("Final solution has " +  fullSolution.toString());
		if (fullSolution.isNice())
			System.out.println("This seems to be nice.");
		
		return fullSolution.getAreas(null);
	}

	/**
	 * Split with a given polygon and max nodes threshold. If the polygon
	 * is not singular, it is divided into singular areas.
	 * @param maxNodes
	 * @param polygonArea
	 * @return
	 */
	private List<Area> split(java.awt.geom.Area polygonArea) {
		if (polygonArea == null)
			return split();
		if (polygonArea.isSingular()){
			java.awt.geom.Area rasteredArea = allDensities.rasterPolygon(polygonArea);
			if (rasteredArea.isEmpty()){
				System.err.println("Bounding polygon doesn't intersect with the bounding box of the input file(s)");
				return Collections.emptyList();
			}
			
			prepare(polygonArea);
			Tile tile = new Tile(rasteredArea.getBounds().x,rasteredArea.getBounds().y,rasteredArea.getBounds().width,rasteredArea.getBounds().height);
			Solution solution = findSolutionWithSinglePolygon(0, tile, rasteredArea);
			return solution.getAreas(polygonArea);
		}
		if (polygonArea.intersects(Utils.area2Rectangle(allDensities.getBounds(),0)))
			return splitPolygon(polygonArea);
		System.err.println("Bounding polygon doesn't intersect with the bounding box of the input file(s)");
		return Collections.emptyList();
	}

	/**
	 * Split a list of named polygons. Overlapping areas of the polygons are
	 * extracted and each one is split for itself. A polygon may not be singular. 
	 * @param maxNodes 
	 * @param namedPolygons
	 * @return
	 */
	public List<Area> split(List<PolygonDesc> namedPolygons) {
		if (namedPolygons.isEmpty())
			return split();
		List<Area> result = new ArrayList<>();
		class ShareInfo {
			java.awt.geom.Area area;
			final IntArrayList sharedBy = new IntArrayList();
		}
		List<ShareInfo> sharedParts = new ArrayList<>();
		for (int i = 0; i < namedPolygons.size(); i++){
			boolean wasDistinct = true;
			PolygonDesc namedPart = namedPolygons.get(i);
			java.awt.geom.Area distinctPart = new java.awt.geom.Area (namedPart.area);
			for(int j = 0; j < namedPolygons.size(); j++){
				if (j == i)
					continue;
				java.awt.geom.Area test = new java.awt.geom.Area(namedPart.area);
				test.intersect(namedPolygons.get(j).area);
				if (test.isEmpty() == false){
					wasDistinct = false;
					distinctPart.subtract(namedPolygons.get(j).area);
					if (j > i){
						ShareInfo si = new ShareInfo();
						si.area = test;
						si.sharedBy.add(i);
						si.sharedBy.add(j);
						sharedParts.add(si);
					}
				}
			}
			if (distinctPart.isEmpty() == false && distinctPart.intersects(Utils.area2Rectangle(allDensities.getBounds(),0))){
//				KmlWriter.writeKml("e:/ld_sp/distinct_"+namedPart.name, "distinct", distinctPart);
				if (wasDistinct == false)
					System.out.println("splitting distinct part of " + namedPart.name);
				else 
					System.out.println("splitting " + namedPart.name);
				result.addAll(split(distinctPart));
			}
		}
		
		for (int i = 0; i < sharedParts.size(); i++){
			ShareInfo si = sharedParts.get(i);
			int last = namedPolygons.size(); // list is extended in the loop
			for (int j = 0; j < last; j++){
				if (si.sharedBy.contains(j))
					continue;
				java.awt.geom.Area test = new java.awt.geom.Area(si.area);
				test.intersect(namedPolygons.get(j).area);
				if (test.isEmpty() == false){
					si.area.subtract(test);
					if (j > si.sharedBy.getInt(si.sharedBy.size()-1)){
						ShareInfo si2 = new ShareInfo();
						si2.area = test;
						si2.sharedBy.addAll(si.sharedBy);
						si2.sharedBy.add(j);
						sharedParts.add(si2);
					}
				}
				if (si.area.isEmpty())
					break;
			}
			if (si.area.isEmpty() == false && si.area.intersects(Utils.area2Rectangle(allDensities.getBounds(),0))){
				String desc = "";
				for (int pos : si.sharedBy)
					desc += namedPolygons.get(pos).name + " and ";
				desc = desc.substring(0,desc.lastIndexOf(" and"));
				System.out.println("splitting area shared by exactly " + si.sharedBy.size() + " polygons: " + desc);
//				KmlWriter.writeKml("e:/ld_sp/shared_"+desc.replace(" " , "_"), desc, si.area);
				result.addAll(split(si.area));
			}
		}
		return result;
	}

	/**
	 * Split a list of named polygons into a given number of tiles.
	 * This is probably only useful with an empty list of polygons
	 * or a list containing one polygon.
	 * @param wantedTiles
	 * @return
	 */
	public List<Area> split(int wantedTiles) {
		long currMaxNodes = this.allDensities.getNodeCount() / wantedTiles;
		class Pair {
			long maxNodes;
			int numTiles;
			
			Pair(long maxNodes, int numTiles){
				this.maxNodes = maxNodes;
				this.numTiles = numTiles;  
			}
		}
		Pair bestBelow = null;
		Pair bestAbove = null;
		beQuiet = true;
		while (true) {
			setMaxNodes(currMaxNodes);
			System.out.println("Trying a max-nodes value of " + currMaxNodes + " to split " + allDensities.getNodeCount() + " nodes into " + wantedTiles + " areas");
			List<Area> res = split();
			if (res.isEmpty() || res.size() == wantedTiles){
				beQuiet = false;
				res = split();
				return res;
			}
			goodSolutions = new HashMap<>();
			Pair pair = new Pair(currMaxNodes, res.size());
			if (res.size() > wantedTiles){
				if (bestAbove == null)
					bestAbove = pair;
				else if (bestAbove.numTiles > pair.numTiles)
					bestAbove = pair;
				else  if (bestAbove.numTiles == pair.numTiles && pair.maxNodes < bestAbove.maxNodes)
					bestAbove = pair;
			} else {
				if (bestBelow == null)
					bestBelow = pair;
				else if (bestBelow.numTiles < pair.numTiles)
					bestBelow = pair;
				else  if (bestBelow.numTiles == pair.numTiles && pair.maxNodes > bestBelow.maxNodes)
					bestBelow = pair;
			}
			long testMaxNodes;
			if (bestBelow == null || bestAbove == null)
				testMaxNodes = Math.round((double) currMaxNodes * res.size() / wantedTiles);
			else 
				testMaxNodes = (bestBelow.maxNodes + bestAbove.maxNodes) / 2;
			if (testMaxNodes == currMaxNodes){
				System.err.println("Cannot find a good split with exactly " + wantedTiles + " areas");
				return res;
			}
			currMaxNodes = testMaxNodes;
		} 
	}

	
	/** 
	 * Filter the density data, calculate once complex trigonometric results 
	 * @param polygonArea
	 */
	private void prepare(java.awt.geom.Area polygonArea){
		aspectRatioFactor = new double[allDensities.getHeight()+1];
		int minLat = allDensities.getBounds().getMinLat(); 
		int maxLat = allDensities.getBounds().getMaxLat();
		int lat = 0;
		double maxAspectRatioFactor = Double.MIN_VALUE;
		// performance: calculate only once the needed complex math results
		for (int i = 0; i < aspectRatioFactor.length; i++ ){
			lat = minLat + i * (1 << shift);
			assert lat <= maxLat;
			aspectRatioFactor[i] = Math.cos(Math.toRadians(Utils.toDegrees(lat))) ;
			if (maxAspectRatioFactor < aspectRatioFactor[i]){
				maxAspectRatioFactor = aspectRatioFactor[i];
				minAspectRatioFactorPos = i;
			}
		}
		assert lat == maxLat;
		int maxNodesInDensityMapGridElement = Integer.MIN_VALUE;
		int width = allDensities.getWidth();
		int height = allDensities.getHeight();
		xyMap = new int [width][height];
		if (polygonArea != null)
			trimTiles = true;
		for (int x = 0; x < width; x++){
			int polyXPos = allDensities.getBounds().getMinLong() +  (x << shift);
			
			for(int y = 0; y < height; y++){
				int count = allDensities.getNodeCount(x, y);
				if (polygonArea != null){
					int polyYPos = allDensities.getBounds().getMinLat() + (y << shift);
					if (polygonArea.intersects(polyXPos, polyYPos, 1<<shift, 1<<shift))
						count = Math.max(1, count);
					else 
						count = 0;
				}
				if (count > 0){
					if (count > maxNodesInDensityMapGridElement)
						maxNodesInDensityMapGridElement = count;
					xyMap[x][y] = count;
				}
			}
		}
		if (!beQuiet)
			System.out.println("Highest node count in a single grid element is " +Utils.format(maxNodesInDensityMapGridElement));
		yxMap = new int [height][width];
		for(int y = 0; y < height; y++){
			for (int x = 0; x < width; x++){
				yxMap[y][x] = xyMap[x][y];
			}
		}
	}

	/**
	 * Get next higher spread value
	 * @param currSpread
	 * @return
	 */
	private static int getNextSpread(int currSpread) {
		for (int i = 0; i < SPREAD_VALUES.length; i++){
			if (currSpread == SPREAD_VALUES[i]){
				return SPREAD_VALUES[i+1];
			}
		}
		return currSpread;
	}

	/**
	 * Check if the solution should be stored in the map of partial good solutions 
	 * @param tile the tile for which the solution was found
	 * @param sol the solution for the tile
	 */
	private void checkIfGood(Tile tile, Solution sol){
		if (sol.isNice() == false || sol.tiles.size() < 2)
			return;
		if (sol.getWorstMinNodes() > (goodRatio * maxNodes)){
			Solution good = sol.copy();
			Solution prevSol = goodSolutions.put(tile, good);
			if (prevSol != null){
				if (prevSol.getWorstMinNodes() > good.getWorstMinNodes())
					goodSolutions.put(tile, prevSol);
			}
		}
	}

	/**
	 * Remove entries from the map of partial good solutions which
	 * cannot help to improve the best solution. 
	 * @param best the best known solution
	 */
	private void filterGoodSolutions(Solution best){
		if (best == null || best.isEmpty())
			return;
		Iterator<Entry<Tile, Solution>> iter = goodSolutions.entrySet().iterator();
		while (iter.hasNext()){
			Entry<Tile, Solution> entry = iter.next();
			if (entry.getValue().getWorstMinNodes() <= best.getWorstMinNodes())
				iter.remove();
		}
		goodRatio = Math.max(0.5, (double) best.getWorstMinNodes() / maxNodes);
	}

	/**
	 * Search a solution for the given tile in the map of partial good solutions 
	 * @param tile the tile to split
	 * @return a copy of the best known solution or null
	 */
	private Solution searchGoodSolutions(Tile tile){
		Solution sol = goodSolutions.get(tile);
		if (sol != null){
			if (sol.getWorstMinNodes() < minNodes)
				return null;
			sol = sol.copy();
		}
		return sol;
	}
	
	
	/**
	 * Try to find empty areas. This will fail if the empty area is enclosed by a
	 * non-empty area.
	 * @param depth recursion depth
	 * @param tile the tile that might contain an empty area
	 * @param splitHoriz true: search horizontal, else vertical
	 * @return a list containing one or more tiles, cut from the original tile, or 
	 * just the original tile
	 */
	private ArrayList<Tile> checkForEmptyClusters(int depth, final Tile tile, boolean splitHoriz) {
		java.awt.geom.Area area = new java.awt.geom.Area(tile);
		int firstEmpty = -1;
		int countEmpty = 0;
		long countLastPart = 0;
		long countRemaining = tile.count;
		long maxEmpty = Utils.toMapUnit(30) / (1 << shift);
		if (splitHoriz){
			for (int i = 0; i < tile.width; i++){
				long count = tile.getColSum(i);
				if (count == 0){
					if (firstEmpty < 0)
						firstEmpty = i;
					countEmpty++;
				} else {
					if (countEmpty > maxEmpty || (countEmpty > 10 && countLastPart > maxNodes/3 && countRemaining > maxNodes/3)){
						java.awt.geom.Area empty = new java.awt.geom.Area(new Rectangle(firstEmpty,tile.y,countEmpty,tile.height));
						area.subtract(empty);
						countLastPart = 0;
					}
					countRemaining -= count;
					firstEmpty = -1;
					countEmpty = 0;
					countLastPart += count;
				}
			}
		} else {
			for (int i = 0; i < tile.height; i++){
				long count = tile.getRowSum(i);
				if (count == 0){
					if (firstEmpty < 0)
						firstEmpty = i;
					countEmpty++;
				} else {
					if (countEmpty > maxEmpty || (countEmpty > 10 && countLastPart > maxNodes/3 && countRemaining > maxNodes/3)){
						java.awt.geom.Area empty = new java.awt.geom.Area(new Rectangle(tile.x,firstEmpty,tile.width,countEmpty));
						area.subtract(empty);
						countLastPart = 0;
					}
					countRemaining -= count;
					firstEmpty = -1;
					countEmpty = 0;
					countLastPart += count;
				}
			}
		}
		ArrayList<Tile> clusters = new ArrayList<>();
		if (depth == 0 && area.isSingular()){
			// try also the other split axis 
			clusters.addAll(checkForEmptyClusters(depth + 1, tile.trim(), !splitHoriz ));
		} else {
			if (area.isSingular()){
				clusters.add(tile.trim());
			} else {
				List<List<Point>> shapes = Utils.areaToShapes(area);
				for (List<Point> shape: shapes){
					java.awt.geom.Area part = Utils.shapeToArea(shape);
					Rectangle r = part.getBounds();
					Tile t = new Tile(r.x,r.y,r.width,r.height);
					if (t.count > 0)
						clusters.addAll(checkForEmptyClusters(depth + 1, t.trim(), !splitHoriz ));
				}
			}
		}
		return clusters;
	}

	/**
	 * Split, handling a polygon that may contain multiple distinct areas.
	 * @param polygonArea
	 * @return a list of areas that cover the polygon
	 */
	private List<Area> splitPolygon(final java.awt.geom.Area polygonArea) {
		List<Area> result = new ArrayList<>();
		List<List<Point>> shapes = Utils.areaToShapes(polygonArea);
		for (int i = 0; i < shapes.size(); i++){
			List<Point> shape = shapes.get(i);
			if (Utils.clockwise(shape) == false)
				continue;
			java.awt.geom.Area shapeArea = Utils.shapeToArea(shape);
			Rectangle rShape = shapeArea.getBounds();
			if (shape.size() > MAX_SINGLE_POLYGON_VERTICES){
				shapeArea = new java.awt.geom.Area(rShape);
				System.out.println("Warning: shape is too complex, using rectangle " + rShape+ " instead");
			}
			Area shapeBounds = new Area(rShape.y, rShape.x,(int)rShape.getMaxY(), (int)rShape.getMaxX());
			int resolution = 24-allDensities.getShift();
			shapeBounds  = RoundingUtils.round(shapeBounds, resolution);
			SplittableDensityArea splittableArea = new SplittableDensityArea(allDensities.subset(shapeBounds), searchLimit);
			splittableArea.setMaxNodes(maxNodes);
			if (splittableArea.hasData() == false){
				System.out.println("Warning: a part of the bounding polygon would be empty and is ignored:" + shapeBounds);
				//result.add(shapeBounds);
				continue;
			}
			List<Area> partResult = splittableArea.split(shapeArea);
			if (partResult != null)
				result.addAll(partResult);
		}
		return result;
	}
	

	/**
	 * Split the given tile using the given (singular) polygon area. The routine splits the polygon into parts
	 * and calls itself recursively for each part that is not rectangular.
	 * @param depth recursion depth
	 * @param tile the tile to split
	 * @param rasteredPolygonArea an area describing a rectilinear shape
	 * @return a solution or null if splitting failed
	 */
//	private int rectangles = 0;
	private Solution findSolutionWithSinglePolygon(int depth, final Tile tile, java.awt.geom.Area rasteredPolygonArea) {
		assert rasteredPolygonArea.isSingular();
		if (rasteredPolygonArea.isRectangular()){
			Rectangle r = rasteredPolygonArea.getBounds();
			Tile part = new Tile(r.x, r.y, r.width, r.height);
//			KmlWriter.writeKml("e:/ld_sp/rect"+rectangles, "rect", allDensities.getArea(r.x,r.y,r.width,r.height).getJavaArea());
			return solveRectangularArea(part);
		}
		List<List<Point>> shapes = Utils.areaToShapes(rasteredPolygonArea);
		List<Point> shape = shapes.get(0);
		
		if (shape.size() > MAX_SINGLE_POLYGON_VERTICES){
			Rectangle r = rasteredPolygonArea.getBounds();
			Tile part = new Tile(r.x, r.y, r.width, r.height);
			System.out.println("Warning: shape is too complex, using rectangle " + part + " instead");
			return solveRectangularArea(part);
		}
		
		Rectangle pBounds = rasteredPolygonArea.getBounds();
		int lastPoint = shape.size() - 1;
		if (shape.get(0).equals(shape.get(lastPoint)))
			--lastPoint;
		for (int i = 0; i <= lastPoint; i++){
			Point point = shape.get(i);
			if (i > 0 && point.equals(shape.get(0)))
				continue;
			int cutX = point.x;
			int cutY = point.y;
			Solution part0Sol = null,part1Sol = null;
			for (int axis = 0; axis < 2; axis++){
				Rectangle r1,r2;
				if (axis == AXIS_HOR){
					r1 = new Rectangle(pBounds.x,pBounds.y,cutX-pBounds.x,pBounds.height);
					r2 = new Rectangle(cutX,pBounds.y,(int)(pBounds.getMaxX()-cutX),pBounds.height);
				} else {
					r1 = new Rectangle(pBounds.x,pBounds.y,pBounds.width,cutY-pBounds.y);
					r2 = new Rectangle(pBounds.x,cutY,pBounds.width,(int)(pBounds.getMaxY()-cutY));
				}

				if (r1.width * r1.height> r2.width * r2.height){
					Rectangle help = r1;
					r1 = r2;
					r2 = help;
				}
				if (r1.isEmpty() == false && r2.isEmpty() == false){
					java.awt.geom.Area area = new java.awt.geom.Area(r1);
					area.intersect(rasteredPolygonArea);
					
					part0Sol = findSolutionWithSinglePolygon(depth+1, tile, area);
					if (part0Sol != null && part0Sol.isEmpty() == false){
						area = new java.awt.geom.Area(r2);
						area.intersect(rasteredPolygonArea);
						part1Sol = findSolutionWithSinglePolygon(depth+1, tile, area);
						if (part1Sol != null && part1Sol.isEmpty() == false)
							break;
					}
				}
			}
			if (part1Sol != null){
				part0Sol.merge(part1Sol, 0);
				return part0Sol;
			}
		}
		return null;
	}
	
	/**
	 * Try to split the tile into nice parts recursively. 
	 * @param depth the recursion depth
	 * @param tile the tile to be split
	 * @return a solution instance or null 
	 */
	private Solution findSolution(int depth, final Tile tile, Tile parent, SplitMetaInfo smiParent){
		boolean addAndReturn = false;
		if (tile.count == 0){
			if (!allowEmptyPart)
				return null;
			if  (tile.width * tile.height <= 4) 
				return null;
			return new Solution(spread); // allow empty part of the world
		} else if (tile.count > maxNodes && tile.width == 1 && tile.height == 1) {
			addAndReturn = true;  // can't split further
		} else if (tile.count < minNodes && depth == 0) {
			addAndReturn = true; // nothing to do
		} else if (tile.count < minNodes) {
			return null;
		} else if (tile.count <= maxNodes) {
			double ratio = tile.getAspectRatio();
			if (ratio < 1.0)
				ratio = 1.0 / ratio;
			if (ratio > maxAspectRatio) 
				return null;
			else if (tile.checkSize())
				addAndReturn = true;
		} else if (tile.width < 2 && tile.height < 2) {
			return null;
		} 
		if (addAndReturn){
			Solution solution = new Solution(spread);
			solution.add(tile);  // can't split further
			return solution;
		}
		if (tile.count < minNodes * 2){
			return null;
		}
		Solution cached = searchGoodSolutions(tile);
		if (cached != null){
			return cached;
		} 
		
		// we have to split the tile
		Integer alreadyDone = null;
		if (countBad == 0 && incomplete.size() > 0){
			alreadyDone = incomplete.remove(tile);
			if (alreadyDone == null)
				incomplete.clear(); // rest is not useful
		}
		
		if (alreadyDone == null && depth > 0 && tile.width * tile.height > 100){
			if (knownBad.contains(tile))
				return null;
		}

		// copy the existing density info from parent 
		// typically, at least one half can be re-used
		SplitMetaInfo smi = new SplitMetaInfo(tile, parent, smiParent);
		
		// we have to split the tile
		IntArrayList offsets = null;
		IntArrayList splitXPositions = null;
		IntArrayList splitYPositions = null;
		
		int axis = (tile.getAspectRatio() >= 1.0) ? AXIS_HOR:AXIS_VERT;
		if (searchAll){
			splitXPositions = tile.genXTests(smi);
			splitYPositions = tile.genYTests(smi);
		} else {
			if (spread == 0){
				offsets = new IntArrayList(1);
				offsets.add(0);
			}else {
				if (tile.count > maxNodes * 8 ){
					splitXPositions = new IntArrayList();
					splitYPositions = new IntArrayList();
					
					// jump around
					int step = tile.width / spread;
					int pos = step;
					while (pos + spread < tile.width){
						splitXPositions.add(pos);
						pos+= step;
					}
					
					step = tile.height / spread;
					pos = step;
					while (pos + spread < tile.height){
						splitYPositions.add(pos);
						pos+= step;
					}
				} else {
					long nMax = tile.count / minNodes;
					if (nMax * minNodes < tile.count)
						nMax++;
					long nMin = tile.count / maxNodes;
					if (nMin * maxNodes < tile.count)
						nMin++;
					splitXPositions = new IntArrayList();
					splitYPositions = new IntArrayList();
					if (smi.horMidPos < 0)
						tile.findHorizontalMiddle(smi);
					if (smi.vertMidPos < 0)
						tile.findVerticalMiddle(smi);
					if (nMax == 2 || nMin == 2){
						splitXPositions.add(smi.horMidPos);
						splitYPositions.add(smi.vertMidPos);
					} else {
						if (nMax == 3){
							splitXPositions.add(tile.findValidStartX(smi));
							splitXPositions.add(tile.findValidEndX(smi));
							splitYPositions.add(tile.findValidStartY(smi));
							splitYPositions.add(tile.findValidEndY(smi));
						} else {
							splitXPositions.add(smi.horMidPos);
							splitXPositions.add(tile.findValidStartX(smi));
							splitXPositions.add(tile.findValidEndX(smi));
							splitYPositions.add(smi.vertMidPos);
							splitYPositions.add(tile.findValidStartY(smi));
							splitYPositions.add(tile.findValidEndY(smi));
						}
					}
				}
			}
		}
		
		int currX = 0, currY = 0;
		int usedX = -1, usedY = -1;
		Solution res = null;
		int maxX, maxY;
		if (offsets != null){
			maxX = maxY = offsets.size();
		} else {
			maxX = splitXPositions.size();
			maxY = splitYPositions.size();
		}
		
		int countDone = 0;
		while(true){
			if (currX >= maxX && currY >= maxY)
				break;
			if (axis == AXIS_HOR){
				if (currX >= maxX){
					axis = AXIS_VERT;
					continue;
				}
				usedX = currX++;
			} else {
				if (currY >= maxY){
					axis = AXIS_HOR;
					continue;
				}
				usedY = currY++;
			}
			countDone++;

			if (alreadyDone != null && countDone <= alreadyDone.intValue()){
				continue;
			}
			
			// create the two parts of the tile 
			boolean ok = false;
			int usedOffset = 0;

			if (offsets != null){
				if (usedX >= 0){
					usedOffset = offsets.getInt(usedX);
					ok = tile.splitHorizWithOffset(usedOffset, smi);
				} else {
					usedOffset = offsets.getInt(usedY);
					ok = tile.splitVertWithOffset(usedOffset, smi);
				}
			} else {
				int splitPos;
				if (usedX >= 0){
					splitPos = splitXPositions.getInt(usedX);
					ok = tile.splitHoriz(splitPos, smi);
				} else {
					splitPos = splitYPositions.getInt(usedY);
					ok = tile.splitVert(splitPos, smi);
				}
			}
			if (!ok)
				continue;

			Tile[] parts = smi.parts;
			if (parts[0].count > parts[1].count){
				// first try the less populated part
				Tile help = parts[0];
				parts[0] = parts[1];
				parts[1] = help;
			}
			if (trimTiles){
				parts[0] = parts[0].trim();
				parts[1] = parts[1].trim();
			}
			Solution [] sols = new Solution[2];
			int countOK = 0;
			for (int i = 0; i < 2; i++){
				// depth first recursive search
				sols[i] = findSolution(depth + 1, parts[i], tile, smi);
				if (sols[i] == null){
					countBad++;
//					if (countBad >= searchLimit){
//						if (countBad == searchLimit)
//							System.out.println("giving up at depth " + depth + " with currX = " + currX + " and currY = "+ currY + " at tile " + tile + " bad: " + saveCountBad );
//						else 
//							System.out.println(".. " + depth + " with currX = " + currX + " and currY = "+ currY + " at tile " + tile + " bad: " + saveCountBad );
//					}
					break;
				}
				checkIfGood(parts[i], sols[i]);
				countOK++;
			}
			if (countOK == 2){
				res = sols[0];
				res.merge(sols[1], depth);
				break; // we found a valid split 
			}
			if (countBad >= searchLimit){
				incomplete.put(tile, countDone-1); 
				break;
			}
		}
		
		smi.propagateToParent(smiParent, tile, parent);
			
		if (res == null && countBad < searchLimit && depth > 0 && tile.width * tile.height > 100){
			knownBad.add(tile);
		}
		return res;
	}
	
	/**
	 * Get a first solution and search for better ones until
	 * either a nice solution is found or no improvement was
	 * found.
	 * @param startTile the tile to split
	 * @return a solution (maybe be empty)
	 */
	private Solution solveRectangularArea(Tile startTile){
		// start values for optimization process (they make sure that we find a solution)
		spread = 0;
		minNodes = maxNodes / 100;
		
		maxAspectRatio = startTile.getAspectRatio();
		if (maxAspectRatio < 1)
			maxAspectRatio = 1 / maxAspectRatio;
		if (maxAspectRatio < NICE_MAX_ASPECT_RATIO)
			maxAspectRatio = NICE_MAX_ASPECT_RATIO;
		goodSolutions = new HashMap<>();
		goodRatio = 0.5;
		SplitMetaInfo smiStart = new SplitMetaInfo(startTile, null, null);

		if (startTile.checkSize()){
			searchAll = true;
		}
		
		if (!beQuiet)
			System.out.println("Trying to find nice split for " + startTile);
		Solution bestSolution = new Solution(spread);
		Solution prevBest = new Solution(spread);
		long t1 = System.currentTimeMillis();
		incomplete = new LinkedHashMap<>();
		for (int numLoops = 0; numLoops < MAX_LOOPS; numLoops++){
			double saveMaxAspectRatio = maxAspectRatio; 
			long saveMinNodes = minNodes;
			boolean foundBetter = false;
			resetCaches();
			Solution solution = null;
			countBad = 0;
			if (!beQuiet){
				if (searchAll)
					System.out.println("searching for split with min-nodes " + minNodes + ", learned " + goodSolutions.size() + " good partial solutions");
				else
					System.out.println("searching for split with spread " + spread + " and min-nodes " + minNodes + ", learned " + goodSolutions.size() + " good partial solutions");
			}
			solution = findSolution(0, startTile, startTile, smiStart);
			if (solution != null){
				foundBetter = bestSolution.compareTo(solution) > 0;
				if (foundBetter){
					prevBest = bestSolution;
					bestSolution = solution;
					System.out.println("Best solution until now: " + bestSolution.toString() + ", elapsed search time: " + (System.currentTimeMillis() - t1) / 1000 + " s");
					filterGoodSolutions(bestSolution);
					// change criteria to find a better(nicer) result
					double factor = 1.10;
					if (prevBest.isEmpty() == false && prevBest.isNice() )
						factor = Math.min(1.30,(double)bestSolution.getWorstMinNodes() / prevBest.getWorstMinNodes());
					minNodes = Math.max(maxNodes /3, (long) (bestSolution.getWorstMinNodes() * factor));
				}

				if (bestSolution.size() == 1){
					if (!beQuiet)
						System.out.println("This can't be improved.");
					break;
				}
			} 
			else {
				if ((searchAll || spread >= MAX_SPREAD) && bestSolution.isEmpty() == false){
					if (minNodes > bestSolution.getWorstMinNodes() + 1){
						// reduce minNodes
						minNodes = (bestSolution.getWorstMinNodes() + minNodes) / 2;
						if (minNodes < bestSolution.getWorstMinNodes() * 1.001)
							minNodes = bestSolution.getWorstMinNodes() + 1;
						if (bestSolution.spread < MAX_SPREAD){
							spread = bestSolution.spread;
							incomplete.clear();
							continue;
						}
					}
				}
			}
			if (!searchAll && foundBetter == false && spread < MAX_SPREAD){
				// no (better) solution found for the criteria, search also with "non-natural" split lines
				if (spread == 0 || minNodes > 0.66 * maxNodes){
					spread = getNextSpread(spread);
					incomplete.clear();
					continue;
				}
			}
			maxAspectRatio = Math.max(bestSolution.getWorstAspectRatio()/2, NICE_MAX_ASPECT_RATIO);
			maxAspectRatio = Math.min(32,maxAspectRatio);
			
			
			if (bestSolution.isEmpty() == false && bestSolution.getWorstMinNodes() > VERY_NICE_FILL_RATIO * maxNodes)
				break;
			if (minNodes > VERY_NICE_FILL_RATIO * maxNodes)
				minNodes = (long) (VERY_NICE_FILL_RATIO * maxNodes);
			if (saveMaxAspectRatio == maxAspectRatio && saveMinNodes == minNodes){
				break;
			}
		} 
		printFinishMsg(bestSolution);
		return bestSolution;
	}

	private void resetCaches(){
		knownBad = new HashSet<>();
//		incomplete = new LinkedHashMap<>();
//		System.out.println("resetting caches");
	}
	
	private void printFinishMsg(Solution solution){
		if (!beQuiet){
			if (solution.isEmpty() == false){
				if (solution.getWorstMinNodes() > VERY_NICE_FILL_RATIO * maxNodes && solution.isNice())
					System.out.println("Solution is very nice. No need to search for a better solution: " + solution.toString());
				else 
					System.out.println("Solution is " + (solution.isNice() ? "":"not ") + "nice. Can't find a better solution: " + solution.toString());
			}
		}
		return;
	}
	/**
	 * Helper class to store area info with node counters.
	 * The node counters use the values saved in the xyMap / yxMap.
	 * @author GerdP
	 *
	 */
	@SuppressWarnings("serial")
	class Tile extends Rectangle{
		final long count;
		
		/**
		 * create a tile with unknown number of nodes
		 * @param x
		 * @param y
		 * @param width
		 * @param height
		 */
		public Tile(int x,int y, int width, int height) {
			super(x,y,width,height);
			count = calcCount();
		}

		public IntArrayList genXTests(SplitMetaInfo smi) {
			int start = this.findValidStartX(smi);
			int end = this.findValidEndX(smi);
			return genTests(start, end);
		}

		public IntArrayList genYTests(SplitMetaInfo smi) {
			int start = this.findValidStartY(smi);
			int end = this.findValidEndY(smi);
			return genTests(start, end);
		}
		
		public IntArrayList genTests(int start, int end) {
			if (end-start < 0)
				return new IntArrayList(1);
			int mid = (start + end) / 2;
			int toAdd = end-start+1;
			IntArrayList list = new IntArrayList(toAdd);
			for (int i = 0; i <= mid; i++){
				int pos = mid + i;
				if (pos >= start && pos <= end)
					list.add(mid+i);
				if (list.size() >= toAdd)
					break;
				if (i == 0)
					continue;
				pos = mid - i;
				if (pos > start && pos < end)
					list.add(mid-i);
			}
			return list;
		}



		/**
		 * create a tile with a known number of nodes
		 * @param x
		 * @param y
		 * @param width
		 * @param height
		 * @param count
		 */
		private Tile(int x,int y, int width, int height, long count) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.count = count; 
//			if (count != calcCount()){
//				System.err.println(count + " <> " + calcCount());
//				assert false;
//			}
		}

		/**
		 * calculate the numnber of nodes in this tile
		 * @return
		 */
		private long calcCount(){
			long sum = 0;
			for (int i=0;i<height;i++){
				sum += getRowSum(i);
			}
			return sum;
		}
		
		/**
		 * @return true if the tile size is okay
		 */
		public boolean checkSize() {
			
			if (height > maxTileHeight|| width > maxTileWidth)
				return false;
			return true;
		}

		/**
		 * Calculate the sum of all grid elements within a row
		 * @param row the row within the tile (0..height-1)
		 * @return
		 */
		private long getRowSum(int row) {
			assert row >= 0 && row < height;
			int mapRow = row + y;
			long sum = 0;
			int[] vector = yxMap[mapRow];
			if (vector != null){
				int lastX = x + width;
				for (int i = x; i < lastX; i++)
					sum += vector[i];
			}
			return sum;
		}
		private long getRowSum(int row, long []rowSums){
			if (rowSums[row] < 0)
				rowSums[row] = getRowSum(row);
			return rowSums[row];
		}
		
		/**
		 * Calculate the sum of all grid elements within a column.
		 * @param col the column within the tile
		 * @return
		 */
		private long getColSum(int col) {
			assert col >= 0 && col < width;
			int mapCol = col + x;
			long sum = 0;
			int[] vector = xyMap[mapCol];
			if (vector != null){
				int lastY = y + height;
				for (int i = y; i < lastY; i++)
					sum += vector[i];
			}
			return sum;
		}
		private long getColSum(int col, long[] colSums){
			if (colSums[col] < 0)
				colSums[col] = getColSum(col);
			return colSums[col];
		}

		/**
		 * Find the horizontal middle of the tile (using the node counts).
		 * Add the offset and split at this position.   
		 * If the tile is large, the real middle is used to avoid
		 * time consuming calculations.
		 * @param offset the desired offset
		 * @return array with two parts or null in error cases
		 */
		public boolean splitHorizWithOffset(final int offset, SplitMetaInfo smi) {
			if (count == 0 || width < 2)
				return false;
			int middle = width / 2;
			if(count > maxNodes * 16 && width > 256)
				return splitHoriz(middle + offset, smi);
			
			int splitX = -1;
			long sum = 0;
			long lastSum = 0;
			long target = count/2;
			
			if (smi.horMidPos < 0)
				findHorizontalMiddle(smi);
			splitX = smi.horMidPos;
			lastSum = smi.horMidSum;
			boolean checkMove = false;
			if (splitX == 0)
				lastSum += getColSum(splitX++, smi.colSums);
			else 
				checkMove = true;


			int splitPos = splitX + offset;
			if (splitPos <= 0 || splitPos >= width)
				return false;
			
			if (offset > 0){
				if (width - splitPos < offset)
					return splitHoriz(splitPos, smi);
				
				for (int i = 0; i < offset; i++){
					lastSum += getColSum(splitX + i, smi.colSums);
				}
				
			} else if (offset < 0){
				if (splitPos < -offset)
					return splitHoriz(splitPos, smi);
				int toGo = offset;
				while (toGo != 0){
					// we can use the array here because we can be sure that all used fields are filled
					// the loop should run forward as this seems to be faster
					lastSum -= smi.colSums[splitX + toGo++]; 
				}
			}
			sum = lastSum + getColSum(splitPos, smi.colSums); 
			if (checkMove && offset >= 0 && splitPos + 1 < width  && target - lastSum > sum - target){
				lastSum = sum;
				splitPos++;
			}
			if (lastSum < minNodes || count - lastSum < minNodes)
				return false;
			assert splitX > 0 && splitX < width; 
			smi.parts[0] = new Tile(x, y, splitPos, height, lastSum);
			smi.parts[1] = new Tile(x + splitPos, y, width - splitPos,height, count -lastSum);
			assert smi.parts[0].width + smi.parts[1].width == this.width; 
			return true;
			
			
		}
		/**
		 * Find the vertical middle of the tile (using the node counts).
		 * Add the offset and split at this position.   
		 * If the tile is large, the real middle is used to avoid
		 * time consuming calculations.
		 * @param offset the desired offset
		 * @return array with two parts or null in error cases
		 */
		public boolean splitVertWithOffset(int offset, SplitMetaInfo smi) {
			if (count == 0 || height < 2)
				return false;
			int middle = height/2;
			if (count > maxNodes * 16 && height > 128)
				return splitVert(middle + offset, smi);
			long target = count/2;
			int splitY = -1;
			long sum = 0;
			long lastSum = 0;
			if (smi.vertMidPos < 0)
				findVerticalMiddle(smi);
			splitY = smi.vertMidPos;
			lastSum = smi.vertMidSum;
			boolean checkMove = false;
			if (splitY == 0)
				lastSum += getRowSum(splitY++, smi.rowSums);
			else 
				checkMove = true;

			int splitPos = splitY + offset;
			if (splitPos <= 0 || splitPos >= height)
				return false;
			
			if (offset > 0){
				if (height - splitPos < offset)
					return splitVert(splitPos, smi);
				
				for (int i = 0; i < offset; i++){
					lastSum += getRowSum(splitY + i, smi.rowSums);
				}
				
			} else if (offset < 0){
				if (splitPos < -offset)
					return splitVert(splitPos, smi);
				int toGo = offset;
				while (toGo != 0){
					// we can use the array here because we can be sure that all used fields are filled
					// the loop should run forward as this seems to be faster
					lastSum -= smi.rowSums[splitY + toGo++]; 
				}
			}
			sum = lastSum + getRowSum(splitPos, smi.rowSums); 
			if (checkMove && offset >= 0 && splitPos + 1 < height  && target - lastSum > sum - target){
				lastSum = sum;
				splitPos++;
			}
			if (lastSum < minNodes || count - lastSum < minNodes)
				return false;
			smi.parts[0] = new Tile(x, y, width, splitPos, lastSum);
			smi.parts[1] = new Tile(x, y + splitPos, width, height- splitPos, count- lastSum);
			assert smi.parts[0].height + smi.parts[1].height == this.height;
			return true;
			
		}
		
		/**
		 * Find first y so that sums of columns for 0-y is > count/2
		 * Update corresponding fields in smi.
		 * 
		 * @param smi fields firstNonZeroX, horMidPos and horMidSum may be updated
		 * @return true if the above fields are usable 
		 */
		private boolean findHorizontalMiddle(SplitMetaInfo smi) {
			if (count == 0 || width < 2)
				return false;

			int start = (smi.firstNonZeroX > 0) ? smi.firstNonZeroX : 0;
			long sum = 0;
			long lastSum = 0;
			long target = count/2;
			
			for (int pos = start; pos <= width; pos++) {
				lastSum = sum;
				sum += getColSum(pos, smi.colSums);
				if (lastSum <= 0 && sum > 0)
					smi.firstNonZeroX = pos;
				if (sum > target){
					smi.horMidPos = pos;
					smi.horMidSum = lastSum;
					break;
				}
			}
			return false;
		}

		/**
		 * Find first x so that sums of rows for 0-x is > count/2. 
		 * Update corresponding fields in smi.
		 * @param smi fields firstNonZeroY, vertMidPos, and vertMidSum may be updated 
		 * @return true if the above fields are usable 
		 */
		private boolean findVerticalMiddle(SplitMetaInfo smi) {
			if (count == 0 || height < 2)
				return false;
			
			long sum = 0;
			long lastSum;
			long target = count/2;
			int start = (smi.firstNonZeroY > 0) ? smi.firstNonZeroY : 0;
			for (int pos = start; pos <= height; pos++) {
				lastSum = sum;
				sum += getRowSum(pos, smi.rowSums);
				if (lastSum <= 0 && sum > 0)
					smi.firstNonZeroY = pos;
				
				if (sum > target){
					smi.vertMidPos = pos;
					smi.vertMidSum = lastSum;
					return true;
				}
			}
			return false; // should not happen
		} 		

		/**
		 * Split at a desired horizontal position.
		 * @param splitX the horizontal split line
		 * @return array with two parts
		 */
		public boolean splitHoriz(int splitX, SplitMetaInfo smi) {
			if (splitX <= 0 || splitX >= width)
				return false;
			long sum = 0;
			
			if (splitX <= width / 2){
				int start = (smi.firstNonZeroX > 0) ? smi.firstNonZeroX : 0;
				for (int pos = start; pos < splitX; pos++) {
					sum += getColSum(pos, smi.colSums);
				}
			} else {
				int end = (smi.lastNonZeroX > 0) ? smi.lastNonZeroX + 1: width;
				for (int pos = splitX; pos < end; pos++) {
					sum += getColSum(pos, smi.colSums);
				}
				sum = count - sum;
			}
			if (sum < minNodes || count - sum < minNodes)
				return false;
			smi.parts[0] = new Tile(x, y, splitX, height, sum);
			smi.parts[1] = new Tile(x + splitX, y, width - splitX,height, count - sum);
			assert smi.parts[0].width + smi.parts[1].width == this.width; 
			return true;
		}

		/**
		 * Split at a desired vertical position.
		 * @param splitY the vertical split line
		 * @return array with two parts
		 */
		public boolean splitVert(int splitY, SplitMetaInfo smi) {
			if (splitY <= 0 || splitY >= height)
				return false;
			long sum = 0;
			
			if (splitY <= height / 2){
				int start = (smi.firstNonZeroY > 0) ? smi.firstNonZeroY : 0; 
				for (int pos = start; pos < splitY; pos++) {
					sum += getRowSum(pos, smi.rowSums);
				}
			} else {
				int end = (smi.lastNonZeroY > 0) ? smi.lastNonZeroY+1 : height; 
				for (int pos = splitY; pos < end; pos++) {
					sum += getRowSum(pos, smi.rowSums);
				}
				sum = count - sum;
			}

			if (sum < minNodes || count - sum < minNodes)
				return false;
			smi.parts[0] = new Tile(x, y, width, splitY, sum);
			smi.parts[1] = new Tile(x, y + splitY, width, height - splitY, count - sum);
			assert smi.parts[0].height + smi.parts[1].height == this.height; 
			return true;
		}

		public int findValidStartX(SplitMetaInfo smi) {
			if (smi.validStartX >= 0)
				return smi.validStartX;
			long sum = 0;
			for (int i = 0; i < smi.colSums.length; i++) {
				sum += getColSum(i, smi.colSums);
				if (sum >= minNodes){
					smi.validStartX = i;
					return i;
				}
			}
			smi.validStartX = width;
			return width;
		}

		public int findValidEndX(SplitMetaInfo smi) {
			long sum = 0;
			for (int i = smi.colSums.length - 1; i >= 0; --i) {
				sum += getColSum(i, smi.colSums);
				if (sum >= minNodes)
					return i;
			}
			return 0;
		}

		public int findValidStartY(SplitMetaInfo smi) {
			if (smi.validStartY > 0)
				return smi.validStartY;
			long sum = 0;
			for (int i = 0; i < height; i++) {
				sum += getRowSum(i, smi.rowSums);
				if (sum >= minNodes){
					smi.validStartY = i;
					return i;
				}
			}
			smi.validStartY = height;
			return height;
		}

		public int findValidEndY(SplitMetaInfo smi) {
			long sum = 0;
			for (int i = height - 1; i >= 0; --i) {
				sum += getRowSum(i, smi.rowSums);
				if (sum >= minNodes)
					return i;
			}
			return 0;
		}
		
		/**
		 * Calculate aspect ratio 
		 * @param tile
		 * @return
		 */
		public double getAspectRatio() {
			double ratio;
			double maxWidth ;
			if (y < minAspectRatioFactorPos && y+height > minAspectRatioFactorPos){
				maxWidth = width; // tile crosses equator
			}else {
				double width1 = width * aspectRatioFactor[y];
				double width2 = width * aspectRatioFactor[y + height];
				maxWidth = Math.max(width1, width2);		
			}
			ratio = maxWidth/height;
			return ratio;
		}
		
		/**
		 * Calculate the trimmed tile so that it has no empty outer rows or columns.
		 * Does not change the tile itself.
		 * @return the trimmed version of the tile.
		 */
		public Tile trim() {
			int minX = -1;
			for (int i = 0; i < width; i++) {
				if (getColSum(i) > 0){
					minX = x + i;
					break;
				}
			}
			int maxX = -1;
			for (int i = width - 1; i >= 0; i--) {
				if (getColSum(i) > 0){
					maxX = x + i;
					break;
				}
			}
			int minY = -1;
			for (int i = 0; i < height; i++) {
				if (getRowSum(i) > 0){
					minY = y + i;
					break;
				}
			}
			int maxY = -1;
			for (int i = height - 1; i >= 0; i--) {
				if (getRowSum(i) > 0){
					maxY = y + i;
					break;
				}
			}

			assert minX <= maxX;
			assert minY <= maxY;
			return new Tile(minX, minY, maxX - minX + 1, maxY - minY + 1, count);
		} 		
		
		@Override
		public String toString(){
			Area area = allDensities.getArea(x,y,width,height); 
			return  (area.toString() + " with " + Utils.format(count) + " nodes");
//			StringBuilder sb = new StringBuilder();
//			sb.append("(");
//			sb.append(x);
//			sb.append(",");
//			sb.append(y);
//			sb.append(",");
//			sb.append(width);
//			sb.append(",");
//			sb.append(height);
//			sb.append(") with ");
//			sb.append(Utils.format(count));
//			sb.append(" nodes");
//			return sb.toString(); 		
		}
	}
	
	/**
	 * A helper class to store all kind of
	 * information which is CPU intensive
	 *
	 */
	class SplitMetaInfo {
		final long[] rowSums;
		final long[] colSums;
		final Tile[] parts = new Tile[2];
		int validStartX = -1;
		int validStartY = -1;
		int firstNonZeroX = -1;
		int firstNonZeroY = -1;
		int lastNonZeroX = -1;
		int lastNonZeroY = -1;
		long vertMidSum = -1;
		long horMidSum = -1;
		int vertMidPos = -1;
		int horMidPos = -1;
		
		/**
		 * Copy information from parent tile to child. Reusing these values
		 * saves a lot of time.
		 * @param tile
		 * @param parent
		 * @param smiParent
		 */
		public SplitMetaInfo(Tile tile, Tile parent, SplitMetaInfo smiParent) {
			rowSums = new long[tile.height];
			colSums = new long[tile.width]; 
			if (parent != null && parent.width == tile.width){
				int srcPos = tile.y - parent.y;
				System.arraycopy(smiParent.rowSums, srcPos, rowSums, 0, rowSums.length);
				if (srcPos == 0)
					firstNonZeroY = smiParent.firstNonZeroY;
			} else 
				Arrays.fill(rowSums, -1);
			if (parent != null && parent.height == tile.height){
				int srcPos = tile.x - parent.x;
				System.arraycopy(smiParent.colSums, srcPos, colSums, 0, colSums.length);
				if (srcPos == 0)
					firstNonZeroX = smiParent.firstNonZeroX;
					
			} else 
				Arrays.fill(colSums, -1);

		}
		
		/**
		 * Copy the information back from child to parent so that next child has more info.
		 * @param smiParent
		 * @param tile
		 * @param parent
		 */
		void propagateToParent(SplitMetaInfo smiParent, Tile tile, Tile parent){
			if (parent.width == tile.width){
				int destPos = tile.y - parent.y;
				System.arraycopy(this.rowSums, 0, smiParent.rowSums, destPos, this.rowSums.length);
				if (destPos == 0) {
					if (smiParent.firstNonZeroY < 0 && this.firstNonZeroY >= 0)
						smiParent.firstNonZeroY = this.firstNonZeroY;
					if (smiParent.validStartY < 0 && this.validStartY >= 0)
						smiParent.validStartY = this.validStartY;
				} else {
					if (smiParent.lastNonZeroY < 0 && this.lastNonZeroY >= 0)
						smiParent.lastNonZeroY = this.lastNonZeroY;
				}
			} 
			if (parent.height == tile.height){
				int destPos = tile.x - parent.x;
				System.arraycopy(this.colSums, 0, smiParent.colSums, destPos, this.colSums.length);
				if (destPos == 0) {
					if (smiParent.firstNonZeroX < 0 && this.firstNonZeroX >= 0)
						smiParent.firstNonZeroX = this.firstNonZeroX;
					if (smiParent.validStartX < 0 && this.validStartX >= 0)
						smiParent.validStartX = this.validStartX;
				} else {
					if (smiParent.lastNonZeroX < 0 && this.lastNonZeroX >= 0)
						smiParent.lastNonZeroX = this.lastNonZeroX;
				}
			}
			
		}
	}
	 	
	/**
	 * Helper class to combine a list of tiles with some
	 * values that measure the quality.
	 * @author GerdP 
	 * 
	 */
	private class Solution {
		private final List<Tile> tiles;
		private final int spread;
		private double worstAspectRatio = -1;
		private long worstMinNodes = Long.MAX_VALUE;
		private int depth;
		
		public Solution(int spread) {
			tiles = new ArrayList<>();
			this.spread = spread;
			this.depth = Integer.MAX_VALUE;
		}

		public Solution copy(){
			Solution s = new Solution(this.spread);
			for (Tile t : tiles)
				s.add(t);
			s.depth = this.depth;
			return s;
		} 
		
		public boolean add(Tile tile){
			tiles.add(tile);
			double aspectRatio = tile.getAspectRatio();
			if (aspectRatio < 1.0)
				aspectRatio = 1.0 / aspectRatio;
			worstAspectRatio = Math.max(aspectRatio, worstAspectRatio);
			worstMinNodes = Math.min(tile.count, worstMinNodes); 		
			return true;
		}
		
		/**
		 * Combine this solution with the other.
		 * @param other
		 * @param mergeAtDepth
		 */
		public void merge(Solution other, int mergeAtDepth){
			if (other.tiles.isEmpty())
				return;
			
			if (tiles.isEmpty()){
				worstAspectRatio = other.worstAspectRatio;
				worstMinNodes = other.worstMinNodes;
			} else {
				if (other.worstAspectRatio > worstAspectRatio)
					worstAspectRatio = other.worstAspectRatio;
				if (worstMinNodes > other.worstMinNodes)
					worstMinNodes = other.worstMinNodes;
			}
			if (this.depth > mergeAtDepth )
				this.depth = mergeAtDepth; 
			tiles.addAll(other.tiles);
		}

		public long getWorstMinNodes(){
			return worstMinNodes;
		}

		public double getWorstAspectRatio(){
			return worstAspectRatio;
		}
		
		public boolean isEmpty(){
			return tiles.isEmpty();
		}
		
		public int size(){
			return tiles.size();
		}
		
		/**
		 * Compare two solutions 
		 * @param other
		 * @return -1 if this is better, 1 if other is better, 0 if both are equal
		 */
		public int compareTo(Solution other){
			if (other == null)
				return -1;
			if (other == this)
				return 0;
			if (isEmpty() != other.isEmpty())
				return isEmpty() ? 1 : -1;
			if (isNice() != other.isNice())
				return isNice() ? -1 : 1;
			
			if (depth != other.depth)
				return (depth < other.depth) ? -1 : 1;
			if (worstMinNodes != other.worstMinNodes)
				return (worstMinNodes > other.worstMinNodes) ? -1 : 1;
			if (tiles.size() != other.tiles.size())
				return tiles.size() < other.tiles.size() ? -1 : 1;
			return 0;
		}
		
		/**
		 * Convert the list of Tile instances to Area instances, report some
		 * statistics.
		 * @param polygonArea 
		 * 
		 * @return list of areas
		 */
		public List<Area> getAreas(java.awt.geom.Area polygonArea) {
			List<Area> result = new ArrayList<>();
			int minLat = allDensities.getBounds().getMinLat();
			int minLon = allDensities.getBounds().getMinLong();
			
			if (polygonArea != null){
				System.out.println("Trying to cut the areas so that they fit into the polygon ...");
			} else {
				if (trimShape)
					trimOuterTiles();
			}
			
			boolean fits  = true;
			for (Tile tile : tiles) {
				if (tile.count == 0)
					continue;
				Rectangle r = new Rectangle(minLon + (tile.x << shift), 
						minLat + (tile.y << shift), 
						tile.width << shift,
						tile.height << shift);
				
				if (polygonArea != null){
					java.awt.geom.Area cutArea = new java.awt.geom.Area(r);
					cutArea.intersect(polygonArea);
					if (cutArea.isEmpty() == false && cutArea.isRectangular() )
						r = cutArea.getBounds();
					else {
						fits = false;
					}
				}
				Area area = new Area(r.y,r.x,(int)r.getMaxY(),(int)r.getMaxX());
				if (!beQuiet){
					String note;
					if (tile.count > maxNodes)
						note = " but is already at the minimum size so can't be split further";
					else
						note = "";
					long percentage = 100 * tile.count / maxNodes;
					System.out.println("Area " + currMapId++ + " covers " + area 
							+ " and contains " + tile.count + " nodes (" + percentage + " %)" + note);
				}
				result.add(area);
			}
			if (fits == false){
				System.out.println("One or more areas do not exactly fit into the bounding polygon");
			}
			return result;

		}

		
		/**
		 * Trim tiles without creating holes or gaps between tiles
		 */
		private void trimOuterTiles() {
			while (true){
				boolean trimmedAny = false;

				int minX = Integer.MAX_VALUE;
				int maxX = Integer.MIN_VALUE;
				int minY = Integer.MAX_VALUE;
				int maxY = Integer.MIN_VALUE;
				
				for (Tile tile : tiles){
					if (minX > tile.x) minX = tile.x;
					if (minY > tile.y) minY = tile.y;
					if (maxX < tile.getMaxX()) maxX = (int) tile.getMaxX();
					if (maxY < tile.getMaxY()) maxY = (int) tile.getMaxY();
				}
				for (sides side:sides.values()){
					for (int direction = -1; direction <= 1; direction += 2){
						int trimToPos = -1;
						switch (side){
						case LEFT:
						case BOTTOM: trimToPos = Integer.MAX_VALUE;
						break;
						case TOP:
						case RIGHT: trimToPos = -1;
						}
						while (true){
							Tile candidate = null;
							boolean trimmed = false;
							for (Tile tile : tiles){
								if (tile.count == 0)
									continue;
								switch (side){
								case LEFT: 
									if (minX == tile.x){
										if (candidate == null)
											candidate = tile;
										else if (direction < 0 && candidate.y > tile.y)
											candidate = tile;
										else if (direction > 0 && candidate.getMaxY() < tile.getMaxY())
											candidate = tile;
									}
									break;
								case RIGHT: 
									if (maxX == tile.getMaxX()){
										if (candidate == null)
											candidate = tile;
										else if (direction < 0 && candidate.y > tile.y)
											candidate = tile;
										else if (direction > 0 && candidate.getMaxY() < tile.getMaxY())
											candidate = tile;
									}
									break;
								case BOTTOM: 
									if (minY == tile.y){
										if (candidate == null)
											candidate = tile;
										else if (direction < 0 && candidate.x > tile.x)
											candidate = tile;
										else if (direction > 0 && candidate.getMaxX() < tile.getMaxX())
											candidate = tile;
									}
									break;
								case TOP: 
									if (maxY == tile.getMaxY()){
										if (candidate == null)
											candidate = tile;
										else if (direction < 0 && candidate.x > tile.x)
											candidate = tile;
										else if (direction > 0 && candidate.getMaxX() < tile.getMaxX())
											candidate = tile;
									}
									break;
								}
							}
							if (candidate == null)
								break;
							Rectangle before = new Rectangle(candidate);
							switch (side){
							case LEFT:  
								while (candidate.x < trimToPos && candidate.getColSum(0) == 0){
									candidate.x ++;
									candidate.width--;
								}
								if (candidate.x < trimToPos)
									trimToPos = candidate.x;
								break;
							case RIGHT:
								while ((candidate.getMaxX() > trimToPos) && candidate.getColSum(candidate.width-1) == 0){
									candidate.width--;
								}
								if (candidate.getMaxX() > trimToPos)
									trimToPos = (int) candidate.getMaxX();
								break;
							case BOTTOM:
								while (candidate.y < trimToPos && candidate.getRowSum(0) == 0){
									candidate.y ++;
									candidate.height--;
								}
								if (candidate.y < trimToPos)
									trimToPos = candidate.y;
								break;
							case TOP:
								while (candidate.getMaxY() > trimToPos && candidate.getRowSum(candidate.height-1) == 0){
									candidate.height--;
								}
								if (candidate.getMaxX() > trimToPos)
									trimToPos = (int) candidate.getMaxY();
								break;
							}
							if (before.equals(candidate) == false){
								trimmed = true;
								trimmedAny = true;
							}
							if (!trimmed)
								break;
						}
					}
				}
				if (!trimmedAny)
					return;
			}
		}
	
		
		/**
		 * A solution is considered to be nice when aspect 
		 * ratios are not extreme and every tile is filled
		 * with at least 33% of the max-nodes value.
		 * @return
		 */
		public boolean isNice() {
			if (isEmpty())
				return false;
			if (worstAspectRatio > NICE_MAX_ASPECT_RATIO)
				return false;
			if (tiles.size() == 1)
				return true;
			if (worstMinNodes < maxNodes / 3)
				return false;
			return true;
		}
		
		public String toString(){
			double ratio = (double) Math.round(worstAspectRatio * 100) / 100;
			long percentage = 100 * worstMinNodes / maxNodes;
			return tiles.size() + " tile(s). The smallest node count is " + worstMinNodes + " (" +  percentage + " %), the worst aspect ratio is near " + ratio;
			
		}
	}
}


