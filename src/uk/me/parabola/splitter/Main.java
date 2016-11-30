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

import org.xmlpull.v1.XmlPullParserException;

import uk.me.parabola.splitter.args.ParamParser;
import uk.me.parabola.splitter.args.SplitterParams;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Splitter for OSM files with the purpose of providing input files for mkgmap.
 * <p/>
 * The input file is split so that no piece has more than a given number of
 * nodes in it.
 *
 * @author Steve Ratcliffe
 */
public class Main {

	private static final String DEFAULT_DIR = ".";

	// We store area IDs and all used combinations of area IDs in a dictionary.
	// The index to this
	// dictionary is saved in short values. If Short.MaxValue() is reached, the
	// user might limit
	// the number of areas that is processed in one pass.
	private int maxAreasPerPass;

	// A list of the OSM files to parse.
	private List<String> fileNameList;

	// The description to write into the template.args file.
	private String description;

	// The starting map ID.
	private int mapId;

	// The amount in map units that tiles overlap. The default is overwritten
	// depending on user settings.
	private int overlapAmount = -1;

	// A threshold value that is used when no split-file is given. Splitting is
	// done so that
	// no tile has more than maxNodes nodes inside the bounding box of the tile.
	// Nodes added by overlap or keep-complete are not taken into account.
	private long maxNodes;

	private int numTiles = -1;

	// This is a value in the range 0-24.
	// Higher numbers mean higher detail. The resolution determines how the
	// tiles must
	// be aligned. Eg a resolution of 13 means the tiles need to have their
	// edges aligned to
	// multiples of 2 ^ (24 - 13) = 2048 map units.
	private int resolution;

	// Whether or not to trim tiles of any empty space around their edges.
	private boolean trim;
	// Set if there is a previous area file given on the command line.
	private AreaList areaList;
	// Whether or not the source OSM file(s) contain strictly nodes first, then
	// ways, then rels,
	// or they're all mixed up. Running with mixed enabled takes longer.
	private boolean mixed;
	// A polygon file in osmosis polygon format
	private String polygonFile;

	// The path where the results are written out to.
	private File fileOutputDir;
	// A GeoNames file to use for naming the tiles.
	private String geoNamesFile;
	// How often (in seconds) to provide JVM status information. Zero = no
	// information.
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

	// option for fine tuning the keep-complete processing
	private int wantedAdminLevel;

	private String[] boundaryTags;

	private String stopAfter;

	private String precompSeaDir;

	private String polygonDescFile;

	private int searchLimit;

	private String handleElementVersion;

	private boolean ignoreBoundsTags;

	private final OSMFileHandler osmFileHandler = new OSMFileHandler();
	private final AreasCalculator areasCalculator = new AreasCalculator();
	private final ProblemLists problemList = new ProblemLists();

	public static void main(String[] args) {
		Main m = new Main();
		try {
			int rc = m.start(args);
			if (rc != 0)
				System.exit(1);
		} catch (StopNoErrorException e) {
			if (e.getMessage() != null)
				System.out.println(e.getMessage());
		}
	}

