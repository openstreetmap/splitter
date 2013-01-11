package uk.me.parabola.splitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class PrecompSeaReader {
	/** The size (lat and long) of the precompiled sea tiles */
	private final static int PRECOMP_RASTER = 1 << 15;

	private final Area bounds;
	private final File precompSeaDir;
	private static ThreadLocal<Map<String,String>> precompIndex = new ThreadLocal<Map<String,String>>();

	public PrecompSeaReader(Area bounds, File precompSeaDir) {
		this.bounds = bounds;
		this.precompSeaDir = precompSeaDir;
		init();
	}
	
	/**
	 * Sort out options from the command line.
	 * Returns true only if the option to generate the sea is active, so that
	 * the whole thing is omitted if not used.
	 */
	public boolean init() {
		if (precompSeaDir.exists()) {
			if (precompIndex.get() == null) {
				File indexFile = new File(precompSeaDir, "index.txt.gz");
				if (indexFile.exists() == false) {
					// check if the unzipped index file exists
					indexFile = new File(precompSeaDir, "index.txt");
				}

				if (indexFile.exists()) {
					try {
						InputStream fileStream = new FileInputStream(indexFile);
						if (indexFile.getName().endsWith(".gz")) {
							fileStream = new GZIPInputStream(fileStream);
						}
						LineNumberReader indexReader = new LineNumberReader(
								new InputStreamReader(fileStream));
						Pattern csvSplitter = Pattern.compile(Pattern
								.quote(";"));
						String indexLine = null;
						Map<String, String> indexItems = new HashMap<String, String>();
						while ((indexLine = indexReader.readLine()) != null) {
							String[] items = csvSplitter.split(indexLine);
							if (items.length != 2) {
								System.out.println("Invalid format in index file: "
										+ indexLine);
								continue;
							}
							if (items[0].startsWith("#")) {
								// comment
								continue;
							}
							String val = items[1].trim();
							if ("sea".equals(val) || "land".equals(val)){
								// ignore
							} else {
								indexItems.put(items[0].trim(),val);
							}
						}
						indexReader.close();
						precompIndex.set(indexItems);
					} catch (IOException exp) {
						System.out.println("Cannot read index file " + indexFile + " " + 
								exp);
						precompIndex.set(null);
					}
				} else {
					System.out.println("Disable precompiled sea due to missing index.txt file in precompiled sea directory "
							+ precompSeaDir);
					System.err.println("Disable precompiled sea due to missing index.txt file in precompiled sea directory "
							+ precompSeaDir);
					precompIndex.set(null);
					
				}
			}
		}
		return precompIndex.get() != null;
	}
	
	/**
	 * Calculates the key names of the precompiled sea tiles for the bounding box.
	 * The key names are compiled of {@code lat+"_"+lon}.
	 * @return the key names for the bounding box
	 */
	public List<String> getPrecompFileNames() {
		List<String> precompFiles = new ArrayList<String>();
		Map<String,String> index = precompIndex.get();
		for (int lat = getPrecompTileStart(bounds.getMinLat()); lat < getPrecompTileEnd(bounds
				.getMaxLat()); lat += PRECOMP_RASTER) {
			for (int lon = getPrecompTileStart(bounds.getMinLong()); lon < getPrecompTileEnd(bounds
					.getMaxLong()); lon += PRECOMP_RASTER) {
				String precompKey = lat+"_"+lon;
				String tileName = index.get(precompKey);
				
				if (tileName == null) {
					// a tile with only sea or land or tile doesn't exist
					continue;
				}
				
				if ("sea".equals(tileName) || "land".equals(tileName)) {
					// should not happen
				} else {
					String precompTile = new File(precompSeaDir,tileName).getAbsolutePath();
					precompFiles.add(precompTile);
				}
				
			}
		}
		return precompFiles;
	}
	private static int getPrecompTileStart(int value) {
		int rem = value % PRECOMP_RASTER;
		if (rem == 0) {
			return value;
		} else if (value >= 0) {
			return value - rem;
		} else {
			return value - PRECOMP_RASTER - rem;
		}
	}

	private static int getPrecompTileEnd(int value) {
		int rem = value % PRECOMP_RASTER;
		if (rem == 0) {
			return value;
		} else if (value >= 0) {
			return value + PRECOMP_RASTER - rem;
		} else {
			return value - rem;
		}
	}
	
	

}
