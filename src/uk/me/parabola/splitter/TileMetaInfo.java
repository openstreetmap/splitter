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

import java.util.Arrays;


/**
 * A helper class to store all kind of
 * information which cannot be easily calculated
 * @author GerdP
 */
class TileMetaInfo {
	private long minNodes;
	private final long[] rowSums;
	private final long[] colSums;
	private final Tile[] parts = new Tile[2];
	private int validStartX = -1;
	private int validStartY = -1;
	private int firstNonZeroX = -1;
	private int firstNonZeroY = -1;
	private int lastNonZeroX = -1;
	private int lastNonZeroY = -1;
	private long vertMidSum = -1;
	private long horMidSum = -1;
	private int vertMidPos = -1;
	private int horMidPos = -1;
	private int validEndX = -1;
	private int validEndY = -1;
	
	/**
	 * Copy information from parent tile to child. Reusing these values
	 * saves a lot of time.
	 * @param tile
	 * @param parent
	 * @param smiParent
	 */
	public TileMetaInfo(Tile tile, Tile parent, TileMetaInfo smiParent) {
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
		if (smiParent != null)
			this.minNodes = smiParent.minNodes;
	}

	/**
	 * Set new minNodes value. This invalidates cached values if the value is
	 * different to the previously used one.
	 * @param minNodes
	 */
	public void setMinNodes(long minNodes){
		if (this.minNodes == minNodes)
			return;
		this.minNodes = minNodes;
		this.validStartX = -1;
		this.validStartY = -1;
		this.validEndX = -1;
		this.validEndY = -1;
	}
	
	public int getValidStartX() {
		return validStartX;
	}

	public void setValidStartX(int validStartX) {
		this.validStartX = validStartX;
	}

	public int getValidStartY() {
		return validStartY;
	}

	public void setValidStartY(int validStartY) {
		this.validStartY = validStartY;
	}

	public int getFirstNonZeroX() {
		return firstNonZeroX;
	}

	public void setFirstNonZeroX(int firstNonZeroX) {
		this.firstNonZeroX = firstNonZeroX;
	}

	public int getFirstNonZeroY() {
		return firstNonZeroY;
	}

	public void setFirstNonZeroY(int firstNonZeroY) {
		this.firstNonZeroY = firstNonZeroY;
	}

	public int getLastNonZeroX() {
		return lastNonZeroX;
	}

	public void setLastNonZeroX(int lastNonZeroX) {
		this.lastNonZeroX = lastNonZeroX;
	}

	public int getLastNonZeroY() {
		return lastNonZeroY;
	}

	public void setLastNonZeroY(int lastNonZeroY) {
		this.lastNonZeroY = lastNonZeroY;
	}

	public long getVertMidSum() {
		return vertMidSum;
	}

	public void setVertMidSum(long vertMidSum) {
		this.vertMidSum = vertMidSum;
	}

	public long getHorMidSum() {
		return horMidSum;
	}

	public void setHorMidSum(long horMidSum) {
		this.horMidSum = horMidSum;
	}

	public int getVertMidPos() {
		return vertMidPos;
	}

	public void setVertMidPos(int vertMidPos) {
		this.vertMidPos = vertMidPos;
	}

	public int getHorMidPos() {
		return horMidPos;
	}

	public void setHorMidPos(int horMidPos) {
		this.horMidPos = horMidPos;
	}

	public long getMinNodes() {
		return minNodes;
	}

	public long[] getRowSums() {
		return rowSums;
	}

	public long[] getColSums() {
		return colSums;
	}

	public Tile[] getParts() {
		return parts;
	}

	public int getValidEndX() {
		return validEndX;
	}

	public void setValidEndX(int pos) {
		this.validEndX = pos;
	}

	public int getValidEndY() {
		return validEndY;
	}

	public void setValidEndY(int pos) {
		this.validEndY = pos;
	}
	
	/**
	 * Copy the information back from child to parent so that next child has more info.
	 * @param smiParent
	 * @param tile
	 * @param parent
	 */
	void propagateToParent(TileMetaInfo smiParent, Tile tile, Tile parent){
		if (parent.width == tile.width){
			int destPos = tile.y - parent.y;
			System.arraycopy(this.rowSums, 0, smiParent.rowSums, destPos, this.rowSums.length);
			if (destPos == 0) {
				if (smiParent.firstNonZeroY < 0 && this.firstNonZeroY >= 0)
					smiParent.firstNonZeroY = this.firstNonZeroY;
				if (smiParent.validStartY < 0 && this.validStartY >= 0)
					smiParent.validStartY = this.validStartY;
			} else {
				if (smiParent.lastNonZeroY < 0 && this.lastNonZeroY >= 0){
					smiParent.lastNonZeroY = destPos + this.lastNonZeroY;
					assert smiParent.lastNonZeroY <= parent.height;
				}
				if (smiParent.validEndY < 0 && this.validEndY >= 0){
					smiParent.validEndY = destPos + this.validEndY;
					assert smiParent.validEndY <= parent.height;
				}
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
				if (smiParent.lastNonZeroX < 0 && this.lastNonZeroX >= 0){
					smiParent.lastNonZeroX = destPos + this.lastNonZeroX;
					assert parent.getColSum(smiParent.lastNonZeroX) > 0;
				}
				if (smiParent.validEndX < 0 && this.validEndX >= 0){
					smiParent.validEndX = destPos + this.validEndX;
					assert smiParent.validEndX <= parent.width;
				}
			}
		}
//		verify(tile);
//		smiParent.verify(parent);
	}

	boolean verify(Tile tile){
		if (firstNonZeroX >= 0){
			assert tile.getColSum(firstNonZeroX) > 0;
			for (int i = 0; i < firstNonZeroX; i++)
				assert tile.getColSum(i) == 0;
		}
		if (lastNonZeroX >= 0){
			assert tile.getColSum(lastNonZeroX) > 0;
			for (int i = lastNonZeroX+1; i < tile.width; i++)
				assert tile.getColSum(i) == 0;
		}
		if (validEndX >= 0){
			long sum = 0;
			for (int i = validEndX; i < tile.width; i++){
				sum += tile.getColSum(i);
			}
			assert sum >= minNodes;
			assert sum - tile.getColSum(validEndX) < minNodes;
		}
		if (validStartX >= 0){
			if (tile.count == 209218100){
				long dd = 4;
			}
			long sum = 0;
			for (int i = 0; i < validStartX; i++){
				sum += tile.getColSum(i);
			}
			assert sum < minNodes;
			assert sum + tile.getColSum(validStartX) >= minNodes;
		}
		if (firstNonZeroY >= 0){
			assert tile.getRowSum(firstNonZeroY) > 0;
			for (int i = 0; i < firstNonZeroY; i++)
				assert tile.getRowSum(i) == 0;
		}
		if (lastNonZeroY >= 0){
			assert tile.getRowSum(lastNonZeroY) > 0;
			for (int i = lastNonZeroY+1; i < tile.height; i++)
				assert tile.getRowSum(i) == 0;
		}
		if (validStartY >= 0){
			long sum = 0;
			for (int i = 0; i < validStartY; i++){
				sum += tile.getRowSum(i);
			}
			assert sum < minNodes;
			assert sum + tile.getRowSum(validStartY) >= minNodes;
		}
		if (validEndY >= 0){
			long sum = 0;
			for (int i = validEndY; i < tile.height; i++){
				sum += tile.getRowSum(i);
			}
			assert sum >= minNodes;
			assert sum - tile.getRowSum(validEndY) < minNodes;
		}
		return false;
	}

}