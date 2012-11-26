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

import java.awt.Rectangle;
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
	private double minAspectRatio = Double.MAX_VALUE;
	private double maxAspectRatio = Double.MIN_VALUE;
	private long minNodes = Long.MAX_VALUE;
	private DensityMap allDensities;
	private int spread = 0;
	private long startSplit = System.currentTimeMillis();
	private Solution bestResult;
	private double[] aspectRatioFactor;
	private HashSet<Tile> cache;
	private HashMap<Tile,Long> knownTileCounts;
	private long maxNodes;

	public SplittableDensityArea(DensityMap densities) {
		
		this.allDensities = densities;
		if (densities.getWidth() == 0 || densities.getHeight() == 0)
			return;
		aspectRatioFactor = new double[densities.getHeight()+1];
		int minLat = densities.getBounds().getMinLat(); 
		int maxLat = densities.getBounds().getMaxLat();
		int lat = 0;
		// performance: calculate only once the needed complex math results
		for (int i = 0; i < aspectRatioFactor.length; i++ ){
			lat = minLat + i* (1<<densities.getShift());
			assert lat <= maxLat;
			aspectRatioFactor[i] = Math.cos(Math.toRadians(Utils.toDegrees(lat))) * (1L<<allDensities.getShift());
		}
		assert lat == maxLat; 
	}

	@Override
	public boolean hasData(){
		return allDensities != null && allDensities.getNodeCount() > 0;
	}

	@Override
	public List<Area> split(long maxNodes) {
		knownTileCounts = new HashMap<SplittableDensityArea.Tile, Long>();
		this.maxNodes = maxNodes; 
		if (allDensities == null || allDensities.getNodeCount() == 0)
			return Collections.emptyList();
		cache = new HashSet<Tile>();
		List<Tile> tiles;
		// start values for optimization process (they make sure that we find a solution)
		maxAspectRatio = 1L<<allDensities.getShift();
		minAspectRatio = 1.0/maxAspectRatio; 
		minNodes = 0;
		
		Tile startTile = new Tile(0,0,allDensities.getWidth(),allDensities.getHeight());
		for (int numLoops = 0; numLoops < MAX_LOOPS; numLoops++){
			tiles = new ArrayList<Tile>();
			boolean res = false;

			double saveMinAspectRatio = minAspectRatio; 
			double saveMaxAspectRatio = maxAspectRatio; 
			double saveMinNodes = minNodes;
			res = findSolution(0,startTile, tiles);
			if (res == true){
				Solution solution = new Solution(tiles);
				if (bestResult == null || solution.rating < bestResult.rating) {
					bestResult = solution;
					System.out.println("Best solution until now has "
							+ bestResult.tiles.size()
							+ " tiles and a rating of " + bestResult.rating);
					if (bestResult.isNice()){
						System.out.println("This seems to be nice.");
						break;
					}
				}
			}
			if (res == false && (spread >= 1 && bestResult != null || allDensities.getBounds().getWidth() == 0x800000*2 )){
				System.out.println("Can't find a better solution");
				break; // no hope to find something better in a reasonable time
			}
			if (res == false){
				// no solution found for the criteria, search also with "non-natural" split lines
				spread = 3;
				continue;
			}
			
			if (bestResult != null){
				// found a correct start, change criteria to find a better(nicer) result
				minAspectRatio = Math.min(bestResult.worstMinAspectRatio*2, 0.5);
				maxAspectRatio = Math.max(bestResult.worstMaxAspectRatio/2, 2);
				minNodes = Math.min(maxNodes / 3, bestResult.worstMinNodes + maxNodes / 20);
			} else if (numLoops > 0){
				// no correct solution found, try also with "unnatural" split lines
				spread = Math.min(spread+1, 3);
			}
			
			if (saveMaxAspectRatio == maxAspectRatio && saveMinAspectRatio == minAspectRatio && saveMinNodes == minNodes){
				break;
			}
		} 
		List<Area> res = bestResult.getAreas();
		System.out.println("Creating the initial areas took " + (System.currentTimeMillis()- startSplit) + " ms");
		return res;
	}

	@Override
	public Area getBounds() {
		return allDensities.getBounds();
	}

	/**
	 * Calculate aspect ratio 
	 * @param tile
	 * @return
	 */
	private double getAspectRatio(Tile tile) {
		int width1 = (int) (tile.width * aspectRatioFactor[tile.y]);
		int width2 = (int) (tile.width * aspectRatioFactor[tile.y+tile.height]);
		int width = Math.max(width1, width2);		
		double ratio = ((double)width)/(tile.height << allDensities.getShift());
		return ratio;
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
		int checkRes = check(depth, tile, tiles);
		if (checkRes == OK_RETURN)
			return true;
		else if (checkRes == NOT_OK)
			return false;
		int splitX = tile.getSplitHoriz();
		int splitY = tile.getSplitVert();
		ArrayList<Integer> dx = new ArrayList<Integer>();
		ArrayList<Integer> dy = new ArrayList<Integer>();
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
		int currX = 0, currY = 0;
		String axis;
		axis = (getAspectRatio(tile) >= 1.0) ? "H":"V";
		boolean res = false;
		
		while(true){
			Tile[] parts;
				
			if ("H".equals(axis) || currY >= dy.size()){
				if (currX >= dx.size()){
					break;
				}
				parts = tile.splitHoriz(dx.get(currX++));
			}
			else {
				parts = tile.splitVert(dy.get(currY++));
			}
			parts[0].trim();
			parts[1].trim();
			
			int currentAreas = tiles.size();
			
			if (cache.contains(parts[0]) || cache.contains(parts[1])){
				res= false;
			}
			else 
				res = findSolution(depth + 1, parts[0], tiles);
			if (res == true){
				res = findSolution(depth + 1, parts[1], tiles);
				if (res == true){
					break;
				}
			}
			if (!res){
				while(tiles.size() > currentAreas){
					tiles.remove(tiles.size()-1);
				}
			}
			if (currX >= dx.size() && currY >= dy.size())
				break;
			axis = "H".equals(axis) ? "V":"H";
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
			double ratio = getAspectRatio(tile);
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
			calcCount();
		}

		private Tile(int x,int y, int width, int height, long count) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.count = count; 
		}

		private void calcCount(){
			count = 0;
			for (int i=0;i<width;i++){
				for (int j=0;j<height;j++){
					count += allDensities.getNodeCount(x+i, y+j);
				}
			}
		}
		public void trim(){
			if (allDensities.isTrim() == false)
				return;
			int minX = -1;
			for (int i=0;i<width;i++){
				for (int j=0;j<height;j++){
					if (allDensities.getNodeCount(x+i, y+j) > 0){
						minX = x+i;
						break;
					}
				}
				if (minX >= 0)
					break;
			}
			int maxX = -1;
			for (int i=width-1; i>= 0; i--){
				for (int j=0;j<height;j++){
					if (allDensities.getNodeCount(x+i, y+j) > 0){
						maxX = x+i;
						break;
					}
				}
				if (maxX >= 0)
					break;
			}
			int minY = -1;
			for (int j=0;j<height;j++){
				for (int i=0; i < width; i++){
					if (allDensities.getNodeCount(x+i, y+j) > 0){
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
					if (allDensities.getNodeCount(x+i, y+j) > 0){
						maxY = y+j;
						break;
					}
				}
				if (maxY >= 0)
					break;
			}
			
			assert minX <= maxX;
			assert minY <= maxY;
			super.x = minX;
			super.y = minY;
			super.width = maxX-minX+1;
			super.height = maxY-minY+1;
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
			long sum = 0;
			if (count > 16*maxNodes && width > 256)
				return width/2;
			long target = count/2;
			for (int i = 0; i < width; i++) {
				for (int j = 0; j < height; j++) {
					long count = allDensities.getNodeCount(i+x, j+y);
					sum += count;
					if (sum > target){
						if (i == 0 ){
							return 1;
						}
						return i;
					}
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
			if (count > 16 * maxNodes && height > 256)
				return height/2;
			long sum = 0;
			long target = count/2;
			for (int j = 0; j < height; j++) {
				for (int i = 0; i < width; i++) {
					long count = allDensities.getNodeCount(i+x, j+ y);
					sum += count;
				}
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
		 * Get the actual split tiles
		 * @param splitX the horizontal split line
		 * @return array with two parts
		 */
		public Tile[] splitHoriz(int splitX) {
			Tile left = new Tile(x, y, splitX, height,0);
			Long cachedCount = knownTileCounts.get(left);
			if (cachedCount == null){
				left = new Tile(x, y, splitX, height);
				knownTileCounts.put(left, left.count);
			} else 
				left.count = cachedCount;
			Tile right = new Tile(x + splitX, y, width - splitX,height, count -left.count);
			knownTileCounts.put(right, right.count);
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
			Tile bottom = new Tile(x, y, width, splitY, 0);
			Long cachedCount = knownTileCounts.get(bottom);
			if (cachedCount == null){
				bottom = new Tile(x, y, width, splitY);
				knownTileCounts.put(bottom, bottom.count);
			}
			else
				bottom.count = cachedCount;
			Tile top = new Tile(x, y + splitY, width, height- splitY, count-bottom.count);
			knownTileCounts.put(top, top.count);
			assert bottom.height + top.height == height;
			Tile[] result = { bottom, top };
			
			return result;
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
				double aspectRatio = getAspectRatio(tile);
				worstMinAspectRatio = Math.min(aspectRatio, worstMinAspectRatio);
				worstMaxAspectRatio = Math.max(aspectRatio, worstMaxAspectRatio);
				worstMinNodes = Math.min(tile.count, worstMinNodes);
				if (aspectRatio < 1)
					aspectRatio = 1.0d / aspectRatio;
				if (aspectRatio > 1) 
					rating += aspectRatio * 100;
			}
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
			int shift = allDensities.getShift();
			int minLat = allDensities.getBounds().getMinLat();
			int minLon = allDensities.getBounds().getMinLong();
			String note;
			for (Tile tile : tiles) {
				tile.trim();
				Area area = new Area(minLat + (tile.y << shift), minLon
						+ (tile.x << shift), minLat
						+ ((int) tile.getMaxY() << shift), minLon
						+ ((int) tile.getMaxX() << shift));
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
			if (worstMaxAspectRatio > 8)
				return false;
			if (worstMinAspectRatio < 1.0 / 8)
				return false;
			if (worstMinNodes < maxNodes / 3)
				return false;
			return true;
		}
	}
}


