/*
 * Copyright (C) 2017
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Gerd Petermann
 * Create date: 2017-01-10
 */
package func.lib;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Useful constants that are used for arguments etc. in the functional tests.
 *
 * @author Gerd Petermann
 */
public interface Args {
	String TEST_RESOURCE_OSM = "test/resources/in/osm/";

	String DEF_TEMPLATE = "template.args";
	String DEF_DENSITIES = "densities-out.txt";
	String DEF_AREAS_KML = "areas.kml";
	String DEF_AREAS_LIST = "areas.list";
	String DEF_AREAS_POLY = "areas.poly";
	String DEF_PROBLEM_LIST = "problem.list";
	
	String[] MAIN_ARGS = { "--status-freq=0",
			"--write-kml=" + DEF_AREAS_KML,
			"--problem-report=" + DEF_PROBLEM_LIST,
			"--max-nodes=500000", 
			};
	
	String ALASKA = TEST_RESOURCE_OSM + "alaska-2016-12-27.osm.pbf";
	String HAMBURG = TEST_RESOURCE_OSM + "hamburg-2016-12-26.osm.pbf";

	/** expected summed line sizes for ALASKA file */
	Map<String, Integer> expectedAlaska = new LinkedHashMap<String, Integer>() {
		{
			put(DEF_AREAS_KML, 5158);
			put(DEF_AREAS_LIST, 1076);
			put(DEF_AREAS_POLY, 371);
			put(DEF_DENSITIES, 769055);
			put(DEF_PROBLEM_LIST, 12157);
			put(DEF_TEMPLATE, 930);
		}
	};

	/** expected summed line sizes for ALASKA file */
	Map<String, Integer> expectedAlaskaOverlap = new LinkedHashMap<String, Integer>() {
		{
			putAll(expectedAlaska);
			remove(DEF_PROBLEM_LIST);
		}
	};

	/** expected summed line sizes for HAMBURG file */
	Map<String, Integer> expectedHamburg = new LinkedHashMap<String, Integer>() {
		{
			put(DEF_AREAS_KML, 3143);
			put(DEF_AREAS_LIST, 616);
			put(DEF_AREAS_POLY, 204);
			put(DEF_DENSITIES, 2157);
			put(DEF_PROBLEM_LIST, 51017);
			put(DEF_TEMPLATE, 662);
		}
	};
}
