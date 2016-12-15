/*
 * Copyright (c) 2016, Gerd Petermann
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import uk.me.parabola.splitter.OSMMessage.Type;

/**
 * Simple helper to allow all existing processors to use the producer/consumer
 * pattern. For each call of a supplier (one of the OSM parsers) it either
 * passes the call to the original processor or adds messages to the queue..
 * 
 * @author Gerd Petermann
 *
 */
public class QueueProcessor extends AbstractMapProcessor {
	private final BlockingQueue<OSMMessage> queue;
	private final MapProcessor realProcessor;

	public QueueProcessor(BlockingQueue<OSMMessage> queue, MapProcessor realProcessor) {
		this.queue = queue;
		this.realProcessor = realProcessor;
	}

	@Override
	public boolean skipTags() {
		return realProcessor.skipTags();
	}

	@Override
	public boolean skipNodes() {
		return realProcessor.skipNodes();
	}

	@Override
	public boolean skipWays() {
		return realProcessor.skipWays();
	}

	@Override
	public boolean skipRels() {
		return realProcessor.skipRels();
	}

	@Override
	public void boundTag(Area bounds) {
		addToQueue(bounds);
	}

	@Override
	public void processNode(Node n) {
		addToQueue(n);
	}

	@Override
	public void processWay(Way w) {
		addToQueue(w);
	}

	@Override
	public void processRelation(Relation r) {
		addToQueue(r);
	}

	@Override
	public void startFile() {
		try {
			flush();
			queue.put(new OSMMessage(Type.START_FILE));
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Override	
	public boolean endMap() {
		try {
			flush();
			queue.put(new OSMMessage(Type.END_MAP));
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		return true;
	}

	public int getPhase() {
		throw new UnsupportedOperationException("call getPhase() of real processor"); 
	}

	/** number of OSM elements to collect before adding them to the queue */
	private static final int NUM_STAGING = 1000;
	private List<Element> staging = new ArrayList<>(NUM_STAGING);

	private void addToQueue(Element el) {
		try {
			staging.add(el);
			if (staging.size() >= NUM_STAGING)
				flush();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private void addToQueue(Area bounds) {
		try {
			flush();
			queue.put(new OSMMessage(bounds));
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private void flush() throws InterruptedException {
		if (staging == null || staging.isEmpty())
			return;
		queue.put(new OSMMessage(staging));
		staging = new ArrayList<>(NUM_STAGING);
	}
}
