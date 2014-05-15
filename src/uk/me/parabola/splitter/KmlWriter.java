/*
 * Copyright (c) 2014.
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

import java.awt.geom.PathIterator;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
/**
 * A class to create kml files from java areas (polygons) or rectangular areas.
 * @author GerdP
 *
 */
public class KmlWriter {
	
	private static void writeKmlHeader(PrintWriter pw){
		pw.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
				"<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n" +
				"<Document>\n" +
				"  <Style id=\"transWhitePoly\">\n" +
				"    <LineStyle>\n" +
				"      <width>1.5</width>\n" +
				"    </LineStyle>\n" +
				"    <PolyStyle>\n" +
				"      <color>00ffffff</color>\n" +
				"      <colorMode>normal</colorMode>\n" +
				"    </PolyStyle>\n" +
				"  </Style>\n\n");
	}
	
	private static void writeLineHeader(PrintWriter pw, int id, String name){
		pw.format(Locale.ROOT,
				"  <Placemark>\n" +
						"    <name>%1$d</name>\n" +
						"    <styleUrl>#transWhitePoly</styleUrl>\n" +
						"      <description>\n" +
						"        <![CDATA[%2$s]]>\n" +
						"      </description>\n" +
						"    <Polygon>\n" +
						"      <outerBoundaryIs>\n" +
						"        <LinearRing>\n" +
						"          <coordinates>\n", id, name);


	}
	
	private static void writeLineFooter(PrintWriter pw){
		pw.format(Locale.ROOT,
				"          </coordinates>\n" +
				"        </LinearRing>\n" +
				"      </outerBoundaryIs>\n" +
				"    </Polygon>\n" +
				"  </Placemark>\n");
	}

	private static void writeKmlFooter(PrintWriter pw){
		pw.format("</Document>\n</kml>\n");
		
	}

	private static void writeCoordinates(PrintWriter pw, double x, double y){
		pw.format(Locale.ROOT, "            %f,%f\n",x,y);
	}
	
	
	
	/**
	 * Write a java area in kml format.
	 * @param filename
	 * @param name
	 * @param area
	 */
	public static void writeKml(String filename, String name, java.awt.geom.Area area){
		String filePath = filename;
		if (filePath.endsWith(".kml") == false)
			filePath += ".kml";
		try (PrintWriter pw = new PrintWriter(filePath))
		{
			writeKmlHeader(pw);
			int linePart = 0 ;
			double startx = 0,starty = 0;
			double[] res = new double[6];
			PathIterator pit = area.getPathIterator(null);
			int id = 0;
			while (!pit.isDone()) {
				int type = pit.currentSegment(res);
				double x = Utils.toDegrees((int) res[0]);
				double y = Utils.toDegrees((int) res[1]);
				switch (type) {
				case PathIterator.SEG_MOVETO:
					writeLineHeader(pw, id++, name + linePart++);
					writeCoordinates(pw, x,y);
					startx = x;
					starty = y;
					break;
				case PathIterator.SEG_LINETO:
					writeCoordinates(pw, x,y);
					break;
				case PathIterator.SEG_CLOSE:
					writeCoordinates(pw, startx,starty);
					writeLineFooter(pw);
					break;
				default:
					// should not happen
					System.err.println("Unsupported path iterator type " + type
							+ ". This is an mkgmap error.");
					throw new IOException(); 
				}
				pit.next();
			} 			

			writeKmlFooter(pw);
		} catch (IOException e) {
			System.err.println("Could not write KML file " + filePath + ", processing continues");
		}
	}
	
	/**
	 * Write out a KML file containing the areas that we calculated. This KML file
	 * can be opened in Google Earth etc to see the areas that were split.
	 *
	 * @param filename The KML filename to write to.
	 */
	public static void writeKml(String filename, List<Area> areas) {
		try (PrintWriter pw = new PrintWriter(filename);) {
			writeKmlHeader(pw);
			for (Area area : areas) {
				double south = Utils.toDegrees(area.getMinLat());
				double west = Utils.toDegrees(area.getMinLong());
				double north = Utils.toDegrees(area.getMaxLat());
				double east = Utils.toDegrees(area.getMaxLong());

				String name = area.getName() == null ? String.valueOf(area.getMapId()) : area.getName();
				writeLineHeader(pw, area.getMapId(), name);
				writeCoordinates(pw, west, south);
				writeCoordinates(pw, west, north);
				writeCoordinates(pw, east, north);
				writeCoordinates(pw, east, south);
				writeCoordinates(pw, west, south);
				writeLineFooter(pw);
			}
			writeKmlFooter(pw);
		} catch (IOException e) {
			System.err.println("Could not write KML file " + filename + ", processing continues");
		}
	}
	
}
