package uk.me.parabola.splitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import it.unimi.dsi.fastutil.longs.LongArrayList;

public class ProblemLists {
    private final LongArrayList problemWays = new LongArrayList();
    private final LongArrayList problemRels = new LongArrayList();
    private final TreeSet<Long> calculatedProblemWays = new TreeSet<>();
    private final TreeSet<Long> calculatedProblemRels = new TreeSet<>();
    
    /**
     * Calculate lists of ways and relations that appear in multiple areas for a given list
     * of areas.
     * @param osmFileHandler
     * @param realAreas
     * @param wantedAdminLevel
     * @param boundaryTags
     * @param maxAreasPerPass
     * @param overlapAmount
     * @return
     */
    public DataStorer calcProblemLists(OSMFileHandler osmFileHandler, List<Area> realAreas, int wantedAdminLevel,
            String[] boundaryTags, int maxAreasPerPass, int overlapAmount) {
        long startProblemListGenerator = System.currentTimeMillis();
        ArrayList<Area> distinctAreas = AreasCalculator.getNonOverlappingAreas(realAreas);
        if (distinctAreas.size() > realAreas.size()) {
            System.err.println("Waring: The areas given in --split-file are overlapping.");
            Set<Integer> overlappingTiles = new TreeSet<>();
            for (int i = 0; i < realAreas.size(); i++) {
                Area a1 = realAreas.get(i);
                for (int j = i+1; j < realAreas.size(); j++) {
                    Area a2 = realAreas.get(j);
                    if (a1.intersects(a2)) {
                        overlappingTiles.add(a1.getMapId());
                        overlappingTiles.add(a2.getMapId());
                    }
                }
            }
            if (!overlappingTiles.isEmpty()) {
                System.out.println("Overlaping tiles: " + overlappingTiles.toString());
            }
        }
        System.out.println("Generating problem list for " + distinctAreas.size() + " distinct areas");
        List<Area> workAreas = AreasCalculator.addPseudoAreas(distinctAreas);
        
        int numPasses = (int) Math.ceil((double) workAreas.size() / maxAreasPerPass);
        int areasPerPass = (int) Math.ceil((double) workAreas.size() / numPasses);
        if (numPasses > 1) {
            System.out.println("Processing " + distinctAreas.size() + " areas in " + numPasses + " passes, " + areasPerPass + " areas at a time");
        } else {
            System.out.println("Processing " + distinctAreas.size() + " areas in a single pass");
        }

        ArrayList<Area> allAreas = new ArrayList<>();

        System.out.println("Pseudo areas:");
        for (int j = 0;j < workAreas.size(); j++){
            Area area = workAreas.get(j);
            allAreas.add(area);
            if (area.isPseudoArea())
                System.out.println("Pseudo area " + area.getMapId() + " covers " + area);
        }
        
        DataStorer distinctDataStorer = new DataStorer(workAreas, overlapAmount);
        System.out.println("Starting problem-list-generator pass(es)"); 
        
        for (int pass = 0; pass < numPasses; pass++) {
            System.out.println("-----------------------------------");
            System.out.println("Starting problem-list-generator pass " + (pass+1) + " of " + numPasses);
            long startThisPass = System.currentTimeMillis();
            int areaOffset = pass * areasPerPass;
            int numAreasThisPass = Math.min(areasPerPass, workAreas.size() - pass * areasPerPass);
            ProblemListProcessor processor = new ProblemListProcessor(distinctDataStorer, areaOffset,
                    numAreasThisPass, boundaryTags);
            processor.setWantedAdminLevel(wantedAdminLevel);
            
            boolean done = false;
            while (!done){
                done = osmFileHandler.process(processor);
                calculatedProblemWays.addAll(processor.getProblemWays());
                calculatedProblemRels.addAll(processor.getProblemRels());
            }
            System.out.println("Problem-list-generator pass " + (pass+1) + " took " + (System.currentTimeMillis() - startThisPass) + " ms"); 
        }
        System.out.println("Problem-list-generator pass(es) took " + (System.currentTimeMillis() - startProblemListGenerator) + " ms");
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
            for (long id: calculatedProblemWays){
                w.println("way: " + id + " #");
            }
            w.println("# rels");
            for (long id: calculatedProblemRels){
                w.println("rel: " + id + " #");
            }

            w.println();
        } catch (IOException e) {
            System.err.println("Warning: Could not write problem-list file " + fname + ", processing continues");
        }
    }

    /**
     * Calculate writers for elements which cross areas.
     * @param dataStorer stores data that is needed in different passes of the program.
     * @param osmFileHandler used to access OSM input files
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
        // multiTileProcessor stores the problem relations in its own structures return memory to GC
        problemRels.clear();
        problemWays.clear();
        problemRels.trim();
        problemWays.trim();
        
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
            done = osmFileHandler.process(multiProcessor);
            prevPhase = phase;
            if (done || (phase != multiProcessor.getPhase())){
                System.out.println("Multi-tile analyses phase " + phase + " took " + (System.currentTimeMillis() - startThisPhase) + " ms");
            }
        }

        System.out.println("-----------------------------------");
    }


}
