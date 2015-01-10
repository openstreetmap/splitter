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

/**
 * A single map node.
 *
 * @author Steve Ratcliffe
 */
public class Node extends Element {
	private double lat, lon;
	private int mapLat, mapLon;

	public void set(long id, double lat, double lon) {
		setId(id);
		this.lat = lat;
		this.lon = lon;
		this.mapLat = Utils.toMapUnit(lat);
		this.mapLon = Utils.toMapUnit(lon);
		if (mapLat < Utils.MIN_LAT_MAP_UNITS || mapLat > Utils.MAX_LAT_MAP_UNITS)
			throw new IllegalArgumentException("invalid lattitude value " + lat);
		if (mapLon < Utils.MIN_LON_MAP_UNITS || mapLon > Utils.MAX_LON_MAP_UNITS)
			throw new IllegalArgumentException("invalid longitude value " + lon);
	}

	public double getLat() {
		return lat;
	}

	public double getLon() {
		return lon;
	}

	public int getMapLat() {
		return mapLat;
	}

	public int getMapLon() {
		return mapLon;
	}
}
