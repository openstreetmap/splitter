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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.awt.geom.Path2D;
import java.awt.geom.Area;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 
 * Class to read a polygon description file.
 * Expected input are nodes and ways. Ways with
 * tag name=* and mapid=nnnnnnnn should describe polygons
 * which are used to calculate a combined polygon and
 * area lists.  
 * @author GerdP
 *
 */
class PolygonDescProcessor extends AbstractMapProcessor {
	private Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private final List<PolygonDesc> polygonDescriptions = new ArrayList<>();
	private HashMap<Integer,Double> latValues = new HashMap<>();
	private HashMap<Integer,Double> lonValues = new HashMap<>();
	final int resolution;

	// helper class to store data
	private class PolygonDesc {
		private final java.awt.geom.Area area;
		private final String name;
		private final int mapId;
		public PolygonDesc(String name, Area area, int mapId) {
			this.name = name;
			this.area = area;
			this.mapId = mapId;
		}
	}


	public PolygonDescProcessor(int resolution) {
		this.resolution = resolution;
	}

	@Override
	public void processNode(Node n){
		int lat = getRoundedCoord(n.getMapLat());
		int lon = getRoundedCoord(n.getMapLon());
		double roundedLat = Utils.toDegrees(lat);
		double roundedLon = Utils.toDegrees(lon);
		Double reusedLat = latValues.get(lat);
		Double reusedLon = lonValues.get(lon);
		if (reusedLat == null)
			latValues.put(lat, roundedLat);
		else 
			roundedLat = reusedLat;
		if (reusedLon == null)
			lonValues.put(lon, roundedLon);
		else 
			reusedLon = roundedLon;
		
		Node rNode = new Node();
		rNode.set(n.getId(),roundedLat,roundedLon);
		nodes.put(rNode.getId(), rNode);
		
	}

	@Override
	public void processWay(Way w){
		String name = w.getTag("name");
		if (name == null){
			System.out.println("name missing, ignoring way " + w.getId());
			return;
		}
		String mapIdString = w.getTag("mapid");
		if (mapIdString == null){
			System.out.println("mapid missing, ignoring way " + w.getId());
			return;
		}
		int mapId;
		try{
			mapId = Integer.parseInt(mapIdString);
		} catch (NumberFormatException e){
			System.out.println("invalid mapid in way " + w.getId());
			return;
		}
		Path2D path = null;
		for (long ref : w.getRefs()){
			Node n = nodes.get(ref);
			if (n != null){
				if (path == null){
					path = new Path2D.Double();
					path.moveTo(n.getMapLon(), n.getMapLat());
				} else 
					path.lineTo(n.getMapLon(), n.getMapLat());
			}
		}
		PolygonDesc pd = new PolygonDesc(name, new Area(path), mapId);
		polygonDescriptions.add(pd); 
	}

	@Override
	public boolean endMap(){
		nodes = null;
		latValues = null;
		lonValues = null;
		return true;
	}
	
	/**
	 * @return the combined polygon 
	 */
	Area getPolygon(){
		Area combinedArea = new Area();  
		for (PolygonDesc pd : polygonDescriptions){
			combinedArea.add(pd.area);
		}
		return combinedArea;
	}
	
	/**
	 * Calculate and write the area lists for each named polygon.
	 * @param fileOutputDir
	 * @param areas the list of all areas 
	 * @param kmlOutputFile optional kml file name or null
	 * @param outputType file name extension of output files
	 * @throws IOException 
	 */
	public void writeListFiles(File fileOutputDir,
			List<uk.me.parabola.splitter.Area> areas, String kmlOutputFile, String outputType) throws IOException {
		for (PolygonDesc pd : polygonDescriptions){
			List<uk.me.parabola.splitter.Area> areasPart = new ArrayList<>();
			for (uk.me.parabola.splitter.Area a : areas){
				if (pd.area.intersects(a.getRect()))
					areasPart.add(a);
			}
			AreaList al = new AreaList(areasPart);
			if (kmlOutputFile != null){
				String kmlOutputFilePart = pd.name + "-" + kmlOutputFile;
				File out = new File(kmlOutputFilePart);
				if (!out.isAbsolute())
					out = new File(fileOutputDir, kmlOutputFilePart);
				System.out.println("Writing KML file to " + out.getPath());
				al.writeKml(out.getPath());
			}
			al.writePoly(new File(fileOutputDir, pd.name + "-" + "areas.poly").getPath());
			al.writeArgsFile(new File(fileOutputDir, pd.name + "-" + "template.args").getPath(), outputType, pd.mapId);
		}
	}
	
	private int getRoundedCoord(int val){
		int shift = 24 - resolution;
		int half = 1 << (shift - 1);	// 0.5 shifted
		int mask = ~((1 << shift) - 1); // to remove fraction bits
		return (val + half) & mask; 
	}
}
