/*
 * Copyright (c) 2016
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

import java.util.Arrays;
import java.util.Iterator;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * A partly set implementation. Used as a replacement for BitSet which is slow when 
 * values are rather high, e.g. > 50000. 
 *  
 * @author Gerd Petermann
 *
 */
public final class AreaSet implements Iterable<Integer> {
	private static final int BIN_SEARCH_LIMIT = 10;
	private final IntArrayList list;
	private boolean locked;
	
	/** Create empty set. */
	public AreaSet() {
		list = new IntArrayList();
	}
	
	/** Copy constructor creates set with the same entries. 
	 * @param other set to clone
	 */
	public AreaSet(final AreaSet other) {
		if (!other.isEmpty()) {
			list = new IntArrayList(other.list);
		} else 
			list = new IntArrayList();
	}
	
	/**
	 * Create new set with one element.
	 * @param index the index of the element
	 */
	AreaSet(final int index) {
		list = new IntArrayList();
		list.add(index);
	}
	
	/**
	 * Lock this set. A locked set cannot be changed.
	 */
	public void lock() {
		this.list.trim();
		this.locked = true;
	}
	
    /**
     * Returns true if the element with the index 
     * {@code bitIndex} is currently in this set; false otherwise.
     *
     * @param  index   the bit index
     * @return the value of the bit with the specified index
     */
	public boolean get(final int index) {
		if (list.size() < BIN_SEARCH_LIMIT) {
			return list.contains(index);
		}
		return Arrays.binarySearch(list.elements(), 0, list.size(), index) >= 0;
	}

	/**
	 * Add the element to the set. No effect if index is already in the set.
	 * @param index the element
	 */
	public void set(final int index) {
		if (locked)
			throw new IllegalAccessError("AreaSet is locked");
		if (list.isEmpty()) {
			list.add(index);
		} else {
			int p = Arrays.binarySearch(list.elements(), 0, list.size(), index);
			if (p < 0) {
				list.add(-p - 1, index);
			}
		}
	}

	/**
	 * Remove the element from the set. 
	 * @param index the element
	 */
	public void clear(final int index) {
		if (locked)
			throw new IllegalAccessError("AreaSet is locked");
		int pos;
		if (list.size() < BIN_SEARCH_LIMIT) {
			list.rem(index);
		} else {
			pos = Arrays.binarySearch(list.elements(), index);
			if (pos >= 0) {
				list.removeInt(pos);
		}
		}
	}

	/**
	 * Merge with other set. Result contains elements of both sets. 
	 * @param other the other set
	 */
	void or(final AreaSet other) {
		if (locked)
			throw new IllegalAccessError("AreaSet is locked");
		if (other.isEmpty())
			return;
		if (list.isEmpty()) {
			list.addAll(other.list);
		} else { 
			for (int i : other.list) {
				set(i);
			}
		}
	}
	
	/**
	 * Remove elements in this set which are contained in the other set.
	 * @param other the other set
	 */
	public void subtract(final AreaSet other) {
		if (locked)
			throw new IllegalAccessError("AreaSet is locked");
		for (int i : other.list) { 
			clear(i);
		}
	}
	
	/**
	 * @return number of elements in this set
	 */
	public int cardinality() {
		return list.size();
	}

	/**
	 * @return true if this set contains no elements.
	 */
	public boolean isEmpty() {
		return cardinality() == 0;
	}
	
	/**
	 * remove all elements from the set. Doesn't free storage.
	 */
	public void clear() {
		if (locked)
			throw new IllegalAccessError("AreaSet is locked");
		list.clear();
	}
	

	/**
	 * @return an iterator over this set.
	 */
	@Override
	public Iterator<Integer> iterator() {
		return list.iterator();
		
	}
	
	@Override
	public int hashCode() {
		return list.hashCode();
	}

	@Override
	public boolean equals(final Object obj) {
		if (!(obj instanceof AreaSet))
			return false;
		if (this == obj)
			return true;
		AreaSet other = (AreaSet) obj;
		if (isEmpty() && other.isEmpty())
			return true;
		return list.equals(other.list);
	}

	@Override
	public String toString() {
		return list.toString();
	}
}

