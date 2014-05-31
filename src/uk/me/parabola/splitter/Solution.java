/*
 * Copyright (C) 2014.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */ 
package uk.me.parabola.splitter;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to combine a list of tiles with some
 * values that measure the quality.
 * @author GerdP 
 * 
 */
class Solution {
	/**
	 * 
	 */
	private static enum sides {TOP,RIGHT,BOTTOM,LEFT}

	private final List<Tile> tiles;
	private final int spread;
	private final long maxNodes;
	private double worstAspectRatio = -1;
	private long worstMinNodes = Long.MAX_VALUE;
	private int depth;
	
	public Solution(int spread, long maxNodes) {
		tiles = new ArrayList<>();
		this.spread = spread;
		this.maxNodes = maxNodes;
		this.depth = Integer.MAX_VALUE;
	}

	public Solution copy(){
		Solution s = new Solution(this.spread, this.maxNodes);
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

	public List<Tile> getTiles() {
		return tiles;
	}

	public int getSpread() {
		return spread;
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
	 * Trim tiles without creating holes or gaps between tiles
	 */
	public void trimOuterTiles() {
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
		if (worstAspectRatio > SplittableDensityArea.NICE_MAX_ASPECT_RATIO)
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
		if (isEmpty())
			return "is empty";
		return tiles.size() + " tile(s). The smallest node count is " + worstMinNodes + " (" +  percentage + " %), the worst aspect ratio is near " + ratio;
		
	}
}