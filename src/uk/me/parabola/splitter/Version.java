/*
 * File: Version.java
 * 
 * Copyright (C) 2007 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 12 Dec 2007
 */

package uk.me.parabola.splitter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Definitions of version numbers.
 *
 * @author Steve Ratcliffe
 */
public class Version {

	public static final String VERSION = getSvnVersion();
	public static final String TIMESTAMP = getTimeStamp();

	// A default version to use.  
	private static final String DEFAULT_VERSION = "unknown";
	private static final String DEFAULT_TIMESTAMP = "unknown";

	/**
	 * Get the version number if we can find one, else a default string.
	 * This looks in a file called splitter-version.properties on the
	 * classpath.
	 * This is created outside of the system by the build script.
	 *
	 * @return The version number or a default string if a version number
	 * cannot be found.
	 */
	private static String getSvnVersion() {
		try (InputStream is = Version.class
				.getResourceAsStream("/splitter-version.properties")) {
			if (is == null)
				return DEFAULT_VERSION;

			Properties props = new Properties();
			props.load(is);
			String version = props.getProperty("svn.version", DEFAULT_VERSION);
			if (version.matches("[1-9]+.*"))
				return version;
			return DEFAULT_VERSION;
		} catch (IOException e) {
			return DEFAULT_VERSION;
		}
	}
	private static String getTimeStamp() {
		try (InputStream is = Version.class
				.getResourceAsStream("/splitter-version.properties")) {
			if (is == null)
				return DEFAULT_TIMESTAMP;

			Properties props = new Properties();
			props.load(is);

			return props.getProperty("build.timestamp", DEFAULT_TIMESTAMP);
		} catch (IOException e) {
			return DEFAULT_TIMESTAMP;
		}
	}
}

