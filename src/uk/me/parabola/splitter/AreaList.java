/*
 * Copyright (c) 2009, Steve Ratcliffe
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
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParserException;

/**
 * A list of areas.  It can be read and written to a file.
 */
public class AreaList {
	private List<Area> areas;

	public AreaList(List<Area> areas) {
		this.areas = areas;
	}

	/**
	 * This constructor is called when you are going to be reading in the list from
	 * a file, rather than making it from an already constructed list.
	 */
	public AreaList() {
	}

	/**
	 * Write out a file containing the list of areas that we calculated.  This allows us to reuse the
	 * same areas on a subsequent run without having to re-calculate them.
	 *
	 * @param filename The filename to write to.
	 */
	public void write(String filename) {
		try (Writer w = new FileWriter(filename);
				PrintWriter pw = new PrintWriter(w);) {
			pw.println("# List of areas");
			pw.format("# Generated %s%n", new Date());
			pw.println("#");

			for (Area area : areas) {
				pw.format(Locale.ROOT, "%08d: %d,%d to %d,%d%n",
						area.getMapId(),
						area.getMinLat(), area.getMinLong(),
						area.getMaxLat(), area.getMaxLong());
				pw.format(Locale.ROOT, "#       : %f,%f to %f,%f%n",
						Utils.toDegrees(area.getMinLat()), Utils.toDegrees(area.getMinLong()),
						Utils.toDegrees(area.getMaxLat()), Utils.toDegrees(area.getMaxLong()));
				pw.println();
			}

		} catch (IOException e) {
			System.err.println("Could not write areas.list file, processing continues");
		}
	}

	/**
	 * Write out a KML file containing the areas that we calculated. This KML file
	 * can be opened in Google Earth etc to see the areas that were split.
	 *
	 * @param filename The KML filename to write to.
	 */
	public void writeKml(String filename) {
		KmlWriter.writeKml(filename, areas);
	}

	public void read(String filename) throws IOException {
		String lower = filename.toLowerCase();
		if (lower.endsWith(".kml") || lower.endsWith(".kml.gz") || lower.endsWith(".kml.bz2")) {
			readKml(filename);
		} else {
			readList(filename);
		}
	}

	/**
	 * Read in an area definition file that we previously wrote.
	 * Obviously other tools could create the file too.
	 */
	private void readList(String filename) throws IOException {
		areas = new ArrayList<>();

		Pattern pattern = Pattern.compile("([0-9]{8}):" +
		" ([\\p{XDigit}x-]+),([\\p{XDigit}x-]+)" +
		" to ([\\p{XDigit}x-]+),([\\p{XDigit}x-]+)");

		try (Reader r = new FileReader(filename);
				BufferedReader br = new BufferedReader(r)) {			
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.charAt(0) == '#')
					continue;

				Matcher matcher = pattern.matcher(line);
				matcher.find();
				String mapid = matcher.group(1);

				Area area = new Area(
						Integer.decode(matcher.group(2)),
						Integer.decode(matcher.group(3)),
						Integer.decode(matcher.group(4)),
						Integer.decode(matcher.group(5)));
				if (!area.verify())
					throw new IllegalArgumentException("Invalid area in file "+ filename+ ": " + line);
				area.setMapId(Integer.parseInt(mapid));
				areas.add(area);
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Bad number in areas list file");
		}
	}

	private void readKml(String filename) throws IOException {
		try {
			KmlParser parser = new KmlParser();
			parser.setReader(Utils.openFile(filename, false));
			parser.parse();
			areas = parser.getAreas();
		} catch (XmlPullParserException e) {
			throw new IOException("Unable to parse KML file " + filename, e);
		}
	}

	public List<Area> getAreas() {
		return areas;
	}

	public void dump() {
		System.out.println("Areas read from file");
		for (Area area : areas) {
			System.out.println(area.getMapId() + " " + area.toString());
		}
	}

	/**
	 * Write out a poly file containing the bounding polygon for the areas 
	 * that we calculated. 
	 *
	 * @param filename The poly filename to write to.
	 */
	public void writePoly(String filename) {
		java.awt.geom.Area polygonArea = new java.awt.geom.Area();
		for (Area area : areas) {
			polygonArea.add(new java.awt.geom.Area(Utils.area2Rectangle(area, 0)));
		}
		List<List<Point>> shapes = Utils.areaToShapes(polygonArea);
		// start with outer polygons
		Collections.reverse(shapes);
		
		try (PrintWriter pw = new PrintWriter(filename)) {
			pw.println("area");
			for (int i = 0; i < shapes.size(); i++){
				List<Point> shape = shapes.get(i);
				if (Utils.clockwise(shape))
					pw.println(i+1);
				else 
					pw.println("!" + (i+1));
				Point point = null,lastPoint = null;
				for (int j = 0; j < shape.size(); j++){
					if (j > 0)
						lastPoint = point;
					point = shape.get(j);
					if (j > 0 && j+1 < shape.size()){
						Point nextPoint = shape.get(j+1); 
						if (point.x == nextPoint.x && point.x == lastPoint.x)
							continue;
						if (point.y == nextPoint.y && point.y == lastPoint.y)
							continue;
					}
					pw.format(Locale.ROOT, "  %e  %e%n",Utils.toDegrees(point.x) ,Utils.toDegrees(point.y));
					
				}
				pw.println("END");
			}
			pw.println("END");
		} catch (IOException e) {
			System.err.println("Could not write polygon file " + filename + ", processing continues");
		}
	}
	
	/**
	 * Write a file that can be given to mkgmap that contains the correct arguments
	 * for the split file pieces.  You are encouraged to edit the file and so it
	 * contains a template of all the arguments that you might want to use.
	 */
	public void writeArgsFile(String filename, String outputType, int startMapId) {
		try (PrintWriter w = new PrintWriter(new FileWriter(filename))){

			w.println("#");
			w.println("# This file can be given to mkgmap using the -c option");
			w.println("# Please edit it first to add a description of each map.");
			w.println("#");
			w.println();

			w.println("# You can set the family id for the map");
			w.println("# family-id: 980");
			w.println("# product-id: 1");

			w.println();
			w.println("# Following is a list of map tiles.  Add a suitable description");
			w.println("# for each one.");
			int mapId = startMapId;
			if (mapId % 100 == 0)
				mapId++;
			for (Area a : areas) {
				w.println();

				w.format("mapname: %08d%n", (startMapId <0) ? a.getMapId() : mapId++);
				if (a.getName() == null)
					w.println("# description: OSM Map");
				else
					w.println("description: " + (a.getName().length() > 50 ? a.getName().substring(0, 50) : a.getName()));
				String ext;
				if("pbf".equals(outputType))
					ext = ".osm.pbf";
				else if("o5m".equals(outputType))
					ext = ".o5m";
				else
					ext = ".osm.gz";
				w.format("input-file: %08d%s%n", a.getMapId(), ext);
			}
			w.println();
		} catch (IOException e) {
			throw new SplitFailedException("Could not write template.args file " + filename, e.getCause());
		}
	}

}
