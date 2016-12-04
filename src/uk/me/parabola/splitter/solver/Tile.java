/*
 * Copyright (c) 2014, Gerd Petermann
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

package uk.me.parabola.splitter.solver;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.Utils;

import java.awt.Rectangle;

/**
	 * This class implements a "view" on a rectangle covering a part
	 * of a {@link DensityMap}. 
	 * It contains the sum of all nodes in this area and has methods to
	 * help splitting it into smaller parts.
	 * 
	 * We want to keep the memory footprint of this class small as
	 * many instances are kept in maps. 
	 * @author GerdP
	 *
	 */
	class Tile extends Rectangle{
		/**
		 * 
		 */
		private final EnhancedDensityMap densityInfo;
		private final long count;
		
		/**
		 * Create tile for whole density map.
		 * @param densityInfo
		 */
		public Tile(EnhancedDensityMap densityInfo) {
			this(densityInfo, 0, 0, densityInfo.getDensityMap().getWidth(), 
					densityInfo.getDensityMap().getHeight(),
					densityInfo.getNodeCount());
		}

		/**
		 * create a tile with unknown number of nodes
		 * @param r the rectangle
		 * @param densityInfo
		 */
		public Tile(EnhancedDensityMap densityInfo, Rectangle r) {
			super(r);
			if (r.x < 0 || r.y < 0
				|| r.x + r.width > densityInfo.getDensityMap().getWidth()
				|| r.y + r.height > densityInfo.getDensityMap().getHeight())
			throw new IllegalArgumentException("Rectangle doesn't fit into density map");
			
			this.densityInfo = densityInfo;
			count = calcCount();
		}

		/**
		 * create a tile with a known number of nodes
		 * @param densityInfo
		 * @param x
		 * @param y
		 * @param width
		 * @param height
		 * @param count caller must ensure that this value is correct. See also verify()
		 */
		private Tile(EnhancedDensityMap densityInfo, int x,int y, int width, int height, long count) {
			super(x,y,width,height);
			this.densityInfo = densityInfo;
			this.count = count; 
//			if (!verify()){
//				System.out.println(count + " <> " + calcCount());
//				assert false;
//			}
		}

		public long getCount() {
			return count;
		}

		/**
		 * @return true if the saved count value is correct. 
		 */
		public boolean verify(){
			return (getCount() == calcCount()); 
		}
		
		public IntArrayList genXTests(TileMetaInfo smi) {
			int start = this.findValidStartX(smi);
			int end = this.findValidEndX(smi);
			return genTests(start, end);
		}

		public IntArrayList genYTests(TileMetaInfo smi) {
			int start = this.findValidStartY(smi);
			int end = this.findValidEndY(smi);
			return genTests(start, end);
		}
		
		public static IntArrayList genTests(int start, int end) {
			if (end-start < 0)
				return new IntArrayList(1);
			int mid = (start + end) / 2;
			int toAdd = end-start+1;
			IntArrayList list = new IntArrayList(toAdd);
			for (int i = 0; i <= mid; i++){
				int pos = mid + i;
				if (pos >= start && pos <= end)
					list.add(pos);
				if (list.size() >= toAdd)
					break;
				if (i == 0)
					continue;
				pos = mid - i;
				if (pos >= start && pos <= end)
					list.add(pos);
			}
			return list;
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
		 * Calculate the sum of all grid elements within a row
		 * @param row the row within the tile (0..height-1)
		 * @return
		 */
		public long getRowSum(int row) {
			assert row >= 0 && row < height;
			int mapRow = row + y;
			long sum = 0;
			int[] vector = densityInfo.getMapRow(mapRow);
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
		public long getColSum(int col) {
			assert col >= 0 && col < width;
			int mapCol = col + x;
			long sum = 0;
			int[] vector = densityInfo.getMapCol(mapCol);
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
		 * Find first y so that sums of columns for 0-y is > count/2
		 * Update corresponding fields in smi.
		 * 
		 * @param smi fields firstNonZeroX, horMidPos and horMidSum may be updated
		 * @return true if the above fields are usable 
		 */
		public int findHorizontalMiddle(TileMetaInfo smi) {
			if (getCount() == 0 || width < 2)
				smi.setHorMidPos(0);
			else {
				int start = (smi.getFirstNonZeroX() > 0) ? smi.getFirstNonZeroX() : 0;
				long sum = 0;
				long lastSum = 0;
				long target = getCount()/2;

				for (int pos = start; pos <= width; pos++) {
					lastSum = sum;
					sum += getColSum(pos, smi.getColSums());
					if (sum == 0)
						continue;
					if (lastSum <= 0)
						smi.setFirstNonZeroX(pos);
					if (sum > target){
						if (sum - target < target - lastSum && pos + 1 < width){
							smi.setHorMidPos(pos+1); 
							smi.setHorMidSum(sum);
						} else {
							smi.setHorMidPos(pos); 
							smi.setHorMidSum(lastSum);
						}
						break;
					}
				}
			}
			return smi.getHorMidPos();
		}

		/**
		 * Find first x so that sums of rows for 0-x is > count/2. 
		 * Update corresponding fields in smi.
		 * @param smi fields firstNonZeroY, vertMidPos, and vertMidSum may be updated 
		 * @return true if the above fields are usable 
		 */
		public int findVerticalMiddle(TileMetaInfo smi) {
			if (getCount() == 0 || height < 2)
				smi.setVertMidPos(0);
			else {
				long sum = 0;
				long lastSum;
				long target = getCount()/2;
				int start = (smi.getFirstNonZeroY() > 0) ? smi.getFirstNonZeroY() : 0;
				for (int pos = start; pos <= height; pos++) {
					lastSum = sum;
					sum += getRowSum(pos, smi.getRowSums());
					if (sum == 0)
						continue;
					if (lastSum <= 0)
						smi.setFirstNonZeroY(pos);
					if (sum > target) {
						if (sum - target < target - lastSum && pos + 1 < height) {
							smi.setVertMidPos(pos + 1);
							smi.setVertMidSum(sum);
						} else {
							smi.setVertMidPos(pos);
							smi.setVertMidSum(lastSum);
						}
						break;
					}
				}
			}
			return smi.getVertMidPos();
		} 		

		/**
		 * Split at the given horizontal position.
		 * @param splitX the horizontal split line
		 * @return true if result in smi is OK
		 */
		public boolean splitHoriz(int splitX, TileMetaInfo smi) {
			if (splitX <= 0 || splitX >= width)
				return false;
			long sum = 0;
			
			if (splitX <= width / 2){
				int start = (smi.getFirstNonZeroX() > 0) ? smi.getFirstNonZeroX() : 0;
				for (int pos = start; pos < splitX; pos++) {
					sum += getColSum(pos, smi.getColSums());
				}
			} else {
				int end = (smi.getLastNonZeroX() > 0) ? smi.getLastNonZeroX() + 1: width;
				for (int pos = splitX; pos < end; pos++) {
					sum += getColSum(pos, smi.getColSums());
				}
				sum = getCount() - sum;
			}
			if (sum < smi.getMinNodes() || getCount() - sum < smi.getMinNodes())
				return false;
			assert splitX > 0 && splitX < width;
			Tile[] parts = smi.getParts();
			parts[0] = new Tile(densityInfo, x, y, splitX, height, sum);
			parts[1] = new Tile(densityInfo, x + splitX, y, width - splitX,height, getCount() - sum);
			assert smi.getParts()[0].width + smi.getParts()[1].width == this.width; 
			return true;
		}

		/**
		 * Split at the given vertical position.
		 * @param splitY the vertical split line
		 * @return true if result in smi is OK
		 */
		public boolean splitVert(int splitY, TileMetaInfo smi) {
			if (splitY <= 0 || splitY >= height)
				return false;
			long sum = 0;
			
			if (splitY <= height / 2){
				int start = (smi.getFirstNonZeroY() > 0) ? smi.getFirstNonZeroY() : 0; 
				for (int pos = start; pos < splitY; pos++) {
					sum += getRowSum(pos, smi.getRowSums());
				}
			} else {
				int end = (smi.getLastNonZeroY() > 0) ? smi.getLastNonZeroY()+1 : height; 
				for (int pos = splitY; pos < end; pos++) {
					sum += getRowSum(pos, smi.getRowSums());
				}
				sum = getCount() - sum;
			}

			if (sum < smi.getMinNodes() || getCount() - sum < smi.getMinNodes())
				return false;
			assert splitY > 0 && splitY < height;
			Tile[] parts = smi.getParts();
			parts[0] = new Tile(densityInfo, x, y, width, splitY, sum);
			parts[1] = new Tile(densityInfo, x, y + splitY, width, height- splitY, getCount()- sum);
			assert parts[0].height + parts[1].height == this.height;
			
			return true;
		}

		/**
		 * 
		 * @param smi
		 * @return lowest horizontal position at which a split will work regarding minNodes
		 */
		public int findValidStartX(TileMetaInfo smi) {
			if (smi.getValidStartX() >= 0)
				return smi.getValidStartX();
			long sum = 0;
			int start = (smi.getFirstNonZeroX() > 0) ? smi.getFirstNonZeroX() : 0;
			for (int i = start; i < width; i++) {
				sum += getColSum(i, smi.getColSums());
				if (sum == 0)
					continue;
				if (smi.getFirstNonZeroX() < 0)
					smi.setFirstNonZeroX(i);
				if (sum >= smi.getMinNodes()) {
					int splitPos = i + 1;
					smi.setValidStartX(splitPos);
					return splitPos;
				}
			}
			smi.setValidStartX(width);
			return width;
		}

		/**
		 * 
		 * @param smi
		 * @return highest position at which all columns on the right have a sum < minNodes
		 */
		public int findValidEndX(TileMetaInfo smi) {
			if (smi.getValidEndX() < 0){
				int end = smi.getLastNonZeroX() > 0 ? smi.getLastNonZeroX() : width - 1; 
				long sum = 0;
				for (int i = end; i >= 0; --i) {
					sum += getColSum(i, smi.getColSums());
					if (sum > 0 && smi.getLastNonZeroX() < 0)
						smi.setLastNonZeroX(i);
					if (sum >= smi.getMinNodes()){
						smi.setValidEndX(i);
						break;
					}
				}
			}
			return smi.getValidEndX();
		}
		
		/**
		 * 
		 * @param smi
		 * @return lowest vertical position at which a split will work regarding minNodes 
		 * or height if no such position exists
		 */
		public int findValidStartY(TileMetaInfo smi) {
			if (smi.getValidStartY() > 0)
				return smi.getValidStartY();
			long sum = 0;
			int start = (smi.getFirstNonZeroY() > 0) ? smi.getFirstNonZeroY() : 0;
			for (int i = start; i < height; i++) {
				sum += getRowSum(i, smi.getRowSums());
				if (sum == 0)
					continue;
				if (smi.getFirstNonZeroY() < 0)
					smi.setFirstNonZeroY(i);
				if (sum >= smi.getMinNodes()){
					int splitPos = i+1;
					smi.setValidStartY(splitPos);
					return splitPos;
				}
			}
			smi.setValidStartY(height);
			return height;
		}

		/**
		 * 
		 * @param smi
		 * @return highest position at which all upper rows have a sum < minNodes
		 */
		public int findValidEndY(TileMetaInfo smi) {
			if (smi.getValidEndY() < 0){
				int end = smi.getLastNonZeroY() > 0 ? smi.getLastNonZeroY() : height - 1; 
				long sum = 0;
				for (int i = end; i >= 0; --i) {
					sum += getRowSum(i, smi.getRowSums());
					if (sum > 0 && smi.getLastNonZeroY() < 0)
						smi.setLastNonZeroY(i);
					if (sum >= smi.getMinNodes()){
						smi.setValidEndY(i);
						break;
					}
				}
			}
			return smi.getValidEndY();
		}
		
		public int findFirstXHigher(TileMetaInfo smi, long limit){
			long sum = 0;
			int start = (smi.getFirstNonZeroX() > 0) ? smi.getFirstNonZeroX() : 0;
			for (int i = start; i < width; i++) {
				sum += getColSum(i, smi.getColSums());
				if (sum == 0)
					continue;
				if (smi.getFirstNonZeroX() < 0)
					smi.setFirstNonZeroX(i);
				if (sum > limit){
					return i;
					
				}
			}
			return height;
		}

		public int findFirstYHigher(TileMetaInfo smi, long limit){
			long sum = 0;
			int start = (smi.getFirstNonZeroY() > 0) ? smi.getFirstNonZeroY() : 0;
			for (int i = start; i < height; i++) {
				sum += getRowSum(i, smi.getRowSums());
				if (sum == 0)
					continue;
				if (smi.getFirstNonZeroY() < 0)
					smi.setFirstNonZeroY(i);
				if (sum > limit){
					return i;
				}
			}
			return height;
		}

		
		/**
		 *  
		 * @return aspect ratio of this tile
		 */
		public double getAspectRatio() {
			return densityInfo.getAspectRatio(this);
		}
		
		/**
		 * Calculate the trimmed tile so that it has no empty outer rows or columns.
		 * Does not change the tile itself.
		 * @return the trimmed version of the tile.
		 */
		public Tile trim() {
			long sumRemovedColCounts = 0;
			long sumRemovedRowCounts = 0;
			int minX = -1;
			for (int i = 0; i < width; i++) {
				long colSum = getColSum(i) ; 
				boolean needed = (densityInfo.getPolygonArea() == null) ? colSum > 0 : (colOutsidePolygon(i) == false);
				if (needed){
					minX = x + i;
					break;
				}
				sumRemovedColCounts += colSum;
			}
			int maxX = -1;
			for (int i = width - 1; i >= 0; i--) {
				long colSum = getColSum(i) ; 
				boolean needed = (densityInfo.getPolygonArea() == null) ? colSum > 0 : (colOutsidePolygon(i) == false);
				if (needed){
					maxX = x + i;
					break;
				}
				sumRemovedColCounts += colSum;
			}
			int minY = -1;
			for (int i = 0; i < height; i++) {
				long rowSum = getRowSum(i);
				boolean needed = (densityInfo.getPolygonArea() == null) ? rowSum > 0 : (rowOutsidePolygon(i) == false);
				if (needed){
					minY = y + i;
					break;
				}
				sumRemovedRowCounts += rowSum;
			}
			int maxY = -1;
			for (int i = height - 1; i >= 0; i--) {
				long rowSum = getRowSum(i);
				boolean needed = (densityInfo.getPolygonArea() == null) ? rowSum > 0 : (rowOutsidePolygon(i) == false);
				if (needed){
					maxY = y + i;
					break;
				}
				sumRemovedRowCounts += rowSum;
			}
			assert minX <= maxX;
			assert minY <= maxY;
			assert maxX >= 0;
			assert maxY >= 0;
			long newCount = getCount();
			int modWidth = maxX - minX + 1;
			int modHeight = maxY - minY + 1;
			if (densityInfo.getPolygonArea() != null){
				if (modWidth != width || modHeight != height){
					// tile was trimmed, try hard to avoid a new costly calculation of the count value
					if (width == modWidth){
						newCount = getCount() - sumRemovedRowCounts; 
					} else if (height == modHeight){
						newCount = getCount() - sumRemovedColCounts;
					} else {
//						System.out.printf("ouch: %d %d %d %d (%d) -> %d %d %d %d\n",x,y,width,height,count,minX,minY, maxX - minX + 1, maxY - minY + 1 );
						return new Tile (densityInfo, new Rectangle(minX, minY, modWidth, modHeight));
					}
				}
			}
			return new Tile(densityInfo, minX, minY, modWidth, modHeight, newCount);
		}

		private boolean rowOutsidePolygon(int row) {
			if (densityInfo.getPolygonArea() == null)
				return false;
			// performance critical part, check corners first
			if (densityInfo.isGridElemInPolygon(x, y + row) || densityInfo.isGridElemInPolygon(x + width-1, y + row))
				return false;
			// check rest of row
			for (int i = 1; i < width-1; i++) {
				if (densityInfo.isGridElemInPolygon(x + i, y + row))
					return false;
			}
			return true;
		}

		private boolean colOutsidePolygon(int col) {
			if (densityInfo.getPolygonArea() == null)
				return false;
			// performance critical part, check corners first
			if (densityInfo.isGridElemInPolygon(x + col, y) || densityInfo.isGridElemInPolygon(x + col, y + height - 1))
				return false;
			// check rest of column
			for (int i = 1; i < height - 1; i++) {
				if (densityInfo.isGridElemInPolygon(x + col, y + i))
					return false;
			}
			return true;
		}

		public boolean outsidePolygon(){
			java.awt.geom.Area polygonArea = densityInfo.getPolygonArea();
			if (polygonArea == null)
				return false;
			if (polygonArea.intersects(getRealBBox()))
				return false;
			return true;
		}

		/**
		 * Count the number of grid elements which are outside of the polygon area,
		 * divide it by the total number of grid elements covered by this tile to
		 * get a value between 0 and 1 (including).
		 * @return
		 */
		public double calcOutsidePolygonRatio (){
			if (densityInfo.getPolygonArea() == null)
				return 0;
			Rectangle realBBox = getRealBBox();
//			if (densityInfo.getPolygonArea().contains(realBBox) )
//				return 0;
			// check special case: tile may contain the polygon
			Rectangle polyBBox = densityInfo.getPolygonArea().getBounds();
			if (realBBox.contains(polyBBox)){
				return 0;
			}
			int countOutside = 0;
			for (int i = x; i < x+width; i++){
				for (int j = y; j < y+height; j++){
					if (densityInfo.isGridElemInPolygon(i,j) == false)
						countOutside++;
				}
			}
			double ratio = (double) countOutside  / (width * height) ;
			return ratio;
		}
		
		public Rectangle getRealBBox(){
			int shift = densityInfo.getDensityMap().getShift();
			int polyYPos = densityInfo.getDensityMap().getBounds().getMinLat() + (y << shift);
			int polyXPos = densityInfo.getDensityMap().getBounds().getMinLong() + (x << shift);
			return new Rectangle(polyXPos, polyYPos, width<<shift, height<<shift);
		}

		@Override
		public int hashCode() {
			int hash = x << 24 | y << 16 | width << 8 | height;
			return hash;
		}
		
		@Override
		public String toString(){
			Area area = densityInfo.getDensityMap().getArea(x,y,width,height); 
			return  (area.toString() + " with " + Utils.format(getCount()) + " nodes");
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
//			sb.append(" nodes and ratio ");
//			sb.append(getAspectRatio());
//			return sb.toString(); 		
		}
	}
