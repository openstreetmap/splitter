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

/**
 * Contains info that is needed by the {@link Tile} class
 * @author GerdP
 *
 */
public class EnhancedDensityMap {
	private final DensityMap densityMap;
	private int[][] xyMap;
	private int[][] yxMap;
	private double[] aspectRatioFactor;
	private int minAspectRatioFactorPos;
	private int maxNodesInDensityMapGridElement = Integer.MIN_VALUE;

	public EnhancedDensityMap(DensityMap densities, java.awt.geom.Area polygonArea) {
		this.densityMap = densities;
		prepare(polygonArea);
	}

	
	/** 
	 * Filter the density data, calculate once complex trigonometric results 
	 * @param polygonArea
	 */
	private void prepare(java.awt.geom.Area polygonArea){
		aspectRatioFactor = new double[densityMap.getHeight()+1]; 
		int minLat = densityMap.getBounds().getMinLat(); 
		int maxLat = densityMap.getBounds().getMaxLat();
		int lat = 0;
		double maxAspectRatioFactor = Double.MIN_VALUE;
		int minPos = Integer.MAX_VALUE;
		// performance: calculate only once the needed complex math results
		for (int i = 0; i < aspectRatioFactor.length; i++ ){
			lat = minLat + i * (1 << densityMap.getShift());
			assert lat <= maxLat;
			aspectRatioFactor[i] = Math.cos(Math.toRadians(Utils.toDegrees(lat))) ;
			if (maxAspectRatioFactor < aspectRatioFactor[i]){
				maxAspectRatioFactor = aspectRatioFactor[i];
				minPos = i;
			}
		}
		minAspectRatioFactorPos = minPos;
		assert lat == maxLat;
		int width = densityMap.getWidth();
		int height = densityMap.getHeight();
		xyMap = new int [width][height];
		int shift = densityMap.getShift();
		for (int x = 0; x < width; x++){
			int polyXPos = densityMap.getBounds().getMinLong() +  (x << shift);
			
			for(int y = 0; y < height; y++){
				int count = densityMap.getNodeCount(x, y);
				if (polygonArea != null){
					int polyYPos = densityMap.getBounds().getMinLat() + (y << shift);
					if (polygonArea.intersects(polyXPos, polyYPos, 1<<shift, 1<<shift))
						count = Math.max(1, count);
					else 
						count = 0;
				}
				if (count > 0){
					if (count > maxNodesInDensityMapGridElement)
						maxNodesInDensityMapGridElement = count;
					xyMap[x][y] = count;
				}
			}
		}
		yxMap = new int [height][width];
		for(int y = 0; y < height; y++){
			for (int x = 0; x < width; x++){
				yxMap[y][x] = xyMap[x][y];
			}
		}
	}

	// calculate aspect ratio of a tile which is a view on the densityMap
	public double getAspectRatio(Tile r) {
		double ratio;
		double maxWidth ;
		if (r.y < minAspectRatioFactorPos && r.y+r.height > minAspectRatioFactorPos){
			maxWidth = r.width; // tile crosses equator
		}else {
			double width1 = r.width * aspectRatioFactor[r.y];
			double width2 = r.width * aspectRatioFactor[r.y + r.height];
			maxWidth = Math.max(width1, width2);		
		}
		ratio = maxWidth/r.height;
		return ratio;
	}
	public Area getBounds() {
		return densityMap.getBounds();
	}
	public DensityMap getDensityMap() {
		return densityMap;
	}
	
	public long getNodeCount(){
		return densityMap.getNodeCount();
	}
	public int[] getMapRow(int mapRow) {
		return yxMap[mapRow];
	}
	public int[] getMapCol(int mapCol) {
		return xyMap[mapCol];
	}
	public double[] getAspectRatioFactor() {
		return aspectRatioFactor;
	}
	public int getMinAspectRatioFactorPos() {
		return minAspectRatioFactorPos;
	}

	public int getMaxNodesInDensityMapGridElement() {
		return maxNodesInDensityMapGridElement;
	}
}
