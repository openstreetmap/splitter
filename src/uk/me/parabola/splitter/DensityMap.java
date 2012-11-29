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
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.regex.Pattern;

/**
 * Builds up a map of node densities across the total area being split.
 * Density information is held at the maximum desired map resolution.
 * Every step up in resolution increases the size of the density map by
 * a factor of 4.
 *
 * @author Chris Miller
 */
public class DensityMap {
	private final int width, height, shift;
	private final int[][] nodeMap;
	private Area bounds;
	private long totalNodeCount;
	private boolean trim;

	/**
	 * Creates a density map.
	 * @param area the area that the density map covers.
	 * @param resolution the resolution of the density map. This must be a value between 1 and 24.
	 */
	public DensityMap(Area area, boolean trim, int resolution) {
		this.trim = trim;
		assert resolution >=1 && resolution <= 24;
		shift = 24 - resolution;

		bounds = RoundingUtils.round(area, resolution);
		height = bounds.getHeight() >> shift;
		width = bounds.getWidth() >> shift;
		nodeMap = new int[width][];
	}

	/**
	 * Trim to the bounding box of the polygon area
	 * @param polygonArea area with values in degrees
	 * @return trimmed map
	 */
	public DensityMap trimToPolygon (java.awt.geom.Area polygonArea){
		if (polygonArea == null)
			return this;
		Rectangle2D polygonBbox = polygonArea.getBounds2D();
		int minY = Math.max(0, latToY(Utils.toMapUnit(polygonBbox.getMinY()))-1);
		int maxY = Math.min(height, latToY(Utils.toMapUnit(polygonBbox.getMaxY()))+1);
		int minX = Math.max(0, lonToX(Utils.toMapUnit(polygonBbox.getMinX()))-1);
		int maxX = Math.min(width, lonToX(Utils.toMapUnit(polygonBbox.getMaxX()))+1);
		Area bounds = new Area(yToLat(minY),xToLon(minX),yToLat(maxY),xToLon(maxX));
		return subset(bounds);
	}
	
	/**
	 * Set those grid elements to zero that do not intersect with the polygon area.
	 * Make sure that intersecting elements have a count > 0.
	 * @param polygonArea the polygon area with values in degrees
	 * @return an area with rectilinear shape with values in map units 
	 */
	public java.awt.geom.Area filterWithPolygon(java.awt.geom.Area polygonArea){
		if (polygonArea == null)
			return null;
		java.awt.geom.Area simpleArea = new java.awt.geom.Area();
		double gridElemWidth = Utils.toDegrees(bounds.getWidth() / width);
		double gridElemHeight= Utils.toDegrees(bounds.getHeight()/ height);
		
		Rectangle2D polygonBbox = polygonArea.getBounds2D();
		int minY = Math.max(0, latToY(Utils.toMapUnit(polygonBbox.getMinY()))-1);
		int maxY = Math.min(height, latToY(Utils.toMapUnit(polygonBbox.getMaxY()))+1);
		
		for (int x = 0; x < width; x++) {
			double xDegrees = Utils.toDegrees(xToLon(x));
			if (xDegrees + gridElemWidth < polygonBbox.getMinX() 
					|| xDegrees > polygonBbox.getMaxX()
					|| polygonArea.intersects(xDegrees, polygonBbox.getMinY(), gridElemWidth, polygonBbox.getHeight()) == false){
				nodeMap[x] = null;
				continue;
			}
			if (nodeMap[x] == null) 
				nodeMap[x] = new int[height];
			int firstY = -1;
			for (int y = 0; y < height; y++) {
				double yDegress = Utils.toDegrees(yToLat(y));
				if (y < minY || y > maxY 
						|| polygonArea.intersects(xDegrees, yDegress, gridElemWidth, gridElemHeight) == false){
					nodeMap[x][y] = 0;
					if (firstY >= 0){
						simpleArea.add(new java.awt.geom.Area(new Rectangle(x,firstY,1,y-firstY)));
						firstY = -1; 
					}
				} else {
					// make sure that trimming doesn't produce holes 
					nodeMap[x][y] = Math.max(1, nodeMap[x][y]);
					if (firstY < 0)
						firstY = y; 
				}
			}
			if (firstY >= 0){
				simpleArea.add(new java.awt.geom.Area(new Rectangle(x,firstY,1,height-firstY)));
			}
		}
		// we need trimming  when a polygon is used
		this.trim = true;
		return simpleArea;
	}
	
	public boolean isTrim(){
		return trim == true;
	}
	
