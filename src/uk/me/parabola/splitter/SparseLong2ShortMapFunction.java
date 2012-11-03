package uk.me.parabola.splitter;

//import it.unimi.dsi.fastutil.longs.Long2ShortFunction;

/**
 * Stores long/short pairs. 
 * 
 */
interface SparseLong2ShortMapFunction {
	final short UNASSIGNED = Short.MIN_VALUE;
	public short put(long key, short val);
	public void clear();
	public boolean containsKey(long key);
	public short get(long key);
	public void stats(int msgLevel);
	public long size();
	public short defaultReturnValue();
	public void defaultReturnValue(short arg0);
}
