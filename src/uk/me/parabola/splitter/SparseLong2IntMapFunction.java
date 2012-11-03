package uk.me.parabola.splitter;

//import it.unimi.dsi.fastutil.longs.Long2ShortFunction;

/**
 * Stores long/int pairs 
 * 
 */
interface SparseLong2IntMapFunction {
	final int UNASSIGNED = Integer.MIN_VALUE;
	public int put(long key, int val);
	public void clear();
	public boolean containsKey(long key);
	public int get(long key);
	public void stats(int msgLevel);
	public long size();
	public int defaultReturnValue();
	public void defaultReturnValue(int arg0);
}
