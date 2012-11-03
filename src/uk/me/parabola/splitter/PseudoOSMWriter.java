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
import java.io.IOException;

public class PseudoOSMWriter extends AbstractOSMWriter{

	public PseudoOSMWriter(Area bounds, File outputDir, int mapId, int extra) {
		super(bounds, outputDir, mapId, extra);
	}
	
	@Override
	public void write(Relation rel) throws IOException {}
	
	@Override
	public void write(Way way) throws IOException {}
	
	@Override
	public void write(Node node) throws IOException {}
	
	@Override
	public void initForWrite() {}
	
	@Override
	public void finishWrite() {}
}
