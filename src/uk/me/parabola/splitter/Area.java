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

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

/**
 * A map area in map units.  There is a constructor available for creating
 * in lat/long form.
 *
 * @author Steve Ratcliffe
 */
public class Area {

	public static final Area EMPTY = new Area();

	private int mapId;
	private String name;
	private final int minLat;
	private final int minLong;
	private final int maxLat;
	private final int maxLong;
	private Rectangle javaRect;
	private boolean isJoinable = true;
	private boolean isPseudoArea;
	
	public boolean isJoinable() {
		return isJoinable;
	}

	public void setJoinable(boolean isJoinable) {
		this.isJoinable = isJoinable;
	}

	/**
	 * Create an area from the given Garmin coordinates. We ensure that no dimension is zero.
	 *
	 * @param minLat The western latitude.
	 * @param minLong The southern longitude.
	 * @param maxLat The eastern lat.
	 * @param maxLong The northern long.
	 */
	public Area(int minLat, int minLong, int maxLat, int maxLong) {
		this.minLat = minLat;
		if (maxLat == minLat)
			this.maxLat = minLat + 1;
		else
			this.maxLat = maxLat;

		this.minLong = minLong;
		if (minLong == maxLong)
			this.maxLong = maxLong + 1;
		else
			this.maxLong = maxLong;
	}

	/**
	 * Apply bbox to area.
	 * @param area the area
	 * @param bbox the bounding box
	 * @return A new area instance that covers the intersection of area and bbox
	 * or null if they don't intersect
	 */
	public static Area calcArea (Area area, Rectangle bbox) {
		Rectangle dest = new Rectangle();
		Rectangle2D.intersect(area.getRect(), bbox, dest);
		if (dest.getHeight() > 0 && dest.getWidth() > 0)
			return new Area(dest.y, dest.x, dest.y + dest.height, dest.x + dest.width);
		return null;
	}


	/**
	 * Creates an empty area.
	 */
	private Area() {
		minLat = 0;
		maxLat = 0;
		minLong = 0;
		maxLong = 0;
	}

	public boolean verify(){
		if (minLat > maxLat || minLong > maxLong
				|| minLong < Utils.MIN_LON_MAP_UNITS
				|| maxLong > Utils.MAX_LON_MAP_UNITS
				|| minLat < Utils.MIN_LAT_MAP_UNITS
				|| maxLat > Utils.MAX_LAT_MAP_UNITS)
			return false;
		return true;
	}
	
	
	public Rectangle getRect(){
		if (javaRect == null)
			javaRect = new Rectangle(this.minLong, this.minLat, this.maxLong-this.minLong, this.maxLat-this.minLat);
		return javaRect;
	}
	
	public java.awt.geom.Area getJavaArea(){
		return new java.awt.geom.Area(getRect());
	}
	public void setMapId(int mapId) {
		this.mapId = mapId;
	}

	public int getMapId() {
		return mapId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getMinLat() {
		return minLat;
	}

	public int getMinLong() {
		return minLong;
	}

	public int getMaxLat() {
		return maxLat;
	}

	public int getMaxLong() {
		return maxLong;
	}

	public int getWidth() {
		return maxLong - minLong;
	}

	public int getHeight() {
		return maxLat - minLat;
	}

	public String toString() {
		return "("
				+ Utils.toDegrees(minLat) + ','
				+ Utils.toDegrees(minLong) + ") to ("
				+ Utils.toDegrees(maxLat) + ','
				+ Utils.toDegrees(maxLong) + ')'
				;
	}

	public String toHexString() {
		return "(0x"
				+ Integer.toHexString(minLat) + ",0x"
				+ Integer.toHexString(minLong) + ") to (0x"
				+ Integer.toHexString(maxLat) + ",0x"
				+ Integer.toHexString(maxLong) + ')';
	}

	public boolean contains(int lat, int lon) {
		return lat >= minLat
				&& lat <= maxLat
				&& lon >= minLong
				&& lon <= maxLong;
	}

	public boolean contains(Node node) {
		return contains(node.getMapLat(), node.getMapLon());
	}

	/**
	 * 
	 * @param other an area
	 * @return true if the other area is inside the Area (it may touch the boundary)
	 */
	public final boolean contains(Area other) {
		return other.getMinLat() >= minLat
				&& other.getMaxLat() <= maxLat
				&& other.getMinLong() >= minLong
				&& other.getMaxLong() <= maxLong;
	}

	/**
	 * Checks if this area intersects the given bounding box at least
	 * in one point.
	 * 
	 * @param bbox an area
	 * @return <code>true</code> if this area intersects the bbox; 
	 * 		   <code>false</code> else
	 */
	public final boolean intersects(Area bbox) {
		return minLat <= bbox.getMaxLat() && maxLat >= bbox.getMinLat() && 
			minLong <= bbox.getMaxLong() && maxLong >= bbox.getMinLong();
	}
 
	public Area add(Area area) {
		return new Area(
						Math.min(minLat, area.minLat),
						Math.min(minLong, area.minLong),
						Math.max(maxLat, area.maxLat),
						Math.max(maxLong, area.maxLong)
		);
	}

	public boolean isPseudoArea() {
		return isPseudoArea;
	}

	public void setPseudoArea(boolean isPseudoArea) {
		this.isPseudoArea = isPseudoArea;
	}

}
