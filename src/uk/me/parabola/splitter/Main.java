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

import crosby.binary.file.BlockInputStream;

import org.openstreetmap.osmosis.core.filter.common.PolygonFileReader;
import org.xmlpull.v1.XmlPullParserException;

import com.sun.org.apache.bcel.internal.generic.GETSTATIC;

import uk.me.parabola.splitter.args.ParamParser;
import uk.me.parabola.splitter.args.SplitterParams;
import uk.me.parabola.splitter.geo.City;
import uk.me.parabola.splitter.geo.CityFinder;
import uk.me.parabola.splitter.geo.CityLoader;
import uk.me.parabola.splitter.geo.DefaultCityFinder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Splitter for OSM files with the purpose of providing input files for mkgmap.
 * <p/>
 * The input file is split so that no piece has more than a given number of nodes in it.
 *
 * @author Steve Ratcliffe
 */
public class Main {
	
	private static final String DEFAULT_DIR = ".";

	// We store area IDs and all used combinations of area IDs in a dictionary. The index to this
	// dictionary is saved in short values. If Short.MaxValue() is reached, the user might limit 
	// the number of areas that is processed in one pass.  
	private int maxAreasPerPass;

	// A list of the OSM files to parse.
	private List<String> fileNameList;

	// The description to write into the template.args file.
	private String description;

	// The starting map ID.
	private int mapId;

	// The amount in map units that tiles overlap. The default is overwritten depending on user settings.
	private int overlapAmount = -1;

	// A threshold value that is used when no split-file is given. Splitting is done so that
	// no tile has more than maxNodes nodes inside the bounding box of the tile.
	// Nodes added by overlap or keep-complete are not taken into account. 
	private long maxNodes;

	private int numTiles = -1;
	
	// This is a value in the range 0-24.
	// Higher numbers mean higher detail. The resolution determines how the tiles must
	// be aligned. Eg a resolution of 13 means the tiles need to have their edges aligned to
	// multiples of 2 ^ (24 - 13) = 2048 map units. 
	private int resolution;

	// Whether or not to trim tiles of any empty space around their edges.
	private boolean trim;
	// Set if there is a previous area file given on the command line.
	private AreaList areaList;
	// Whether or not the source OSM file(s) contain strictly nodes first, then ways, then rels,
	// or they're all mixed up. Running with mixed enabled takes longer.
	private boolean mixed;
	// A polygon file in osmosis polygon format
	private String polygonFile;
	private List<PolygonDesc> polygons = new ArrayList<>();

	// The path where the results are written out to.
	private File fileOutputDir;
	// A GeoNames file to use for naming the tiles.
	private String geoNamesFile;
	// How often (in seconds) to provide JVM status information. Zero = no information.
	private int statusFreq;

	private String kmlOutputFile;
	// The maximum number of threads the splitter should use.
	private int maxThreads;
	// The output type
	private String outputType;
	// a list of way or relation ids that should be handled specially
	private String problemFile;
	// Whether or not splitter should keep  
	private boolean keepComplete;
	
	private String problemReport;
	
	private String[] boundaryTags;
	
	private LongArrayList problemWays = new LongArrayList();
	private LongArrayList problemRels = new LongArrayList();
	private TreeSet<Long> calculatedProblemWays = new TreeSet<>();
	private TreeSet<Long> calculatedProblemRels = new TreeSet<>();
	
	// map with relations that should be complete and are written to only one tile 
	private final OSMId2ObjectMap<Short> oneTileOnlyRels = new OSMId2ObjectMap<>();

	// for faster access on blocks in pbf files
	private final HashMap<String, ShortArrayList> blockTypeMap = new HashMap<>(); 
	// for faster access on blocks in o5m files
	private final HashMap<String, long[]> skipArrayMap = new HashMap<>();

	private String stopAfter;

	private String precompSeaDir;

	private String polygonDescFile;
	private PolygonDescProcessor polygonDescProcessor;

	private int searchLimit;
	
	public static void main(String[] args) {
		Main m = new Main();
		try{
			int rc = m.start(args);
			if (rc != 0)
				System.exit(1);
		} catch (StopNoErrorException e){
			if (e.getMessage() != null)
				System.out.println(e.getMessage());
		}
	}
	
	private int start(String[] args) {
		int rc = 0;
		JVMHealthMonitor healthMonitor = null;
		
		try{
			readArgs(args);
		} catch (IllegalArgumentException e) {
			if (e.getMessage() != null)
				System.out.println("Error: " + e.getMessage());
			return 1;
		}
		if (statusFreq > 0) {
			healthMonitor = new JVMHealthMonitor(statusFreq);
			healthMonitor.start();
		}
		
		checkJREVersion();
		
		long start = System.currentTimeMillis();
		System.out.println("Time started: " + new Date());
		try {
			split();
		} catch (IOException e) {
			System.err.println("Error opening or reading file " + e);
			e.printStackTrace();
			return 1;
		} catch (XmlPullParserException e) {
			System.err.println("Error parsing xml from file " + e);
			e.printStackTrace();
			return 1;
		} catch (SplitFailedException e) {
			e.printStackTrace();
			return 1;
		} catch (StopNoErrorException e){
			if (e.getMessage() != null)
				System.out.println(e.getMessage());
			// nothing to do
		} catch (RuntimeException e) {
			e.printStackTrace();
			return 1;
		}
		System.out.println("Time finished: " + new Date());
		System.out.println("Total time taken: " + (System.currentTimeMillis() - start) / 1000 + 's');
		return rc;
	}

	/**
	 * Check if a JRE 1.7.x or higher is installed.
	 */
	private static void checkJREVersion() {
		/*
		String version = System.getProperty("java.version");
		if (version != null) {
			String[] versionParts =version.split(Pattern.quote(".")); 
			if (versionParts.length >= 2) {
				int major = Integer.valueOf(versionParts[1]);
				if (major < 7) {
					System.out.println("===========================================================");
					System.out.println("You are using an old Java runtime environment "+ version);
					System.out.println("It is no longer supported.");
					System.out.println("Please update Java to the latest release.");
					System.out.println("===========================================================");
					System.exit(1);
				}
			}
		}
		*/
	}
	
