/*
 * Copyright (C) 2012.
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

import java.util.HashMap;

/** A simple partly BitSet implementation optimized for memory 
 * when used to store very large values with a high likelihood 
 * that the stored values build groups like e.g. the OSM node IDs. 
 * 
 * author GerdP */ 
public class SparseBitSet{
	static final int MASK = 63; 						
	static final long TOP_ID_MASK = ~MASK;  

  private HashMap<Long,Long> topMap = new HashMap<Long,Long>();
  private int setBits;
  
  public void set(long key){
      long topId = key & TOP_ID_MASK;
      int bitPos =(int)(key & MASK);
      long val = 1L << (bitPos-1);  
      Long chunk = topMap.get(topId);
      if (chunk != null){
    	  if ((chunk & val) != 0)
    		  return;
          val |= chunk;
      }
      topMap.put(topId, val); 
      ++setBits;
  }

  public void clear(long key){
      long topId = key & TOP_ID_MASK;
      Long chunk = topMap.get(topId);
      if (chunk == null)
          return;
      int bitPos =(int)(key & MASK);
      long val = 1L << (bitPos-1);  
      if ((chunk & val) == 0)
    	  return;
      chunk &= ~val;
      topMap.put(topId, chunk); 
      --setBits;
  }
  
  public boolean get(long key){
      long topId = key & TOP_ID_MASK;
      Long chunk = topMap.get(topId);
      if (chunk == null)
          return false;
      int bitPos =(int)(key & MASK);
      long val = 1L << (bitPos-1);  
      return (val & chunk) != 0; 
  }

  public void clear(){
	  topMap.clear();
	  setBits = 0;
  }
  
  /**
   * calculate estimated required heap
   * @return
   */
  public int bytes(){
	  return topMap.size() * 40;
  }
  
  
  public int cardinality(){
	  return setBits;
  }
}

                                                                           