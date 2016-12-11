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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import uk.me.parabola.splitter.args.ParamParser;
import uk.me.parabola.splitter.args.SplitterParams;
import uk.me.parabola.splitter.kml.KmlWriter;
import uk.me.parabola.splitter.solver.AreasCalculator;
import uk.me.parabola.splitter.writer.AbstractOSMWriter;
import uk.me.parabola.splitter.writer.BinaryMapWriter;
import uk.me.parabola.splitter.writer.O5mMapWriter;
import uk.me.parabola.splitter.writer.OSMWriter;
import uk.me.parabola.splitter.writer.OSMXMLWriter;
import uk.me.parabola.splitter.writer.PseudoOSMWriter;

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

	/** A list of the OSM files to parse. */
	private List<String> fileNameList;

	/** The amount in map units that tiles overlap. The default is overwritten depending on user settings. */
	private int overlapAmount = -1;

	/** The number of tiles to be written. The default is overwritten depending on user settings. */
	private int numTiles = -1;

	/** The path where the results are written out to. */
	private File fileOutputDir;

	private final OSMFileHandler osmFileHandler = new OSMFileHandler();
	private final ProblemLists problemList = new ProblemLists();

	private SplitterParams mainOptions;

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
//		uk.me.parabola.splitter.tools.TestCustomCollections.testSparseLong2IntMap();
//		int v1 = Integer.MIN_VALUE + 12;
//		int bias1 = Integer.MIN_VALUE + 16;
//		int d1 = v1 - bias1;
//		int v2 = d1 + bias1;
//		
//		SparseLong2IntMap map = new SparseLong2IntMap("test");
//		for (int i = 0; i < (1 << 29); i++) {
//			map.put(i, 1);
//		}
//		map.stats(1);
//		map.trim();
//		map.stats(1);
//		map = null;
		int rc = 0;
		JVMHealthMonitor healthMonitor = null;

		try {
			mainOptions = readArgs(args);
		} catch (IllegalArgumentException e) {
			if (e.getMessage() != null)
				System.out.println("Error: " + e.getMessage());
			return 1;
		}
		if (mainOptions.getStatusFreq() > 0) {
			healthMonitor = new JVMHealthMonitor(mainOptions.getStatusFreq());
			healthMonitor.start();
		}

		long start = System.currentTimeMillis();
		System.out.println("Time started: " + new Date());
		try {
			// configure the input file handler
			osmFileHandler.setFileNames(fileNameList);
			osmFileHandler.setMixed(mainOptions.isMixed()); 
			osmFileHandler.setMaxThreads(mainOptions.getMaxThreads().getCount());

			if (mainOptions.isKeepComplete() && mainOptions.getProblemFile() != null) {
				// read the user list now so that possible problems are reported early
				if (!problemList.readProblemIds(mainOptions.getProblemFile()))
					throw new IllegalArgumentException();
			}
			
			// first step: either read or calculate the list of areas
			List<Area> areas = split();
			DataStorer dataStorer;
			if (mainOptions.isKeepComplete()) {
				// optional step a: calculate list of ways and relations which are contained in multiple areas 
				dataStorer = calcProblemLists(areas);
				// optional step b: calculate the writers for the list of "problem" ways and relations 
				useProblemLists(dataStorer);
			} else {
				dataStorer = new DataStorer(areas, overlapAmount);
			}
			// final step: write the OSM output files
			writeTiles(dataStorer);
			dataStorer.finish();
		} catch (IOException e) {
			System.err.println("Error opening or reading file " + e);
			e.printStackTrace();
			return 1;
		} catch (SplitFailedException e) {
			if (e.getMessage() != null && e.getMessage().length() > 0)
				e.printStackTrace();
			return 1;
		} catch (StopNoErrorException e) {
			if (e.getMessage() != null) {
				String msg = "Stopped after " + e.getMessage();
				System.err.println(msg);
				System.out.println(msg);
			}
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
	 * Fill the list of areas. The list might be read from an existing file or it might
	 * be freshly calculated by scanning the input files.  
	 * @return List of areas which might overlap each other if they were read from an existing file. 
	 * @throws IOException
	 */
	private List<Area> split() throws IOException {
		final File outputDir = fileOutputDir;
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
		
		final String splitFile = mainOptions.getSplitFile();
		// A polygon file in osmosis polygon format
		final String polygonFile = mainOptions.getPolygonFile();
		final String polygonDescFile = mainOptions.getPolygonDescFile();
		final AreaList areaList = new AreaList(mainOptions.getDescription());
		boolean writeAreas = false;

		if (splitFile != null) {
			try {
				areaList.read(splitFile);
				areaList.dump();
			} catch (IOException e) {
				throw new IllegalArgumentException("Could not read area list file " + splitFile);
			}
			if (polygonFile != null) {
				System.out.println("Warning: parameter polygon-file is ignored because split-file is used.");
			}
			if (polygonDescFile != null) {
				System.out.println("Warning: parameter polygon-desc-file is ignored because split-file is used.");
			}
		} else {
			writeAreas = true;
		}
		areaList.setGeoNamesFile(mainOptions.getGeonamesFile());

		AreasCalculator areasCalculator = new AreasCalculator(mainOptions, numTiles);
		if (areaList.getAreas().isEmpty()) {
			int resolution = mainOptions.getResolution();
			writeAreas = true;
			int alignment = 1 << (24 - resolution);
			System.out.println("Map is being split for resolution " + resolution + ':');
			System.out.println(" - area boundaries are aligned to 0x" + Integer.toHexString(alignment) + " map units ("
					+ Utils.toDegrees(alignment) + " degrees)");
			System.out.println(
					" - areas are multiples of 0x" + Integer.toHexString(alignment) + " map units wide and high");
			areasCalculator.fillDensityMap(osmFileHandler, fileOutputDir);
			areaList.setAreas(areasCalculator.calcAreas());
			if (areaList.getAreas().isEmpty()) {
				System.err.println("Failed to calculate areas. See stdout messages for details.");
				System.out.println("Failed to calculate areas.");
				System.out.println("Sorry. Cannot split the file without creating huge, almost empty, tiles.");
				System.out.println("Please specify a bounding polygon with the --polygon-file parameter.");
				throw new SplitFailedException("");
			}
			int mapId = mainOptions.getMapid();
			if (mapId + areaList.getAreas().size() > 99999999) {
				throw new SplitFailedException("Too many areas for initial mapid " + mapId);
			}
			areaList.setMapIds(mapId);
		}
		areaList.setAreaNames();
		if (writeAreas) {
			areaList.write(new File(fileOutputDir, "areas.list").getPath());
			areaList.writePoly(new File(fileOutputDir, "areas.poly").getPath());
		}

		List<Area> areas = areaList.getAreas();

		String kmlOutputFile = mainOptions.getWriteKml();
		if (kmlOutputFile != null) {
			File out = new File(kmlOutputFile);
			if (!out.isAbsolute())
				out = new File(fileOutputDir, kmlOutputFile);
			KmlWriter.writeKml(out.getPath(), areas);
		}
		String outputType = mainOptions.getOutput();
		
		if (!areasCalculator.getPolygons().isEmpty()) {
			areaList.writeListFiles(outputDir, areasCalculator.getPolygons(), kmlOutputFile, outputType);
		}
		areaList.writeArgsFile(new File(fileOutputDir, "template.args").getPath(), outputType, -1);
		areaList.dumpHex();

		if ("split".equals(mainOptions.getStopAfter())) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
			throw new StopNoErrorException(mainOptions.getStopAfter());
		}

		return areaList.getAreas();
	}

	private DataStorer calcProblemLists(List<Area> areas) {
		DataStorer dataStorer = problemList.calcProblemLists(osmFileHandler, areas, overlapAmount, mainOptions);
		String problemReport = mainOptions.getProblemReport();
		if (problemReport != null) {
			problemList.writeProblemList(fileOutputDir, problemReport);
		}

		if ("gen-problem-list".equals(mainOptions.getStopAfter())) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
			throw new StopNoErrorException(mainOptions.getStopAfter());
		}
		return dataStorer;
	}

	/**
	 * Deal with the command line arguments.
	 */
	private SplitterParams readArgs(String[] args) {
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
		
		boolean filesOK = fileNameList.stream().allMatch(fname -> testAndReportFname(fname, "input file"));
		if (!filesOK) {
			System.out.println("Make sure that option parameters start with -- ");
			throw new IllegalArgumentException();
		}
		int mapId = params.getMapid();
		if (mapId < 0 || mapId > 99999999 ) {
			System.err.println("The --mapid parameter must be a value between 0 and 99999999.");
			throw new IllegalArgumentException();
		} 
		if (params.getMaxNodes() < 10000) {
			System.err.println("Error: Invalid number " + params.getMaxNodes()
					+ ". The --max-nodes parameter must be an integer value of 10000 or higher.");
			throw new IllegalArgumentException();
		}
		String numTilesParm = params.getNumTiles();
		if (numTilesParm != null) {
			try {
				numTiles = Integer.parseInt(numTilesParm);
				if (numTiles < 2) {
					System.err.println("Error: The --num-tiles parameter must be 2 or higher.");
					throw new IllegalArgumentException();
				}
			} catch (NumberFormatException e) {
				System.err.println("Error: Invalid number " + numTilesParm
						+ ". The --num-tiles parameter must be an integer value of 2 or higher.");
				throw new IllegalArgumentException();
			}
		}
		// The description to write into the template.args file.
		String geoNamesFile = params.getGeonamesFile();
		checkOptionalFileOption(geoNamesFile, "geonames-file");
		String outputType = params.getOutput();
		if ("xml pbf o5m simulate".contains(outputType) == false) {
			System.err.println("The --output parameter must be either xml, pbf, o5m, or simulate. Resetting to xml.");
			throw new IllegalArgumentException();
		}

		int resolution = params.getResolution();
		if (resolution < 1 || resolution > 24) {
			System.err.println("The --resolution parameter must be a value between 1 and 24. Reasonable values are close to 13.");
			throw new IllegalArgumentException();
		}

		String outputDir = params.getOutputDir();
		fileOutputDir = new File(outputDir == null ? DEFAULT_DIR : outputDir);

		int maxAreasPerPass = params.getMaxAreas();
		if (maxAreasPerPass < 1 || maxAreasPerPass > 9999) {
			System.err.println("The --max-areas parameter must be a value between 1 and 9999.");
			throw new IllegalArgumentException();
		}
		String problemFile = params.getProblemFile();
		checkOptionalFileOption(params.getProblemFile(), "problem-file");
		checkOptionalFileOption(params.getSplitFile(), "split-file");
		checkOptionalFileOption(params.getPolygonFile(), "polygon-file");
		checkOptionalFileOption(params.getPolygonDescFile(), "polygon-desc-file");
		if (params.getPolygonDescFile() != null && params.getPolygonFile() != null) {
			throw new IllegalArgumentException("--polygon-desc-file and --polygon-file are mutually exclusive");
		}
		String precompSeaDir = params.getPrecompSea();
		if (precompSeaDir != null) {
			File dir = new File(precompSeaDir);
			if (dir.exists() == false || dir.canRead() == false) {
				throw new IllegalArgumentException(
						"precomp-sea directory doesn't exist or is not readable: " + precompSeaDir);
			}
		}

		boolean keepComplete = params.isKeepComplete();
		if (params.isMixed()  && (keepComplete || problemFile != null)) {
			System.err.println(
					"--mixed=true is not supported in combination with --keep-complete=true or --problem-file.");
			System.err.println("Please use e.g. osomosis to sort the data in the input file(s)");
			throw new IllegalArgumentException();
		}

		String overlap = params.getOverlap();
		if ("auto".equals(overlap) == false) {
			try {
				overlapAmount = Integer.valueOf(overlap);
				if (overlapAmount < 0)
					throw new IllegalArgumentException("--overlap=" + overlap + " is not is not a valid option.");
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("--overlap=" + overlap + " is not is not a valid option.");
			}
		}
		String boundaryTagsParm = params.getBoundaryTags();
		// TODO: check ?

		int wantedAdminLevelString = params.getWantedAdminLevel();
		if (wantedAdminLevelString < 0 || wantedAdminLevelString > 12) {
			throw new IllegalArgumentException("The --wanted-admin-level parameter must be between 0 and 12.");
		}
		final List<String> validVersionHandling = Arrays.asList("remove", "fake", "keep");
		if (!validVersionHandling.contains(params.getHandleElementVersion())) {
			throw new IllegalArgumentException(
					"the --handle-element-version parameter must be one of " + validVersionHandling + ".");
		}
		final List<String> validStopAfter = Arrays.asList("split", "gen-problem-list", "handle-problem-list", "dist");
		if (!validStopAfter.contains(params.getStopAfter())) {
			throw new IllegalArgumentException(
					"the --stop-after parameter must be one of " + validStopAfter + ".");
		}
		int searchLimit = params.getSearchLimit();
		if (searchLimit < 1000) {
			throw new IllegalArgumentException("The --search-limit parameter must be 1000 or higher.");
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

			if (mainOptions.getProblemReport() != null) {
				System.out.println(
						"Parameter --problem-report is ignored, because parameter --keep-complete=false is used");
			}
			if (boundaryTagsParm != null) {
				System.out.println(
						"Parameter --boundaryTags is ignored, because parameter --keep-complete=false is used");
			}
		}
		return params;
	}

	private static void checkOptionalFileOption(String fname, String option) {
		if (fname != null) {
			if (testAndReportFname(fname, option) == false) {
				throw new IllegalArgumentException();
			}
		}
		
	}

	private OSMWriter[] createWriters(List<Area> areas) {
		OSMWriter[] allWriters = new OSMWriter[areas.size()];
		for (int j = 0; j < allWriters.length; j++) {
			Area area = areas.get(j);
			AbstractOSMWriter w;
			String outputType = mainOptions.getOutput();
			if ("pbf".equals(outputType))
				w = new BinaryMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount);
			else if ("o5m".equals(outputType))
				w = new O5mMapWriter(area, fileOutputDir, area.getMapId(), overlapAmount);
			else if ("simulate".equals(outputType))
				w = new PseudoOSMWriter(area);
			else
				w = new OSMXMLWriter(area, fileOutputDir, area.getMapId(), overlapAmount);
			switch (mainOptions.getHandleElementVersion()) {
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
		if ("handle-problem-list".equals(mainOptions.getStopAfter())) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
			throw new StopNoErrorException(mainOptions.getStopAfter());
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

		int numPasses = (int) Math.ceil((double) areas.size() / mainOptions.getMaxAreas());
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
			SplitProcessor processor = new SplitProcessor(dataStorer, areaOffset, numAreasThisPass, mainOptions);

			System.out.println("Starting distribution pass " + (i + 1) + " of " + numPasses + ", processing "
					+ numAreasThisPass + " areas (" + areas.get(i * areasPerPass).getMapId() + " to "
					+ areas.get(i * areasPerPass + numAreasThisPass - 1).getMapId() + ')');
			osmFileHandler.execute(processor);
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
