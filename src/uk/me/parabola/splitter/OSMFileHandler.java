package uk.me.parabola.splitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;

import org.xmlpull.v1.XmlPullParserException;

import crosby.binary.file.BlockInputStream;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;

/**
 * A class which stores parameters needed to process input (OSM) files
 * 
 * @author Gerd Petermann
 *
 */
public class OSMFileHandler {
	/** list of OSM input files to process */
	private List<String> filenames;
	// for faster access on blocks in pbf files
	private final HashMap<String, ShortArrayList> blockTypeMap = new HashMap<>();
	// for faster access on blocks in o5m files
	private final HashMap<String, long[]> skipArrayMap = new HashMap<>();

	// Whether or not the source OSM file(s) contain strictly nodes first, then
	// ways, then rels,
	// or they're all mixed up. Running with mixed enabled takes longer.
	private boolean mixed;

	private int maxThreads = 1;

	public void setFileNames(List<String> filenames) {
		this.filenames = filenames;
	}

	public void setMixed(boolean f) {
		mixed = f;
	}

	public void setMaxThreads(int maxThreads) {
		this.maxThreads = maxThreads;
	}

	public boolean process(MapProcessor processor) {
		// Create both an XML reader and a binary reader, Dispatch each input to
		// the
		// Appropriate parser.

		for (int i = 0; i < filenames.size(); i++) {
			String filename = filenames.get(i);
			System.out.println("Processing " + filename);
			if (i == 1 && processor instanceof DensityMapCollector) {
				((DensityMapCollector) processor).checkBounds();
			}

			try {
				if (filename.endsWith(".o5m")) {
					File file = new File(filename);
					try (InputStream stream = new FileInputStream(file)) {
						long[] skipArray = skipArrayMap.get(filename);
						O5mMapParser o5mParser = new O5mMapParser(processor, stream, skipArray);
						o5mParser.parse();
						if (skipArray == null) {
							skipArray = o5mParser.getSkipArray();
							skipArrayMap.put(filename, skipArray);
						}
					}
				} else if (filename.endsWith(".pbf")) {
					// Is it a binary file?
					File file = new File(filename);
					ShortArrayList blockTypes = blockTypeMap.get(filename);
					BinaryMapParser binParser = new BinaryMapParser(processor, blockTypes, 1);
					try (InputStream stream = new FileInputStream(file)) {
						BlockInputStream blockinput = (new BlockInputStream(stream, binParser));
						blockinput.process();
						if (blockTypes == null) {
							// remember this file
							blockTypes = binParser.getBlockList();
							blockTypeMap.put(filename, blockTypes);
						}
					}
				} else {
					// No, try XML.
					try (Reader reader = Utils.openFile(filename, maxThreads > 1)) {
						OSMXMLParser parser = new OSMXMLParser(processor, mixed);
						parser.setReader(reader);
						parser.parse();
					}
				}
			} catch (FileNotFoundException e) {
				System.out.println(e);
				throw new SplitFailedException("ERROR: file " + filename + " was not found");
			} catch (XmlPullParserException e) {
				e.printStackTrace();
				throw new SplitFailedException("ERROR: file " + filename + " is not a valid OSM XML file");
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
				throw new SplitFailedException("ERROR: file " + filename + " contains unexpected data");
			} catch (IOException e) {
				e.printStackTrace();
				throw new SplitFailedException("ERROR: file " + filename + " caused I/O exception");
			}
		}
		boolean done = processor.endMap();
		return done;
	}

}
