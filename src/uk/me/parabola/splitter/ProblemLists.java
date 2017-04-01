/*
 * Copyright (c) 2016, Gerd Petermann
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

import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import uk.me.parabola.splitter.args.SplitterParams;

public class ProblemLists {
	private final LongArrayList problemWays = new LongArrayList();
	private final LongArrayList problemRels = new LongArrayList();
	private final TreeSet<Long> calculatedProblemWays = new TreeSet<>();
	private final TreeSet<Long> calculatedProblemRels = new TreeSet<>();

	
	/**
	 * Calculate lists of ways and relations that appear in multiple areas for a
	 * given list of areas.
	 * @param osmFileHandler
	 * @param realAreas list of areas, possibly overlapping if read from split-file
	 * @param overlapAmount 
	 * @param mainOptions main options
	 * @return
	 */
	public DataStorer calcProblemLists(OSMFileHandler osmFileHandler, List<Area> realAreas, int overlapAmount, SplitterParams mainOptions) {
		long startProblemListGenerator = System.currentTimeMillis();
		ArrayList<Area> distinctAreas = getNonOverlappingAreas(realAreas);
		if (distinctAreas.size() > realAreas.size()) {
			System.err.println("Waring: The areas given in --split-file are overlapping.");
			Set<Integer> overlappingTiles = new TreeSet<>();
			for (int i = 0; i < realAreas.size(); i++) {
				Area a1 = realAreas.get(i);
				for (int j = i + 1; j < realAreas.size(); j++) {
					Area a2 = realAreas.get(j);
					if (a1.overlaps(a2)) {
						overlappingTiles.add(a1.getMapId());
						overlappingTiles.add(a2.getMapId());
						System.out.format("overlapping areas %08d and %08d : (%d,%d to %d,%d) and (%d,%d to %d,%d)\n",
								a1.getMapId(), a2.getMapId(), 
								a1.getMinLat(), a1.getMinLong(), a1.getMaxLat(), a1.getMaxLong(),
								a2.getMinLat(), a2.getMinLong(), a2.getMaxLat(), a2.getMaxLong());
						a1.overlaps(a2);
					}
				}
			}
			if (!overlappingTiles.isEmpty()) {
				System.out.println("Overlaping tiles: " + overlappingTiles.toString());
			}
		}
		System.out.println("Generating problem list for " + distinctAreas.size() + " distinct areas");
		List<Area> workAreas = addPseudoAreas(distinctAreas);

		int numPasses = (int) Math.ceil((double) workAreas.size() / mainOptions.getMaxAreas());
		int areasPerPass = (int) Math.ceil((double) workAreas.size() / numPasses);
		if (numPasses > 1) {
			System.out.println("Processing " + distinctAreas.size() + " areas in " + numPasses + " passes, "
					+ areasPerPass + " areas at a time");
		} else {
			System.out.println("Processing " + distinctAreas.size() + " areas in a single pass");
		}

		ArrayList<Area> allAreas = new ArrayList<>();

		System.out.println("Pseudo areas:");
		for (int j = 0; j < workAreas.size(); j++) {
			Area area = workAreas.get(j);
			allAreas.add(area);
			if (area.isPseudoArea())
				System.out.println("Pseudo area " + area.getMapId() + " covers " + area);
		}

		DataStorer distinctDataStorer = new DataStorer(workAreas, overlapAmount);
		System.out.println("Starting problem-list-generator pass(es)");
		
		for (int pass = 0; pass < numPasses; pass++) {
			System.out.println("-----------------------------------");
			System.out.println("Starting problem-list-generator pass " + (pass + 1) + " of " + numPasses);
			long startThisPass = System.currentTimeMillis();
			int areaOffset = pass * areasPerPass;
			int numAreasThisPass = Math.min(areasPerPass, workAreas.size() - pass * areasPerPass);
			ProblemListProcessor processor = new ProblemListProcessor(distinctDataStorer, areaOffset, numAreasThisPass,
					mainOptions);

			boolean done = false;
			while (!done) {
				done = osmFileHandler.execute(processor);

				calculatedProblemWays.addAll(processor.getProblemWays());
				calculatedProblemRels.addAll(processor.getProblemRels());
			}
			System.out.println("Problem-list-generator pass " + (pass + 1) + " took "
					+ (System.currentTimeMillis() - startThisPass) + " ms");
		}
		System.out.println("Problem-list-generator pass(es) took "
				+ (System.currentTimeMillis() - startProblemListGenerator) + " ms");
		DataStorer dataStorer = new DataStorer(realAreas, overlapAmount);
		dataStorer.translateDistinctToRealAreas(distinctDataStorer);
		return dataStorer;
	}

	/** Read user defined problematic relations and ways */
	public boolean readProblemIds(String problemFileName) {
		File fProblem = new File(problemFileName);
		boolean ok = true;

		if (!fProblem.exists()) {
			System.out.println("Error: problem file doesn't exist: " + fProblem);
			return false;
		}
		try (InputStream fileStream = new FileInputStream(fProblem);
				LineNumberReader problemReader = new LineNumberReader(new InputStreamReader(fileStream));) {
			Pattern csvSplitter = Pattern.compile(Pattern.quote(":"));
			Pattern commentSplitter = Pattern.compile(Pattern.quote("#"));
			String problemLine;
			String[] items;
			while ((problemLine = problemReader.readLine()) != null) {
				items = commentSplitter.split(problemLine);
				if (items.length == 0 || items[0].trim().isEmpty()) {
					// comment or empty line
					continue;
				}
				items = csvSplitter.split(items[0].trim());
				if (items.length != 2) {
					System.out.println("Error: Invalid format in problem file, line number "
							+ problemReader.getLineNumber() + ": " + problemLine);
					ok = false;
					continue;
				}
				long id = 0;
				try {
					id = Long.parseLong(items[1]);
				} catch (NumberFormatException exp) {
					System.out.println("Error: Invalid number format in problem file, line number "
							+ +problemReader.getLineNumber() + ": " + problemLine + exp);
					ok = false;
				}
				if ("way".equals(items[0]))
					problemWays.add(id);
				else if ("rel".equals(items[0]))
					problemRels.add(id);
				else {
					System.out.println("Error in problem file: Type not way or relation, line number "
							+ +problemReader.getLineNumber() + ": " + problemLine);
					ok = false;
				}
			}
		} catch (IOException exp) {
			System.out.println("Error: Cannot read problem file " + fProblem + exp);
			return false;
		}
		return ok;
	}

	/**
	 * Write a file that can be given to mkgmap that contains the correct
	 * arguments for the split file pieces. You are encouraged to edit the file
	 * and so it contains a template of all the arguments that you might want to
	 * use.
	 * 
	 * @param problemRelsThisPass
	 * @param problemWaysThisPass
	 */
	public void writeProblemList(File fileOutputDir, String fname) {
		try (PrintWriter w = new PrintWriter(new FileWriter(new File(fileOutputDir, fname)));) {

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
			for (long id : calculatedProblemWays) {
				w.println("way: " + id + " #");
			}
			w.println("# rels");
			for (long id : calculatedProblemRels) {
				w.println("rel: " + id + " #");
			}

			w.println();
		} catch (IOException e) {
			System.err.println("Warning: Could not write problem-list file " + fname + ", processing continues");
		}
	}

	/**
	 * Calculate writers for elements which cross areas.
	 * 
	 * @param dataStorer
	 *            stores data that is needed in different passes of the program.
	 * @param osmFileHandler
	 *            used to access OSM input files
	 */
	public void calcMultiTileElements(DataStorer dataStorer, OSMFileHandler osmFileHandler) {
		// merge the calculated problem ids and the user given problem ids
		problemWays.addAll(calculatedProblemWays);
		problemRels.addAll(calculatedProblemRels);
		calculatedProblemRels.clear();
		calculatedProblemWays.clear();

		if (problemWays.isEmpty() && problemRels.isEmpty())
			return;

		// calculate which ways and relations are written to multiple areas.
		MultiTileProcessor multiProcessor = new MultiTileProcessor(dataStorer, problemWays, problemRels);
		// multiTileProcessor stores the problem relations in its own structures
		// return memory to GC
		problemRels.clear();
		problemWays.clear();
		problemRels.trim();
		problemWays.trim();

		boolean done = false;
		long startThisPhase = System.currentTimeMillis();
		int prevPhase = -1;
		while (!done) {
			int phase = multiProcessor.getPhase();
			if (prevPhase != phase) {
				startThisPhase = System.currentTimeMillis();
				System.out.println("-----------------------------------");
				System.out.println("Executing multi-tile analyses phase " + phase);
			}
			done = osmFileHandler.execute(multiProcessor);

			prevPhase = phase;
			if (done || (phase != multiProcessor.getPhase())) {
				System.out.println("Multi-tile analyses phase " + phase + " took "
						+ (System.currentTimeMillis() - startThisPhase) + " ms");
			}
		}

		System.out.println("-----------------------------------");
	}
	
	/**
	 * Make sure that our areas cover the planet. This is done by adding
	 * pseudo-areas if needed.
	 * 
	 * @param realAreas
	 *            list of areas (read from split-file or calculated)
	 * @return new list of areas containing the real areas and additional areas
	 */
	public static List<Area> addPseudoAreas(List<Area> realAreas) {
		ArrayList<Area> areas = new ArrayList<>(realAreas);
		Rectangle planetBounds = new Rectangle(Utils.toMapUnit(-180.0), Utils.toMapUnit(-90.0),
				2 * Utils.toMapUnit(180.0), 2 * Utils.toMapUnit(90.0));

		while (!checkIfCovered(planetBounds, areas)) {
			boolean changed = addPseudoArea(areas);

			if (!changed) {
				throw new SplitFailedException("Failed to fill planet with pseudo-areas");
			}
		}
		return areas;
	}

	/**
	 * Work around for possible rounding errors in area.subtract processing
	 * 
	 * @param area
	 *            an area that is considered to be empty or a rectangle
	 * @return
	 */
	private static java.awt.geom.Area simplifyArea(java.awt.geom.Area area) {
		if (area.isEmpty() || area.isRectangular())
			return area;
		// area.isRectugular() may returns false although the shape is a
		// perfect rectangle :-( If we subtract the area from its bounding
		// box we get better results.
		java.awt.geom.Area bbox = new java.awt.geom.Area(area.getBounds2D());
		bbox.subtract(area);
		if (bbox.isEmpty()) // bbox equals area: is a rectangle
			return new java.awt.geom.Area(area.getBounds2D());
		return area;
	}

	private static boolean checkIfCovered(Rectangle bounds, ArrayList<Area> areas) {
		java.awt.geom.Area bbox = new java.awt.geom.Area(bounds);
		long sumTiles = 0;

		for (Area area : areas) {
			sumTiles += (long) area.getHeight() * (long) area.getWidth();
			bbox.subtract(area.getJavaArea());
		}
		long areaBox = (long) bounds.height * (long) bounds.width;

		if (sumTiles != areaBox)
			return false;

		return bbox.isEmpty();
	}

	/**
	 * Create a list of areas that do not overlap. If areas in the original list
	 * are overlapping, they can be replaced by up to 5 disjoint areas. This is
	 * done if parameter makeDisjoint is true
	 * 
	 * @param realAreas
	 *            the list of areas
	 * @return the new list
	 */
	public static ArrayList<Area> getNonOverlappingAreas(final List<Area> realAreas) {
		java.awt.geom.Area covered = new java.awt.geom.Area();
		ArrayList<Area> splitList = new ArrayList<>();
		int artificialId = -99999999;
		boolean foundOverlap = false;
		for (Area area1 : realAreas) {
			Rectangle r1 = area1.getRect();
			if (covered.intersects(r1) == false) {
				splitList.add(area1);
			} else {
				if (foundOverlap == false) {
					foundOverlap = true;
					System.out.println("Removing overlaps from tiles...");
				}
				// String msg = "splitting " + area1.getMapId() + " " + (i+1) +
				// "/" + realAreas.size() + " overlapping ";
				// find intersecting areas in the already covered part
				ArrayList<Area> splitAreas = new ArrayList<>();

				for (int j = 0; j < splitList.size(); j++) {
					Area area2 = splitList.get(j);
					if (area2 == null)
						continue;
					Rectangle r2 = area2.getRect();
					if (r1.intersects(r2)) {
						java.awt.geom.Area overlap = new java.awt.geom.Area(area1.getRect());
						overlap.intersect(area2.getJavaArea());
						Rectangle ro = overlap.getBounds();
						if (ro.height == 0 || ro.width == 0)
							continue;
						// msg += area2.getMapId() + " ";
						Area aNew = new Area(ro.y, ro.x, (int) ro.getMaxY(), (int) ro.getMaxX());
						aNew.setMapId(artificialId++);
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

						Rectangle[] rectPair = { r1, r2 };
						Area[] areaPair = { area1, area2 };
						int lx = minX;
						int lw = ro.x - minX;
						int rx = (int) ro.getMaxX();
						int rw = maxX - rx;
						int uy = (int) ro.getMaxY();
						int uh = maxY - uy;
						int by = minY;
						int bh = ro.y - by;
						Rectangle[] clippers = { new Rectangle(lx, minY, lw, bh), // lower
																					// left
								new Rectangle(ro.x, minY, ro.width, bh), // lower
																			// middle
								new Rectangle(rx, minY, rw, bh), // lower right
								new Rectangle(lx, ro.y, lw, ro.height), // left
								new Rectangle(rx, ro.y, rw, ro.height), // right
								new Rectangle(lx, uy, lw, uh), // upper left
								new Rectangle(ro.x, uy, ro.width, uh), // upper
																		// middle
								new Rectangle(rx, uy, rw, uh) // upper right
						};

						for (Rectangle clipper : clippers) {
							for (int k = 0; k <= 1; k++) {
								Rectangle test = clipper.intersection(rectPair[k]);
								if (!test.isEmpty()) {
									testSplit.add(new java.awt.geom.Area(test));
									if (k == 1 || covered.intersects(test) == false) {
										aNew = new Area(test.y, test.x, (int) test.getMaxY(), (int) test.getMaxX());
										aNew.setMapId(areaPair[k].getMapId());
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
				for (Area splitArea : splitAreas) {
					if (splitArea.isJoinable()) {
						for (int j = 0; j < splitList.size(); j++) {
							Area area = splitList.get(j);
							if (area == null || area.isJoinable() == false || area.getMapId() != splitArea.getMapId())
								continue;
							boolean doJoin = false;
							if (splitArea.getMaxLat() == area.getMaxLat() && splitArea.getMinLat() == area.getMinLat()
									&& (splitArea.getMinLong() == area.getMaxLong()
											|| splitArea.getMaxLong() == area.getMinLong()))
								doJoin = true;
							else if (splitArea.getMinLong() == area.getMinLong()
									&& splitArea.getMaxLong() == area.getMaxLong()
									&& (splitArea.getMinLat() == area.getMaxLat()
											|| splitArea.getMaxLat() == area.getMinLat()))
								doJoin = true;
							if (doJoin) {
								splitArea = area.add(splitArea);
								splitArea.setMapId(area.getMapId());
								splitList.set(j, splitArea);
								splitArea = null; // don't add later
								break;
							}
						}
					}
					if (splitArea != null) {
						splitList.add(splitArea);
					}
				}
				/*
				 * if (msg.isEmpty() == false) System.out.println(msg);
				 */
			}
			covered.add(new java.awt.geom.Area(r1));
		}
		covered.reset();
		Iterator<Area> iter = splitList.iterator();
		while (iter.hasNext()) {
			Area a = iter.next();
			if (a == null)
				iter.remove();
			else {
				Rectangle r1 = a.getRect();
				if (covered.intersects(r1) == true) {
					throw new SplitFailedException("Failed to create list of distinct areas");
				}
				covered.add(a.getJavaArea());
			}
		}
		return splitList;
	}

	/**
	 * Fill uncovered parts of the planet with pseudo-areas. TODO: check if
	 * better algorithm reduces run time in ProblemListProcessor We want a small
	 * number of pseudo areas because many of them will require more memory or
	 * more passes, esp. when processing whole planet. Also, the total length of
	 * all edges should be small.
	 * 
	 * @param areas
	 *            list of areas (either real or pseudo)
	 * @return true if pseudo-areas were added
	 */
	private static boolean addPseudoArea(ArrayList<Area> areas) {
		int oldSize = areas.size();
		Rectangle planetBounds = new Rectangle(Utils.toMapUnit(-180.0), Utils.toMapUnit(-90.0),
				2 * Utils.toMapUnit(180.0), 2 * Utils.toMapUnit(90.0));
		java.awt.geom.Area uncovered = new java.awt.geom.Area(planetBounds);
		java.awt.geom.Area covered = new java.awt.geom.Area();
		for (Area area : areas) {
			uncovered.subtract(area.getJavaArea());
			covered.add(area.getJavaArea());
}
		Rectangle rCov = covered.getBounds();
		Rectangle[] topAndBottom = {
				new Rectangle(planetBounds.x, (int) rCov.getMaxY(), planetBounds.width,
						(int) (planetBounds.getMaxY() - rCov.getMaxY())), // top
				new Rectangle(planetBounds.x, planetBounds.y, planetBounds.width, rCov.y - planetBounds.y) }; // bottom
		for (Rectangle border : topAndBottom) {
			if (!border.isEmpty()) {
				uncovered.subtract(new java.awt.geom.Area(border));
				covered.add(new java.awt.geom.Area(border));
				Area pseudo = new Area(border.y, border.x, (int) border.getMaxY(), (int) border.getMaxX());
				pseudo.setMapId(-1 * (areas.size() + 1));
				pseudo.setPseudoArea(true);
				areas.add(pseudo);
			}
		}
		while (uncovered.isEmpty() == false) {
			boolean changed = false;
			List<List<Point>> shapes = Utils.areaToShapes(uncovered);
			// we divide planet into stripes for all vertices of the uncovered
			// area
			int minX = uncovered.getBounds().x;
			int nextX = Integer.MAX_VALUE;
			for (int i = 0; i < shapes.size(); i++) {
				List<Point> shape = shapes.get(i);
				for (Point point : shape) {
					int lon = point.x;
					if (lon < nextX && lon > minX)
						nextX = lon;
				}
			}
			java.awt.geom.Area stripeLon = new java.awt.geom.Area(
					new Rectangle(minX, planetBounds.y, nextX - minX, planetBounds.height));
			// cut out already covered area
			stripeLon.subtract(covered);
			assert stripeLon.isEmpty() == false;
			// the remaining area must be a set of zero or more disjoint
			// rectangles
			List<List<Point>> stripeShapes = Utils.areaToShapes(stripeLon);
			for (int j = 0; j < stripeShapes.size(); j++) {
				List<Point> rectShape = stripeShapes.get(j);
				java.awt.geom.Area test = Utils.shapeToArea(rectShape);
				test = simplifyArea(test);
				assert test.isRectangular();
				Rectangle pseudoRect = test.getBounds();
				if (uncovered.contains(pseudoRect)) {
					assert test.getBounds().width == stripeLon.getBounds().width;
					boolean wasMerged = false;
					// check if new area can be merged with last rectangles
					for (int k = areas.size() - 1; k >= oldSize; k--) {
						Area prev = areas.get(k);
						if (prev.getMaxLong() < pseudoRect.x || prev.isPseudoArea() == false)
							continue;
						if (prev.getHeight() == pseudoRect.height && prev.getMaxLong() == pseudoRect.x
								&& prev.getMinLat() == pseudoRect.y) {
							// merge
							Area pseudo = prev.add(new Area(pseudoRect.y, pseudoRect.x, (int) pseudoRect.getMaxY(),
									(int) pseudoRect.getMaxX()));
							pseudo.setMapId(prev.getMapId());
							pseudo.setPseudoArea(true);
							areas.set(k, pseudo);
							// System.out.println("Enlarged pseudo area " +
							// pseudo.getMapId() + " " + pseudo);
							wasMerged = true;
							break;
						}
					}

					if (!wasMerged) {
						Area pseudo = new Area(pseudoRect.y, pseudoRect.x, (int) pseudoRect.getMaxY(),
								(int) pseudoRect.getMaxX());
						pseudo.setMapId(-1 * (areas.size() + 1));
						pseudo.setPseudoArea(true);
						// System.out.println("Adding pseudo area " +
						// pseudo.getMapId() + " " + pseudo);
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
