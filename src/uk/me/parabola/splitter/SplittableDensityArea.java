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

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;

/**
 * Splits a density map into multiple areas, none of which
 * exceed the desired threshold.
 *
 * @author GerdP
 */
public class SplittableDensityArea implements SplittableArea {
	private static final int MAX_LAT_DEGREES = 85;
	private static final int MAX_LON_DEGREES = 90;
	private static final int MAX_SINGLE_POLYGON_VERTICES = 40;
	private static final int MAX_LOOPS = 20;	// number of loops to find better solution
	private static final int AXIS_HOR = 0; 
	private static final int AXIS_VERT = 1; 
	private static final int NICE_MAX_ASPECT_RATIO = 4;
	private double maxAspectRatio = Double.MIN_VALUE;
	private long minNodes = Long.MAX_VALUE;
	private final DensityMap allDensities;
	//private DensityMap filteredDensities;
	private int spread = 0;
	private double[] aspectRatioFactor;
	int minAspectRatioFactorPos = Integer.MAX_VALUE;
	double maxAspectRatioFactor = Double.MIN_VALUE;
	
	private long maxNodes;
	private final int shift;
	private HashSet<Tile> cache;
	private final HashMap<Rectangle,Long> knownTileCounts;
	private int [][]yxMap;
	private int [][]xyMap;
	private final int maxTileHeight;
	private final int maxTileWidth;
	private boolean trim;
	private boolean allowEmptyPart = false;
	private int startMapId;
	private int maxNodesInDensityMapGridElement;
	
	
	public SplittableDensityArea(DensityMap densities) {
		knownTileCounts = new HashMap<Rectangle, Long>();
		this.shift = densities.getShift();
		maxTileHeight = Utils.toMapUnit(MAX_LAT_DEGREES) / (1 << shift);
		maxTileWidth = Utils.toMapUnit(MAX_LON_DEGREES) / (1 << shift);
		allDensities = densities;
	}

	@Override
	public void setTrim(boolean trim) {
		this.trim = trim;
	}

	@Override
	public boolean hasData(){
		return allDensities != null && allDensities.getNodeCount() > 0;
	}

	@Override
	public Area getBounds() {
		return allDensities.getBounds();
	}

	@Override
	public List<Area> split(long maxNodes) {
		this.maxNodes = maxNodes; 
		
		if (allDensities == null || allDensities.getNodeCount() == 0)
			return Collections.emptyList();
		cache = new HashSet<Tile>();
		prepare();
		Tile startTile = new Tile(0,0,allDensities.getWidth(),allDensities.getHeight(), allDensities.getNodeCount());
		
		Solution fullSolution = new Solution();
		Solution startSolution = solveRectangularArea(startTile);
		
		if (startSolution != null && startSolution.isNice())
			return startSolution.getAreas(null);

		System.out.println("Split was not yet succesfull. Trying to remove large empty areas...");
		List<Tile> startTiles = checkForEmptyClusters(startTile, true);
		if (startTiles.size() == 1){
			Tile tile = startTiles.get(0);
			if (tile.equals(startTile)){
				// don't try again to find a solution
				if (startSolution == null)
					return Collections.emptyList();
				else 
					return startSolution.getAreas(null);
			}
		}			
		System.out.println("Trying again with " + startTiles.size() + " trimmed partition(s), also allowing big empty parts.");
		allowEmptyPart = true;
		for (Tile tile: startTiles){
			System.out.println("Solving partition " + tile.toString());
			Solution solution = solveRectangularArea(tile);
			if (solution != null && solution.isEmpty() == false)
				fullSolution.merge(solution);
			else {
				System.out.println("Warning: No solution found for partition " + tile.toString());
			}
		}
		System.out.println("Final solution has " +  fullSolution.toString());
		if (fullSolution.isNice())
			System.out.println("This seems to be nice.");
		
		return fullSolution.getAreas(null);
	}

