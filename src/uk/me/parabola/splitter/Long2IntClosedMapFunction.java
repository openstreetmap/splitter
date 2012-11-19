package uk.me.parabola.splitter;

import java.io.File;
import java.io.IOException;

/**
 * Stores long/int pairs
 * TODO: add javadoc
 * 
 */
interface Long2IntClosedMapFunction {
	/**
	 * Add a new pair. The key must be higher than then any existing key in the map.  
	 * @param key the key value
	 * @param val the value
	 * @return the position in which the key was inserted
	 */
	public int add(long key, int val);
	public int getRandom(long key);
	public int getSeq(long key);
	public long size();
	public int defaultReturnValue();
	void finish();
	public void close() throws IOException;
	
	void switchToSeqAccess(File directory) throws IOException;
	/**
	 * Return the position of the key if found in the map 
	 * @param key 
	 * @return the position or a negative value to indicate "not found"
	 */
	public int getKeyPos(long key);
	/**
	 * Replace the value for an existing key.
	 * @param key
	 * @param val
	 * @return the previously stored value
	 */
	public int replace(long key, int val);
	public void stats(final String prefix);
}