	private int start(String[] args) {
		int rc = 0;
		JVMHealthMonitor healthMonitor = null;

		try {
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
			List<Area> areas = split();
			DataStorer dataStorer;
			if (keepComplete) {
				dataStorer = calcProblemLists(areas);
				useProblemLists(dataStorer);
			} else {
				dataStorer = new DataStorer(areas, overlapAmount);
			}
			writeTiles(dataStorer);
			dataStorer.finish();
		} catch (IOException e) {
			System.err.println("Error opening or reading file " + e);
			e.printStackTrace();
			return 1;
		} catch (XmlPullParserException e) {
			System.err.println("Error parsing xml from file " + e);
			e.printStackTrace();
			return 1;
		} catch (SplitFailedException e) {
			if (e.getMessage() != null && e.getMessage().length() > 0)
				e.printStackTrace();
			return 1;
		} catch (StopNoErrorException e) {
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
		 * String version = System.getProperty("java.version"); if (version !=
		 * null) { String[] versionParts =version.split(Pattern.quote(".")); if
		 * (versionParts.length >= 2) { int major =
		 * Integer.valueOf(versionParts[1]); if (major < 7) {
		 * System.out.println(
		 * "===========================================================");
		 * System.out.println("You are using an old Java runtime environment "+
		 * version); System.out.println("It is no longer supported.");
		 * System.out.println("Please update Java to the latest release.");
		 * System.out.println(
		 * "===========================================================");
		 * System.exit(1); } } }
		 */
	}

	private List<Area> split() throws IOException, XmlPullParserException {

		File outputDir = fileOutputDir;
		if (!outputDir.exists()) {
			System.out.println("Output directory not found. Creating directory '" + fileOutputDir + "'");
			if (!outputDir.mkdirs()) {
				System.err.println("Unable to create output directory! Using default directory instead");
				fileOutputDir = new File(DEFAULT_DIR);
			}
		} else if (!outputDir.isDirectory()) {
			System.err.println(
					"The --output-dir parameter must specify a directory. The --output-dir parameter is being ignored, writing to default directory instead.");
			fileOutputDir = new File(DEFAULT_DIR);
		}

		if (fileNameList.isEmpty()) {
			throw new IllegalArgumentException("No input files were supplied");
		}

		boolean writeAreas = false;
		if (areaList.getAreas().isEmpty()) {
			writeAreas = true;
			int alignment = 1 << (24 - resolution);
			System.out.println("Map is being split for resolution " + resolution + ':');
			System.out.println(" - area boundaries are aligned to 0x" + Integer.toHexString(alignment) + " map units ("
					+ Utils.toDegrees(alignment) + " degrees)");
			System.out.println(
					" - areas are multiples of 0x" + Integer.toHexString(alignment) + " map units wide and high");
			areaList.setAreas(calculateAreas());
			if (areaList == null || areaList.getAreas().isEmpty()) {
				System.err.println("Failed to calculate areas. See stdout messages for details.");
				System.out.println("Failed to calculate areas.");
				System.out.println("Sorry. Cannot split the file without creating huge, almost empty, tiles.");
				System.out.println("Please specify a bounding polygon with the --polygon-file parameter.");
				throw new SplitFailedException("");
			}
			if (mapId + areaList.getAreas().size() > 99999999) {
				System.err.println("Too many areas for initial mapid " + mapId + ", resetting to 63240001");
				mapId = 63240001;
			}
			areaList.setMapIds(mapId);
		}
		areaList.setAreaNames();
		if (writeAreas) {
			areaList.write(new File(fileOutputDir, "areas.list").getPath());
			areaList.writePoly(new File(fileOutputDir, "areas.poly").getPath());
		}

		List<Area> areas = areaList.getAreas();

		if (kmlOutputFile != null) {
			File out = new File(kmlOutputFile);
			if (!out.isAbsolute())
				out = new File(fileOutputDir, kmlOutputFile);
			KmlWriter.writeKml(out.getPath(), areas);
		}
		areasCalculator.writeListFiles(outputDir, areas, kmlOutputFile, outputType);
		areaList.writeArgsFile(new File(fileOutputDir, "template.args").getPath(), outputType, -1);

		if ("split".equals(stopAfter)) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
			System.err.println("stopped after " + stopAfter);
			throw new StopNoErrorException("stopped after " + stopAfter);
		}

		System.out.println(areas.size() + " areas:");
		for (Area area : areas) {
			System.out.format("Area %08d: %d,%d to %d,%d covers %s", area.getMapId(), area.getMinLat(),
					area.getMinLong(), area.getMaxLat(), area.getMaxLong(), area.toHexString());

			if (area.getName() != null)
				System.out.print(' ' + area.getName());
			System.out.println();
		}
		return areas;
	}