	public int getShift() {
		return shift;
	}

	public Area getBounds() {
		return bounds;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public long addNode(int lat, int lon) {
		if (!bounds.contains(lat, lon))
			return 0;

		totalNodeCount++;
		int x = lonToX(lon);
		if (x == width)
			x--;
		int y = latToY(lat);
		if (y == height)
			y--;

		if (nodeMap[x] == null)
			nodeMap[x] = new int[height];
		return ++nodeMap[x][y];
	}

	public long getNodeCount() {
		return totalNodeCount;
	}

	public int getNodeCount(int x, int y) {
		return nodeMap[x] != null ? nodeMap[x][y] : 0;
	}
	public long getNodeCount(int x, int y1, int y2) {
		long sum = 0;
		if (nodeMap[x] != null){
			int[] col = nodeMap[x];
			for (int y = y1; y < y2; y++)
				sum += col[y];
		}
		return sum;
	}

	public DensityMap subset(final Area subsetBounds) {
		int minLat = Math.max(bounds.getMinLat(), subsetBounds.getMinLat());
		int minLon = Math.max(bounds.getMinLong(), subsetBounds.getMinLong());
		int maxLat = Math.min(bounds.getMaxLat(), subsetBounds.getMaxLat());
		int maxLon = Math.min(bounds.getMaxLong(), subsetBounds.getMaxLong());

		// If the area doesn't intersect with the density map, return an empty map
		if (minLat > maxLat || minLon > maxLon) {
			return new DensityMap(Area.EMPTY, trim, 24 - shift);
		}

		Area subset = new Area(minLat, minLon, maxLat, maxLon);
		if (trim) {
			subset = trim(subset);
		}

		// If there's nothing in the area return an empty map
		if (subset.getWidth() == 0 || subset.getHeight() == 0) {
			return new DensityMap(Area.EMPTY, trim, 24 - shift);
		}

		DensityMap result = new DensityMap(subset, trim, 24 - shift);

		int startX = lonToX(subset.getMinLong());
		int startY = latToY(subset.getMinLat());
		int maxX = subset.getWidth() >> shift;
		int maxY = subset.getHeight() >> shift;
		for (int x = 0; x < maxX; x++) {
			if (startY == 0 && maxY == height) {
				result.nodeMap[x] = nodeMap[startX + x];
			} else if (nodeMap[startX + x] != null) {
				result.nodeMap[x] = new int[maxY];
				try {
					System.arraycopy(nodeMap[startX + x], startY, result.nodeMap[x], 0, maxY);
				} catch (ArrayIndexOutOfBoundsException e) {
					System.out.println("subSet() died at " + startX + ',' + startY + "  " + maxX + ',' + maxY + "  " + x);
				}
			}
			for (int y = 0; y < maxY; y++) {
				if (result.nodeMap[x] != null)
					result.totalNodeCount += result.nodeMap[x][y];
			}
		}
		return result;
	}

	/**
	 * Sets the trimmed bounds based on any empty edges around the density map
	 */
	private Area trim(Area area) {

		int minX = lonToX(area.getMinLong());
		int maxX = lonToX(area.getMaxLong());
		int minY = latToY(area.getMinLat());
		int maxY = latToY(area.getMaxLat());

		while (minX < maxX && (nodeMap[minX] == null || isEmptyX(minX, minY, maxY))) {
			minX++;
		}
		if (minX == maxX) {
			return Area.EMPTY;
		}

		while (nodeMap[maxX - 1] == null || isEmptyX(maxX - 1, minY, maxY)) {
			maxX--;
		}

		while (minY < maxY && isEmptyY(minY, minX, maxX)) {
			minY++;
		}
		if (minY == maxY) {
			return Area.EMPTY;
		}

		while (isEmptyY(maxY - 1, minX, maxX)) {
			maxY--;
		}

		assert yToLat(minY) % (1<<shift) == 0;
		assert yToLat(maxY) % (1<<shift) == 0;
		assert xToLon(minX) % (1<<shift) == 0;
		assert xToLon(maxX) % (1<<shift) == 0;
		Area trimmedArea = new Area(yToLat(minY), xToLon(minX), yToLat(maxY), xToLon(maxX));
		return trimmedArea;
	}

	private boolean isEmptyX(int x, int start, int end) {
		int[] array = nodeMap[x];
		if (array != null) {
			for (int y = start; y < end; y++) {
				if (array[y] != 0)
					return false;
			}
		}
		return true;
	}

	private boolean isEmptyY(int y, int start, int end) {
		for (int x = start; x < end; x++) {
			if (nodeMap[x] != null && nodeMap[x][y] != 0)
				return false;
		}
		return true;
	}

	private int yToLat(int y) {
		return (y << shift) + bounds.getMinLat();
	}

	private int xToLon(int x) {
		return (x << shift) + bounds.getMinLong();
	}

	private int latToY(int lat) {
		return lat - bounds.getMinLat() >>> shift;
	}

	private int lonToX(int lon) {
		return lon - bounds.getMinLong() >>> shift;
	}

	/**
	 * For debugging, to be removed. 
	 * @param fileName
	 * @param detailBounds
	 * @param collectorBounds
	 */
	public void saveMap(String fileName, Area detailBounds, Area collectorBounds) {
		try {
			FileWriter f = new FileWriter(new File(fileName));
			f.write(detailBounds.getMinLat() + "," + detailBounds.getMinLong() + "," + detailBounds.getMaxLat() + "," + detailBounds.getMaxLong() + '\n');
			f.write(collectorBounds.getMinLat() + "," + collectorBounds.getMinLong() + "," + collectorBounds.getMaxLat() + "," + collectorBounds.getMaxLong() + '\n');
			//f.write(bounds.getMinLat() + "," + bounds.getMinLong() + "," + bounds.getMaxLat() + "," + bounds.getMaxLong() + '\n');
			for (int x=0; x<width; x++){
				if (nodeMap[x] != null){
					for (int y=0; y<height; y++){
						if (nodeMap[x][y] != 0)
							f.write(x+ "," + y + "," + nodeMap[x][y] + '\n');
					}
				}
			}
			f.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * For debugging, to be removed.
	 * @param fileName
	 * @param details
	 * @return
	 */
	public Area readMap(String fileName, MapDetails details) {
		File mapFile = new File(fileName);
		Area collectorBounds = null;
		if (!mapFile.exists()) {
			System.out.println("Error: map file doesn't exist: " + mapFile);  
			return null;
		}
		try {
			InputStream fileStream = new FileInputStream(mapFile);
			LineNumberReader problemReader = new LineNumberReader(
					new InputStreamReader(fileStream));
			Pattern csvSplitter = Pattern.compile(Pattern.quote(","));
			
			String problemLine;
			String[] items;
			problemLine = problemReader.readLine();
			if (problemLine != null){
				items = csvSplitter.split(problemLine);
				if (items.length != 4){
					System.out.println("Error: Invalid format in map file, line number " + problemReader.getLineNumber() + ": "   
							+ problemLine);
					return null;
				}
				details.addToBounds(Integer.parseInt(items[0]), Integer.parseInt(items[1]));
				details.addToBounds(Integer.parseInt(items[2]),Integer.parseInt(items[3]));
			}
			problemLine = problemReader.readLine();
			if (problemLine != null){
				items = csvSplitter.split(problemLine);
				if (items.length != 4){
					System.out.println("Error: Invalid format in map file, line number " + problemReader.getLineNumber() + ": "   
							+ problemLine);
					return null;
				}
				collectorBounds = new Area(Integer.parseInt(items[0]), Integer.parseInt(items[1]),
						Integer.parseInt(items[2]),Integer.parseInt(items[3]));
			}
			while ((problemLine = problemReader.readLine()) != null) {
				items = csvSplitter.split(problemLine);
				if (items.length != 3) {
					System.out.println("Error: Invalid format in map file, line number " + problemReader.getLineNumber() + ": "   
							+ problemLine);
					return null;
				}
				int x,y,sum;
				try{
					x = Integer.parseInt(items[0]);
					y = Integer.parseInt(items[1]);
					sum = Integer.parseInt(items[2]);
				
					if (x < 0 || x >= width || y < 0 || y>=height){
						System.out.println("Error: Invalid data in map file, line number " + + problemReader.getLineNumber() + ": "   
								+ problemLine);

					}
					else{
						if (nodeMap[x] == null)
							nodeMap[x] = new int[height];
						nodeMap[x][y] = sum;
						totalNodeCount += sum;
					}
				}
				catch(NumberFormatException exp){
					System.out.println("Error: Invalid number format in problem file, line number " + + problemReader.getLineNumber() + ": "   
							+ problemLine + exp);
					return null;
				}
			}
			problemReader.close();
		} catch (IOException exp) {
			System.out.println("Error: Cannot read problem file " + mapFile +  
					exp);
			return null;
		}
		return collectorBounds;
	}

	

}
