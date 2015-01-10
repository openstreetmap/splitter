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

import java.awt.geom.Area;

/**
 * Store a java area with a name and mapid 
 * @author GerdP
 *
 */
class PolygonDesc {
	final java.awt.geom.Area area;
	final String name;
	final int mapId;
	public PolygonDesc(String name, Area area, int mapId) {
		this.name = name;
		this.area = area;
		this.mapId = mapId;
	}
}
