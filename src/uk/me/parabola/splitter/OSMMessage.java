/*
 * Copyright (c) 2016, Gerd Petermann
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

import java.util.List;

/**
 * For OSM data which is passed between parsers and processors 
 * @author Gerd Petermann
 *
 */
public class OSMMessage {
	public enum Type {START_FILE, ELEMENTS, BOUNDS, END_MAP, EXIT}; 

	// either el or bounds must be null
	List<Element> elements;
	Area bounds;
	Type type;

	public OSMMessage(List<Element> elements) {
		this.elements = elements;
		type = Type.ELEMENTS;
	}

	public OSMMessage(Area bounds) {
		this.bounds = bounds;
		type = Type.BOUNDS;
	}

	public OSMMessage(Type t) {
		assert !t.equals(Type.BOUNDS); 
		assert !t.equals(Type.ELEMENTS); 
		type = t;
	}
}
