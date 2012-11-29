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
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Splits a density map into multiple areas, none of which
 * exceed the desired threshold.
 *
 * @author GerdP
 */
public class SplittableDensityArea implements SplittableArea {
	private static final int MAX_LOOPS = 20;	// number of loops to find better solution
	private static final int AXIS_HOR = 0; 
	private static final int AXIS_VERT = 1; 
	private static final int NICE_MAX_ASPECT_RATIO = 4;
	private double minAspectRatio = Double.MAX_VALUE;
	private double maxAspectRatio = Double.MIN_VALUE;
	private long minNodes = Long.MAX_VALUE;
	private DensityMap filteredDensities;
	private int spread = 0;
	private long startSplit = System.currentTimeMillis();
	private Solution bestResult;
	private double[] aspectRatioFactor;
	private HashSet<Tile> cache;
	private HashMap<Tile,Long> knownTileCounts;
	private long maxNodes;
	private final java.awt.geom.Area polygonArea;

	public SplittableDensityArea(DensityMap densities, Area bounds, java.awt.geom.Area polygonArea) {
		this.filteredDensities = densities.subset(bounds);
		if (polygonArea != null){
			System.out.println("Applying bounding polygon to DensityMap ...");
			long startTSPoly = System.currentTimeMillis();
			filteredDensities = filteredDensities.trimToPolygon(polygonArea);
			java.awt.geom.Area simplePolygonArea = filteredDensities.filterWithPolygon(polygonArea);
			System.out.println("Polygon filtering took " + (System.currentTimeMillis()-startTSPoly) + " ms");
			this.polygonArea = simplePolygonArea;
		}
		else 
			this.polygonArea = null;
		if (filteredDensities.getWidth() == 0 || filteredDensities.getHeight() == 0)
			return;

		aspectRatioFactor = new double[filteredDensities.getHeight()+1];
		int minLat = filteredDensities.getBounds().getMinLat(); 
		int maxLat = filteredDensities.getBounds().getMaxLat();
		int lat = 0;
		// performance: calculate only once the needed complex math results
		for (int i = 0; i < aspectRatioFactor.length; i++ ){
			lat = minLat + i* (1<<filteredDensities.getShift());
			assert lat <= maxLat;
			aspectRatioFactor[i] = Math.cos(Math.toRadians(Utils.toDegrees(lat))) * (1L<<filteredDensities.getShift());
		}
		assert lat == maxLat; 
	}

	@Override
	public boolean hasData(){
		return filteredDensities != null && filteredDensities.getNodeCount() > 0;
	}

