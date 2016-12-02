/*
 * Copyright (c) 2014, Gerd Petermann
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
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * Class to read a polygon description file (OSM)
 * Expected input are nodes and ways. Ways with
 * tag name=* and mapid=nnnnnnnn should describe polygons
 * which are used to calculate area lists.  
 * @author GerdP
 *
 */
class PolygonDescProcessor extends AbstractMapProcessor {
	private Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private final List<PolygonDesc> polygonDescriptions = new ArrayList<>();
	private final int shift;

	public PolygonDescProcessor(int resolution) {
		this.shift = 24 - resolution;
	}

	@Override
	public void processNode(Node n){
		// round all coordinates to be on the used grid. 
		int lat = RoundingUtils.round(n.getMapLat(), shift);
		int lon = RoundingUtils.round(n.getMapLon(), shift);
		double roundedLat = Utils.toDegrees(lat);
		double roundedLon = Utils.toDegrees(lon);
		
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
		System.out.println("found " + polygonDescriptions.size() + " named polygons");
		return true;
	}
	
	public List<PolygonDesc> getPolygons() {
		return polygonDescriptions;
	}
}
