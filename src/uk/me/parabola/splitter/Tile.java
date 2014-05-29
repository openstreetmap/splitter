package uk.me.parabola.splitter;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.awt.Rectangle;

/**
	 * This class implements a "view" on a rectangle covering a part
	 * of a {@link DensityMap}. 
	 * It contains the sum of all nodes in this area and has methods to
	 * help splitting it into smaller parts.
	 * 
	 * It extends java.awt.Rectangle because that implements a useful 
	 * hashCode method. 
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
		public final long count;
		
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
		}

		/**
		 * @return true if the saved count value is correct. 
		 */
		public boolean verify(){
			return (count == calcCount()); 
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
			if (count == 0 || width < 2)
				smi.setHorMidPos(0);
			else {
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
			if (count == 0 || height < 2)
				smi.setVertMidPos(0);
			else {
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
						break;
					}
				}
			}
			return smi.getVertMidPos();
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
			assert splitX > 0 && splitX < width;
			Tile[] parts = smi.getParts();
			parts[0] = new Tile(densityInfo, x, y, splitX, height, sum);
			parts[1] = new Tile(densityInfo, x + splitX, y, width - splitX,height, count - sum);
			assert smi.getParts()[0].width + smi.getParts()[1].width == this.width; 
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
			assert splitY > 0 && splitY < height;
			Tile[] parts = smi.getParts();
			parts[0] = new Tile(densityInfo, x, y, width, splitY, sum);
			parts[1] = new Tile(densityInfo, x, y + splitY, width, height- splitY, count- sum);
			assert parts[0].height + parts[1].height == this.height;
			
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