	@Override
	public List<Area> split(long maxNodes) {
		// TODO: If a polygon is given, find a way to avoid creating tiles that 
		// cover a large area outside of the polygon.
		knownTileCounts = new HashMap<SplittableDensityArea.Tile, Long>();
		this.maxNodes = maxNodes; 
		if (filteredDensities == null || filteredDensities.getNodeCount() == 0)
			return Collections.emptyList();
		cache = new HashSet<Tile>();
		List<Tile> tiles;
		// start values for optimization process (they make sure that we find a solution)
		maxAspectRatio = 1L<<filteredDensities.getShift();
		minAspectRatio = 1.0/maxAspectRatio; 
		minNodes = 0;
		Tile startTile = new Tile(0,0,filteredDensities.getWidth(),filteredDensities.getHeight());
		int bestPossibleNum = Math.round(startTile.count / maxNodes + 1);  
		System.out.println("Best solution would have " + bestPossibleNum + " tiles, each containing ~" + startTile.count/bestPossibleNum + " nodes");
		for (int numLoops = 0; numLoops < MAX_LOOPS; numLoops++){
			tiles = new ArrayList<Tile>();
			boolean res = false;

			double saveMinAspectRatio = minAspectRatio; 
			double saveMaxAspectRatio = maxAspectRatio; 
			double saveMinNodes = minNodes;
			boolean foundBetter = false;
			if (polygonArea != null){
				java.awt.geom.Area startPolygonArea = new java.awt.geom.Area(polygonArea);
				res = findSolutionWithPolygons(0,startTile, tiles, startPolygonArea);
			}
			else 
				res = findSolution(0,startTile, tiles);
			if (res == true){
				Solution solution = new Solution(tiles);
				if (bestResult == null || solution.rating < bestResult.rating) {
					bestResult = solution;
					foundBetter = true;
					System.out.println("Best solution until now has "
							+ bestResult.tiles.size()
							+ " tiles and a rating of " + bestResult.rating + ". The smallest node count is " + solution.worstMinNodes);
					if (bestResult.isNice()){
						System.out.println("This seems to be nice.");
						break;
					}
				}
			}
			if (res == false && (spread >= 1 && bestResult != null || filteredDensities.getBounds().getWidth() == 0x800000*2 )){
				System.out.println("Can't find a better solution");
				break; // no hope to find something better in a reasonable time
			}
			if (res == false || foundBetter == false && spread == 0){
				// no (better) solution found for the criteria, search also with "non-natural" split lines
				spread = 3;
				continue;
			}
			
			if (bestResult != null){
				// found a correct start, change criteria to find a better(nicer) result
				minAspectRatio = Math.min(bestResult.worstMinAspectRatio*2, 1.0/NICE_MAX_ASPECT_RATIO);
				maxAspectRatio = Math.max(bestResult.worstMaxAspectRatio/2, NICE_MAX_ASPECT_RATIO);
				minNodes = Math.min(maxNodes / 10, bestResult.worstMinNodes + maxNodes / 20);
				if (minAspectRatio < bestResult.worstMinAspectRatio && maxAspectRatio > bestResult.worstMaxAspectRatio){
					System.out.println("Won't find a better solution");
					break;
				}
				//minNodes = Math.min(10000, maxNodes / 10);
			} else if (numLoops > 0){
				// no correct solution found, try also with "unnatural" split lines
				spread = Math.min(spread+1, 3);
			}
			
			if (saveMaxAspectRatio == maxAspectRatio && saveMinAspectRatio == minAspectRatio && saveMinNodes == minNodes){
				System.out.println("Can't find a better solution");
				break;
			}
		} 
		List<Area> res = bestResult.getAreas();
		System.out.println("Creating the initial areas took " + (System.currentTimeMillis()- startSplit) + " ms");
		return res;
	}

	private boolean findSolutionWithPolygons(int depth, Tile tile,
			List<Tile> tiles, java.awt.geom.Area polygonArea) {
		
		if (polygonArea == null)
			return findSolution(depth+1, tile, tiles);
		
		boolean res = false;
		List<List<Point>> shapes = Utils.areaToShapes(polygonArea);
		for (int i = 0; i < shapes.size(); i++){
			List<Point> shape = shapes.get(i);
			if (shape.size() > 40){
				System.out.println("Error: Bounding polygon is too complex. Please avoid long diagonal lines, try to make it rectilinear!");
				System.exit(-1);
			}
			java.awt.geom.Area part = Utils.shapeToArea(shape);
			res = findSolutionWithSinglePolygon(depth+1, tile, tiles, part);
			if (res == false)
				return false;
		}
		return res;
	}
	
