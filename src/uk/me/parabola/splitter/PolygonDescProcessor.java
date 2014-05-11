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
import java.util.List;

/**
 * 
 * Class to read a polygon description file.
 * @author GerdP
 *
 */
class PolygonDescProcessor extends AbstractMapProcessor {
	private Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private final List<PolygonDesc> polygonDescriptions = new ArrayList<>();

	@Override
	public void processNode(Node n){
		nodes.put(n.getId(), n);
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
		return true;
	}
	Area getPolygon(){
		Area combinedArea = new Area();  
		for (PolygonDesc pd : polygonDescriptions){
			combinedArea.add(pd.area);
		}
		return combinedArea;
	}
	
	class PolygonDesc {
		private final java.awt.geom.Area area;
		private final String name;
		private final int mapId;
		public PolygonDesc(String name, Area area, int mapId) {
			this.name = name;
			this.area = area;
			this.mapId = mapId;
		}
	}

	public void writeListFiles(File fileOutputDir,
			List<uk.me.parabola.splitter.Area> areas, String kmlOutputFile, String outputType) throws IOException {
		for (PolygonDesc pd : polygonDescriptions){
			List<uk.me.parabola.splitter.Area> areasPart = new ArrayList<>();
			for (uk.me.parabola.splitter.Area a : areas){
				if (pd.area.intersects(a.getRect()))
					areasPart.add(a);
			}
			AreaList al = new AreaList(areasPart);
			String kmlOutputFilePart = pd.name + "-" + kmlOutputFile;
			File out = new File(kmlOutputFilePart);
			if (!out.isAbsolute())
				out = new File(fileOutputDir, kmlOutputFilePart);
			System.out.println("Writing KML file to " + out.getPath());
			al.writeKml(out.getPath());
			
			al.writePoly(new File(fileOutputDir, pd.name + "-" + "areas.poly").getPath());
			al.writeArgsFile(new File(fileOutputDir, pd.name + "-" + "template.args").getPath(), outputType, pd.mapId);
		}
	}
}
