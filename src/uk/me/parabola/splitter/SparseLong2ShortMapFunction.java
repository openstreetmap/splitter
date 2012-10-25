package uk.me.parabola.splitter;

import java.io.DataOutputStream;
import java.io.IOException;

//import it.unimi.dsi.fastutil.longs.Long2ShortFunction;

/**
 * Stores long/short pairs. 
 * 
 */
interface SparseLong2ShortMapFunction {
	
	public short put(long key, short val);
	public void clear();
	public boolean containsKey(long key);
	public short get(long key);
	public void stats(int msgLevel);
	public long size();
	public short defaultReturnValue();
	public void defaultReturnValue(short arg0);
	public void save(DataOutputStream dos) throws IOException;
}
