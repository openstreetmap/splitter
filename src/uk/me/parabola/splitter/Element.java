/*
 * Copyright (c) 2009.
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * @author Steve Ratcliffe
 */
public class Element {
	private static final Iterator<Map.Entry<String, String>> EMPTY_ITERATOR = new EmptyIterator();

	private Map<String, String> tags;
	private int id;

	protected void setId(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public void reset() {
		this.id = 0;
		tags = null;
	}

	public void addTag(String key, String value) {
		if (key.equals("created_by"))
			return;
		// Most elements are nodes. Most nodes have no tags. Create the tag table lazily
		if (tags == null) {
			tags = new HashMap<String, String>(4);
		}
		tags.put(key, value);
	}

	public boolean hasTags() {
		return tags != null;
	}

	public Iterator<Map.Entry<String, String>> tagsIterator() {
		if (tags == null) {
			return EMPTY_ITERATOR;
		}
		return tags.entrySet().iterator();
	}

	private static class EmptyIterator implements Iterator<Map.Entry<String, String>>
	{
		public boolean hasNext()
		{
			return false;
		}

		public Map.Entry<String, String> next() {
			throw new NoSuchElementException();
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
}