	private void split() throws IOException, XmlPullParserException {

		File outputDir = fileOutputDir;
		if (!outputDir.exists()) {
			System.out.println("Output directory not found. Creating directory '" + fileOutputDir + "'");
			if (!outputDir.mkdirs()) {
				System.err.println("Unable to create output directory! Using default directory instead");
				fileOutputDir = new File(DEFAULT_DIR);
			}
		} else if (!outputDir.isDirectory()) {
			System.err.println("The --output-dir parameter must specify a directory. The --output-dir parameter is being ignored, writing to default directory instead.");
			fileOutputDir = new File(DEFAULT_DIR);
		}

		if (fileNameList.isEmpty()) {
			throw new IllegalArgumentException("No input files were supplied");
		}

		if (areaList == null) {
			int alignment = 1 << (24 - resolution);
			System.out.println("Map is being split for resolution " + resolution + ':');
			System.out.println(" - area boundaries are aligned to 0x" + Integer.toHexString(alignment) + " map units (" + Utils.toDegrees(alignment) + " degrees)");
			System.out.println(" - areas are multiples of 0x" + Integer.toHexString(alignment) + " map units wide and high");
			areaList = calculateAreas();
			if (areaList == null || areaList.getAreas().isEmpty()){
				System.err.println("Failed to calculate areas. See log for details.");
				System.out.println("Failed to calculate areas.");
				System.out.println("Sorry. Cannot split the file without creating huge, almost empty, tiles.");
				System.out.println("Please specify a bounding polygon with the --polygon-file parameter.");
				return;
			}
			if (mapId + areaList.getAreas().size() > 99999999){
				System.err.println("Too many areas for initial mapid " + mapId + ", resetting to 63240001");
				mapId = 63240001;
			}
			for (Area area : areaList.getAreas()) {
				area.setMapId(mapId++);
			}
			nameAreas();
			areaList.write(new File(fileOutputDir, "areas.list").getPath());
			areaList.writePoly(new File(fileOutputDir, "areas.poly").getPath());
		} else {
			nameAreas();
		}

		List<Area> areas = areaList.getAreas();

		if (kmlOutputFile != null) {
			File out = new File(kmlOutputFile);
			if (!out.isAbsolute())
				out = new File(fileOutputDir, kmlOutputFile);
			System.out.println("Writing KML file to " + out.getPath());
			areaList.writeKml(out.getPath());
		}
		if (polygonDescProcessor != null)
			polygonDescProcessor.writeListFiles(outputDir, areas, kmlOutputFile, outputType);
		areaList.writeArgsFile(new File(fileOutputDir, "template.args").getPath(), outputType, -1);
		
		if ("split".equals(stopAfter)){
			try {Thread.sleep(1000);}catch (InterruptedException e) {}
			System.err.println("stopped after " + stopAfter); 
			throw new StopNoErrorException("stopped after " + stopAfter);
		}

		System.out.println(areas.size() + " areas:");
		for (Area area : areas) {
			System.out.format("Area %08d: %d,%d to %d,%d covers %s",
					area.getMapId(),
					area.getMinLat(), area.getMinLong(),
					area.getMaxLat(), area.getMaxLong(),
					area.toHexString());
			
			if (area.getName() != null)
				System.out.print(' ' + area.getName());
			System.out.println();
		}
		
		if (keepComplete){
			partitionAreasForProblemListGenerator(areas);
			if ("gen-problem-list".equals(stopAfter)){
				try {Thread.sleep(1000);}catch (InterruptedException e) {}
				System.err.println("stopped after " + stopAfter); 
				throw new StopNoErrorException("stopped after " + stopAfter);
			}
		}
		writeAreas(areas);
	}

	private int getAreasPerPass(int areaCount) {
		return (int) Math.ceil((double) areaCount / (double) maxAreasPerPass);
	}

