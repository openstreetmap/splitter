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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a density map into multiple areas, none of which
 * exceed the desired threshold.
 *
 * @author GerdP
 */
public class SplittableDensityArea implements SplittableArea {
	private static final int MAX_LOOPS = 100;	// number of loops to find better solution
	private double minAspectRatio = Double.MAX_VALUE;
	private double maxAspectRatio = Double.MIN_VALUE;
	private long minNodes = Long.MAX_VALUE;
	private String stack = "";
	private DensityMap allDensities;
	private int spread = 0;
	private int goBack = -1;
	private long startSplit = System.currentTimeMillis();
	private String next;
	private long bestRating = Long.MAX_VALUE;
	private List<Tile> bestResult;
	private double[] aspectRatioFactor;

	public SplittableDensityArea(DensityMap densities) {
		this.allDensities = densities;
		if (densities.getWidth() == 0 || densities.getHeight() == 0)
			return;
		aspectRatioFactor = new double[densities.getHeight()+1];
		int minLat = densities.getBounds().getMinLat(); 
		int maxLat = densities.getBounds().getMaxLat();
		int lat = 0;
		// calculate once the needed complex math functions
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

		List<Tile> tiles;
		long numLoops = 0;
		minAspectRatio = 1.0;
		maxAspectRatio = 1;
		minNodes = maxNodes / 3;
		if (maxNodes < 500000)
			minNodes = 0;
		stack = "";
		next = "";
		while (numLoops < MAX_LOOPS){
			tiles = new ArrayList<Tile>();
			boolean res;
			do{
				if (next.isEmpty()){
					if (minNodes == 0){
						minAspectRatio = 0.0;
						maxAspectRatio = 128;
						
					}
					else {
						minAspectRatio /= 2;
						maxAspectRatio *= 2;
						if (maxAspectRatio >= 16){
							spread = 1;
						}
						if (maxAspectRatio >= 64){
							minAspectRatio = 0.0;
							spread = 2;
						}
					}
				}
				numLoops++;
				Tile startTile = new Tile(0,0,allDensities.getWidth(),allDensities.getHeight());
				res = findSolutions(startTile, maxNodes, "", tiles);
				if (res == false && spread <= 2) 
					++spread;
			}while(res == false && numLoops < MAX_LOOPS);
			if (bestResult == null)
				bestResult = tiles;
			if (numLoops % 10 == 0)
				System.out.println(numLoops + " solutions tested.");
			if (stack.isEmpty())
				break;
			next = stack;
			stack = "";
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
	public double getAspectRatio(Tile tile) {
		int width1 = (int) (tile.width * aspectRatioFactor[tile.y]);
		int width2 = (int) (tile.width * aspectRatioFactor[tile.y+tile.height]);
		int width = Math.max(width1, width2);		
		double ratio = ((double)width)/(tile.height << allDensities.getShift());
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
	List<Area> convert(List<Tile> tiles, long maxNodes){
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
	 * 
	 * @param densities
	 * @param maxNodes
	 * @param splitPath
	 * @param tiles
	 * @return
	 */
	private boolean findSolutions(Tile tile, long maxNodes, String splitPath, List<Tile> tiles){
		boolean addThis = false;
		/*
		if (++tested > 100000 && bestResult == null){
			if (goBack < 0){
				goBack = splitPath.length() / 6 / 2;
				if (spread > 0)
					goBack = 1;
				return false;
			}
			else if (goBack >= 0){ 
				if (splitPath.length() > 6*goBack)
					return false;
				else {
					goBack = -1;
					tested = 0;
				}
			}
		}
		*/
		if (tile.count == 0){
			return true;
		} else if (tile.count > maxNodes && tile.width == 1 && tile.height == 1) {
			addThis = true; // can't split further
		} else if (tile.count < minNodes && splitPath.isEmpty()) {
			addThis = true; // nothing to do
		} else if (tile.count < minNodes){
			return false;
		} else if (tile.count <= maxNodes) {
			double ratio = getAspectRatio(tile);
			if (ratio < minAspectRatio || ratio > maxAspectRatio)
				return false;
			addThis = true;
		} else if (tile.width < 2 && tile.height < 2)
			return false;

		if (addThis){
			tiles.add(new Tile(tile));
			return true;
		}
		int splitX = getSplitHoriz(tile);
		int splitY = getSplitVert(tile);
		ArrayList<Integer> dx = new ArrayList<Integer>();
		ArrayList<Integer> dy = new ArrayList<Integer>();
		for (int i = spread; i < Math.min(spread+1, splitX); i++){
			int pos = splitX+i;
			if (pos > 0 && pos < tile.width)
				dx.add(pos);
			if (i>0){
				pos = splitX-i;
				if (pos > 0 && pos < tile.width)
					dx.add(pos);
			}
		}
		for (int i = spread; i < Math.min(spread+1, splitY); i++){
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
		if (next.isEmpty()){
			axis = (getAspectRatio(tile) >= 1.0) ? "H":"V";
		}
		else {
			assert next.matches("[HV][0-9],[0-9].*");
			axis = next.substring(0, 1);
			currX = Integer.valueOf(next.substring(1, 2));
			currY = Integer.valueOf(next.substring(3, 4));
			if (next.length() >= 6)
				next = next.substring(6);
			else {
				next = "";
				if ("H".equals(axis)){
					axis = "V";
					currX++;
				} else {
					axis = "H";
					currY++;
				}
			}
		}
		boolean ok = false;
		
		while(true){
			Tile[] parts;
			String status = splitPath+axis+currX+","+currY;			
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
			boolean r1 = false,r2=false; 
			int currentAreas = tiles.size();
			r1 = findSolutions(parts[0],maxNodes, status+"p1",tiles);
			if (r1){
				r2 = findSolutions(parts[1],maxNodes, status+"p2",tiles);
				if (r2){
					ok = true;
					if (splitPath.isEmpty()){
						long rating = simplifyAndRate(tiles, maxNodes);
						if (rating < bestRating){
							bestRating = rating;
							bestResult = new ArrayList<SplittableDensityArea.Tile>();
							for (Tile t:tiles){
								bestResult.add(new Tile(t));
							}
							System.out.println("Best solution until now has " + bestResult.size() + " tiles and a rating of " + bestRating);
						}
						
					}
					else{ 
						if (currX < dx.size() || currY < dy.size()) {
							if (stack.isEmpty() )
								stack = status; // we want to come here again
						}
						break;
					}
				}
			}
			if (!ok){
				while(tiles.size() > currentAreas){
					tiles.remove(tiles.size()-1);
				}
			}
			if (currX >= dx.size() && currY >= dy.size())
				break;
			axis = "H".equals(axis) ? "V":"H";
			if (goBack >= 0 && goBack*6 <= splitPath.length()){
				break;
			}
				
		}
		return ok;
	}
	
	/**
	 * 
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
	
	/**
	 * find those areas that build rectangles when they are 
	 * added together. The  
	 */
	private long simplifyAndRate(List<Tile> tiles, long maxNodes){
		long rating = 0;
		List<Tile> combined = null;
		Long newSmallest = Long.MAX_VALUE;
		while (true){

			combined = new LinkedList<Tile>();
			/*
			for (int i = 0; i < tiles.size(); i++){
				Tile t1 =  tiles.get(i);
				if (t1.count < 0)
					continue;
				Tile candidate = null;
				for (int j = 0; j < tiles.size(); j++){
					if (i == j)
						continue;
					Tile t2 =  tiles.get(j);
					if (t2.count >= 0 && t2.count + t1.count <= maxNodes ){
						boolean isSimple = false;
						if (t1.y == t2.y && t1.height == t2.height 
								&& (t1.x == t2.getMaxX() || t2.x == t1.getMaxX())) 
							isSimple = true;
						else if (t1.x == t2.x && t1.width == t2.width 
								&& (t1.y == t2.getMaxY() || t2.y == t1.getMaxY()))
							isSimple = true;
						if (isSimple){
							if (candidate == null)
								candidate = t2;
							else {
								if (candidate.count < t2.count)
									candidate = t2;
							}
						}
					}
				}
				if (candidate != null){
					Tile pair = new Tile(t1);
					pair.add(candidate);
					combined.add(pair);
					//System.out.println(t1.x + " " + t1.y + " merged with " + candidate.x + " " + candidate.y);
					t1.count = -1;
					candidate.count = -1;
				}
			}
			newSmallest = Long.MAX_VALUE;
			*/
			rating = 0;
			for (int i = tiles.size()-1; i >= 0; i--){
				Tile tile = tiles.get(i);
				if (tile.count < 0)
					tiles.remove(i);
				else {
					if (newSmallest > tile.count)
						newSmallest = tile.count;
					
//					rating += tile.width  >> allDensities.getShift();
//					rating += tile.height >> allDensities.getShift();
					double ar1 = (double) tile.width / tile.height;
					if (ar1 < 1)
						ar1 = 1.0d/ar1;
					if (ar1 > 1)
						rating += ar1*100;
				}
			}
			if (combined.isEmpty())
				break;
			tiles.addAll(combined);
			combined = null;
		}
		return rating;
	}
}