	private DataStorer calcProblemLists(List<Area> areas) {
		DataStorer dataStorer = problemList.calcProblemLists(osmFileHandler, areas, wantedAdminLevel, boundaryTags,
				maxAreasPerPass, overlapAmount);
		if (problemReport != null) {
			problemList.writeProblemList(fileOutputDir, problemReport);
		}

		if ("gen-problem-list".equals(stopAfter)) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
			System.err.println("stopped after " + stopAfter);
			throw new StopNoErrorException("stopped after " + stopAfter);
		}
		return dataStorer;
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
		if (fileNameList.isEmpty()) {
			throw new IllegalArgumentException("No file name(s) given");
		}
		boolean filesOK = true;
		for (String fileName : fileNameList) {
			if (testAndReportFname(fileName, "input file") == false) {
				filesOK = false;
			}
		}
		if (!filesOK) {
			System.out.println("Make sure that option parameters start with -- ");
			throw new IllegalArgumentException();
		}
		osmFileHandler.setFileNames(fileNameList);
		mapId = params.getMapid();
		if (mapId > 99999999) {
			mapId = 63240001;
			System.err.println("The --mapid parameter must have less than 9 digits. Resetting to " + mapId + ".");
		}
		maxNodes = params.getMaxNodes();
		if (maxNodes < 10000) {
			System.err.println("Error: Invalid number " + params.getMaxNodes()
					+ ". The --max-nodes parameter must be an integer value of 10000 or higher.");
			throw new IllegalArgumentException();
		}
		String numTilesParm = params.getNumTiles();
		if (numTilesParm != null) {
			try {
				numTiles = Integer.parseInt(numTilesParm);
				if (numTiles >= 0 && numTiles < 2) {
					System.err.println("Error: The --num-tiles parameter must be 2 or higher. Resetting to 2.");
					numTiles = 2;
				}
			} catch (NumberFormatException e) {
				System.err.println("Error: Invalid number " + numTilesParm
						+ ". The --num-tiles parameter must be an integer value of 2 or higher.");
				throw new IllegalArgumentException();
			}
		}
		description = params.getDescription();
		areaList = new AreaList(description);
		geoNamesFile = params.getGeonamesFile();
		if (geoNamesFile != null) {
			if (testAndReportFname(geoNamesFile, "geonames-file") == false) {
				throw new IllegalArgumentException();
			}
			areaList.setGeoNamesFile(geoNamesFile);
		}
		resolution = params.getResolution();
		trim = !params.isNoTrim();
		outputType = params.getOutput();
		if ("xml pbf o5m simulate".contains(outputType) == false) {
			System.err.println("The --output parameter must be either xml, pbf, o5m, or simulate. Resetting to xml.");
			outputType = "xml";
		}

		if (resolution < 1 || resolution > 24) {
			System.err.println("The --resolution parameter must be a value between 1 and 24. Resetting to 13.");
			resolution = 13;
		}
		mixed = params.isMixed();
		osmFileHandler.setMixed(mixed);
		statusFreq = params.getStatusFreq();

		String outputDir = params.getOutputDir();
		fileOutputDir = new File(outputDir == null ? DEFAULT_DIR : outputDir);

		maxAreasPerPass = params.getMaxAreas();
		if (maxAreasPerPass < 1 || maxAreasPerPass > 4096) {
			System.err.println("The --max-areas parameter must be a value between 1 and 4096. Resetting to 4096.");
			maxAreasPerPass = 4096;
		}
		kmlOutputFile = params.getWriteKml();

		maxThreads = params.getMaxThreads().getCount();

		problemFile = params.getProblemFile();
		if (problemFile != null) {
			if (!problemList.readProblemIds(problemFile))
				throw new IllegalArgumentException();
		}
		String splitFile = params.getSplitFile();
		if (splitFile != null) {
			if (testAndReportFname(splitFile, "split-file") == false) {
				throw new IllegalArgumentException();
			}
		}