	/**
	 * Deal with the command line arguments.
	 */
	private void readArgs(String[] args) {
		ParamParser parser = new ParamParser();
		SplitterParams params = parser.parse(SplitterParams.class, args);

		if (!parser.getErrors().isEmpty()) {
			System.out.println();
			System.out.println("Invalid parameter(s):");
			for (String error : parser.getErrors()) {
				System.out.println("  " + error);
			}
			System.out.println();
			parser.displayUsage();
			throw new IllegalArgumentException();
		}

		System.out.println("Splitter version " + Version.VERSION + " compiled " + Version.TIMESTAMP);
		 
		for (Map.Entry<String, Object> entry : parser.getConvertedParams().entrySet()) {
			String name = entry.getKey();
			Object value = entry.getValue();
			System.out.println(name + '=' + (value == null ? "" : value));
		}
		fileNameList = parser.getAdditionalParams();
		if (fileNameList.isEmpty()){
			throw new IllegalArgumentException("No file name(s) given");
		}
		boolean filesOK = true;
		for (String fileName: fileNameList){
			if (testAndReportFname(fileName, "input file") == false){
				filesOK = false;
			}
		}
		if (!filesOK){
			System.out.println("Make sure that option parameters start with -- " );
			throw new IllegalArgumentException();
		}
		mapId = params.getMapid();
		if (mapId > 99999999) {
			mapId = 63240001;
			System.err.println("The --mapid parameter must have less than 9 digits. Resetting to " + mapId + ".");
		} 		
		maxNodes = params.getMaxNodes();
		String numTilesParm = params.getNumTiles();
		if (numTilesParm != null){
			try{
				numTiles = Integer.parseInt(numTilesParm);
				if (numTiles >= 0 && numTiles < 2 ){
					System.err.println("Error: The --num-tiles parameter must be 2 or higher. Resetting to 2.");
					numTiles = 2;
				}
			} catch(NumberFormatException e){
				System.err.println("Error: Invalid number "+ numTilesParm + 
						". The --num-tiles parameter must be an integer value of 2 or higher.");
				throw new IllegalArgumentException();
			}
		}
		description = params.getDescription();
		geoNamesFile = params.getGeonamesFile();
		if (geoNamesFile != null){
			if (testAndReportFname(geoNamesFile, "geonames-file") == false){
				throw new IllegalArgumentException();
			}
		}
		resolution = params.getResolution();
		trim = !params.isNoTrim();
		outputType = params.getOutput();
		if("xml pbf o5m simulate".contains(outputType) == false) {
			System.err.println("The --output parameter must be either xml, pbf, o5m, or simulate. Resetting to xml.");
			outputType = "xml";
		}
		
		if (resolution < 1 || resolution > 24) {
			System.err.println("The --resolution parameter must be a value between 1 and 24. Resetting to 13.");
			resolution = 13;
		}
		mixed = params.isMixed();
		statusFreq = params.getStatusFreq();
		
		String outputDir = params.getOutputDir();
		fileOutputDir = new File(outputDir == null? DEFAULT_DIR: outputDir);

		maxAreasPerPass = params.getMaxAreas();
		if (maxAreasPerPass < 1 || maxAreasPerPass > 4096) {
			System.err.println("The --max-areas parameter must be a value between 1 and 4096. Resetting to 4096.");
			maxAreasPerPass = 4096;
		}
		kmlOutputFile = params.getWriteKml();

		maxThreads = params.getMaxThreads().getCount();
		
		problemFile = params.getProblemFile();
		if (problemFile != null){
			if (!readProblemIds(problemFile))
				throw new IllegalArgumentException();
		}
		String splitFile = params.getSplitFile();
		if (splitFile != null) {
			if (testAndReportFname(splitFile, "split-file") == false){
				throw new IllegalArgumentException();
			}
		}
		
		keepComplete = params.isKeepComplete();
		if (mixed && (keepComplete || problemFile != null)){
			System.err.println("--mixed=true is not supported in combination with --keep-complete=true or --problem-file.");
			System.err.println("Please use e.g. osomosis to sort the data in the input file(s)");
			throw new IllegalArgumentException();
		}
		
		String overlap = params.getOverlap();
		if ("auto".equals(overlap) == false){
			try{
				overlapAmount = Integer.valueOf(overlap);
			} 
			catch (NumberFormatException e){
				throw new IllegalArgumentException("Error: --overlap=" + overlap + " is not is not a valid option.");
			}
		}
		problemReport = params.getProblemReport();
		String boundaryTagsParm = params.getBoundaryTags();
		if ("use-exclude-list".equals(boundaryTagsParm) == false){
			Pattern csvSplitter = Pattern.compile(Pattern.quote(","));
			boundaryTags = csvSplitter.split(boundaryTagsParm);
		}

		// plausibility checks and default handling 
		if (keepComplete){
			if (fileNameList.size() > 1){
				System.err.println("warning: --keep-complete is only used for the first input file.");
			}
			if (overlapAmount > 0){
				System.err.println("Warning: --overlap is used in combination with --keep-complete=true ");
				System.err.println("         The option keep-complete should be used with overlap=0 because it is very unlikely that ");
				System.err.println("         the overlap will add any important data. It will just cause a lot of additional output which ");
				System.err.println("         has to be thrown away again in mkgmap.");
			} else
				overlapAmount = 0;
		}
		else {
			if (overlapAmount < 0){
				overlapAmount = 2000;
				System.out.println("Setting default overlap=2000 because keep-complete=false is in use.");
			}

			if (problemReport != null){
				System.out.println("Parameter --problem-report is ignored, because parameter --keep-complete=false is used");
			}
			if (boundaryTagsParm != null){
				System.out.println("Parameter --boundaryTags is ignored, because parameter --keep-complete=false is used");
			}
		}
		if (splitFile != null) {
			try {
				areaList = new AreaList();
				areaList.read(splitFile);
				areaList.dump();
			} catch (IOException e) {
				areaList = null;
				System.err.println("Could not read area list file");
				e.printStackTrace();
			}
		}
		
		polygonFile = params.getPolygonFile();
		if (polygonFile != null) {
			if (splitFile != null){
				System.out.println("Warning: parameter polygon-file is ignored because split-file is used.");
			} else {
				File f = new File(polygonFile);

				if (!f.exists()){
					throw new IllegalArgumentException("Error: polygon file doesn't exist: " + polygonFile);
				}
				PolygonFileReader polyReader = new PolygonFileReader(f);
				java.awt.geom.Area polygonInDegrees = polyReader.loadPolygon();
				PolygonDesc pd = new PolygonDesc(polyReader.getPolygonName(),
						Utils.AreaDegreesToMapUnit(polygonInDegrees), 
						mapId);
				polygons.add(pd);
			}
		}
		polygonDescFile = params.getPolygonDescFile();
		if (polygonDescFile != null) {
			if (splitFile != null){
				System.out.println("Warning: parameter polygon-desc-file is ignored because split-file is used.");
			} else {
				File f = new File(polygonDescFile);

				if (!f.exists()){
					System.out.println("Error: polygon desc file doesn't exist: " + polygonDescFile);  
					System.exit(-1);
				}
				polygonDescProcessor = new PolygonDescProcessor(resolution);
				try {
					processOSMFiles(polygonDescProcessor, Arrays.asList(polygonDescFile));
					polygons = polygonDescProcessor.getPolygons();
					KmlWriter.writeKml("e:/ld_sp/start-poly.kml", "start", polygonDescProcessor.getCombinedPolygon());
				} catch (XmlPullParserException e) {
					polygons = null;
					polygonDescProcessor = null;
					System.err.println("Could not read polygon desc file");
					e.printStackTrace();
				}
			}
		}
		if (polygons.isEmpty() == false){
			if (checkPolygons() == false){
				System.out.println("Warning: Bounding polygon is complex. Splitter might not be able to fit all tiles into the polygon!");
			}
		}
		stopAfter = params.getStopAfter();
		if ("split gen-problem-list handle-problem-list dist".contains(stopAfter) == false){
			throw new IllegalArgumentException("Error: the --stop-after parameter must be either split, gen-problem-list, handle-problem-list, or dist.");
		}
		
		precompSeaDir = params.getPrecompSea();
		if (precompSeaDir != null){
			File dir = new File (precompSeaDir);
			if (dir.exists() == false || dir.canRead() == false){
				throw new IllegalArgumentException("Error: precomp-sea directory doesn't exist or is not readable: " + precompSeaDir);  
			}
		}
		if (polygons.isEmpty() == false && numTiles > 0){
			System.out.println("Polygons are ignored because parameter --num-tiles is used");
		}
		searchLimit = params.getSearchLimit();
		if (searchLimit < 1000){
			searchLimit = 1000;
			System.err.println("The --search-limit parameter must be 1000 or higher. Resetting to 1000.");
		}
	}

