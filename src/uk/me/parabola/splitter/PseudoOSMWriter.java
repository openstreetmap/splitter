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

public class PseudoOSMWriter extends AbstractOSMWriter{

	public PseudoOSMWriter(Area bounds, int mapId) {
		// no overlap for pseudo writers !
		super(bounds, null, mapId, 0);
	}
	
	@Override
	public void write(Relation rel) {}
	
	@Override
	public void write(Way way) {}
	
	@Override
	public void write(Node node) {}
	
	@Override
	public void initForWrite() {}
	
	@Override
	public void finishWrite() {}
}