	private boolean findSolutionWithSinglePolygon(int depth, Tile tile,
			List<Tile> tiles, java.awt.geom.Area polygonArea) {

		if (polygonArea == null)
			return findSolution(depth+1, tile, tiles);
		assert polygonArea.isSingular();
		if (polygonArea.isRectangular()){
			Tile part = new Tile(polygonArea.getBounds().x,polygonArea.getBounds().y,polygonArea.getBounds().width,polygonArea.getBounds().height);
			return findSolution(depth+1, part, tiles);
		} else {
			boolean res = false;
			List<List<Point>> shapes = Utils.areaToShapes(polygonArea);
			List<Point> shape = shapes.get(0);
			Rectangle pBounds = polygonArea.getBounds();
			int lastPoint = shape.size() - 1;
			if (shape.get(0).equals(shape.get(lastPoint)))
				--lastPoint;
			for (int i = 0; i <= lastPoint; i++){
				Point point = shape.get(i);
				if (i > 0 && point.equals(shape.get(0)))
					continue;
				int currentSizeThisPoint = tiles.size();
				int cutX = point.x;
				int cutY = point.y;
				for (int axis = 0; axis < 2; axis++){
					int currentSizeThisAxis = tiles.size();
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
						area.intersect(polygonArea);
						
						res = findSolutionWithSinglePolygon(depth+1, tile, tiles, area);
						if (res == true){
							area = new java.awt.geom.Area(r2);
							area.intersect(polygonArea);
							res = findSolutionWithSinglePolygon(depth, tile, tiles, area);
							if (res == false){
								while (tiles.size() > currentSizeThisAxis){
									tiles.remove(tiles.size()-1);
								}
							}
							else 
								break;
						}
					}
				}
				if (res == false){
					while (tiles.size() > currentSizeThisPoint){
						tiles.remove(tiles.size()-1);
					}
				}
				else return res;
			}
			return res;
		}
	}

	@Override
	public Area getBounds() {
		return filteredDensities.getBounds();
	}

	/**
	 * Try to split the tile into nice parts recursively 
	 * @param depth the recursion depth
	 * @param tile the tile to be split
	 * @param maxNodes max number of nodes that should be in one tile
	 * @param tiles the list of parts
	 * @return true if a solution was found
	 */
	private boolean findSolution(int depth, final Tile tile, List<Tile> tiles){
		if (depth > 100){
			long dd = 4;
		}
		int checkRes = check(depth, tile, tiles);
		if (checkRes == OK_RETURN)
			return true;
		else if (checkRes == NOT_OK)
			return false;
		
		int splitX = tile.getSplitHoriz();
		int splitY = tile.getSplitVert();
		ArrayList<Integer> dx = new ArrayList<Integer>();
		ArrayList<Integer> dy = new ArrayList<Integer>();
		if (spread == 0 || tile.count < maxNodes*2 ){
			if (splitX > 0 && splitX < tile.width)
				dx.add(splitX);
			if (splitY > 0 && splitY < tile.height)
				dy.add(splitY);
		}
		else {
			for (int i = 0; i < Math.min(spread+1, splitX); i++){
				int pos = splitX+i;
				if (pos > 0 && pos < tile.width)
					dx.add(pos);
				if (i>0){
					pos = splitX-i;
					if (pos > 0 && pos < tile.width)
						dx.add(pos);
				}
			}
			for (int i = 0; i < Math.min(spread+1, splitY); i++){
				int pos = splitY+i;
				if (pos > 0 && pos < tile.height)
					dy.add(pos);
				if (i>0){
					pos = splitY-i;
					if (pos > 0 && pos < tile.height)
						dy.add(pos);
				}
			}
		}
		if (spread > 0 && tile.count > maxNodes * 4){
			int[] splitXIvl = tile.getPossibleSplitHoriz();
			dx.clear();
			splitX = (splitXIvl[0] + splitXIvl[1]) / 2;
			dx.add(splitX);
			dx.add(splitXIvl[0]);
			dx.add(splitXIvl[1]);
			int[] splitYIvl = tile.getPossibleSplitVert();
			dy.clear();
			splitX = (splitYIvl[0] + splitYIvl[1]) / 2;
			dy.add(splitY);
			dy.add(splitYIvl[0]);
			dy.add(splitYIvl[1]);
		}
		
		int currX = 0, currY = 0;
		int axis;
		axis = (tile.getAspectRatio() >= 1.0) ? AXIS_HOR:AXIS_VERT;
		boolean res = false;
		
		while(true){
			Tile[] parts;
				
			if (axis == AXIS_HOR || currY >= dy.size()){
				if (currX >= dx.size()){
					break;
				}
				parts = tile.splitHoriz(dx.get(currX++));
			}
			else {
				parts = tile.splitVert(dy.get(currY++));
			}
			//System.out.println(depth + " " + tile.x + " " + tile.y + " (" +tile.width+"*"+tile.height+") "+ axis + " " + ("H".equals(axis) ? splitX:splitY));
			
			int currentTiles = tiles.size();
			
			if (cache.contains(parts[0]) || cache.contains(parts[1])){
				res= false;
			}
			else 
				res = findSolution(depth + 1, parts[0], tiles);
			if (res == true){
				res = findSolution(depth + 1, parts[1], tiles);
				if (res == true){
					break; // found a solution for this sub tree
				}
			}
			if (!res){
				while(tiles.size() > currentTiles){
					tiles.remove(tiles.size()-1);
				}
			}
			if (currX >= dx.size() && currY >= dy.size())
				break;
			if (depth == 0){
				long dd = 4;
			}
			axis = (axis == AXIS_HOR) ? AXIS_VERT:AXIS_HOR;
		}
		if (res == false){
			markBad(tile);
		}
		return res;
	}
	
	private final static int NOT_OK = -1;
	private final static int OK_CONTINUE = 0;
	private final static int OK_RETURN = 1;
	/**
	 * Evaluate the quality of the tile
	 * @param tile the tile
	 * @param maxNodes 
	 * @param tiles list of tiles that were split
	 * @return
	 */
	private int check(int depth, Tile tile, List<Tile> tiles){
		boolean addThis = false;
		boolean returnFalse = false;
		if (tile.count == 0) {
			return OK_RETURN; 
		} else if (tile.count > maxNodes && tile.width == 1 && tile.height == 1) {
			addThis = true; // can't split further
		} else if (tile.count < minNodes && depth == 0) {
			addThis = true; // nothing to do
		} else if (tile.count < minNodes) {
			returnFalse  = true;
		} else if (tile.count <= maxNodes) {
			double ratio = tile.getAspectRatio();
			if (ratio < minAspectRatio || ratio > maxAspectRatio) 
				returnFalse  = true;
			else
				addThis = true;
		} else if (tile.width < 2 && tile.height < 2) {
			returnFalse  = true;
		}
		if (addThis) {
			tiles.add(tile);
			return OK_RETURN;
		} else if (returnFalse){
			markBad(tile);
			return NOT_OK;
		}
		return OK_CONTINUE;
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
	
	/**
	 * Helper class to store area info with node counters.
	 * The node counters use the values saved in the initial
	 * DensityMap.
	 * @author GerdP
	 *
	 */
	@SuppressWarnings("serial")
	class Tile extends Rectangle{
		long count;
		
		public Tile(int x,int y, int width, int height) {
			super(x,y,width,height);
			trim();
			Long knownCount = knownTileCounts.get(this);
			if (knownCount == null){
				calcCount();
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
			trim();
			knownTileCounts.put(this, this.count);
		}

		private void calcCount(){
			count = 0;
			for (int i=0;i<width;i++){
				count += filteredDensities.getNodeCount(x + i, y, y + height);
			}
		}
		public void trim(){
			int minY = -1;
			for (int j=0;j<height;j++){
				for (int i=0; i < width; i++){
					if (filteredDensities.getNodeCount(x+i, y+j) > 0){
						minY = y+j;
						break;
					}
				}
				if (minY >= 0)
					break;
			}
			int maxY = -1;
			for (int j=height-1;j>=0;j--){
				for (int i=0; i < width; i++){
					if (filteredDensities.getNodeCount(x+i, y+j) > 0){
						maxY = y+j;
						break;
					}
				}
				if (maxY >= 0)
					break;
			}
			assert minY <= maxY;
			y = minY;
			height = maxY-minY+1;
			
			int minX = -1;
			for (int i=0;i<width;i++){
				if (getColSum(i) > 0){
					minX = x+i;
					break;
				}
			}
			int maxX = -1;
			for (int i=width-1; i>= 0; i--){
				if (getColSum(i) > 0){
					maxX = x+i;
					break;
				}
			}
			
			assert minX <= maxX;
			x = minX;
			width = maxX-minX+1;
		}
		/**
		 * Find good position for a horizontal split.
		 * 
		 * @param tile the tile that is to be split
		 * @return the horizontal split line
		 */
		protected int getSplitHoriz() {
			if (count == 0)
				return 0;
			if (width < 2)
				return 1;
			
			int middle = width/2;
			if (count < maxNodes*2){
				int min = 0,max = width-1;
				int bestMin = -1, bestMax = -1;
				long sumUp = 0,sumDown = 0;
				long acceptableNodes = maxNodes / 3;
				while (min < max) {
					sumUp += getColSum(min);
					if (sumUp > acceptableNodes){
						if (count - sumUp < acceptableNodes){
							if (bestMin >= 0)
								return bestMin;
							return min;
						}
						bestMin = min;
						if (bestMin >= middle)
							return bestMin;
					}
					sumDown += getColSum(max);
					if (sumDown > acceptableNodes){
						if (count - sumDown < acceptableNodes)
							return max;
						bestMax = max;
						if (bestMax <= middle)
							return bestMax;
					}
					++min;
					--max;
				}
				return middle;
			}
			long sum = 0;
			long target = count/2;
			for (int i = 0; i < width; i++) {
				sum += getColSum(i);
				if (sum > target){
					if (i == 0 ){
						return 1;
					}
					return i;
				}
			}
			return width;
		}
		/**
		 * Find good position for a vertical split.
		 * 
		 * @param tile the tile that is to be split
		 * @return the vertical split line
		 */
		protected int getSplitVert() {
			if (count == 0)
				return 0;
			if (height < 2)
				return 1;
			int middle = height/2;
			if (count < maxNodes*2){
				int min = 0,max = height-1;
				int bestMin = -1, bestMax = -1;
				long sumUp = 0,sumDown = 0;
				long acceptableNodes = maxNodes / 3;
				while (min < max) {
					sumUp += getRowSum(min);
					if (sumUp > acceptableNodes){
						if (count - sumUp < acceptableNodes)
							return min;
						bestMin = min;
						if (bestMin >= middle)
							return bestMin;
					}
					sumDown += getRowSum(max);
					if (sumDown > acceptableNodes){
						if (count - sumDown < acceptableNodes){
							if (bestMax >= 0)
								return bestMax;
							return max;
						}
						bestMax = max;
						if (bestMax <= middle)
							return bestMax;
					}
					++min;
					--max;
				}
				return middle;
			}

			long sum = 0;
			long target = count/2;
			for (int j = 0; j < height; j++) {
				sum += getRowSum(j);
				if (sum > target){
					if (j == 0 ){
						return 1;
					}
					return j;
				}
			}
			return height; 
		}

		/**
		 * Find good position for a horizontal split.
		 * 
		 * @param tile the tile that is to be split
		 * @return the horizontal split line
		 */
		public int[] getPossibleSplitHoriz() {
			assert count > maxNodes;
			int[] minMax = {1,width};
			if (count == 0)
				return minMax;
			if (width < 2)
				return minMax;
			
			
			long sum = 0;
			for (int i = 0; i < width; i++) {
				sum += getColSum(i);
				if (sum > maxNodes){
					minMax[0] = i;
					break;
				}
			}
			sum = 0;
			for (int i = width-1; i > 0; i--) {
				sum += getColSum(i);
				if (sum > maxNodes){
					minMax[1] = i;
					break;
				}
			}
			return minMax;
		}

		public int[] getPossibleSplitVert() {
			assert count > maxNodes;
			int[] minMax = {1,height};
			if (count == 0)
				return minMax;
			if (height < 2)
				return minMax;
			
			
			long sum = 0;
			for (int i = 0; i < height; i++) {
				sum += getRowSum(i);
				if (sum > maxNodes){
					minMax[0] = Math.max(1, i);
					break;
				}
			}
			sum = 0;
			for (int i = height-1; i > 0; i--) {
				sum += getRowSum(i);
				if (sum > maxNodes){
					assert i < height;
					minMax[1] = Math.min(height-1, i);
					break;
				}
			}
			
			return minMax;
		}

		/**
		 * 
		 * @param row the row within the tile (0..height-1)
		 * @return
		 */
		private long getRowSum(int row) {
			assert row >= 0 && row < height;
			long sum = 0;
			for (int i = 0; i < width; i++) {
				long count = filteredDensities.getNodeCount(i+x, row+y);
				sum += count;
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
			long sum = filteredDensities.getNodeCount(col+x, y, y+height);
			return sum;
		}
		/**
		 * Get the actual split tiles
		 * @param splitX the horizontal split line
		 * @return array with two parts
		 */
		public Tile[] splitHoriz(int splitX) {
			assert splitX > 0 && splitX < width; 
			Tile left = new Tile(x, y, splitX, height);
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
		public Tile[] splitVert(int splitY) {
			assert splitY > 0 && splitY < height;
			Tile bottom = new Tile(x, y, width, splitY);
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
			int width1 = (int) (width * aspectRatioFactor[y]);
			int width2 = (int) (width * aspectRatioFactor[y + height]);
			int width = Math.max(width1, width2);		
			double ratio = ((double)width)/(height << filteredDensities.getShift());
			return ratio;
		}
		
		
	}
	
	/**
	 * Helper class to combine a list of tiles with some
	 * values that measure the quality.
	 * @author GerdP 
	 * 
	 */
	private class Solution {
		private double worstMinAspectRatio = Double.MAX_VALUE;
		private double worstMaxAspectRatio = Double.MIN_VALUE;
		private long worstMinNodes = Long.MAX_VALUE;
		private long rating = 0;
		private final List<Tile> tiles;

		public Solution(List<Tile> tiles) {
			this.tiles = tiles;
			for (Tile tile : tiles) {
				double aspectRatio = tile.getAspectRatio();
				worstMinAspectRatio = Math.min(aspectRatio, worstMinAspectRatio);
				worstMaxAspectRatio = Math.max(aspectRatio, worstMaxAspectRatio);
				worstMinNodes = Math.min(tile.count, worstMinNodes);
			}
			if (worstMinAspectRatio > 1.0)
				rating = Integer.MAX_VALUE;
			else
				rating = tiles.size() * Math.round(worstMaxAspectRatio/worstMinAspectRatio); 
			
		}

		/**
		 * Convert the list of Tile instances to Area instances, report some
		 * statistics.
		 * 
		 * @return list of areas
		 */
		public List<Area> getAreas() {
			List<Area> result = new ArrayList<Area>();
			int num = 1;
			int shift = filteredDensities.getShift();
			int minLat = filteredDensities.getBounds().getMinLat();
			int minLon = filteredDensities.getBounds().getMinLong();
			String note;
			for (Tile tile : tiles) {
				Area area ;
				area = new Area(minLat + (tile.y << shift), 
						minLon + (tile.x << shift), 
						minLat + ((int) tile.getMaxY() << shift), 
						minLon + ((int) tile.getMaxX() << shift));
				if (tile.count > maxNodes)
					note = " but is already at the minimum size so can't be split further";
				else
					note = "";
				System.out.println("Area " + num++ + " covers " + area
						+ " and contains " + tile.count + " nodes" + note);
				result.add(area);
			}
			return result;

		}

		/**
		 * A solution is considered to be nice when aspect 
		 * ratios are not extreme and every tile is filled
		 * with at least 33% of the max-nodes value.
		 * @return
		 */
		public boolean isNice() {
			if (worstMaxAspectRatio > NICE_MAX_ASPECT_RATIO)
				return false;
			if (worstMinAspectRatio < 1.0 / NICE_MAX_ASPECT_RATIO)
				return false;
			if (worstMinNodes < maxNodes / 3)
				return false;
			return true;
		}
	}
	
	
}


