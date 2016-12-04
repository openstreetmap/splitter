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

package uk.me.parabola.splitter.solver;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.osmosis.core.filter.common.PolygonFileReader;
import org.xmlpull.v1.XmlPullParserException;

import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.OSMFileHandler;
import uk.me.parabola.splitter.RoundingUtils;
import uk.me.parabola.splitter.Utils;
import uk.me.parabola.splitter.args.SplitterParams;

/**
 * Some helper methods around area calculation. 
 * @author Gerd Petermann
 *
 */
public class AreasCalculator {
	private final List<PolygonDesc> polygons = new ArrayList<>();
	private final int resolution;
	private final int numTiles;
	private final SplitterParams mainOptions;
	private final DensityMapCollector pass1Collector;
	private Area exactArea; 

	public AreasCalculator(SplitterParams mainOptions, int numTiles) {
		this.mainOptions = mainOptions;
		this.resolution = mainOptions.getResolution();
		this.numTiles = numTiles;
		pass1Collector = new DensityMapCollector(mainOptions);
		readPolygonFile(mainOptions.getPolygonFile(), mainOptions.getMapid());
		readPolygonDescFile(mainOptions.getPolygonDescFile());
		int numPolygons = polygons.size();
		if (numPolygons > 0) {
			if (!checkPolygons()) {
				System.out.println(
						"Warning: Bounding polygon is complex. Splitter might not be able to fit all tiles into the polygon!");
			}
			if (numTiles > 0) {
				System.out.println("Warning: bounding polygons are ignored because --num-tiles is used");
			}
		}
	}

	/**
	 * Check if the bounding polygons are usable.
	 * @return false if any 
	 */
	public boolean checkPolygons() {
		return polygons.stream().allMatch(pd -> checkPolygon(pd.getArea(), resolution));
	}

	/**
	 * Check if the bounding polygon is usable.
	 * @param mapPolygonArea
	 * @param resolution
	 * @return false if the polygon is too complex 
	 */
	private static boolean checkPolygon(java.awt.geom.Area mapPolygonArea, int resolution) {
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
		if (!new File(polygonDescFile).exists()) {
			throw new IllegalArgumentException("polygon desc file doesn't exist: " + polygonDescFile);
		}
		final PolygonDescProcessor polygonDescProcessor = new PolygonDescProcessor(resolution);
		final OSMFileHandler polyDescHandler = new OSMFileHandler();
		polyDescHandler.setFileNames(Arrays.asList(polygonDescFile));
		polyDescHandler.setMixed(false);
		polyDescHandler.process(polygonDescProcessor);
		polygons.addAll(polygonDescProcessor.getPolygons());
	}

	/**
	 * Fill the density map. 
	 * @param osmFileHandler 
	 * @param fileOutputDir 
	 */
	public void fillDensityMap(OSMFileHandler osmFileHandler, File fileOutputDir) {
		long start = System.currentTimeMillis();
		
		// this is typically only used for debugging 
		File densityData = new File("densities.txt");
		File densityOutData = null;
		if (densityData.exists() && densityData.isFile()) {
			System.err.println("reading density data from " + densityData.getAbsolutePath());
			pass1Collector.readMap(densityData.getAbsolutePath());
		} else {
			// fill the map with data from OSM files 
			osmFileHandler.execute(pass1Collector);
			densityOutData = new File(fileOutputDir, "densities-out.txt");
		}
		System.out.println("Fill-densities-map pass took " + (System.currentTimeMillis() - start) + " ms");
		System.out.println("Exact map coverage read from input file(s) is " + exactArea);

		if (densityOutData != null)
			pass1Collector.saveMap(densityOutData.getAbsolutePath());
		
		exactArea = pass1Collector.getExactArea();
		System.out.println("Exact map coverage read from input file(s) is " + exactArea);
		if (polygons.size() == 1) {
			// intersect the bounding polygon with the exact area
			Rectangle polgonsBoundingBox = polygons.get(0).getArea().getBounds();
			exactArea = Area.calcArea(exactArea, polgonsBoundingBox);
			if (exactArea != null)
				System.out.println("Exact map coverage after applying bounding box of polygon-file is " + exactArea);
			else {
				System.out.println("Exact map coverage after applying bounding box of polygon-file is an empty area");
				return;
			}
		}
		
		addPrecompSeaDensityData();
	}

	private void addPrecompSeaDensityData () {
		String precompSeaDir = mainOptions.getPrecompSea();
		if (precompSeaDir != null) {
			System.out.println("Counting nodes of precompiled sea data ...");
			long startSea = System.currentTimeMillis();
			DensityMapCollector seaCollector = new DensityMapCollector(mainOptions);
			PrecompSeaReader precompSeaReader = new PrecompSeaReader(exactArea, new File(precompSeaDir));
			try {
				precompSeaReader.processMap(seaCollector);
			} catch (XmlPullParserException e) {
				// very unlikely because we read generated files
				e.printStackTrace();
			}
			pass1Collector.mergeSeaData(seaCollector, !mainOptions.isNoTrim(), mainOptions.getResolution());
			System.out.println("Precompiled sea data pass took " + (System.currentTimeMillis() - startSea) + " ms");
		}
	}

	/**
	 * Calculate the areas that we are going to split into by getting the total
	 * area and then subdividing down until each area has at most max-nodes
	 * nodes in it. 
	 * If {@code --num-tiles} option is used, tries to find a max-nodes value which results in the wanted number of areas.
	 * 
	 * @return
	 */
	public List<Area> calcAreas () {
		Area roundedBounds = RoundingUtils.round(exactArea, mainOptions.getResolution());
		SplittableDensityArea splittableArea = pass1Collector.getSplitArea(mainOptions.getSearchLimit(), roundedBounds);
		if (splittableArea.hasData() == false) {
			System.out.println("input file(s) have no data inside calculated bounding box");
			return Collections.emptyList();
		}
		System.out.println("Rounded map coverage is " + splittableArea.getBounds());

		splittableArea.setTrim(mainOptions.isNoTrim() == false);
		splittableArea.setMapId(mainOptions.getMapid());
		long startSplit = System.currentTimeMillis();
		List<Area> areas;
		if (numTiles >= 2) {
			System.out.println("Splitting nodes into " + numTiles + " areas");
			areas = splittableArea.split(numTiles);
		} else {
			System.out.println(
					"Splitting nodes into areas containing a maximum of " + Utils.format(mainOptions.getMaxNodes()) + " nodes each...");
			splittableArea.setMaxNodes(mainOptions.getMaxNodes());
			areas = splittableArea.split(polygons);
		}
		if (areas != null && areas.isEmpty() == false)
			System.out.println("Creating the initial areas took " + (System.currentTimeMillis() - startSplit) + " ms");
		return areas;
	}
	
	public List<PolygonDesc> getPolygons() {
		return Collections.unmodifiableList(polygons);
	}

}
