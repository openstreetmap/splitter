package uk.me.parabola.splitter;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.awt.Rectangle;

/**
	 * Helper class to store area info with node counters.
	 * The node counters use the values saved in the xyMap / yxMap.
	 * @author GerdP
	 *
	 */
	@SuppressWarnings("serial")
	class Tile extends Rectangle{
		/**
		 * 
		 */
		private final EnhancedDensityMap densityInfo;
		final long count;
		
		/**
		 * create a tile with unknown number of nodes
		 * @param x
		 * @param y
		 * @param width
		 * @param height
		 * @param splittableDensityArea TODO
		 */
		public Tile(EnhancedDensityMap densityInfo, int x,int y, int width, int height) {
			super(x,y,width,height);
			this.densityInfo = densityInfo;
			count = calcCount();
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
		 * @param densityInfo
		 * @param x
		 * @param y
		 * @param width
		 * @param height
		 * @param count
		 */
		public Tile(EnhancedDensityMap densityInfo, int x,int y, int width, int height, long count) {
			this.densityInfo = densityInfo;
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
		 * Find the horizontal middle of the tile (using the node counts).
		 * Add the offset and split at this position.   
		 * If the tile is large, the real middle is used to avoid
		 * time consuming calculations.
		 * @param offset the desired offset
		 * @return array with two parts or null in error cases
		 */
		public boolean splitHorizWithOffset(final int offset, TileMetaInfo smi, long maxNodes) {
			if (count == 0 || width < 2)
				return false;
			int middle = width / 2;
			if(count > maxNodes * 16 && width > 256)
				return splitHoriz(middle + offset, smi);
			
			int splitX = -1;
			long sum = 0;
			long lastSum = 0;
			long target = count/2;
			
			if (smi.getHorMidPos() < 0)
				findHorizontalMiddle(smi);
			splitX = smi.getHorMidPos();
			lastSum = smi.getHorMidSum();
			boolean checkMove = false;
			if (splitX == 0)
				lastSum += getColSum(splitX++, smi.getColSums());
			else 
				checkMove = true;


			int splitPos = splitX + offset;
			if (splitPos <= 0 || splitPos >= width)
				return false;
			
			if (offset > 0){
				if (width - splitPos < offset)
					return splitHoriz(splitPos, smi);
				
				for (int i = 0; i < offset; i++){
					lastSum += getColSum(splitX + i, smi.getColSums());
				}
				
			} else if (offset < 0){
				if (splitPos < -offset)
					return splitHoriz(splitPos, smi);
				int toGo = offset;
				while (toGo != 0){
					// we can use the array here because we can be sure that all used fields are filled
					// the loop should run forward as this seems to be faster
					lastSum -= smi.getColSums()[splitX + toGo++]; 
				}
			}
			sum = lastSum + getColSum(splitPos, smi.getColSums()); 
			if (checkMove && offset >= 0 && splitPos + 1 < width  && target - lastSum > sum - target){
				lastSum = sum;
				splitPos++;
			}
			if (lastSum < smi.getMinNodes() || count - lastSum < smi.getMinNodes())
				return false;
			assert splitX > 0 && splitX < width; 
			smi.getParts()[0] = new Tile(densityInfo, x, y, splitPos, height, lastSum);
			smi.getParts()[1] = new Tile(densityInfo, x + splitPos, y, width - splitPos,height, count -lastSum);
			assert smi.getParts()[0].width + smi.getParts()[1].width == this.width; 
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
		public boolean splitVertWithOffset(int offset, TileMetaInfo smi, long maxNodes) {
			if (count == 0 || height < 2)
				return false;
			int middle = height/2;
			if (count > maxNodes * 16 && height > 128)
				return splitVert(middle + offset, smi);
			long target = count/2;
			int splitY = -1;
			long sum = 0;
			long lastSum = 0;
			if (smi.getVertMidPos() < 0)
				findVerticalMiddle(smi);
			splitY = smi.getHorMidPos();
			lastSum = smi.getHorMidSum();
			boolean checkMove = false;
			if (splitY == 0)
				lastSum += getRowSum(splitY++, smi.getRowSums());
			else 
				checkMove = true;

			int splitPos = splitY + offset;
			if (splitPos <= 0 || splitPos >= height)
				return false;
			
			if (offset > 0){
				if (height - splitPos < offset)
					return splitVert(splitPos, smi);
				
				for (int i = 0; i < offset; i++){
					lastSum += getRowSum(splitY + i, smi.getRowSums());
				}
				
			} else if (offset < 0){
				if (splitPos < -offset)
					return splitVert(splitPos, smi);
				int toGo = offset;
				while (toGo != 0){
					// we can use the array here because we can be sure that all used fields are filled
					// the loop should run forward as this seems to be faster
					lastSum -= smi.getRowSums()[splitY + toGo++]; 
				}
			}
			sum = lastSum + getRowSum(splitPos, smi.getRowSums()); 
			if (checkMove && offset >= 0 && splitPos + 1 < height  && target - lastSum > sum - target){
				lastSum = sum;
				splitPos++;
			}
			if (lastSum < smi.getMinNodes() || count - lastSum < smi.getMinNodes())
				return false;
			splitAtY(splitPos, lastSum, smi);
			return true;
			
		}
		
		private void splitAtX(int splitPos, long sum1, TileMetaInfo smi) {
			smi.getParts()[0] = new Tile(densityInfo, x, y, splitPos, height, sum1);
			smi.getParts()[1] = new Tile(densityInfo, x + splitPos, y, width - splitPos,height, count - sum1);
			assert smi.getParts()[0].width + smi.getParts()[1].width == this.width; 
			
		}
		private void splitAtY(int splitPos, long sum1, TileMetaInfo smi) {
			smi.getParts()[0] = new Tile(densityInfo, x, y, width, splitPos, sum1);
			smi.getParts()[1] = new Tile(densityInfo, x, y + splitPos, width, height- splitPos, count- sum1);
			assert smi.getParts()[0].height + smi.getParts()[1].height == this.height;
			
		}

		/**
		 * Find first y so that sums of columns for 0-y is > count/2
		 * Update corresponding fields in smi.
		 * 
		 * @param smi fields firstNonZeroX, horMidPos and horMidSum may be updated
		 * @return true if the above fields are usable 
		 */
		public boolean findHorizontalMiddle(TileMetaInfo smi) {
			if (count == 0 || width < 2)
				return false;

			int start = (smi.getLastNonZeroX() > 0) ? smi.getLastNonZeroX() : 0;
			long sum = 0;
			long lastSum = 0;
			long target = count/2;
			
			for (int pos = start; pos <= width; pos++) {
				lastSum = sum;
				sum += getColSum(pos, smi.getColSums());
				if (lastSum <= 0 && sum > 0)
					smi.setFirstNonZeroX(pos);
				if (sum > target){
					smi.setHorMidPos(pos);
					smi.setHorMidSum(lastSum);
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
		public boolean findVerticalMiddle(TileMetaInfo smi) {
			if (count == 0 || height < 2)
				return false;
			
			long sum = 0;
			long lastSum;
			long target = count/2;
			int start = (smi.getFirstNonZeroY() > 0) ? smi.getFirstNonZeroY() : 0;
			for (int pos = start; pos <= height; pos++) {
				lastSum = sum;
				sum += getRowSum(pos, smi.getRowSums());
				if (lastSum <= 0 && sum > 0)
					smi.setFirstNonZeroY(pos);
				
				if (sum > target){
					smi.setVertMidPos(pos);
					smi.setVertMidSum(lastSum);
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
				sum = count - sum;
			}
			if (sum < smi.getMinNodes() || count - sum < smi.getMinNodes())
				return false;
			splitAtX(splitX, sum, smi);
			return true;
		}

		/**
		 * Split at a desired vertical position.
		 * @param splitY the vertical split line
		 * @return array with two parts
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
				sum = count - sum;
			}

			if (sum < smi.getMinNodes() || count - sum < smi.getMinNodes())
				return false;
			splitAtY(splitY, sum, smi);
			return true;
		}

		public int findValidStartX(TileMetaInfo smi) {
			if (smi.getValidStartX() >= 0)
				return smi.getValidStartX();
			long sum = 0;
			for (int i = 0; i < smi.getColSums().length; i++) {
				sum += getColSum(i, smi.getColSums());
				if (sum >= smi.getMinNodes()){
					smi.setValidStartX(i);
					return i;
				}
			}
			smi.setValidStartX(width);
			return width;
		}

		public int findValidEndX(TileMetaInfo smi) {
			long sum = 0;
			for (int i = smi.getColSums().length - 1; i >= 0; --i) {
				sum += getColSum(i, smi.getColSums());
				if (sum >= smi.getMinNodes())
					return i;
			}
			return 0;
		}

		public int findValidStartY(TileMetaInfo smi) {
			if (smi.getValidStartY() > 0)
				return smi.getValidStartY();
			long sum = 0;
			for (int i = 0; i < height; i++) {
				sum += getRowSum(i, smi.getRowSums());
				if (sum >= smi.getMinNodes()){
					smi.setValidStartY(i);
					return i;
				}
			}
			smi.setValidStartY(height);
			return height;
		}

		public int findValidEndY(TileMetaInfo smi) {
			long sum = 0;
			for (int i = height - 1; i >= 0; --i) {
				sum += getRowSum(i, smi.getRowSums());
				if (sum >= smi.getMinNodes())
					return i;
			}
			return 0;
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
			return new Tile(densityInfo, minX, minY, maxX - minX + 1, maxY - minY + 1, count);
		} 		
		
		@Override
		public String toString(){
			Area area = densityInfo.getDensityMap().getArea(x,y,width,height); 
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