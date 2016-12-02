/*
 * Copyright (c) 2016, Gerd Petermann
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

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.osmosis.core.filter.common.PolygonFileReader;

import uk.me.parabola.splitter.args.SplitterParams;

/**
 * Some helper methods around area calculation. 
 * @author Gerd Petermann
 *
 */
public class AreasCalculator {
	private final List<PolygonDesc> polygons = new ArrayList<>();
	private int resolution = 13;
	private PolygonDescProcessor polygonDescProcessor;

	public AreasCalculator() {
	}

	public void setResolution(int resolution) {
		this.resolution = resolution;
	}

	/**
	 * Check if the bounding polygons are usable.
	 * 
	 * @param polygon
	 * @return
	 */
	public boolean checkPolygons() {
		for (PolygonDesc pd : polygons) {
			if (checkPolygon(pd.area) == false)
				return false;
		}
		return true;
	}

	/**
	 * Check if the bounding polygon is usable.
	 * 
	 * @param polygon
	 * @return
	 */
	private boolean checkPolygon(java.awt.geom.Area mapPolygonArea) {
		List<List<Point>> shapes = Utils.areaToShapes(mapPolygonArea);
		int shift = 24 - resolution;
		long rectangleWidth = 1L << shift;
		for (List<Point> shape : shapes) {
			int estimatedPoints = 0;
			Point p1 = shape.get(0);
			for (int i = 1; i < shape.size(); i++) {
				Point p2 = shape.get(i);
				if (p1.x != p2.x && p1.y != p2.y) {
					// diagonal line
					int width = Math.abs(p1.x - p2.x);
					int height = Math.abs(p1.y - p2.y);
					estimatedPoints += (Math.min(width, height) / rectangleWidth) * 2;
				}

				if (estimatedPoints > SplittableDensityArea.MAX_SINGLE_POLYGON_VERTICES)
					return false; // too complex

				p1 = p2;
			}
		}
		return true;
	}

	public void readOptionalFiles(SplitterParams mainOptions) {
		readPolygonFile(mainOptions.getPolygonFile(), mainOptions.getMapid());
		readPolygonDescFile(mainOptions.getPolygonDescFile());
	}

	private void readPolygonFile(String polygonFile, int mapId) {
		if (polygonFile == null)
			return;
		polygons.clear();
		File f = new File(polygonFile);

		if (!f.exists()) {
			throw new IllegalArgumentException("polygon file doesn't exist: " + polygonFile);
		}
		PolygonFileReader polyReader = new PolygonFileReader(f);
		java.awt.geom.Area polygonInDegrees = polyReader.loadPolygon();
		PolygonDesc pd = new PolygonDesc(polyReader.getPolygonName(), Utils.AreaDegreesToMapUnit(polygonInDegrees),
				mapId);
		polygons.add(pd);
	}

	private void readPolygonDescFile(String polygonDescFile) {
		if (polygonDescFile == null)
			return;
		polygons.clear();
		File f = new File(polygonDescFile);

		if (!f.exists()) {
			System.out.println("Error: polygon desc file doesn't exist: " + polygonDescFile);
			System.exit(-1);
		}
		polygonDescProcessor = new PolygonDescProcessor(resolution);
		OSMFileHandler polyDescHandler = new OSMFileHandler();
		polyDescHandler.setFileNames(Arrays.asList(polygonDescFile));
		polyDescHandler.setMixed(false);
		polyDescHandler.process(polygonDescProcessor);
		polygons.addAll(polygonDescProcessor.getPolygons());
	}

	public void writeListFiles(File outputDir, List<Area> areas, String kmlOutputFile, String outputType)
			throws IOException {
		if (polygonDescProcessor != null)
			polygonDescProcessor.writeListFiles(outputDir, areas, kmlOutputFile, outputType);
	}

	public List<PolygonDesc> getPolygons() {
		return Collections.unmodifiableList(polygons);
	}

}
