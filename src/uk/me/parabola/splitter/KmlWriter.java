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
import java.util.Locale;
/**
 * A helper class to create kml files from java areas.
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
	
	private static void writeLineHeader(PrintWriter pw, String name){
		pw.format(Locale.ROOT,
				"  <Placemark>\n" +
						"    <name>%1$d</name>\n" +
						"    <styleUrl>#transWhitePoly</styleUrl>\n" +
						"      <description>\n" +
						"        <![CDATA[%2$s]]>\n" +
						"      </description>\n" +
						"    <Polygon>\n" +
						"      <outerBoundaryIs>\n" +
						"        <LineString>\n" +
						"          <coordinates>\n", 0, name);


	}
	
	private static void writeLineFooter(PrintWriter pw){
		pw.format(Locale.ROOT,
				"          </coordinates>\n" +
				"        </LineString>\n" +
				"      </outerBoundaryIs>\n" +
				"    </Polygon>\n" +
				"  </Placemark>\n");
	}

	private static void writeKmlFooter(PrintWriter pw){
		pw.format("</Document>\n</kml>\n");
		
	}

	private static void writeCoordinates(PrintWriter pw, double x, double y){
		
		pw.format(Locale.ROOT, "            %f,%f\n",Utils.toDegrees((int) x), Utils.toDegrees((int) y));
	}
	
	
	@SuppressWarnings("unused")
	public static void writeKml(String filename, String name, java.awt.geom.Area area){
		if (true)
			return;
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

			while (!pit.isDone()) {
				int type = pit.currentSegment(res);
				switch (type) {
				case PathIterator.SEG_MOVETO:
					writeLineHeader(pw, name + linePart++);
					writeCoordinates(pw, res[0], res[1]);
					startx = res[0];
					starty = res[1];
					break;
				case PathIterator.SEG_LINETO:
					writeCoordinates(pw, res[0], res[1]);
					break;
				case PathIterator.SEG_CLOSE:
					writeCoordinates(pw, startx,starty);
					writeLineFooter(pw);
					break;
				default:
					System.err.println("Unsupported path iterator type " + type
							+ ". This is an mkgmap error.");
				}
				pit.next();
			} 			

			writeKmlFooter(pw);
			pw.close();
		} catch (IOException e) {
			System.err.println("Could not write KML file " + filePath);
		}
	}
}
