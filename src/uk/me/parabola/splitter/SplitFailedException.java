/*
 * Copyright (C) 2014, Gerd Petermann
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

/**
 * Thrown when a severe error occurs while calculating or writing the tile areas
 *
 * @author GerdP
 */
public class SplitFailedException extends RuntimeException {
	public SplitFailedException(String s) {
		super(s);
	}
    public SplitFailedException(String message, Throwable cause) {
        super(message, cause);
    }
	
}
