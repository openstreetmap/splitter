/*
 * Copyright (c) 2009.
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
 * Builds up a density map.
 */
class DensityMapCollector extends AbstractMapProcessor implements MapCollector{
	private final DensityMap densityMap;
	private final MapDetails details = new MapDetails();
	private Area bounds;

	DensityMapCollector(boolean trim, int resolution) {
		this(null, trim, resolution);
	}

	DensityMapCollector(Area bounds, boolean trim, int resolution) {
		if (bounds == null) {
			// If we don't receive any bounds we have to assume the whole planet
			bounds = new Area(-0x400000, -0x800000, 0x400000, 0x800000);
		}
		densityMap = new DensityMap(bounds, trim, resolution);
	}

	@Override
	public boolean isStartNodeOnly() {
		return true;
	}
	@Override
	public boolean skipTags() {
		return true;
	}
	@Override
	public boolean skipWays() {
		return true;
	}
	@Override
	public boolean skipRels() {
		return true;
	}

	@Override
	public void boundTag(Area bounds) {
		if (this.bounds == null)
			this.bounds = bounds;
		else
			this.bounds = this.bounds.add(bounds);
	}

	@Override
	public void processNode(Node n) {
		int glat = n.getMapLat();
		int glon = n.getMapLon();
		densityMap.addNode(glat, glon);
		details.addToBounds(glat, glon);
	}

	@Override
	public Area getExactArea() {
		if (bounds != null) {
			return bounds;
		} else {
			return details.getBounds();
		}
	}

	@Override
	public SplittableArea getRoundedArea(int resolution, java.awt.geom.Area polygon) {
		Area bounds = RoundingUtils.round(getExactArea(), resolution);
		densityMap.filterWithPolygon(polygon);
		return new SplittableDensityArea(densityMap.subset(bounds));
	}

	@Override
	public void saveMap(String fileName) {
		if (bounds != null && details != null && details.getBounds() != null)
			densityMap.saveMap(fileName, details.getBounds(), bounds);
	}
	@Override
	public void readMap(String fileName) {
		bounds = densityMap.readMap(fileName, details);
	}
}
