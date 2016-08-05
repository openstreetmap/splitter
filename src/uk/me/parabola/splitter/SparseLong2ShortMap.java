/*
 * Copyright (C) 2013, Gerd Petermann
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */
package uk.me.parabola.splitter;

/**
 * Helper class to create appropriate instance of a Long/Short map. 
 * @author Gerd
 *
 */
public class SparseLong2ShortMap {
	public static SparseLong2ShortMapFunction createMap(String dataDesc){
		long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
		// prefer implementation with lower memory footprint when free heap is less than 2 GB
		if (maxMem < 2048)
			return new SparseLong2ShortMapInline(dataDesc);
		return new SparseLong2ShortMapHuge(dataDesc);
	}
}