	@Override
	public List<Area> split(long maxNodes, java.awt.geom.Area polygonArea) {
		this.maxNodes = maxNodes;
		if (polygonArea == null)
			return split(maxNodes);
		if (polygonArea.isSingular()){
			prepare();
			java.awt.geom.Area rasteredArea = allDensities.rasterPolygon(polygonArea);
			Tile tile = new Tile(rasteredArea.getBounds().x,rasteredArea.getBounds().y,rasteredArea.getBounds().width,rasteredArea.getBounds().height);
			// todo: check if rasteredArea has too many vertices
			Solution solution = findSolutionWithSinglePolygon(0, tile, rasteredArea);
			return solution.getAreas(polygonArea);
		} else 
			return splitPolygon(polygonArea);
	}

	private void prepare(){
		aspectRatioFactor = new double[allDensities.getHeight()+1];
		int minLat = allDensities.getBounds().getMinLat(); 
		int maxLat = allDensities.getBounds().getMaxLat();
		int lat = 0;
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
		maxNodesInDensityMapGridElement = Integer.MIN_VALUE;
		int width = allDensities.getWidth();
		int height = allDensities.getHeight();
		xyMap = new int [width][height];
		for (int x = 0; x < width; x++){
			for(int y = 0; y < height; y++){
				int count = allDensities.getNodeCount(x, y);
				if (count > 0){
					if (count > maxNodesInDensityMapGridElement)
						maxNodesInDensityMapGridElement = count;
					xyMap[x][y] = count;
				}
			}
		}
		System.out.println("Highest node count in a single tile is " + maxNodesInDensityMapGridElement);
		yxMap = new int [height][width];
		for(int y = 0; y < height; y++){
			for (int x = 0; x < width; x++){
				yxMap[y][x] = xyMap[x][y];
			}
		}
	}

	private ArrayList<Tile> checkForEmptyClusters(final Tile tile, boolean splitHoriz) {
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
		ArrayList<Tile> clusters = new ArrayList<Tile>();
		if (area.isSingular()){
			clusters.add(tile);
		} else {
			List<List<Point>> shapes = Utils.areaToShapes(area);
			for (List<Point> shape: shapes){
				java.awt.geom.Area part = Utils.shapeToArea(shape);
				Rectangle r = part.getBounds();
				Tile t = new Tile(r.x,r.y,r.width,r.height);
				if (t.count > 0)
					clusters.addAll(checkForEmptyClusters(t.trim(), !splitHoriz ));
			}
		}
		return clusters;
	}

	private List<Area> splitPolygon(final java.awt.geom.Area polygonArea) {
		List<Area> result = new ArrayList<Area>();
		List<List<Point>> shapes = Utils.areaToShapes(polygonArea);
		for (int i = 0; i < shapes.size(); i++){
			List<Point> shape = shapes.get(i);
			java.awt.geom.Area shapeArea = Utils.shapeToArea(shape);
			Rectangle rShape = shapeArea.getBounds();
			if (shape.size() > MAX_SINGLE_POLYGON_VERTICES)
				shapeArea = new java.awt.geom.Area(rShape);
			Area shapeBounds = new Area(rShape.y, rShape.x,(int)rShape.getMaxY(), (int)rShape.getMaxX());
			int resolution = 24-allDensities.getShift();
			shapeBounds  = RoundingUtils.round(shapeBounds, resolution);
			SplittableArea splittableArea = new SplittableDensityArea(allDensities.subset(shapeBounds));
			if (splittableArea.hasData() == false){
				result.add(shapeBounds);
				continue;
			}
			List<Area> partResult = splittableArea.split(maxNodes, shapeArea);
			if (partResult != null)
				result.addAll(partResult);
			else {
				//TODO
			}
			
		}
		return result;
	}
	