	/**
	 * Calculate the areas that we are going to split into by getting the total area and
	 * then subdividing down until each area has at most max-nodes nodes in it.
	 */
	private AreaList calculateAreas() throws XmlPullParserException {

		DensityMapCollector pass1Collector = new DensityMapCollector(resolution); 
		MapProcessor processor = pass1Collector;
		
		File densityData = new File("densities.txt");
		File densityOutData = null;
		if (densityData.exists() && densityData.isFile()){
			System.err.println("reading density data from " + densityData.getAbsolutePath());
			pass1Collector.readMap(densityData.getAbsolutePath());
		}
		else {
			densityOutData = new File(fileOutputDir,"densities-out.txt");
			processMap(processor);
		}
		System.out.println("in " + fileNameList.size() + (fileNameList.size() == 1 ? " file" : " files"));
		System.out.println("Time: " + new Date());
		if (densityOutData != null )
			pass1Collector.saveMap(densityOutData.getAbsolutePath());

		Area exactArea = pass1Collector.getExactArea();
		if (precompSeaDir != null){
			System.out.println("Counting nodes of precompiled sea data ...");
			long startSea = System.currentTimeMillis();
			DensityMapCollector seaCollector = new DensityMapCollector(resolution);
			PrecompSeaReader precompSeaReader = new PrecompSeaReader(exactArea, new File(precompSeaDir));
			precompSeaReader.processMap(seaCollector);
			pass1Collector.mergeSeaData(seaCollector, trim);
			System.out.println("Precompiled sea data pass took " + (System.currentTimeMillis()-startSea) + " ms");
		}
		
		SplittableDensityArea splittableArea = pass1Collector.getRoundedArea(resolution, searchLimit);
		if (splittableArea.hasData() == false)
			return new AreaList(new ArrayList<Area>());
		System.out.println("Exact map coverage is " + exactArea);
		System.out.println("Rounded map coverage is " + splittableArea.getBounds());
		
		
		splittableArea.setTrim(trim);
		splittableArea.setMapId(mapId);
		long startSplit = System.currentTimeMillis();
		List<Area> areas ;
		if (numTiles >= 2){
			System.out.println("Splitting nodes into " + numTiles + " areas");
			areas = splittableArea.split(numTiles);
		}
		else {
			System.out.println("Splitting nodes into areas containing a maximum of " + Utils.format(maxNodes) + " nodes each...");
			splittableArea.setMaxNodes(maxNodes);
			areas = splittableArea.split(polygons);
		}
		if (areas != null && areas.isEmpty() == false)
			System.out.println("Creating the initial areas took " + (System.currentTimeMillis()- startSplit) + " ms");
		return new AreaList(areas);
	}
	
	private void nameAreas() {
		if (geoNamesFile == null) 
			return;
		CityLoader cityLoader = new CityLoader(true);
		List<City> cities = cityLoader.load(geoNamesFile);
		if (cities == null)
			return;
		
		CityFinder cityFinder = new DefaultCityFinder(cities);
		for (Area area : areaList.getAreas()) {
			// Decide what to call the area
			Set<City> found = cityFinder.findCities(area);
			City bestMatch = null;
			for (City city : found) {
				if (bestMatch == null || city.getPopulation() > bestMatch.getPopulation()) {
					bestMatch = city;
				}
			}
			if (bestMatch != null)
				area.setName(bestMatch.getCountryCode() + '-' + bestMatch.getName());
			else
				area.setName(description);
		}
	}

	/**
	 * Calculate lists of ways and relations that will be split for a given list
	 * of areas.
	 * @param areas	the list of areas
	 * @param partition used for informational messages
	 * @throws IOException
	 * @throws XmlPullParserException
	 */
	private void genProblemLists(List<Area> areas, int partition) throws IOException, XmlPullParserException {
		List<Area> workAreas = addPseudoWriters(areas);
		
		// debugging
		/*
		AreaList planet = new AreaList(workAreas);
		String planetName = "planet-partition-" + partition + ".kml";
		File out = new File(planetName);
		if (!out.isAbsolute())
			kmlOutputFile = new File(fileOutputDir, planetName).getPath();
		System.out.println("Writing planet KML file " + kmlOutputFile);
		planet.writeKml(kmlOutputFile);
		*/
		int numPasses = getAreasPerPass(workAreas.size());
		int areasPerPass = (int) Math.ceil((double) workAreas.size() / (double) numPasses);
		if (numPasses > 1) {
			System.out.println("Processing " + areas.size() + " areas in " + numPasses + " passes, " + areasPerPass + " areas at a time");
		} else {
			System.out.println("Processing " + areas.size() + " areas in a single pass");
		}

		OSMWriter [] writers = new OSMWriter[workAreas.size()];
		ArrayList<Area> allAreas = new ArrayList<>();

		System.out.println("Pseudo-Writers:");
		for (int j = 0;j < writers.length; j++){
			Area area = workAreas.get(j);
			allAreas.add(area);
			writers[j] = new PseudoOSMWriter(area, area.getMapId(), area.isPseudoArea(), 0);
			if (area.isPseudoArea())
				System.out.println("Pseudo area " + area.getMapId() + " covers " + area);
		}
		DataStorer dataStorer = new DataStorer(writers);
		System.out.println("Starting problem-list-generator pass(es) for partition " + partition); 
		LongArrayList problemWaysThisPart = new LongArrayList();
		LongArrayList problemRelsThisPart = new LongArrayList();
		for (int pass = 0; pass < numPasses; pass++) {
			System.out.println("-----------------------------------");
			System.out.println("Starting problem-list-generator pass " + (pass+1) + " of " + numPasses + " for partition " + partition);
			long startThisPass = System.currentTimeMillis();
			int writerOffset = pass * areasPerPass;
			int numWritersThisPass = Math.min(areasPerPass, workAreas.size() - pass * areasPerPass);
			ProblemListProcessor processor = new ProblemListProcessor(
					dataStorer, writerOffset, numWritersThisPass,
					problemWaysThisPart, problemRelsThisPart, oneTileOnlyRels, boundaryTags);
			
			boolean done = false;
			while (!done){
				done = processMap(processor);
			}
			System.out.println("Problem-list-generator pass " + (pass+1) + " for partition " + partition+ " took " + (System.currentTimeMillis() - startThisPass) + " ms"); 
		}
		//writeProblemList("problem-candidates-partition-" + partition + ".txt", problemWaysThisPart, problemRelsThisPart);
		calculatedProblemWays.addAll(problemWaysThisPart);
		calculatedProblemRels.addAll(problemRelsThisPart);
	}
	
	/**
	 * Separate a list of areas into parts so that no part has overlapping areas.  
	 * If the areas were read from a split-file, they might overlap. 
	 * For the problem-list processing we need disjoint areas.
	 * @param realAreas the list of areas (either from file or calculated by 1st pass)
	 * @throws IOException
	 * @throws XmlPullParserException
	 */
	private void partitionAreasForProblemListGenerator(List<Area> realAreas) throws IOException, XmlPullParserException{
		long startProblemListGenerator = System.currentTimeMillis();

		List<Area> remainingAreas = new ArrayList<>(realAreas);
		List<Area> distinctAreas;
		int partition = 0;
		while (remainingAreas.size() > 0){
			++partition;
			List<Area> workingSet = new ArrayList<>(remainingAreas);
			distinctAreas = getNonOverlappingAreas(workingSet, true);
			if (distinctAreas.size() * 1.25 > maxAreasPerPass){
				workingSet = new ArrayList<>(remainingAreas);
				distinctAreas = getNonOverlappingAreas(workingSet, false);
			}
			System.out.println("Generating problem list for " + distinctAreas.size() + " distinct areas");
			genProblemLists(distinctAreas, partition);
			remainingAreas = workingSet;
			
		} 
		System.out.println("Problem-list-generator pass(es) took " + (System.currentTimeMillis() - startProblemListGenerator) + " ms");
		if (partition > 1){
			// correct wrong entries caused by partitioning 
			for (Long id: calculatedProblemRels){
				oneTileOnlyRels.remove(id);
			}
			System.err.println("Waring: The areas given in --split-file are overlapping. Support for this will be removed in future versions.");
		}
		if (problemReport != null){
			writeProblemList(problemReport, 
					calculatedProblemWays,
					calculatedProblemRels);
		}
	}