		keepComplete = params.isKeepComplete();
		if (mixed && (keepComplete || problemFile != null)) {
			System.err.println(
					"--mixed=true is not supported in combination with --keep-complete=true or --problem-file.");
			System.err.println("Please use e.g. osomosis to sort the data in the input file(s)");
			throw new IllegalArgumentException();
		}

		String overlap = params.getOverlap();
		if ("auto".equals(overlap) == false) {
			try {
				overlapAmount = Integer.valueOf(overlap);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("--overlap=" + overlap + " is not is not a valid option.");
			}
		}
		problemReport = params.getProblemReport();
		String boundaryTagsParm = params.getBoundaryTags();
		if ("use-exclude-list".equals(boundaryTagsParm) == false) {
			boundaryTags = boundaryTagsParm.split(Pattern.quote(","));
		}

		if (keepComplete) {
			String wantedAdminLevelString = params.getWantedAdminLevel();
			try {
				wantedAdminLevel = Integer.valueOf(wantedAdminLevelString);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(
						"--admin-level=" + wantedAdminLevelString + " is not is not a valid option.");
			}
		}

		// plausibility checks and default handling
		if (keepComplete) {
			if (fileNameList.size() > 1) {
				System.err.println("Warning: --keep-complete is only used for the first input file.");
			}
			if (overlapAmount > 0) {
				System.err.println("Warning: --overlap is used in combination with --keep-complete=true ");
				System.err.println(
						"         The option keep-complete should be used with overlap=0 because it is very unlikely that ");
				System.err.println(
						"         the overlap will add any important data. It will just cause a lot of additional output which ");
				System.err.println("         has to be thrown away again in mkgmap.");
			} else
				overlapAmount = 0;
		} else {
			if (overlapAmount < 0) {
				overlapAmount = 2000;
				System.out.println("Setting default overlap=2000 because keep-complete=false is in use.");
			}

			if (problemReport != null) {
				System.out.println(
						"Parameter --problem-report is ignored, because parameter --keep-complete=false is used");
			}
			if (boundaryTagsParm != null) {
				System.out.println(
						"Parameter --boundaryTags is ignored, because parameter --keep-complete=false is used");
			}
		}
		if (splitFile != null) {
			try {
				areaList = new AreaList(description);
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
			if (splitFile != null) {
				System.out.println("Warning: parameter polygon-file is ignored because split-file is used.");
			} else {
				areasCalculator.readPolygonFile(polygonFile, mapId);
			}
		}
		polygonDescFile = params.getPolygonDescFile();
		if (polygonDescFile != null) {
			if (splitFile != null) {
				System.out.println("Warning: parameter polygon-desc-file is ignored because split-file is used.");
			} else {
				areasCalculator.readPolygonDescFile(polygonDescFile);
			}
		}
		areasCalculator.setResolution(resolution);
		if (!areasCalculator.checkPolygons()) {
			System.out.println(
					"Warning: Bounding polygon is complex. Splitter might not be able to fit all tiles into the polygon!");
		}
		stopAfter = params.getStopAfter();
		if (Arrays.asList("split", "gen-problem-list", "handle-problem-list", "dist").contains(stopAfter) == false) {
			throw new IllegalArgumentException(
					"the --stop-after parameter must be either split, gen-problem-list, handle-problem-list, or dist.");
		}

		precompSeaDir = params.getPrecompSea();
		if (precompSeaDir != null) {
			File dir = new File(precompSeaDir);
			if (dir.exists() == false || dir.canRead() == false) {
				throw new IllegalArgumentException(
						"precomp-sea directory doesn't exist or is not readable: " + precompSeaDir);
			}
		}
		int numPolygons = areasCalculator.getPolygons().size();
		if (numPolygons > 0 && numTiles > 0) {
			if (numPolygons == 1) {
				System.out.println(
						"Warning: Parameter polygon-file is only used to calculate the bounds because --num-tiles is used");
			} else {
				System.out.println("Warning: parameter polygon-file is ignored because --num-tiles is used");
			}
		}
		searchLimit = params.getSearchLimit();
		if (searchLimit < 1000) {
			searchLimit = 1000;
			System.err.println("The --search-limit parameter must be 1000 or higher. Resetting to 1000.");
		}
		handleElementVersion = params.getHandleElementVersion();
		if (Arrays.asList("remove", "fake", "keep").contains(handleElementVersion) == false) {
			throw new IllegalArgumentException(
					"the --handle-element-version parameter must be either remove, fake, or keep.");
		}
		ignoreBoundsTags = params.getIgnoreOsmBounds();
	}