	private Solution findSolutionWithSinglePolygon(int depth, final Tile tile, java.awt.geom.Area rasteredPolygonArea) {
		assert rasteredPolygonArea.isSingular();
		if (rasteredPolygonArea.isRectangular()){
			Rectangle r = rasteredPolygonArea.getBounds();
			Tile part = new Tile(r.x, r.y, r.width, r.height);
			return solveRectangularArea(part);
			//return findSolution(0, part);
		} else {
			List<List<Point>> shapes = Utils.areaToShapes(rasteredPolygonArea);
			List<Point> shape = shapes.get(0);
			Rectangle pBounds = rasteredPolygonArea.getBounds();
			if (shape.size() > MAX_SINGLE_POLYGON_VERTICES){
				Rectangle r = rasteredPolygonArea.getBounds();
				Tile part = new Tile(r.x, r.y, r.width, r.height);
				return solveRectangularArea(part);
			}
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

					if (r1.isEmpty() == false && r2.isEmpty() == false){
						java.awt.geom.Area area = new java.awt.geom.Area(r1);
						area.intersect(rasteredPolygonArea);
						
						part0Sol = findSolutionWithSinglePolygon(depth+1, tile, area);
						if (part0Sol != null){
							area = new java.awt.geom.Area(r2);
							area.intersect(rasteredPolygonArea);
							part1Sol = findSolutionWithSinglePolygon(depth, tile, area);
							if (part1Sol != null)
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
	}
	/**
	 * Try to split the tile into nice parts recursively 
	 * @param depth the recursion depth
	 * @param tile the tile to be split
	 * @param maxNodes max number of nodes that should be in one tile
	 * @return true if a solution was found
	 */
	private Solution findSolution(int depth, final Tile tile){
		boolean addAndReturn = false;
		if (tile.count == 0){
			if (!allowEmptyPart)
				return null;
			if  (tile.width * tile.height <= 4) 
				return null;
			else 
				return new Solution(); // allow empty part of the world
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
			Solution solution = new Solution();
			solution.add(tile);  // can't split further
			return solution;
		}

		// we have to split the tile
		
		ArrayList<Integer> offsets = new ArrayList<Integer>();
		ArrayList<Integer> splitXPositions = new ArrayList<Integer>();
		ArrayList<Integer> splitYPositions = new ArrayList<Integer>();

		long[] rowSums = null;
		long[] colSums = null;
		colSums = new long[tile.width]; Arrays.fill(colSums, -1);
		rowSums = new long[tile.height];Arrays.fill(rowSums, -1);
		int numTests = 0;
		int axis = (tile.getAspectRatio() >= 1.0) ? AXIS_HOR:AXIS_VERT;
		if (spread == 0){
			if (numTests == 0){
				offsets.add(0);
				numTests = 2;
			} 
		}else {
			if (tile.count > maxNodes * 4 ){
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
				numTests = splitXPositions.size() + splitYPositions.size();
			} else {
				offsets.add(0);
				for (int i = 1; i < spread; i++){
					offsets.add(i*spread);
					offsets.add(-i*spread);
				}
				numTests = offsets.size() + 1;
				if (spread > 0)
					++numTests;
			}
		}
		int currX = 0, currY = 0;
		int splitX  =-1, splitY = -1;
		int usedAxis = -1;
		while(numTests-- > 0){
			Tile[] parts;
			if (offsets.isEmpty() == false){
				int offset;
				if (axis == AXIS_HOR || currY >= offsets.size()){
					if (currX >= offsets.size()){
						break;
					}
					usedAxis = AXIS_HOR;
					offset = offsets.get(currX++);
					int pos = splitX + offset;
					if (splitX > 0 && (pos >= tile.width || pos <= 0))
						parts = null;
					else 
						parts = tile.splitHorizWithOffset(offset, colSums);
					if (parts != null && offset == 0){
						splitX = parts[1].x;
					}
				} else {
					usedAxis = AXIS_VERT;
					offset = offsets.get(currY++);
					int pos = splitY + offset;
					if (splitY > 0 && (pos >= tile.height || pos <= 0))
						parts = null;
					else 
						parts = tile.splitVertWithOffset(offset, rowSums);
					if (parts != null && offset == 0){
						splitY = parts[1].y;
					}
				}
				
			} else {
				if (axis == AXIS_HOR || currY >= splitYPositions.size()){
					if (currX >= splitXPositions.size())
						break;
					usedAxis = AXIS_HOR;
					parts = tile.splitHoriz(splitXPositions.get(currX++), colSums);
				} else {
					usedAxis = AXIS_VERT;
					parts = tile.splitVert(splitYPositions.get(currY++), rowSums);
				}
			}
			if (parts == null)
				continue;
			Solution part0Sol = null, part1Sol = null; 
			if (cache.contains(parts[0]) == false && cache.contains(parts[1]) == false){
				/*
				if (depth < 4)
					System.out.println(depth + ((usedAxis == AXIS_HOR ? ("H:" + parts[1].x):("V:"+parts[1].y))));
				*/
				if (parts[0].count > parts[1].count){
					// first try the less populated part
					Tile help = parts[0];
					parts[0] = parts[1];
					parts[1] = help;
				}
				
				part0Sol = findSolution(depth + 1, parts[0]);
				if (part0Sol == null)
					markBad(parts[0]);
				else {
					part1Sol = findSolution(depth + 1, parts[1]);
					if (part1Sol == null)
						markBad(parts[1]);
					else {
						part0Sol.merge(part1Sol);
						return part0Sol;
					}
				}
			}
		}
		return null;
	}
	
	/**
	 * Store tiles that can't be split into nice parts. 
	 * @param tile
	 */
	private void markBad(Tile tile){
		cache.add(tile);
		if (cache.size() % 10000 == 0){
			System.out.println("stored states " + cache.size());
		}
	}
	
	private Solution solveRectangularArea(Tile startTile){
		// start values for optimization process (they make sure that we find a solution)
		spread = 0;
		minNodes = 0;
		maxAspectRatio = 1L<<allDensities.getShift();
		System.out.println("Trying to find nice split for " + startTile);
		Solution bestSolution = new Solution();
		for (int numLoops = 0; numLoops < MAX_LOOPS; numLoops++){
			double saveMaxAspectRatio = maxAspectRatio; 
			double saveMinNodes = minNodes;
			boolean foundBetter = false;
			cache = new HashSet<SplittableDensityArea.Tile>();
			Solution solution = findSolution(0, startTile);
			if (solution != null){
				if (solution.isNice() || solution.getRating() < bestSolution.getRating()) {
					bestSolution = solution;
					foundBetter = true;
					System.out.println("Best solution until now: " + bestSolution.toString());
					if (bestSolution.isNice()){
						System.out.println("This seems to be nice.");
						break;
					}
				}
			}
			else {
				if ((spread >= 7 && bestSolution.isEmpty() == false)){
					System.out.println("Can't find a better solution");
					break; // no hope to find something better in a reasonable time
				}
			}
			if (foundBetter == false && spread <= 5){
				// no (better) solution found for the criteria, search also with "non-natural" split lines
				if (spread == 0)
					spread = 3;
				else if (spread <= 3)
					spread = 5;
				else if (spread <= 5)
					spread = 7;
				// maybe try more primes ?
				System.out.println("Trying non-natural splits with spread " + spread + " ...");
				continue;
			}
			
			if (bestSolution.isEmpty() == false){
				// found a correct start, change criteria to find a better(nicer) result
				maxAspectRatio = Math.max(bestSolution.worstAspectRatio/2, NICE_MAX_ASPECT_RATIO);
				maxAspectRatio = Math.min(32,maxAspectRatio);
				
				if (maxAspectRatio == saveMaxAspectRatio){
					if (maxAspectRatio == NICE_MAX_ASPECT_RATIO)
						minNodes = maxNodes / 3;
					else 
						minNodes = Math.min(maxNodes / 3, bestSolution.worstMinNodes + maxNodes/20);
				}
			}
	
			if (saveMaxAspectRatio == maxAspectRatio && saveMinNodes == minNodes){
				if (bestSolution.isEmpty() == false)
					System.out.println("Can't find a better solution");
				break;
			}
			if (cache.size() > 1000000){
				System.out.println("Can't find a better solution");
				break;
			}
		} 
		
		return bestSolution;
	}

	/**
	 * Helper class to store area info with node counters.
	 * The node counters use the values saved in the initial
	 * DensityMap.
	 * @author GerdP
	 *
	 */
	@SuppressWarnings("serial")
	class Tile extends Rectangle{
		final long count;
		
		public Tile(int x,int y, int width, int height) {
			super(x,y,width,height);
			Long knownCount = knownTileCounts.get(this);
			if (knownCount == null){
				count = calcCount();
				knownTileCounts.put(this, this.count);
			}
			else
				count = knownCount;
		}

		private Tile(int x,int y, int width, int height, long count) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.count = count; 
			knownTileCounts.put(this, this.count);
		}

		private long calcCount(){
			//System.out.println("co: x=" + x + ",y=" + y + ",w=" + width + ",h=" + height);
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
		/**
		 * 
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

		/**
		 * Split the tile horizontal so that both parts have almost
		 * the same number of nodes 
		 * @param colSums 
		 * @return array with two parts or null in error cases
		 */
		public Tile[] splitHorizWithOffset(final int offset, long[] colSums) {
			if (count == 0 || width < 2)
				return null;
			
			int splitX = -1;
			long sum = 0;
			long lastSum = 0;
			long target = count/2;
			int middle = width / 2;
			boolean stopInMiddle =  (count > maxNodes * 16 && width > 256);
			for (int pos = 0; pos < width; pos++) {
				lastSum = sum;
				if (colSums != null){
					if (colSums[pos] < 0){
						colSums[pos] = getColSum(pos);
					}
					sum += colSums[pos];
				}
				else 
					sum += getColSum(pos);
				if (stopInMiddle){
					if (pos == middle+offset){
						splitX = pos;
						break;		
					}
				} else if (sum > target){
					if (splitX < 0){
						if (pos == 0 )
							splitX = 1;
						else 
							splitX = pos;
					}
					if (offset <= 0 || pos >= splitX + offset)
						break;
				}

			}
			if (splitX + offset <= 0 || splitX + offset >= width)
				return null;
			if (offset < 0){
				int toGo = offset;
				while (toGo != 0){
					lastSum -= colSums[splitX+toGo];
					toGo++;
				}
			}			
			splitX += offset;
			if (splitX == 1 && lastSum == 0)
				lastSum = sum;
			assert splitX > 0 && splitX < width; 
			Tile left = new Tile(x, y, splitX, height, lastSum);
			Tile right = new Tile(x + splitX, y, width - splitX,height, count -left.count);
			assert left.width+ right.width == width;
			Tile[] result = { left, right };
			return result;
		}
		/**
		 * Get the actual split tiles
		 * @param rowSums 
		 * @param splitY the vertical split line
		 * @return array with two parts
		 */
		public Tile[] splitVertWithOffset(int offset, long[] rowSums) {
			if (count == 0 || height < 2)
				return null;
			/*
			int middle = height/2;
			if (count > maxNodes * 16 && height > 256)
				return middle;
			*/
			int splitY = -1;
			long sum = 0;
			long lastSum = 0;
			long target = count/2;
			int middle = height/2;
			boolean stopInMiddle =  (count > maxNodes * 16 && height > 128);
			for (int pos = 0; pos < height; pos++) {
				lastSum = sum;
				if (rowSums != null){
					if (rowSums[pos] < 0)
						rowSums[pos] = getRowSum(pos);
					sum += rowSums[pos];
				}
				else 
					sum += getRowSum(pos);
				if (stopInMiddle){
					if (pos == middle+offset){
						splitY = pos;
						break;		
					}
				} else if (sum > target){
					if (splitY < 0){
						if (pos == 0 )
							splitY = 1;
						else 
							splitY = pos;
					}
					if (offset <= 0 || pos >= splitY + offset)
						break;
				}
			}
			
			if (splitY + offset <= 0 || splitY + offset >= height)
				return null;
			if (offset < 0){
				int toGo = offset;
				while (toGo != 0){
					lastSum -= rowSums[splitY+toGo];
					toGo++;
				}
			}			
			splitY += offset;
			
			assert splitY > 0 && splitY < height;
			if (splitY == 1 && lastSum == 0)
				lastSum = sum;
			Tile bottom = new Tile(x, y, width, splitY, lastSum);
			Tile top = new Tile(x, y + splitY, width, height- splitY, count-bottom.count);
			assert bottom.height + top.height == height;
			Tile[] result = { bottom, top };
			
			return result;
		}

		/**
		 * Get the actual split tiles
		 * @param splitX the horizontal split line
		 * @param rowSums 
		 * @return array with two parts
		 */
		public Tile[] splitHoriz(int splitX, long[] colSums) {
			if (splitX <= 0 || splitX >= width)
				return null;
			Rectangle r = new Rectangle(x, y, splitX, height);
			Long cachedCount = knownTileCounts.get(r);
			long sum;
			if (cachedCount != null)
				sum = cachedCount;
			else {
				sum = 0;
				assert colSums != null;

				for (int pos = 0; pos < splitX; pos++) {
					if (colSums[pos] < 0){
						colSums[pos] = getColSum(pos);
					}
					sum += colSums[pos];
				}
			}
			if (sum == 0 || sum == count)
				return null;
			Tile left = new Tile(x, y, splitX, height, sum);
			Tile right = new Tile(x + splitX, y, width - splitX,height, count -left.count);
			assert left.width+ right.width == width;
			Tile[] result = { left, right };
			return result;
		}

		/**
		 * Get the actual split tiles
		 * @param splitY the vertical split line
		 * @return array with two parts
		 */
		public Tile[] splitVert(int splitY, long[] rowSums) {
			if (splitY <= 0 || splitY >= height)
				return null;
			Rectangle r = new Rectangle(x, y, width, splitY);
			Long cachedCount = knownTileCounts.get(r);
			long sum;
			if (cachedCount != null)
				sum = cachedCount;
			else {
				assert rowSums != null;
				sum = 0;
				for (int pos = 0; pos < splitY; pos++) {
					if (rowSums[pos] < 0){
						rowSums[pos] = getRowSum(pos);
					}
					sum += rowSums[pos];
				}
			}
			if (sum == 0 || sum == count)
				return null;
			Tile bottom = new Tile(x, y, width, splitY, sum);
			Tile top = new Tile(x, y + splitY, width, height- splitY, count-bottom.count);
			assert bottom.height + top.height == height;
			Tile[] result = { bottom, top };
			
			return result;
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
				if (maxY >= 0)
					break;
			}

			assert minX <= maxX;
			assert minY <= maxY;
			super.x = minX;
			super.y = minY;
			super.width = maxX - minX + 1;
			super.height = maxY - minY + 1;
			return new Tile(minX, minY, maxX - minX + 1, maxY - minY + 1, count);
		} 		
		
		@Override
		public String toString(){
			Area area = allDensities.getArea(x,y,width,height); 
			return  (area.toString() + " with " + Utils.format(count) + " nodes");
		}
	}
	
	/**
	 * Helper class to combine a list of tiles with some
	 * values that measure the quality.
	 * @author GerdP 
	 * 
	 */
	private class Solution {
		private double worstAspectRatio = -1;
		private long worstMinNodes = Long.MAX_VALUE;
		private final List<Tile> tiles;

		public Solution() {
			tiles = new ArrayList<Tile>();
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
		public void merge(Solution other){
			if (other.tiles.isEmpty())
				return;
			
			if (tiles.isEmpty()){
				worstAspectRatio = other.worstAspectRatio;
				worstMinNodes = other.worstMinNodes;
			} else {
				worstAspectRatio = Math.max(worstAspectRatio, other.worstAspectRatio);
				worstMinNodes = Math.min(worstMinNodes, other.worstMinNodes);
			}
			tiles.addAll(other.tiles);
		}
		
		public long getRating(){
			if (tiles.isEmpty())
				return Long.MAX_VALUE;
			int countNonZero = 0;
			worstAspectRatio = -1;
			worstMinNodes = Long.MAX_VALUE;
			for (Tile tile: tiles){
				if (tile.count == 0)
					continue;
				double aspectRatio = tile.getAspectRatio();
				if (aspectRatio < 1.0)
					aspectRatio = 1.0 / aspectRatio;
				worstAspectRatio = Math.max(aspectRatio, worstAspectRatio);
				worstMinNodes = Math.min(tile.count, worstMinNodes); 			
				++countNonZero;
			}
			long rating1 = countNonZero * Math.round(worstAspectRatio*worstAspectRatio);  
			long rating2 = (maxNodes/3)/worstMinNodes;  
			return  rating1 + rating2;
			
		}
		public boolean isEmpty(){
			return tiles.isEmpty();
		}
		/**
		 * Convert the list of Tile instances to Area instances, report some
		 * statistics.
		 * @param polygonArea 
		 * 
		 * @return list of areas
		 */
		public List<Area> getAreas(java.awt.geom.Area polygonArea) {
			List<Area> result = new ArrayList<Area>();
			int num = startMapId;
			int shift = allDensities.getShift();
			int minLat = allDensities.getBounds().getMinLat();
			int minLon = allDensities.getBounds().getMinLong();
			String note;
			
			if (polygonArea != null){
				System.out.println("Trying to cut the areas so that they fit into the polygon ...");
			} else {
				// TODO: maybe trim outer tiles without creating holes or gaps between tiles
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
					else
						fits = false;
				}
				Area area = new Area(r.y,r.x,(int)r.getMaxY(),(int)r.getMaxX());
				if (tile.count > maxNodes)
					note = " but is already at the minimum size so can't be split further";
				else
					note = "";
				long percentage = 100 * tile.count / maxNodes;
				System.out.println("Area " + num++ + " covers " + area 
						+ " and contains " + tile.count + " nodes (" + percentage + " %)" + note);
				result.add(area);
			}
			if (fits == false){
				System.out.println("One or more areas do not exactly fit into the bounding polygon");
			}
			return result;

		}

		/* doesn't work yet
		final private static int LOWER_LEFT  = 0;
		final private static int UPPER_LEFT  = 1;
		final private static int LOWER_RIGHT = 2;
		final private static int UPPER_RIGHT = 3;
		
		private void trimOuterTiles() {
			Point corner1 = new Point();
			Point corner2 = new Point();
			while (true){
				boolean trimmed = false;
				for (int i = 0; i < tiles.size(); i++){
					Tile t1 = tiles.get(i);
					for (int currCorner = 0; currCorner < 4; currCorner++){
						switch (currCorner){
						case LOWER_LEFT: corner1.x = t1.x; corner1.y = t1.y; break; 
						case UPPER_LEFT: corner1.x = t1.x; corner1.y = t1.y + t1.height;break; 
						case LOWER_RIGHT: corner1.x = t1.x + t1.width; corner1.y = t1.y; break; 
						case UPPER_RIGHT: corner1.x = t1.x + t1.width; corner1.y = t1.y + t1.height; 
						}
						boolean free = true;
						for (int j = 0; j < tiles.size(); j++){
							if (i == j)
								continue;
							Tile t2 = tiles.get(j);
							corner2.x = t2.x + t2.width; corner2.y = t2.y;
							if (corner1.equals(corner2)){
								free = false;
								break;
							}
							corner2.x = t2.x + t2.width; corner2.y = t2.y;
							if (corner1.equals(corner2)){
								free = false;
								break;
							}
							corner2.x = t2.x; corner2.y = t2.y + t2.height;
							if (corner1.equals(corner2)){
								free = false;
								break;
							}
							corner2.x = t2.x + t2.width; corner2.y = t2.y + t2.height;
							if (corner1.equals(corner2)){
								free = false;
								break;
							}
							
						}
						if (free){
							Tile old = (Tile) t1.clone();
							// found a free corner, try to trim tile at this corner
							if (currCorner == LOWER_LEFT || currCorner == UPPER_LEFT){
								while (t1.getColSum(0) == 0){
									t1.x ++;
									t1.width--;
								}
							}
							if (currCorner == LOWER_RIGHT || currCorner == LOWER_RIGHT){
								while (t1.getColSum(t1.width-1) == 0){
									t1.width--;
								}
							}	
							if (currCorner == LOWER_LEFT || currCorner == LOWER_RIGHT){
								while (t1.getRowSum(0) == 0){
									t1.y ++;
									t1.height--;
								}
							}	
							if (currCorner == UPPER_LEFT || currCorner == UPPER_RIGHT){
								while (t1.getRowSum(t1.height-1) == 0){
									t1.height--;
								}
							}	
							if (t1.equals(old) == false)
								trimmed = true;
						}
					}
				}
				if (!trimmed)
					return;
					
			}
		}
		*/
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
			long rating = getRating();
			double ratio = (double) Math.round(worstAspectRatio * 100) / 100;
			
			return tiles.size() + " tile(s) and a rating of " + rating 
					+ ". The smallest node count is " + worstMinNodes + ", the worst aspect ratio is near " + ratio;
			
		}
	}

	@Override
	public void setMapId(int mapId) {
		startMapId = mapId;
	}
}