	/**
	 * Final pass(es), we have the areas so parse the file(s) again. 
	 *
	 * @param areas Area list determined on the first pass.
	 */
	private void writeAreas(List<Area> areas) throws IOException, XmlPullParserException {
		OSMWriter[] allWriters = new OSMWriter[areas.size()];
		Map<String, byte[]> wellKnownTagKeys = null;
		Map<String, byte[]> wellKnownTagVals = null;
		if ("o5m".equals(outputType)){
			wellKnownTagKeys = new HashMap<>();
			wellKnownTagVals = new HashMap<>();
			
			String[] tagKeys = { "1", "1outer", "1inner", "type", // relation specific  
					// 50 most often used keys (taken from taginfo 2014-05-19)
					"source", "building",
					"highway", "name", "addr:housenumber", "addr:street",
					"addr:city", "addr:postcode", /*"created_by", */"addr:country", 
					"natural", "source:date", "tiger:cfcc", "tiger:county", 
					"tiger:reviewed", "landuse", "waterway", "wall", "surface", 
					"attribution", "power", "tiger:source", "tiger:tlid",
					"tiger:name_base", "oneway", "amenity", "start_date",
					"tiger:name_type", "ref:bag", "tiger:upload_uuid",  
					"tiger:separated", "ref", "yh:WIDTH", "tiger:zip_left", 
					"note", "source_ref", "tiger:zip_right", "access",
					"yh:STRUCTURE", "yh:TYPE", "yh:TOTYUMONO", "yh:WIDTH_RANK",  
					"maxspeed", "lanes", "service", "barrier", "source:addr",
					"tracktype", "is_in", "layer" , "place"};

			for (String s:tagKeys){
				wellKnownTagKeys.put(s, s.getBytes("UTF-8"));
			}
			
			String[] tagVals = { "yes", "no", "residential", "water", "tower",
					"footway", "Bing", "PGS", "private", "stream", "service",
					"house", "unclassified", "track", "traffic_signals","restaurant","entrance"};
			
			for (String s:tagVals){
				wellKnownTagVals.put(s, s.getBytes("UTF-8"));
			}
			
		}
		
		for (int j = 0; j < allWriters.length; j++) {
			Area area = areas.get(j);
			OSMWriter w;
			if ("pbf".equals(outputType)) 
				w = new BinaryMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount );
			else if ("o5m".equals(outputType))
				w = new O5mMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount, wellKnownTagKeys,wellKnownTagVals);
			else if ("simulate".equals(outputType))
				w = new PseudoOSMWriter(area, area.getMapId(), false, overlapAmount);
			else 
				w = new OSMXMLWriter(area, fileOutputDir, area.getMapId(), overlapAmount );
			allWriters[j] = w;
		}

		int numPasses = getAreasPerPass(areas.size());
		int areasPerPass = (int) Math.ceil((double) areas.size() / (double) numPasses);
		DataStorer dataStorer = new DataStorer(allWriters);
		// add the user given problem polygons
		problemWays.addAll(calculatedProblemWays);
		calculatedProblemWays = null;
		problemRels.addAll(calculatedProblemRels);
		calculatedProblemRels = null;
		if (problemWays.size() > 0 || problemRels.size() > 0){
			// calculate which ways and relations are written to multiple areas. 
			MultiTileProcessor multiProcessor = new MultiTileProcessor(dataStorer, problemWays, problemRels);
			// return memory to GC
			problemRels = null;
			problemWays = null;
			
			boolean done = false;
			long startThisPhase = System.currentTimeMillis();
			int prevPhase = -1; 
			while(!done){
				int phase = multiProcessor.getPhase();
				if (prevPhase != phase){
					startThisPhase = System.currentTimeMillis();
					System.out.println("-----------------------------------");
					System.out.println("Executing multi-tile analyses phase " + phase);
				}
				done = processMap(multiProcessor);
				prevPhase = phase;
				if (done || (phase != multiProcessor.getPhase())){
					System.out.println("Multi-tile analyses phase " + phase + " took " + (System.currentTimeMillis() - startThisPhase) + " ms");
				}
			}

			System.out.println("-----------------------------------");
		}
		if ("handle-problem-list".equals(stopAfter)){
			try {Thread.sleep(1000);}catch (InterruptedException e) {}
			System.err.println("stopped after " + stopAfter); 
			throw new StopNoErrorException("stopped after " + stopAfter);
		}

		// the final split passes
		dataStorer.switchToSeqAccess(fileOutputDir);
		System.out.println("Distributing data " + new Date());
		
		long startDistPass = System.currentTimeMillis();
		if (numPasses > 1) {
			System.out.println("Processing " + areas.size() + " areas in " + numPasses + " passes, " + areasPerPass + " areas at a time");
		} else {
			System.out.println("Processing " + areas.size() + " areas in a single pass");
		}
		for (int i = 0; i < numPasses; i++) {
			int writerOffset = i * areasPerPass;
			int numWritersThisPass = Math.min(areasPerPass, areas.size() - i * areasPerPass);
			dataStorer.restartWriterMaps();
			SplitProcessor processor = new SplitProcessor(dataStorer, oneTileOnlyRels, writerOffset, numWritersThisPass, maxThreads);

			System.out.println("Starting distribution pass " + (i + 1) + " of " + numPasses + ", processing " + numWritersThisPass +
					" areas (" + areas.get(i * areasPerPass).getMapId() + " to " +
					areas.get(i * areasPerPass + numWritersThisPass - 1).getMapId() + ')');

			processMap(processor); 
		}
		System.out.println("Distribution pass(es) took " + (System.currentTimeMillis() - startDistPass) + " ms");
		dataStorer.finish();
		
	}
	
	private boolean processMap(MapProcessor processor) throws XmlPullParserException {
		boolean done = processOSMFiles(processor, fileNameList);
		return done;
	}
	
	/** Read user defined problematic relations and ways */
	private boolean readProblemIds(String problemFileName) {
		File fProblem = new File(problemFileName);
		boolean ok = true;

		if (!fProblem.exists()) {
			System.out.println("Error: problem file doesn't exist: " + fProblem);  
			return false;
		}
		try (InputStream fileStream = new FileInputStream(fProblem);
				LineNumberReader problemReader = new LineNumberReader(
						new InputStreamReader(fileStream));) {
			Pattern csvSplitter = Pattern.compile(Pattern.quote(":"));
			Pattern commentSplitter = Pattern.compile(Pattern.quote("#"));
			String problemLine;
			String[] items;
			while ((problemLine = problemReader.readLine()) != null) {
				items = commentSplitter.split(problemLine);
				if (items.length == 0 || items[0].trim().isEmpty()){
					// comment or empty line
					continue;
				}
				items = csvSplitter.split(items[0].trim());
				if (items.length != 2) {
					System.out.println("Error: Invalid format in problem file, line number " + problemReader.getLineNumber() + ": "   
							+ problemLine);
					ok = false;
					continue;
				}
				long id = 0;
				try{
					id = Long.parseLong(items[1]);
				}
				catch(NumberFormatException exp){
					System.out.println("Error: Invalid number format in problem file, line number " + + problemReader.getLineNumber() + ": "   
							+ problemLine + exp);
					ok = false;
				}
				if ("way".equals(items[0]))
					problemWays.add(id);
				else if ("rel".equals(items[0]))
					problemRels.add(id);
				else {
					System.out.println("Error in problem file: Type not way or relation, line number " + + problemReader.getLineNumber() + ": "   
							+ problemLine);
					ok = false;
				}
			}
		} catch (IOException exp) {
			System.out.println("Error: Cannot read problem file " + fProblem +  
					exp);
			return false;
		}
		return ok;
	}
	
	/**
	 * Write a file that can be given to mkgmap that contains the correct arguments
	 * for the split file pieces.  You are encouraged to edit the file and so it
	 * contains a template of all the arguments that you might want to use.
	 * @param problemRelsThisPass 
	 * @param problemWaysThisPass 
	 */
	protected void writeProblemList(String fname, Set<Long> pWays, Set<Long> pRels) {
		try (PrintWriter w = new PrintWriter(new FileWriter(new File(
				fileOutputDir, fname)));) {

			w.println("#");
			w.println("# This file can be given to splitter using the --problem-file option");
			w.println("#");
			w.println("# List of relations and ways that are known to cause problems");
			w.println("# in splitter or mkgmap");
			w.println("# Objects listed here are specially treated by splitter to assure"); 
			w.println("# that complete data is written to all related tiles");  
			w.println("# Format:");
			w.println("# way:<id>");
			w.println("# rel:<id>");
			w.println("# ways");
			for (long id: pWays){
				w.println("way: " + id + " #");
			}
			w.println("# rels");
			for (long id: pRels){
				w.println("rel: " + id + " #");
			}

			w.println();
		} catch (IOException e) {
			System.err.println("Warning: Could not write problem-list file " + fname + ", processing continues");
		}
	}
	
	/**
	 * Make sure that our writer areas cover the planet. This is done by adding 
	 * pseudo-writers. 
	 * @param realAreas
	 * @return
	 */
	private static List<Area> addPseudoWriters(List<Area> realAreas){
		ArrayList<Area> areas = new ArrayList<>(realAreas);
		Rectangle planetBounds = new Rectangle(Utils.toMapUnit(-180.0), Utils.toMapUnit(-90.0), 2* Utils.toMapUnit(180.0), 2 * Utils.toMapUnit(90.0));

		while (!checkIfCovered(planetBounds, areas)){
			boolean changed = addPseudoArea(areas);
			
			if (!changed){
				throw new SplitFailedException("Failed to fill planet with pseudo-areas");
			}
		}
		return areas;
	}
	/**
	 * Work around for possible rounding errors in area.subtract processing
	 * @param area an area that is considered to be empty or a rectangle
	 * @return 
	 */
	private static java.awt.geom.Area simplifyArea(java.awt.geom.Area area) {
		if (area.isEmpty() || area.isRectangular())
			return area;
		// area.isRectugular() may returns false although the shape is a
		// perfect rectangle :-( If we subtract the area from its bounding
		// box we get better results.
		java.awt.geom.Area bbox = new java.awt.geom.Area (area.getBounds2D());
		bbox.subtract(area);
		if (bbox.isEmpty()) // bbox equals area: is a rectangle 
			return new java.awt.geom.Area (area.getBounds2D());
		return area;
	}

	private static boolean checkIfCovered(Rectangle bounds, ArrayList<Area> areas){
		java.awt.geom.Area bbox = new java.awt.geom.Area(bounds); 
		long sumTiles = 0;

		for (Area area: areas){
			sumTiles += (long)area.getHeight() * (long)area.getWidth();
			bbox.subtract(area.getJavaArea());
		}
		long areaBox = (long) bounds.height*(long)bounds.width;
		
		if (sumTiles != areaBox)
			return false;
			
		return bbox.isEmpty();
	}

	/**
	 * Create a list of areas that do not overlap. If areas in the original
	 * list are overlapping, they can be replaced by up to 5 disjoint areas.
	 * This is done if parameter makeDisjoint is true
	 * @param realAreas the list of areas (is modified in this method)
	 * @param makeDisjoint if true, replace overlapping areas by disjoint ones
	 * @return the new list
	 */
	private static ArrayList<Area> getNonOverlappingAreas(List<Area> realAreas, boolean makeDisjoint){
		java.awt.geom.Area covered = new java.awt.geom.Area();
		ArrayList<Area> splitList = new ArrayList<>();
		int artificialId = -99999999;
		boolean foundOverlap = false;
		Iterator<Area> realAreaIter = realAreas.iterator();
			
		while (realAreaIter.hasNext()){
			Area area1 = realAreaIter.next();
			Rectangle r1 = area1.getRect();
			if (covered.intersects(r1) == false){
				splitList.add(area1);
				realAreaIter.remove();
			}
			else {
				if (makeDisjoint == false)
					continue;
				//check if area is completely within covered area
				java.awt.geom.Area copyArea = new java.awt.geom.Area(area1.getJavaArea());
				copyArea.subtract(covered);
				if (copyArea.isEmpty())
					continue;
				if (makeDisjoint && foundOverlap == false){
					foundOverlap = true;
					System.out.println("Removing overlaps from tiles...");
				}
				realAreaIter.remove();
				//String msg = "splitting " + area1.getMapId() + " " + (i+1) + "/" + realAreas.size() + " overlapping ";	
				// find intersecting areas in the already covered part
				ArrayList<Area> splitAreas = new ArrayList<>();
				
				for (int j = 0; j < splitList.size(); j++){
					Area area2 = splitList.get(j);
					if (area2 == null)
						continue;
					Rectangle r2 = area2.getRect();
					if (r1.intersects(r2)){
						java.awt.geom.Area overlap = new java.awt.geom.Area(area1.getRect());
						overlap.intersect(area2.getJavaArea());
						Rectangle ro = overlap.getBounds();
						if (ro.height == 0 || ro.width == 0)
							continue;
						//msg += area2.getMapId() + " ";
						Area aNew = new Area(ro.y, ro.x, (int)ro.getMaxY(),(int)ro.getMaxX());
						aNew.setMapId(artificialId++);
						aNew.setResultOfSplitting(true);
						aNew.setName("" + area1.getMapId());
						aNew.setJoinable(false);
						covered.subtract(area2.getJavaArea());
						covered.add(overlap);
						splitList.set(j, aNew);
 
						java.awt.geom.Area coveredByPair = new java.awt.geom.Area(r1);
						coveredByPair.add(new java.awt.geom.Area(r2));
						
						java.awt.geom.Area originalPair = new java.awt.geom.Area(coveredByPair);
						
						int minX = coveredByPair.getBounds().x;
						int minY = coveredByPair.getBounds().y;
						int maxX = (int) coveredByPair.getBounds().getMaxX();
						int maxY = (int) coveredByPair.getBounds().getMaxY();
						coveredByPair.subtract(overlap);
						if (coveredByPair.isEmpty())
							continue; // two equal areas a

						coveredByPair.subtract(covered);
						java.awt.geom.Area testSplit = new java.awt.geom.Area(overlap);
						
						Rectangle[] rectPair = {r1,r2};
						Area[] areaPair = {area1,area2};
						int lx = minX;
						int lw = ro.x-minX;
						int rx = (int)ro.getMaxX();
						int rw = maxX - rx;
						int uy = (int)ro.getMaxY();
						int uh = maxY - uy;
						int by = minY;
						int bh = ro.y - by;
						Rectangle[] clippers = {
								new Rectangle(lx, 	minY, lw, 	    bh),		// lower left
								new Rectangle(ro.x,	minY, ro.width, bh),     	// lower middle
								new Rectangle(rx, 	minY, rw, 		bh),     	// lower right
								new Rectangle(lx, 	ro.y, lw, 		ro.height), // left
								new Rectangle(rx, 	ro.y, rw, 		ro.height), // right
								new Rectangle(lx, 	uy,   lw, 		uh), 		// upper left
								new Rectangle(ro.x, uy,   ro.width, uh), 		// upper middle
								new Rectangle(rx, 	uy,   rw, 		uh)  		// upper right
								}; 
						
						for (Rectangle clipper: clippers){
							for (int k = 0; k <= 1; k++){
								Rectangle test = clipper.intersection(rectPair[k]);
								if (!test.isEmpty()){
									testSplit.add(new java.awt.geom.Area(test));
									if (k==1 || covered.intersects(test) == false){
										aNew = new Area(test.y,test.x,(int)test.getMaxY(),(int)test.getMaxX());
										aNew.setMapId(areaPair[k].getMapId());
										aNew.setResultOfSplitting(true);
										splitAreas.add(aNew);
										covered.add(aNew.getJavaArea());
									}
								}
							}
						}
						assert testSplit.equals(originalPair);
					}
				}
				
				// recombine parts that form a rectangle
				for (Area splitArea: splitAreas){
					if (splitArea.isJoinable()){
						for (int j = 0; j < splitList.size(); j++){
							Area area = splitList.get(j);
							if (area == null || area.isJoinable() == false || area.getMapId() != splitArea.getMapId() )
								continue;
							boolean doJoin = false;
							if (splitArea.getMaxLat() == area.getMaxLat()
									&& splitArea.getMinLat() == area.getMinLat()
									&& (splitArea.getMinLong() == area.getMaxLong() || splitArea.getMaxLong() == area.getMinLong()))
									doJoin = true;
							else if (splitArea.getMinLong() == area.getMinLong()
									&& splitArea.getMaxLong()== area.getMaxLong()
									&& (splitArea.getMinLat() == area.getMaxLat() || splitArea.getMaxLat() == area.getMinLat()))
									doJoin = true;
							if (doJoin){
								splitArea = area.add(splitArea);
								splitArea.setMapId(area.getMapId());
								splitArea.setResultOfSplitting(true);
								splitList.set(j, splitArea);
								splitArea = null; // don't add later
								break;
							}
						}
					}
					if (splitArea != null){
						splitList.add(splitArea);
					}
				}
				/*
				if (msg.isEmpty() == false) 
					System.out.println(msg);
					*/
			}
			covered.add(new java.awt.geom.Area(r1));
		}
		covered.reset();
		Iterator <Area> iter = splitList.iterator();
		while (iter.hasNext()){
			Area a = iter.next();
			if (a == null)
				iter.remove();
			else {
				Rectangle r1 = a.getRect();
				if (covered.intersects(r1) == true){
					throw new SplitFailedException("Failed to create list of distinct areas");
				}
				covered.add(a.getJavaArea());
			}
		}
		return splitList;
	}

	/**
	 * Fill uncovered parts of the planet with pseudo-areas.
	 * TODO: check if better algorithm reduces run time in ProblemListProcessor
	 * We want a small number of pseudo areas because many of them will
	 * require more memory or more passes, esp. when processing whole planet.
	 * Also, the total length of all edges should be small.
	 * @param areas list of areas (either real or pseudo)
	 * @return true if pseudo-areas were added
	 */
	private static boolean addPseudoArea(ArrayList<Area> areas) {
		int oldSize = areas.size();
		Rectangle planetBounds = new Rectangle(Utils.toMapUnit(-180.0), Utils.toMapUnit(-90.0), 2* Utils.toMapUnit(180.0), 2 * Utils.toMapUnit(90.0));
		java.awt.geom.Area uncovered = new java.awt.geom.Area(planetBounds); 
		java.awt.geom.Area covered = new java.awt.geom.Area(); 
		for (Area area: areas){
			uncovered.subtract(area.getJavaArea());
			covered.add(area.getJavaArea());
		}
		Rectangle rCov = covered.getBounds();
		Rectangle[] topAndBottom = {
				new Rectangle(planetBounds.x,(int)rCov.getMaxY(),planetBounds.width, (int)(planetBounds.getMaxY()-rCov.getMaxY())), // top
				new Rectangle(planetBounds.x,planetBounds.y,planetBounds.width,rCov.y-planetBounds.y)}; // bottom
		for (Rectangle border: topAndBottom){
			if (!border.isEmpty()){
				uncovered.subtract(new java.awt.geom.Area(border));
				covered.add(new java.awt.geom.Area(border));
				Area pseudo = new Area(border.y,border.x,(int)border.getMaxY(),(int)border.getMaxX());
				pseudo.setMapId(-1 * (areas.size()+1));
				pseudo.setPseudoArea(true);
				areas.add(pseudo);
			}
		}
		while (uncovered.isEmpty() == false){
			boolean changed = false;
			List<List<Point>> shapes = Utils.areaToShapes(uncovered);
			// we divide planet into stripes for all vertices of the uncovered area
			int minX = uncovered.getBounds().x;
			int nextX = Integer.MAX_VALUE;
			for (int i = 0; i < shapes.size(); i++){
				List<Point> shape = shapes.get(i);
				for (Point point: shape){
					int lon = point.x;
					if (lon < nextX && lon > minX) 
						nextX = lon;
				}
			}
			java.awt.geom.Area stripeLon = new java.awt.geom.Area(new Rectangle(minX, planetBounds.y, nextX - minX, planetBounds.height));
			// cut out already covered area
			stripeLon.subtract(covered);
			assert stripeLon.isEmpty() == false;
			// the remaining area must be a set of zero or more disjoint rectangles
			List<List<Point>> stripeShapes = Utils.areaToShapes(stripeLon);
			for (int j = 0; j < stripeShapes .size(); j++){
				List<Point> rectShape = stripeShapes .get(j);
				java.awt.geom.Area test = Utils.shapeToArea(rectShape);
				test = simplifyArea(test);
				assert test.isRectangular();
				Rectangle pseudoRect = test.getBounds();
				if (uncovered.contains(pseudoRect)){
					assert test.getBounds().width == stripeLon.getBounds().width;
					boolean wasMerged = false;
					// check if new area can be merged with last rectangles
					for (int k=areas.size()-1; k >= oldSize; k--){
						Area prev = areas.get(k);
						if (prev.getMaxLong() < pseudoRect.x || prev.isPseudoArea() == false)
							continue;
						if (prev.getHeight() == pseudoRect.height && prev.getMaxLong() == pseudoRect.x && prev.getMinLat() == pseudoRect.y){
							// merge
							Area pseudo = prev.add(new Area(pseudoRect.y,pseudoRect.x,(int)pseudoRect.getMaxY(),(int)pseudoRect.getMaxX()));
							pseudo.setMapId(prev.getMapId());
							pseudo.setPseudoArea(true);
							areas.set(k, pseudo);
							//System.out.println("Enlarged pseudo area " + pseudo.getMapId() + " " + pseudo);
							wasMerged = true;
							break;
						}
					}
					
					if (!wasMerged){
						Area pseudo = new Area(pseudoRect.y, pseudoRect.x, (int)pseudoRect.getMaxY(), (int)pseudoRect.getMaxX());
						pseudo.setMapId(-1 * (areas.size()+1));
						pseudo.setPseudoArea(true);
						//System.out.println("Adding pseudo area " + pseudo.getMapId() + " " + pseudo); 
						areas.add(pseudo);
					}
					uncovered.subtract(test);
					covered.add(test);
					changed = true;
				}
			}
			if (!changed)
				break;
		}
		return oldSize != areas.size();
	}

	/**
	 * Check if the bounding polygons are usable.
	 * @param polygon
	 * @return
	 */
	private boolean checkPolygons() {
		for (PolygonDesc pd : polygons){
			if (checkPolygon(pd.area) == false)
				return false;
		}
		return true;
	}


	/**
	 * Check if the bounding polygon is usable.
	 * @param polygon
	 * @return
	 */
	private boolean checkPolygon(java.awt.geom.Area mapPolygonArea) {
		List<List<Point>> shapes = Utils.areaToShapes(mapPolygonArea);
		int shift = 24 - resolution;
		long rectangleWidth = 1L << shift;
		for (List<Point> shape: shapes){
			int estimatedPoints = 0;
			Point p1 = shape.get(0);
			for (int i = 1; i < shape.size(); i++){
				Point p2 = shape.get(i);
				if (p1.x != p2.x && p1.y != p2.y){
					// diagonal line
					int width = Math.abs(p1.x-p2.x);
					int height =  Math.abs(p1.y-p2.y);
					estimatedPoints += (Math.min(width, height) / rectangleWidth) * 2;
				}
				
				if (estimatedPoints > SplittableDensityArea.MAX_SINGLE_POLYGON_VERTICES)
					return false; // too complex
					
				p1 = p2;
			}
		}
		return true;
	}

	static boolean testAndReportFname(String fileName, String type){
		File f = new File(fileName);
		if (f.exists() == false || f.isFile() == false || f.canRead() == false){
			String msg = "Error: " + type + " doesn't exist or is not a readable file: " + fileName;
			System.out.println(msg);
			System.err.println(msg);
			return false;
		}
		return true;
	}

	private boolean processOSMFiles(MapProcessor processor, List<String> filenames) throws XmlPullParserException {
		// Create both an XML reader and a binary reader, Dispatch each input to the
		// Appropriate parser.
		OSMParser parser = new OSMParser(processor, mixed);
	
		for (int i = 0; i < filenames.size(); i++){
			String filename = filenames.get(i);
			System.out.println("Processing " + filename);
			if (i == 1 && processor instanceof DensityMapCollector){
				((DensityMapCollector) processor).checkBounds();
			}
			
			try {
				if (filename.endsWith(".o5m")) {
					File file = new File(filename);
					try(InputStream stream = new FileInputStream(file)){
						long[] skipArray = skipArrayMap.get(filename);
						O5mMapParser o5mParser = new O5mMapParser(processor, stream, skipArray);
						o5mParser.parse();
						if (skipArray == null){
							skipArray = o5mParser.getSkipArray();
							skipArrayMap.put(filename, skipArray);
						}
					}
				}
				else if (filename.endsWith(".pbf")) {
					// Is it a binary file?
					File file = new File(filename);
					ShortArrayList blockTypes = blockTypeMap.get(filename);
					BinaryMapParser binParser = new BinaryMapParser(processor, blockTypes);
					try(InputStream stream = new FileInputStream(file)){
						BlockInputStream blockinput = (new BlockInputStream(stream, binParser));
						blockinput.process();
						if (blockTypes == null){
							// remember this file 
							blockTypes = binParser.getBlockList();
							blockTypeMap.put(filename, blockTypes);
						}
					}
				} else {
					// No, try XML.
					try (Reader reader = Utils.openFile(filename, maxThreads > 1)){
						parser.setReader(reader);
						parser.parse();
					}
				}
			} catch (FileNotFoundException e) {
				System.out.println(e);
				throw new SplitFailedException("ERROR: file " + filename + " was not found");
			} catch (XmlPullParserException e) {
				System.out.println(e);
				throw new SplitFailedException("ERROR: file " + filename + " is not a valid OSM XML file");
			} catch (IllegalArgumentException e) {
				System.out.println(e);
				throw new SplitFailedException("ERROR: file " + filename + " contains unexpected data");
			} catch (IOException e) {
				System.out.println(e);
				throw new SplitFailedException("ERROR: file " + filename + " caused I/O exception");
			}
		}
		boolean done = processor.endMap();
		return done;
	}
}
