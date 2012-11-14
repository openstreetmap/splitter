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

import org.xmlpull.v1.XmlPullParserException;

import uk.me.parabola.splitter.args.ParamParser;
import uk.me.parabola.splitter.args.SplitterParams;
import uk.me.parabola.splitter.geo.City;
import uk.me.parabola.splitter.geo.CityFinder;
import uk.me.parabola.splitter.geo.CityLoader;
import uk.me.parabola.splitter.geo.DefaultCityFinder;
import uk.me.parabola.splitter.geo.DummyCityFinder;

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
import java.nio.charset.Charset;
import java.util.ArrayList;
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
	private List<String> filenames;

	// The description to write into the template.args file.
	private String description;

	// The starting map ID.
	private int mapId;

	// The amount in map units that tiles overlap (note that the final img's will not overlap
	// but the input files do).
	private int overlapAmount;

	// The max number of nodes that will appear in a single file.
	private long maxNodes;

	// The maximum resolution of the map to be produced by mkgmap. This is a value in the range
	// 0-24. Higher numbers mean higher detail. The resolution determines how the tiles must
	// be aligned. Eg a resolution of 13 means the tiles need to have their edges aligned to
	// multiples of 2 ^ (24 - 13) = 2048 map units, and their widths and heights must be a multiple
	// of 2 * 2 ^ (24 - 13) = 4096 units. The tile widths and height multiples are double the tile
	// alignment because the center point of the tile is stored, and that must be aligned the
	// same as the tile edges are.
	private int resolution;

	// Whether or not to trim tiles of any empty space around their edges.
	private boolean trim;
	// This gets set if no osm file is supplied as a parameter and the cache is empty.
	private boolean useStdIn;
	// Set if there is a previous area file given on the command line.
	private AreaList areaList;
	// Whether or not the source OSM file(s) contain strictly nodes first, then ways, then rels,
	// or they're all mixed up. Running with mixed enabled takes longer.
	private boolean mixed;
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
	private boolean generateProblemList;
	
	private LongArrayList problemWays = new LongArrayList();
	private LongArrayList problemRels = new LongArrayList();
	private TreeSet<Long> calculatedProblemWays = new TreeSet<Long>();
	private TreeSet<Long> calculatedProblemRels = new TreeSet<Long>();
	
	// for faster access on blocks in pbf files
	private final HashMap<String, ShortArrayList> blockTypeMap = new HashMap<String, ShortArrayList>(); 
	// for faster access on blocks in o5m files
	private final HashMap<String, long[]> skipArrayMap = new HashMap<String, long[]>(); 
	
	public static void main(String[] args) {

		Main m = new Main();
		m.start(args);
	}

	private void start(String[] args) {
		readArgs(args);
		if (statusFreq > 0) {
			JVMHealthMonitor.start(statusFreq);
		}
		long start = System.currentTimeMillis();
		System.out.println("Time started: " + new Date());
		try {
			split();
		} catch (IOException e) {
			System.err.println("Error opening or reading file " + e);
			e.printStackTrace();
		} catch (XmlPullParserException e) {
			System.err.println("Error parsing xml from file " + e);
			e.printStackTrace();
		}
		System.out.println("Time finished: " + new Date());
		System.out.println("Total time taken: " + (System.currentTimeMillis() - start) / 1000 + 's');
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

		if (filenames.isEmpty()) {
			if (areaList == null) {
				throw new IllegalArgumentException("No .osm files were supplied so at least one of --cache or --split-file must be specified");
			} else {
				int areaCount = areaList.getAreas().size();
				int passes = getAreasPerPass(areaCount);
				if (passes > 1) {
					throw new IllegalArgumentException("No .osm files or --cache parameter were supplied, but stdin cannot be used because " + passes
							+ " passes are required to write out the areas. Either provide --cache or increase --max-areas to match the number of areas (" + areaCount + ')');
				}
				useStdIn = true;
			}
		}

		if (areaList == null) {
			int alignment = 1 << (24 - resolution);
			System.out.println("Map is being split for resolution " + resolution + ':');
			System.out.println(" - area boundaries are aligned to 0x" + Integer.toHexString(alignment) + " map units");
			System.out.println(" - areas are multiples of 0x" + Integer.toHexString(alignment * 2) + " map units wide and high");
			areaList = calculateAreas();
			for (Area area : areaList.getAreas()) {
				area.setMapId(mapId++);
			}
			nameAreas();
			areaList.write(new File(fileOutputDir, "areas.list").getPath());
		} else {
			nameAreas();
		}

		List<Area> areas = areaList.getAreas();
		System.out.println(areas.size() + " areas:");
		for (Area area : areas) {
			System.out.print("Area " + area.getMapId() + " covers " + area.toHexString());
			if (area.getName() != null)
				System.out.print(' ' + area.getName());
			System.out.println();
		}

		if (kmlOutputFile != null) {
			File out = new File(kmlOutputFile);
			if (!out.isAbsolute())
				kmlOutputFile = new File(fileOutputDir, kmlOutputFile).getPath();
			System.out.println("Writing KML file to " + kmlOutputFile);
			areaList.writeKml(kmlOutputFile);
		}
		if (generateProblemList){
			partitionAreasForProblemListGenerator(areas);
		}
		writeAreas(areas);
		writeArgsFile(areas);
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
			System.exit(-1);
		}

		for (Map.Entry<String, Object> entry : parser.getConvertedParams().entrySet()) {
			String name = entry.getKey();
			Object value = entry.getValue();
			System.out.println(name + '=' + (value == null ? "" : value));
		}

		mapId = params.getMapid();
		overlapAmount = params.getOverlap();
		maxNodes = params.getMaxNodes();
		description = params.getDescription();
		geoNamesFile = params.getGeonamesFile();
		resolution = params.getResolution();
		trim = !params.isNoTrim();
		outputType = params.getOutput();
		// Remove warning and make the default pbf after a while.
		if (outputType.equals("unset")) {
			System.err.println("\n\n**** WARNING: the default output type has changed to pbf, use --output=xml for .osm.gz files\n");
			outputType = "pbf";
		}
		if(!outputType.equals("xml") && !outputType.equals("pbf") && !outputType.equals("o5m")) {
			System.err.println("The --output parameter must be either xml or pbf or o5m. Resetting to xml.");
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
		if (maxAreasPerPass < 1 || maxAreasPerPass > 2048) {
			System.err.println("The --max-areas parameter must be a value between 1 and 2048. Resetting to 2048.");
			maxAreasPerPass = 2048;
		}
		kmlOutputFile = params.getWriteKml();

		maxThreads = params.getMaxThreads().getCount();
		filenames = parser.getAdditionalParams();
		
		String splitFile = params.getSplitFile();
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
		problemFile = params.getProblemFile();
		if (problemFile != null){
			if (!readProblemIds(problemFile))
				System.exit(-1);
		}
		generateProblemList = params.isKeepComplete();
	}

	/**
	 * Calculate the areas that we are going to split into by getting the total area and
	 * then subdividing down until each area has at most max-nodes nodes in it.
	 */
	private AreaList calculateAreas() throws IOException, XmlPullParserException {

		MapCollector pass1Collector = new DensityMapCollector(trim, resolution); 
		MapProcessor processor = pass1Collector;

		processMap(processor);
		//MapReader mapReader = processMap(processor);

		//System.out.print("A total of " + Utils.format(mapReader.getNodeCount()) + " nodes, " +
		//				Utils.format(mapReader.getWayCount()) + " ways and " +
		//				Utils.format(mapReader.getRelationCount()) + " relations were processed ");

		System.out.println("in " + filenames.size() + (filenames.size() == 1 ? " file" : " files"));

		//System.out.println("Min node ID = " + mapReader.getMinNodeId());
		//System.out.println("Max node ID = " + mapReader.getMaxNodeId());

		System.out.println("Time: " + new Date());

		Area exactArea = pass1Collector.getExactArea();
		SplittableArea splittableArea = pass1Collector.getRoundedArea(resolution);
		System.out.println("Exact map coverage is " + exactArea);
		System.out.println("Trimmed and rounded map coverage is " + splittableArea.getBounds());
		System.out.println("Splitting nodes into areas containing a maximum of " + Utils.format(maxNodes) + " nodes each...");

		List<Area> areas = splittableArea.split(maxNodes);
		return new AreaList(areas);
	}

	private void nameAreas() throws IOException {
		CityFinder cityFinder;
		if (geoNamesFile != null) {
			CityLoader cityLoader = new CityLoader(true);
			List<City> cities = cityLoader.load(geoNamesFile);
			cityFinder = new DefaultCityFinder(cities);
		} else {
			cityFinder = new DummyCityFinder();
		}

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
	 * 
	 * @param areas
	 * @throws IOException
	 * @throws XmlPullParserException
	 */
	private void genProblemLists(List<Area> areas, int partition) throws IOException, XmlPullParserException {
		List<Area> workAreas = addPseudoWriters(areas);
		
		// debugging
		AreaList planet = new AreaList(workAreas);
		String planetName = "planet_partition_" + partition + ".kml";
		File out = new File(planetName);
		if (!out.isAbsolute())
			kmlOutputFile = new File(fileOutputDir, planetName).getPath();
		System.out.println("Writing planet KML file " + kmlOutputFile);
		planet.writeKml(kmlOutputFile);
		
		int numPasses = getAreasPerPass(workAreas.size());
		int areasPerPass = (int) Math.ceil((double) workAreas.size() / (double) numPasses);

		OSMWriter [] writers = new OSMWriter[workAreas.size()];
		ArrayList<Area> allAreas = new ArrayList<Area>();

		System.out.println("Pseudo-Writers:");
		for (int j = 0;j < writers.length; j++){
			Area area = workAreas.get(j);
			allAreas.add(area);
			writers[j] = new PseudoOSMWriter(area, area.getMapId(), area.isPseudoArea());
			if (area.isPseudoArea())
				System.out.println("Pseudo area " + area.getMapId() + " covers " + area);
		}
		DataStorer dataStorer = new DataStorer(writers);
		System.out.println("Starting problem-list-generator pass(es) for partition " + partition); 
		LongArrayList problemWaysThisPart = new LongArrayList();
		LongArrayList problemRelsThisPart = new LongArrayList();
		for (int pass = 0; pass < numPasses; pass++) {

			/*TODO: if two or passes are needed, all pseudo-writers will be used in the last pass.
			 * This might be bad if the pseudo-writers cover most of the input area. 
			 */
			System.out.println("-----------------------------------");
			System.out.println("Starting problem-list-generator pass " + (pass+1) + " of " + numPasses + " for writer set " + partition);
			long startThisPass = System.currentTimeMillis();
			int writerOffset = pass * areasPerPass;
			int numWritersThisPass = Math.min(areasPerPass, workAreas.size() - pass * areasPerPass);
			ProblemListProcessor processor = new ProblemListProcessor(
					dataStorer, writerOffset, numWritersThisPass,
					problemWaysThisPart, problemRelsThisPart);
			
			processMap(processor); 
			System.out.println("Problem-list-generator pass " + (pass+1) + " took " + (System.currentTimeMillis() - startThisPass) + " ms"); 
		}
		writeProblemList("problem-candidates-pass" + partition + ".txt", problemWaysThisPart, problemRelsThisPart);
		calculatedProblemWays.addAll(problemWaysThisPart);
		calculatedProblemRels.addAll(problemRelsThisPart);
	}
	
	/**
	 * If the areas were read from a split-file, they might overlap. 
	 * For the problem-list processing we need disjoint areas.
	 * @param realAreas
	 * @throws IOException
	 * @throws XmlPullParserException
	 */
	private void partitionAreasForProblemListGenerator(List<Area> realAreas) throws IOException, XmlPullParserException{
		long startProblemListGenerator = System.currentTimeMillis();

		ArrayList<Area> remainingAreas = new ArrayList<Area>(realAreas);
		ArrayList<Area> distinctAreas;
		int partition = 0;
		while (remainingAreas.size() > 0){
			++partition;
			distinctAreas = getNonOverlappingAreas(remainingAreas, true);
			if (distinctAreas.size() * 1.25 > maxAreasPerPass)
				distinctAreas = getNonOverlappingAreas(remainingAreas, false);
			else 
				remainingAreas.clear();
			genProblemLists(distinctAreas, partition);
			remainingAreas.removeAll(distinctAreas);
		} 
		System.out.println("Problem-list-generator pass(es) took " + (System.currentTimeMillis() - startProblemListGenerator) + " ms");
		writeProblemList("problem-candidates-merged" + partition + ".txt",
				new ArrayList<Long>(calculatedProblemWays),
				new ArrayList<Long>(calculatedProblemRels));
	}


	/**
	 * Final pass(es), we have the areas so parse the file(s) again and calculate 
	 * which ways and relations are written to multiple areas.
	 *
	 * @param areas Area list determined on the first pass.
	 */
	private void writeAreas(List<Area> areas) throws IOException, XmlPullParserException {
		//OSMWriter[] allWriters = new OSMWriter[areas.size()];
		OSMWriter[] allWriters = new OSMWriter[areas.size()];
		for (int j = 0; j < allWriters.length; j++) {
			Area area = areas.get(j);
			OSMWriter w;
			if ("pbf".equals(outputType)) 
				w = new BinaryMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount );
			else if ("o5m".equals(outputType))
				w = new O5mMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount );
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
			MultiTileProcessor multiProcessor = new MultiTileProcessor(dataStorer, problemWays, problemRels);
			// return memory to GC
			problemRels = null;
			problemWays = null;
			
			int pass = 0;
			boolean done = false;
			while(!done){
				long startThisPass = System.currentTimeMillis();
				++pass;
				System.out.println("-----------------------------------");
				System.out.println("Starting multi-tile analyses pass " + pass);
				done = processMap(multiProcessor);
				System.out.println("Multi-tile analyses pass " + pass + " took " + (System.currentTimeMillis() - startThisPass) + " ms"); 
			}

			System.out.println("-----------------------------------");
		}
		
		dataStorer.setReadOnly(fileOutputDir);

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
			dataStorer.restart();
			SplitProcessor processor = new SplitProcessor(dataStorer, writerOffset, numWritersThisPass, maxThreads);

			System.out.println("Starting distribution pass " + (i + 1) + " of " + numPasses + ", processing " + numWritersThisPass +
					" areas (" + areas.get(i * areasPerPass).getMapId() + " to " +
					areas.get(i * areasPerPass + numWritersThisPass - 1).getMapId() + ')');

			processMap(processor); 
		}
		System.out.println("Distribution pass(es) took " + (System.currentTimeMillis() - startDistPass) + " ms"); 
		dataStorer.finish();
		
	}
	
	private boolean processMap(MapProcessor processor) throws XmlPullParserException {
		// Create both an XML reader and a binary reader, Dispatch each input to the
		// Appropriate parser.
		OSMParser parser = new OSMParser(processor, mixed);
		if (useStdIn) {
			System.out.println("Reading osm data from stdin...");
			Reader reader = new InputStreamReader(System.in, Charset.forName("UTF-8"));
			parser.setReader(reader);
			try {
				try {
					parser.parse();
				} finally {
					reader.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		for (String filename : filenames) {
			System.out.println("Processing " + filename);
			try {
				if (filename.endsWith(".o5m")) {
					File file = new File(filename);
					InputStream stream = new FileInputStream(file);
					long[] skipArray = skipArrayMap.get(filename);
					O5mMapParser o5mParser = new O5mMapParser(processor, stream, skipArray);
					o5mParser.parse();
					if (skipArray == null){
						skipArray = o5mParser.getSkipArray();
						skipArrayMap.put(filename, skipArray);
					}
				}
				else if (filename.endsWith(".pbf")) {
					// Is it a binary file?
					File file = new File(filename);
					ShortArrayList blockTypes = blockTypeMap.get(filename);
					BinaryMapParser binParser = new BinaryMapParser(processor, blockTypes);
					BlockInputStream blockinput = (new BlockInputStream(
							new FileInputStream(file), binParser));
					try {
						blockinput.process();
					} finally {
						blockinput.close();
						if (blockTypes == null){
							// remember this file 
							blockTypes = binParser.getBlockList();
							blockTypeMap.put(filename, blockTypes);
						}
					}
				} else {
					// No, try XML.
					Reader reader = Utils.openFile(filename, maxThreads > 1);
					parser.setReader(reader);
					try {
						parser.parse();
					} finally {
						reader.close();
					}
				}
			} catch (FileNotFoundException e) {
				System.out.printf("ERROR: file %s was not found\n", filename);
			} catch (XmlPullParserException e) {
				System.out.printf("ERROR: file %s is not a valid OSM XML file\n", filename);
			} catch (IllegalArgumentException e) {
				System.out.printf("ERROR: file %s contains unexpected data\n", filename);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		boolean done = processor.endMap();
		return done;
	}
	
	/**
	 * Write a file that can be given to mkgmap that contains the correct arguments
	 * for the split file pieces.  You are encouraged to edit the file and so it
	 * contains a template of all the arguments that you might want to use.
	 */
	protected void writeArgsFile(List<Area> areas) {
		PrintWriter w;
		try {
			w = new PrintWriter(new FileWriter(new File(fileOutputDir, "template.args")));
		} catch (IOException e) {
			System.err.println("Could not write template.args file");
			return;
		}

		w.println("#");
		w.println("# This file can be given to mkgmap using the -c option");
		w.println("# Please edit it first to add a description of each map.");
		w.println("#");
		w.println();

		w.println("# You can set the family id for the map");
		w.println("# family-id: 980");
		w.println("# product-id: 1");

		w.println();
		w.println("# Following is a list of map tiles.  Add a suitable description");
		w.println("# for each one.");
		for (Area a : areas) {
			w.println();
			w.format("mapname: %08d\n", a.getMapId());
			if (a.getName() == null)
				w.println("# description: OSM Map");
			else
				w.println("description: " + (a.getName().length() > 50 ? a.getName().substring(0, 50) : a.getName()));
			if("pbf".equals(outputType))
				  w.format("input-file: %08d.osm.pbf\n", a.getMapId());
			else if("o5m".equals(outputType))
				  w.format("input-file: %08d.o5m\n", a.getMapId());
			else
			  w.format("input-file: %08d.osm.gz\n", a.getMapId());
		}

		w.println();
		w.close();
	}
	
	/** Read user defined problematic relations and ways */
	private boolean readProblemIds(String problemFileName) {
		File problemFile = new File(problemFileName);
		boolean ok = true;

		if (!problemFile.exists()) {
			System.out.println("Error: problem file doesn't exist: " + problemFile);  
			return false;
		}
		try {
			InputStream fileStream = new FileInputStream(problemFile);
			LineNumberReader problemReader = new LineNumberReader(
					new InputStreamReader(fileStream));
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
			problemReader.close();
		} catch (IOException exp) {
			System.out.println("Error: Cannot read problem file " + problemFile +  
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
	protected void writeProblemList(String fname, List<Long> pWays, List<Long> pRels) {
		PrintWriter w;
		try {
			w = new PrintWriter(new FileWriter(new File(fileOutputDir, fname)));
		} catch (IOException e) {
			System.err.println("Could not write problem-list file " + fname);
			return;
		}

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
		w.close();
	}
	
	/**
	 * Make sure that our writer areas cover the planet. This is done by adding 
	 * pseudo-writers. 
	 * TODO: It seems to be best to have as few pseudo-areas as 
	 * possible, so this might be optimized   
	 * @param realAreas
	 * @return
	 */
	private List<Area> addPseudoWriters(List<Area> realAreas){
		ArrayList<Area> areas = new ArrayList<Area>(realAreas);
		Rectangle planetBounds = new Rectangle(Utils.toMapUnit(-180.0), Utils.toMapUnit(-90.0), 2* Utils.toMapUnit(180.0), 2 * Utils.toMapUnit(90.0));

		while (!checkIfCovered(planetBounds, areas)){
			boolean changed = addPseudoArea(areas);
			
			if (!changed){
				System.out.println("Failed to fill planet with pseudo-areas");
				System.exit(-1);
			}
		}
		return areas;
	}
	/**
	 * Work around for possible rounding errors in area.subtract processing
	 * @param area an area that is considered to be empty or a rectangle
	 * @return 
	 */
	private java.awt.geom.Area simplifyArea(java.awt.geom.Area area) {
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

	private boolean checkIfCovered(Rectangle bounds, ArrayList<Area> areas){
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
	 * @param realAreas the list of areas 
	 * @param makeDisjoint if true, replace overlapping areas by disjoint ones
	 * @return the new list
	 */
	private static ArrayList<Area> getNonOverlappingAreas(List<Area> realAreas, boolean makeDisjoint){
		
		java.awt.geom.Area covered = new java.awt.geom.Area();
		ArrayList<Area> splitList = new ArrayList<Area>();
		int artificialId = -99999999;
		if (makeDisjoint)
			System.out.println("Removing overlaps from tiles...");
		for (int i = 0; i < realAreas.size(); i++){
			Area area1= realAreas.get(i);
			
			Rectangle r1 = area1.getRect();
			if (covered.intersects(r1) == false){
				splitList.add(area1);
			}
			else {
				if (makeDisjoint == false)
					continue;
				//String msg = "splitting " + area1.getMapId() + " " + (i+1) + "/" + realAreas.size() + " overlapping ";	
				// find intersecting areas in the already covered part
				ArrayList<Area> splitAreas = new ArrayList<Area>();
				
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
					System.out.println("Failed to create list of distinct areas");
					System.exit(-1);
				}
				covered.add(a.getJavaArea());
			}
		}
		return splitList;
	}

	/**
	 * Fill uncovered parts of the planet with pseudo-areas.
	 * TODO: find better algorithm
	 * We want a small number of pseudo areas because many of them will
	 * require more memory or more passes, esp. when processing whole planet.
	 * Also, the total length of all edges should be small.
	 * @param areas list of areas (either real or pseudo)
	 * @return true if pseudo-areas were added
	 */
	private boolean addPseudoArea(ArrayList<Area> areas) {
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

}
