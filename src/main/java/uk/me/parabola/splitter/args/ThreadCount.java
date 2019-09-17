/*
 * Copyright (c) 2010, Chris Miller
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

package uk.me.parabola.splitter.args;

/**
 * @author Chris Miller
 */
public class ThreadCount {
	private final int count;
	private final boolean auto;

	public ThreadCount(int count, boolean isAuto) {
		this.count = count;
		auto = isAuto;
	}

	public int getCount() {
		return count;
	}

	public boolean isAuto() {
		return auto;
	}

	@Override
	public String toString() {
		if (auto) 
			return count + " (auto)";
		return String.valueOf(count);
	}
}
