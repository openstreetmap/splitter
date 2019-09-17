/*
 * Copyright (c) 2012, Gerd Petermann
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

package uk.me.parabola.splitter.writer;

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;

import uk.me.parabola.splitter.Area;
import uk.me.parabola.splitter.Element;
import uk.me.parabola.splitter.Node;
import uk.me.parabola.splitter.Relation;
import uk.me.parabola.splitter.Utils;
import uk.me.parabola.splitter.Way;

public abstract class AbstractOSMWriter implements OSMWriter{
	public static final int REMOVE_VERSION = 1;
	public static final int FAKE_VERSION = 2;
	public static final int KEEP_VERSION = 3;
	protected final Area bounds;
	protected final Area extendedBounds;
	protected final File outputDir;
	protected final int mapId;
	protected final Rectangle bbox;
	protected int versionMethod; 
	

	public AbstractOSMWriter(Area bounds, File outputDir, int mapId, int extra) {
		this.mapId = mapId;
		this.bounds = bounds;
		this.outputDir = outputDir;
		extendedBounds = new Area(bounds.getMinLat() - extra,
				bounds.getMinLong() - extra,
				bounds.getMaxLat() + extra,
				bounds.getMaxLong() + extra);
		this.bbox = Utils.area2Rectangle(bounds, 1);
	}

	public void setVersionMethod (int versionMethod){
		this.versionMethod = versionMethod;
	}
	
	protected int getWriteVersion (Element el){
		if (versionMethod == REMOVE_VERSION)
			return 0;
		if (versionMethod == FAKE_VERSION)
			return 1;
		// XXX maybe return 1 if no version was read ?
		return el.getVersion();

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
	
	public Rectangle getBBox(){
		return bbox;
	}
	
	public void write (Element element) throws IOException {
		if (element instanceof Node) {
			write((Node) element);
		} else if (element instanceof Way) {
			write((Way) element);
		} else if (element instanceof Relation) {
			write((Relation) element);
		}
	}
}
