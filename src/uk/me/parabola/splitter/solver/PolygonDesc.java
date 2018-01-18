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
package uk.me.parabola.splitter.solver;

import java.awt.geom.Area;
import uk.me.parabola.splitter.Utils;

/**
 * Store a java area with a name and mapid 
 * @author GerdP
 *
 */
public class PolygonDesc {
	private final java.awt.geom.Area area;
	private final String name;
	private final int mapId;
	public PolygonDesc(String name, Area area, int mapId) {
		this.name = name;
		this.area = area;
		this.mapId = mapId;
	}
	
	public java.awt.geom.Area getArea() {
		return area;
	}
	
	public String getName() {
		return name;
	}

	public int getMapId() {
		return mapId;
	}

	public PolygonDesc realignForDem(int res) {
		if (res < 0)
			return this;
		Area alignedArea = Utils.AreaMapUnitAlignForDem(area, res);
		return new PolygonDesc(name, alignedArea, mapId);
	}
}
