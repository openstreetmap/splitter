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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
	private long bestRating = Long.MAX_VALUE;
	private List<Tile> bestResult;
	private double[] aspectRatioFactor;
	private Set<Tile> cache ;

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
			res = findSolution(0,startTile, maxNodes, tiles);
			if (res == true){
				long rating = getRating(tiles, maxNodes);
				if (rating < bestRating){
					bestRating = rating;
					bestResult = tiles;
					System.out.println("Best solution until now has " + bestResult.size() + " tiles and a rating of " + bestRating);
				}
			}
			if (res == false && spread >= 1 && bestResult != null)
				break; // no hope to find something better in a reasonable time
			if (res == false){
				// no solution found for the criteria, search also with "non-natural" split lines
				spread = 1;
				continue;
			}
			if (bestResult == null){
				bestResult = tiles;
				if (bestResult.size() == 1)
					break;;
			}

			if (bestResult != null){
				// found a correct start, change criteria to find a better(nicer) result
				minAspectRatio *= 2;
				maxAspectRatio /= 2;
				if (minAspectRatio > 0.5)
					minAspectRatio = 0.5;
				if (maxAspectRatio < 2 )
					minAspectRatio = 2;
				if (minNodes == 0)
					minNodes += maxNodes/20;
			} else if (numLoops > 0){
				// no correct solution found, try also with "unnatural" split lines
				spread = Math.min(spread+1, 3);
			}

			if (saveMaxAspectRatio != maxAspectRatio || saveMinAspectRatio != minAspectRatio || saveMinNodes != minNodes){
				System.out.println("criterias were changed, resetting cache");
				cache.clear();
			}
		} 
		List<Area> res = convert(bestResult, maxNodes);
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
		if (ratio <= 0.0){
			assert Boolean.TRUE;
		}
		return ratio;
	}
	
	/** Get the actual split tiles
	 * 
	 * @param tile the tile that is to be split
	 * @param splitX the horizontal split line
	 * @return
	 */
	protected Tile[] splitHoriz(Tile tile, int splitX) {
		Tile left = new Tile(tile.x,tile.y,splitX,tile.height);
		Tile right = new Tile(tile.x+splitX,tile.y,tile.width-splitX,tile.height);
		Tile[] result = {left,right};  
		return result;
	}

	/** Get the actual split tiles 
	 * 
	 * @param tile the tile that is to be split
	 * @param splitY the vertical split line
	 * @return
	 */
	protected Tile[] splitVert(Tile tile, int splitY) {
		Tile bottom = new Tile(tile.x,tile.y,tile.width,splitY);
		Tile top = new Tile(tile.x,tile.y+splitY,tile.width,tile.height - splitY);
		Tile[] result = {bottom,top};
		return result;
	}

	/**
	 * Find the best position for a horizontal split.
	 * 
	 * @param tile the tile that is to be split
	 * @return the horizontal split line
	 */
	protected int getSplitHoriz(Tile tile) {
		if (tile.count == 0)
			return 0;
		long sum = 0;
		long target = tile.count / 2;
		for (int x = 0; x < tile.width; x++) {
			for (int y = 0; y < tile.height; y++) {
				long count = allDensities.getNodeCount(x+tile.x, y+tile.y);
				sum += count;
				if (sum > target){
					if (x == 0 ){
						return 1;
					}
					return x;
				}
			}
		}
		return tile.width;
	}
	/**
	 * Find the best position for a vertical split.
	 * 
	 * @param tile the tile that is to be split
	 * @return the vertical split line
	 */
	protected int getSplitVert(Tile tile) {
		if (tile.count == 0)
			return 0;
		long sum = 0;
		long target = tile.count / 2;
		for (int y = 0; y < tile.height; y++) {
			for (int x = 0; x < tile.width; x++) {
				long count = allDensities.getNodeCount(x+tile.x, y+tile.y);
				sum += count;
			}
			if (sum > target){
				if (y == 0 ){
					return 1;
				}
				return y;
			}
		}
		return tile.height;
	}

	/**
	 * Convert the list of Tile instances to Area instances, report some stats
	 * @param tiles list of tiles
	 * @return list of areas
	 */
	private List<Area> convert(List<Tile> tiles, long maxNodes){
		List<Area> result = new ArrayList<Area>();
		int num = 1;
		int shift = allDensities.getShift();
		int minLat = allDensities.getBounds().getMinLat();
		int minLon = allDensities.getBounds().getMinLong();
		String note;
		for (Tile tile:tiles){
			tile.trim();
			Area area = new Area(minLat+(tile.y << shift), 
					minLon+ (tile.x << shift), 
					minLat+((int)tile.getMaxY() << shift), 
					minLon+((int)tile.getMaxX() << shift)); 
			if (tile.count > maxNodes )
				note = " but is already at the minimum size so can't be split further";
			else
				note = "";
			System.out.println("Area " + num++ + " covers " + area + " and contains " + tile.count + " nodes" + note);
			result.add(area);
		}
		return result;
		
	}
	/**
	 * Try to split the tile into nice parts recursively 
	 * @param depth the recursion depth
	 * @param tile the tile to be split
	 * @param maxNodes max number of nodes that should be in one tile
	 * @param tiles the list of parts
	 * @return true if a solution was found
	 */
	private boolean findSolution(int depth, final Tile tile, long maxNodes, List<Tile> tiles){
		int checkRes = check(depth, tile, maxNodes, tiles);
		if (checkRes == OK_RETURN)
			return true;
		else if (checkRes == NOT_OK)
			return false;
		int splitX = getSplitHoriz(tile);
		int splitY = getSplitVert(tile);
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
				parts = splitHoriz(tile, dx.get(currX++));
				assert parts[0].width+ parts[1].width == tile.width;
			}
			else {
				parts = splitVert(tile, dy.get(currY++));
				assert parts[0].height + parts[1].height == tile.height;
			}
			assert parts[0].count + parts[1].count == tile.count;
			parts[0].trim();
			parts[1].trim();
			
			boolean resPart1 = false; 
			int currentAreas = tiles.size();
			if (cache.contains(parts[0])){
				resPart1 = false;
			}
			else 
				resPart1 = findSolution(depth+1,parts[0],maxNodes, tiles);
			if (resPart1 == true){
				if (cache.contains(parts[1]))
					res = false;
				else
					res = findSolution(depth+1,parts[1],maxNodes, tiles);
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
	
	/**
	 * find those areas that build rectangles when they are 
	 * added together. The  
	 */
	private long getRating(List<Tile> tiles, long maxNodes){
		long rating = 0;
		for (Tile tile: tiles){
			double aspectRatio = getAspectRatio(tile);
			if (aspectRatio < 1)
				aspectRatio = 1.0d/aspectRatio;
			if (aspectRatio > 1)
				rating += aspectRatio*100;
		}
		return rating;
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
	private int check(int depth, Tile tile, long maxNodes, List<Tile> tiles){
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
			tiles.add(new Tile(tile));
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
			count = 0;
			for (int i=0;i<width;i++){
				for (int j=0;j<height;j++){
					count += allDensities.getNodeCount(x+i, y+j);
				}
			}
		}

		public Tile(Tile other) {
			super(other);
			this.count = other.count; 
		}

		public void add(Tile other){
			super.add(other);
			this.count += other.count;
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
	}
	
}