	/**
	 * Calculate the areas that we are going to split into by getting the total
	 * area and then subdividing down until each area has at most max-nodes
	 * nodes in it.
	 */
	private List<Area> calculateAreas() throws XmlPullParserException {

		DensityMapCollector pass1Collector = new DensityMapCollector(resolution, ignoreBoundsTags);
		MapProcessor processor = pass1Collector;

		File densityData = new File("densities.txt");
		File densityOutData = null;
		if (densityData.exists() && densityData.isFile()) {
			System.err.println("reading density data from " + densityData.getAbsolutePath());
			pass1Collector.readMap(densityData.getAbsolutePath());
		} else {
			densityOutData = new File(fileOutputDir, "densities-out.txt");
			ProducerConsumer producerConsumer = new ProducerConsumer(osmFileHandler, processor);
			producerConsumer.execute();
		}
		System.out.println("in " + fileNameList.size() + (fileNameList.size() == 1 ? " file" : " files"));
		System.out.println("Time: " + new Date());
		if (densityOutData != null)
			pass1Collector.saveMap(densityOutData.getAbsolutePath());

		Area exactArea = pass1Collector.getExactArea();
		System.out.println("Exact map coverage read from input file(s) is " + exactArea);
		if (areasCalculator.getPolygons().size() == 1) {
			Rectangle polgonsBoundingBox = areasCalculator.getPolygons().get(0).area.getBounds();
			exactArea = Area.calcArea(exactArea, polgonsBoundingBox);
			if (exactArea != null)
				System.out.println("Exact map coverage after applying bounding box of polygon-file is " + exactArea);
			else {
				System.out.println("Exact map coverage after applying bounding box of polygon-file is an empty area");
				return Collections.emptyList();
			}
		}

		if (precompSeaDir != null) {
			System.out.println("Counting nodes of precompiled sea data ...");
			long startSea = System.currentTimeMillis();
			DensityMapCollector seaCollector = new DensityMapCollector(resolution, true);
			PrecompSeaReader precompSeaReader = new PrecompSeaReader(exactArea, new File(precompSeaDir));
			precompSeaReader.processMap(seaCollector);
			pass1Collector.mergeSeaData(seaCollector, trim, resolution);
			System.out.println("Precompiled sea data pass took " + (System.currentTimeMillis() - startSea) + " ms");
		}
		Area roundedBounds = RoundingUtils.round(exactArea, resolution);
		SplittableDensityArea splittableArea = pass1Collector.getSplitArea(searchLimit, roundedBounds);
		if (splittableArea.hasData() == false) {
			System.out.println("input file(s) have no data inside calculated bounding box");
			return Collections.emptyList();
		}
		System.out.println("Rounded map coverage is " + splittableArea.getBounds());

		splittableArea.setTrim(trim);
		splittableArea.setMapId(mapId);
		long startSplit = System.currentTimeMillis();
		List<Area> areas;
		if (numTiles >= 2) {
			System.out.println("Splitting nodes into " + numTiles + " areas");
			areas = splittableArea.split(numTiles);
		} else {
			System.out.println(
					"Splitting nodes into areas containing a maximum of " + Utils.format(maxNodes) + " nodes each...");
			splittableArea.setMaxNodes(maxNodes);
			areas = splittableArea.split(areasCalculator.getPolygons());
		}
		if (areas != null && areas.isEmpty() == false)
			System.out.println("Creating the initial areas took " + (System.currentTimeMillis() - startSplit) + " ms");
		return areas;
	}

