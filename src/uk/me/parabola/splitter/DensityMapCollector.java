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

import uk.me.parabola.splitter.args.SplitterParams;

/**
 * Builds up a density map.
 */
class DensityMapCollector extends AbstractMapProcessor{
	private final DensityMap densityMap;
	private final MapDetails details = new MapDetails();
	private Area bounds;
	private final boolean ignoreBoundsTags;
	private int files;
	

	public DensityMapCollector(SplitterParams mainOptions) {
		Area densityBounds = new Area(-0x400000, -0x800000, 0x400000, 0x800000);
		densityMap = new DensityMap(densityBounds, mainOptions.getResolution());
		this.ignoreBoundsTags = mainOptions.getIgnoreOsmBounds();
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
	public void startFile() {
		if (++files > 1)
			checkBounds();
	}
	
	@Override
	public void boundTag(Area fileBbox) {
		if (ignoreBoundsTags)
			return;
		if (this.bounds == null){
			this.bounds = fileBbox;
		}
		else
			this.bounds = this.bounds.add(fileBbox);
	}

	@Override
	public void processNode(Node n) {
		int glat = n.getMapLat();
		int glon = n.getMapLon();
		densityMap.addNode(glat, glon);
		details.addToBounds(glat, glon);
	}

	
	/**
	 * Check if a bounds tag was found. If not,
	 * use the bbox of the data that was collected so far.
	 * This is used when multiple input files are used
	 * and first doesn't contain a bounds tag.
	 */
	public void checkBounds(){
		if (this.bounds == null)
			this.bounds = getExactArea();
	}
	
	public Area getExactArea() {
		if (bounds != null) {
			return bounds;
		}
		return details.getBounds();
	}
	
	public SplittableDensityArea getSplitArea(int searchLimit, Area roundedBounds) {
		return new SplittableDensityArea(densityMap.subset(roundedBounds), searchLimit);
	} 

	public void mergeSeaData(DensityMapCollector seaData, boolean trim, int resolution) {
		Area roundedBounds = RoundingUtils.round(getExactArea(), resolution);
		densityMap.mergeSeaData(seaData.densityMap, roundedBounds, trim);
	}

	public void saveMap(String fileName) {
		if (details != null && details.getBounds() != null)
			densityMap.saveMap(fileName, details.getBounds(), bounds);
	}
	public void readMap(String fileName) {
		bounds = densityMap.readMap(fileName, details);
	}

}
