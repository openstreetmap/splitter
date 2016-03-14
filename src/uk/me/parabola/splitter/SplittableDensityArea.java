/*
 * Copyright (c) 2009, Chris Miller
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
* @author Chris Miller
 */
public class SplittableDensityArea {
	private static final int MAX_LAT_DEGREES = 85;
	private static final int MAX_LON_DEGREES = 90;
	public static final int MAX_SINGLE_POLYGON_VERTICES = 40;
	private static final int MAX_LOOPS = 100;	// number of loops to find better solution for one rectangular area
	private static final int AXIS_HOR = 0; 
	private static final int AXIS_VERT = 1; 
	public static final double NICE_MAX_ASPECT_RATIO = 4;
	private static final double VERY_NICE_FILL_RATIO = 0.93;
	
	private double maxAspectRatio;
	private long minNodes;
	private final int startSearchLimit;
	private int searchLimit;
	private double maxOutsidePolygonRatio = 0.5; // TODO: maybe reduce it when a good solution was found

	private final DensityMap allDensities;
	private EnhancedDensityMap extraDensityInfo;
	
	private boolean beQuiet = false;
	private long maxNodes;
	private final int shift;
	
	private HashSet<Tile> knownBad;
	private LinkedHashMap<Tile, Integer> incomplete;
	private long countBad;
	private boolean searchAll = false;
	
	final int maxTileHeight;
	final int maxTileWidth;
	
	private HashMap<Tile,Solution> goodSolutions;
	private double goodRatio; 
	private boolean trimShape;
	private boolean trimTiles;
	private boolean allowEmptyPart = false;
	private int currMapId;
	private boolean hasEmptyPart;
	
	public SplittableDensityArea(DensityMap densities, int startSearchLimit) {
		this.shift = densities.getShift();
		this.searchLimit = this.startSearchLimit = startSearchLimit;
		maxTileHeight = Utils.toMapUnit(MAX_LAT_DEGREES) / (1 << shift);
		maxTileWidth = Utils.toMapUnit(MAX_LON_DEGREES) / (1 << shift);
		allDensities = densities;
	}
	public DensityMap getAllDensities() {
		return allDensities;
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
		Tile startTile = new Tile(extraDensityInfo);
		List<Tile> startTiles = new ArrayList<>();
		if (trimShape || allDensities.getBounds().getWidth() >= 0x1000000){
			// if trim is wanted or tile spans over planet 
			// we try first to find large empty areas (sea) 
			startTiles.addAll(checkForEmptyClusters(0, startTile, true));
		}
		else 
			startTiles.add(startTile);
		
		Solution fullSolution = new Solution(maxNodes);
		int countNoSol;
		while (true){
			countNoSol = 0;
			for (Tile tile: startTiles){
				hasEmptyPart = false;
				if (!beQuiet)
					System.out.println("Solving partition " + tile.toString());
				Solution solution = solveRectangularArea(tile);
				if (solution != null && solution.isEmpty() == false)
					fullSolution.merge(solution);
				else {
					countNoSol++;
					if (!beQuiet)
						System.out.println("Warning: No solution found for partition " + tile.toString());
				}
			}
			if (countNoSol == 0)
				break;
			if (allowEmptyPart || hasEmptyPart == false)
				break;
			allowEmptyPart = true;
			fullSolution = new Solution(maxNodes);
		}
		if(countNoSol > 0)
			throw new SplitFailedException("Failed to find a correct split");
		System.out.println("Final solution: " +  fullSolution.toString());
		if (fullSolution.isNice())
			System.out.println("This seems to be nice.");
		return getAreas(fullSolution, null);
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
			Tile tile = new Tile(extraDensityInfo, rasteredArea.getBounds());
			Solution solution = findSolutionWithSinglePolygon(0, tile, rasteredArea);
			return getAreas(solution, polygonArea);
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
				testMaxNodes = Math.min(Math.round((double) currMaxNodes * res.size() / wantedTiles), this.allDensities.getNodeCount()-1);
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
		extraDensityInfo = new EnhancedDensityMap(allDensities, polygonArea);
		if (!beQuiet){
			System.out.println("Highest node count in a single grid element is "
					+ Utils.format(extraDensityInfo.getMaxNodesInDensityMapGridElement()));
			if (polygonArea != null)
			System.out.println("Highest node count in a single grid element within the bounding polygon is "
					+ Utils.format(extraDensityInfo.getMaxNodesInDensityMapGridElementInPoly()));
		}
		if (polygonArea != null)
			trimTiles = true;

	}

