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

import java.io.File;

public abstract class AbstractOSMWriter implements OSMWriter{
	
	protected final Area bounds;
	protected final Area extendedBounds;
	protected final File outputDir;
	protected final int mapId;
	

	public AbstractOSMWriter(Area bounds, File outputDir, int mapId, int extra) {
		this.mapId = mapId;
		this.bounds = bounds;
		this.outputDir = outputDir;
		extendedBounds = new Area(bounds.getMinLat() - extra,
				bounds.getMinLong() - extra,
				bounds.getMaxLat() + extra,
				bounds.getMaxLong() + extra);
	}

	public Area getBounds() {
		return bounds;
	}
	
	public Area getExtendedBounds() {
		return extendedBounds;
	}
	public int getMapId(){
		return mapId;
	}
	
	public boolean nodeBelongsToThisArea(Node node) {
		return (extendedBounds.contains(node.getMapLat(), node.getMapLon()));
	}
	
}