	private OSMWriter[] createWriters(List<Area> areas) {
		OSMWriter[] allWriters = new OSMWriter[areas.size()];
		for (int j = 0; j < allWriters.length; j++) {
			Area area = areas.get(j);
			AbstractOSMWriter w;
			if ("pbf".equals(outputType))
				w = new BinaryMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount);
			else if ("o5m".equals(outputType))
				w = new O5mMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount);
			else if ("simulate".equals(outputType))
				w = new PseudoOSMWriter(area);
			else
				w = new OSMXMLWriter(area, fileOutputDir, area.getMapId(), overlapAmount);
			switch (handleElementVersion) {
			case "keep":
				w.setVersionMethod(AbstractOSMWriter.KEEP_VERSION);
				break;
			case "remove":
				w.setVersionMethod(AbstractOSMWriter.REMOVE_VERSION);
				break;
			default:
				w.setVersionMethod(AbstractOSMWriter.FAKE_VERSION);
			}
			allWriters[j] = w;
		}
		return allWriters;
	}

	private void useProblemLists(DataStorer dataStorer) {
		problemList.calcMultiTileElements(dataStorer, osmFileHandler);
		if ("handle-problem-list".equals(stopAfter)) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
			System.err.println("stopped after " + stopAfter);
			throw new StopNoErrorException("stopped after " + stopAfter);
		}
	}

	/**
	 * Final pass(es), we have the areas so parse the file(s) again.
	 * 
	 * @param dataStorer
	 *            collects data used in different program passes
	 */
	private void writeTiles(DataStorer dataStorer) throws IOException {
		List<Area> areas = dataStorer.getAreaDictionary().getAreas();
		// the final split passes,
		dataStorer.switchToSeqAccess(fileOutputDir);
		dataStorer.setWriters(createWriters(areas));

		System.out.println("Distributing data " + new Date());

		int numPasses = (int) Math.ceil((double) areas.size() / maxAreasPerPass);
		int areasPerPass = (int) Math.ceil((double) areas.size() / numPasses);

		long startDistPass = System.currentTimeMillis();
		if (numPasses > 1) {
			System.out.println("Processing " + areas.size() + " areas in " + numPasses + " passes, " + areasPerPass
					+ " areas at a time");
		} else {
			System.out.println("Processing " + areas.size() + " areas in a single pass");
		}
		for (int i = 0; i < numPasses; i++) {
			int areaOffset = i * areasPerPass;
			int numAreasThisPass = Math.min(areasPerPass, areas.size() - i * areasPerPass);
			dataStorer.restartWriterMaps();
			SplitProcessor processor = new SplitProcessor(dataStorer, areaOffset, numAreasThisPass, maxThreads);

			System.out.println("Starting distribution pass " + (i + 1) + " of " + numPasses + ", processing "
					+ numAreasThisPass + " areas (" + areas.get(i * areasPerPass).getMapId() + " to "
					+ areas.get(i * areasPerPass + numAreasThisPass - 1).getMapId() + ')');

			osmFileHandler.process(processor);
		}
		System.out.println("Distribution pass(es) took " + (System.currentTimeMillis() - startDistPass) + " ms");
	}

	static boolean testAndReportFname(String fileName, String type) {
		File f = new File(fileName);
		if (f.exists() == false || f.isFile() == false || f.canRead() == false) {
			String msg = "Error: " + type + " doesn't exist or is not a readable file: " + fileName;
			System.out.println(msg);
			System.err.println(msg);
			return false;
		}
		return true;
	}

}