	/**
	 * Check if the solution should be stored in the map of partial good solutions 
	 * @param tile the tile for which the solution was found
	 * @param sol the solution for the tile
	 */
	private void checkIfGood(Tile tile, Solution sol){
		if (sol.isNice() == false || sol.getTiles().size() < 2)
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
					Tile t = new Tile(extraDensityInfo, part.getBounds());
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
			SplittableDensityArea splittableArea = new SplittableDensityArea(allDensities.subset(shapeBounds), startSearchLimit);
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
	private Solution findSolutionWithSinglePolygon(int depth, final Tile tile, java.awt.geom.Area rasteredPolygonArea) {
		assert rasteredPolygonArea.isSingular();
		if (rasteredPolygonArea.isRectangular()){
			Tile part = new Tile(extraDensityInfo, rasteredPolygonArea.getBounds());
			return solveRectangularArea(part);
		}
		List<List<Point>> shapes = Utils.areaToShapes(rasteredPolygonArea);
		List<Point> shape = shapes.get(0);
		
		if (shape.size() > MAX_SINGLE_POLYGON_VERTICES){
			Tile part = new Tile(extraDensityInfo, rasteredPolygonArea.getBounds());
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
				part0Sol.merge(part1Sol);
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
	private Solution findSolution(int depth, final Tile tile, Tile parent, TileMetaInfo smiParent){
		boolean addAndReturn = false;
		if (tile.count == 0){
			if (!allowEmptyPart){
				hasEmptyPart  = true;
				return null;
			}
			if  (tile.width * tile.height <= 4) 
				return null;
			return new Solution(maxNodes); // allow empty part of the world
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
			if (ratio < maxAspectRatio){ 
				if (checkSize(tile))
					addAndReturn = true;
				else {
//					System.out.println("too large at depth " + depth + " " + tile);
//					if (depth > 10){
//						long dd = 4;
//					}
				}
			}
		} else if (tile.width < 2 && tile.height < 2) {
			return null;
		} 
		if (tile.outsidePolygon()){
			return new Solution(maxNodes);
		}
		if (addAndReturn){
			double outsidePolygonRatio = tile.calcOutsidePolygonRatio();
			if (outsidePolygonRatio > maxOutsidePolygonRatio ){
				return null;
			}
			Solution solution = new Solution(maxNodes);
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
		TileMetaInfo smi = new TileMetaInfo(tile, parent, smiParent);
		
		// we have to split the tile
		int axis = (tile.getAspectRatio() >= 1.0) ? AXIS_HOR:AXIS_VERT;
		
		IntArrayList todoList = generateTestCases(axis, tile, smi);
		int countAxis = 0;
		int usedTestPos = 0;
		int countDone = 0;
		Solution bestSol = null;
		while(true){
			if (usedTestPos >= todoList.size()){
				countAxis++;
				if (countAxis > 1)
					break;
				axis = axis == AXIS_HOR ? AXIS_VERT : AXIS_HOR;
				todoList = generateTestCases(axis, tile, smi);
				usedTestPos = 0;
				continue;
			}
			countDone++;
			if (alreadyDone != null && countDone <= alreadyDone.intValue()){
				continue;
			}
			int splitPos = todoList.getInt(usedTestPos++);
//			if (tested.get(splitPos)){
//				continue;
//			}
//			tested.set(splitPos);
			// create the two parts of the tile 
			boolean ok = false;
			if (axis == AXIS_HOR){
				ok = tile.splitHoriz(splitPos, smi);
			} else {
				ok = tile.splitVert(splitPos, smi);
			}
			if (!ok)
				continue;

			Tile[] parts = smi.getParts();
			if (trimTiles){
				parts[0] = parts[0].trim();
				parts[1] = parts[1].trim();
			}
			if (parts[0].count > parts[1].count){
				// first try the less populated part
				Tile help = parts[0];
				parts[0] = parts[1];
				parts[1] = help;
			}
			Solution [] sols = new Solution[2];
			int countOK = 0;
			for (int i = 0; i < 2; i++){
				// depth first recursive search
//				long t1 = System.currentTimeMillis();
				sols[i] = findSolution(depth + 1, parts[i], tile, smi);
//				long dt = System.currentTimeMillis() - t1;
//				if (dt > 100 && tile.count < 8 * maxNodes)
//					System.out.println("took " + dt + " ms to find " + sols[i] + " for "+ parts[i]);
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
				Solution sol = sols[0];
				sol.merge(sols[1]);
				bestSol = sol;
				break; // we found a valid split and searched the direct neighbours
			}
			if (countBad >= searchLimit){
				incomplete.put(tile, countDone-1); 
				break;
			}
		}
		
		smi.propagateToParent(smiParent, tile, parent);
			
		if (bestSol == null && countBad < searchLimit && depth > 0 && tile.width * tile.height > 100){
			knownBad.add(tile);
		}
		return bestSol;
	}
	
	private boolean checkSize(Tile tile) {
		if (tile.height > maxTileHeight|| tile.width > maxTileWidth)
			return false;
		return true;
	}
	/**
	 * Get a first solution and search for better ones until
	 * either a nice solution is found or no improvement was
	 * found.
	 * @param startTile the tile to split
	 * @return a solution (maybe be empty)
	 */
	private Solution solveRectangularArea(Tile startTile){
		// start values for optimization process: we make little steps towards a good solution
//		spread = 7;
		searchLimit = startSearchLimit;
		minNodes = Math.max(Math.min((long)(0.05 * maxNodes), extraDensityInfo.getNodeCount()), 1); 

		if (extraDensityInfo.getNodeCount() / maxNodes < 4){
			maxAspectRatio = 32;
		} else { 
			maxAspectRatio = startTile.getAspectRatio();
			if (maxAspectRatio < 1)
				maxAspectRatio = 1 / maxAspectRatio;
			if (maxAspectRatio < NICE_MAX_ASPECT_RATIO)
				maxAspectRatio = NICE_MAX_ASPECT_RATIO;
		}
		goodSolutions = new HashMap<>();
		goodRatio = 0.5;
		TileMetaInfo smiStart = new TileMetaInfo(startTile, null, null);
		if (startTile.count < 300 * maxNodes && (checkSize(startTile) || startTile.count < 10 * maxNodes) ){
			searchAll = true;
		}
		
		if (!beQuiet)
			System.out.println("Trying to find nice split for " + startTile);
		Solution bestSolution = new Solution(maxNodes);
		Solution prevBest = new Solution(maxNodes);
		long t1 = System.currentTimeMillis();
		incomplete = new LinkedHashMap<>();
		resetCaches();
		for (int numLoops = 0; numLoops < MAX_LOOPS; numLoops++){
			double saveMaxAspectRatio = maxAspectRatio; 
			long saveMinNodes = minNodes;
			boolean foundBetter = false;
			Solution solution = null;
			countBad = 0;
			if (!beQuiet){
				System.out.println("searching for split with min-nodes " + minNodes + ", learned " + goodSolutions.size() + " good partial solutions");
			}
			smiStart.setMinNodes(minNodes);
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
				if (bestSolution.isEmpty() == false){
					if (minNodes > bestSolution.getWorstMinNodes() + 1){
						// reduce minNodes
						minNodes = (bestSolution.getWorstMinNodes() + minNodes) / 2;
						if (minNodes < bestSolution.getWorstMinNodes() * 1.001)
							minNodes = bestSolution.getWorstMinNodes() + 1;
					}
				}
			}
			maxAspectRatio = Math.max(bestSolution.getWorstAspectRatio()/2, NICE_MAX_ASPECT_RATIO);
			maxAspectRatio = Math.min(32,maxAspectRatio);
			if (bestSolution.isEmpty() == false && bestSolution.getWorstMinNodes() > VERY_NICE_FILL_RATIO * maxNodes)
				break;
			if (minNodes > VERY_NICE_FILL_RATIO * maxNodes)
				minNodes = (long) (VERY_NICE_FILL_RATIO * maxNodes);
			if (saveMaxAspectRatio == maxAspectRatio && saveMinNodes == minNodes){
				if (bestSolution.isEmpty() || bestSolution.getWorstMinNodes() < 0.5 * maxNodes){
					if (countBad > searchLimit && searchLimit < 5_000_000){
						searchLimit *= 2;
						resetCaches();
						System.out.println("No good solution found, duplicated search-limit to " + searchLimit);
						continue;
					}
					if (bestSolution.isEmpty() && minNodes > 1){
						minNodes = 1;
						resetCaches();
						searchLimit = startSearchLimit;
						// sanity check
						System.out.println("No good solution found, trying to find one accepting anything");
						int highestCount = extraDensityInfo.getMaxNodesInDensityMapGridElement();
						// inform user about possible better options?
						double ratio = (double) highestCount / maxNodes;
						if (ratio > 4)
							System.err.printf("max-nodes value %d is far below highest node count %d in single grid element, consider using a higher resolution.\n", maxNodes , highestCount);
						else if (ratio > 1)
							System.err.printf("max-nodes value %d is below highest node count %d in single grid element, consider using a higher resolution.\n", maxNodes , highestCount);
						else if (ratio < 0.25)
							System.err.printf("max-nodes value %d is far above highest node count %d in single grid element, consider using a lower resolution.\n", maxNodes , highestCount);
						
						continue;
					}
					if (searchAll){
						searchAll = false;
						if (bestSolution.isEmpty() == false)
							minNodes = bestSolution.getWorstMinNodes() + 1;
						else 
							minNodes = maxNodes / 100;
						System.out.println("Still no good solution found, trying alternate algorithm");
						continue;
					}
				}  
				break;
			}

		} 
		if (!beQuiet)
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
					System.out.println("Solution is " + (solution.isNice() ? "":"not ") + "nice. Can't find a better solution with search-limit " + searchLimit + ": " + solution.toString());
			}
		}
		return;
	}
	
	/**
	 * Convert the list of Tile instances of a solution to Area instances, report some
	 * statistics.
	 * @param sol the solution
	 * @param polygonArea 
	 * 
	 * @return list of areas
	 */
	private List<Area> getAreas(Solution sol, java.awt.geom.Area polygonArea) {
		List<Area> result = new ArrayList<>();
		int minLat = getAllDensities().getBounds().getMinLat();
		int minLon = getAllDensities().getBounds().getMinLong();
		
		if (polygonArea != null){
			System.out.println("Trying to cut the areas so that they fit into the polygon ...");
		} else {
			if (trimShape)
				sol.trimOuterTiles();
		}
		
		boolean fits  = true;
		for (Tile tile : sol.getTiles()) {
			if (tile.count == 0)
				continue;
			if (tile.verify() == false)
				throw new SplitFailedException("found invalid tile");
			Rectangle r = new Rectangle(minLon + (tile.x << shift), minLat + (tile.y << shift), 
					tile.width << shift, tile.height << shift);
			
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
	 * Generate a list of split positions. 
	 * This is essential: The shorter the list, the faster the search, so we try to
	 * put only good candidates into the list. 
	 * @param axis
	 * @param tile
	 * @param smi
	 * @return
	 */
	private IntArrayList generateTestCases(int axis, Tile tile, TileMetaInfo smi) {
		if (searchAll){
			return (axis == AXIS_HOR) ? tile.genXTests(smi) : tile.genYTests(smi);
		}
		double ratio = tile.getAspectRatio();
		IntArrayList tests = new IntArrayList();
		if (ratio < 1.0/32 || ratio > 32)
			return tests;
		if (ratio < 1.0/16 && axis == AXIS_HOR || ratio > 16 && axis == AXIS_VERT)
			return tests;
		int start = (axis == AXIS_HOR) ? tile.findValidStartX(smi) : tile.findValidStartY(smi);
		int end = (axis == AXIS_HOR) ? tile.findValidEndX(smi) : tile.findValidEndY(smi);
		int range = end - start;
		if (range < 0){
			// can't split tile without having one part that has too few nodes
			return tests;
		}
		if (range > 1024 && (axis == AXIS_HOR && tile.width >= maxTileWidth || axis == AXIS_VERT && tile.height >= maxTileWidth)){
			// large tile, just split at a few valid positions 
			for (int i = 5; i > 1; --i)
				tests.add(start + range / i);
		}
		else if (tile.count < maxNodes * 4 && range > 256){
			// large tile with rather few nodes, allow more tests
			int step = (range) / 20;
			for (int pos = start; pos <= end; pos += step)
				tests.add(pos);
		}
		else if (tile.count > maxNodes * 4){
			int step = range / 7; // 7 turned out to be a good value here
			if (step < 1)
				step = 1;
			for (int pos = start; pos <= end; pos += step)
				tests.add(pos);
			if (tests.isEmpty() && end > start){
				tests.add((start + end) / 2);
			}
		} else {
			// this will be one of the last splits 
			long nMax = tile.count / minNodes;
			if (nMax * minNodes < tile.count)
				nMax++;
			long nMin = tile.count / maxNodes;
			if (nMin * maxNodes < tile.count)
				nMin++;
			if (nMin > 2 && nMin * maxNodes - minNodes < tile.count && ratio > 0.125 && ratio < 8){
				// count is near (but below) a multiple of max-nodes, we have to test all candidates  
				// to make sure that we don't miss a good split 
				return (axis == AXIS_HOR) ? tile.genXTests(smi) : tile.genYTests(smi);
			}
			if (nMax == 2 || nMin == 2){
				tests.add((axis == AXIS_HOR) ? tile.findHorizontalMiddle(smi) : tile.findVerticalMiddle(smi));
				int pos = (axis == AXIS_HOR) ? tile.findFirstXHigher(smi, minNodes) + 1 : tile.findFirstYHigher(smi, minNodes) + 1;
				if (tests.contains(pos) == false)
					tests.add(pos);
				pos = (axis == AXIS_HOR) ? tile.findFirstXHigher(smi, maxNodes) : tile.findFirstYHigher(smi, maxNodes);
				if (tests.contains(pos) == false)
					tests.add(pos);
			} else {
				if (nMax != 3)
					tests.add((axis == AXIS_HOR) ? tile.findHorizontalMiddle(smi) : tile.findVerticalMiddle(smi));
				tests.add(start);
				tests.add(end);
			}
		}
		return tests;
	}
}


