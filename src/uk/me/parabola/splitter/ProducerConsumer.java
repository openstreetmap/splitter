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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ProducerConsumer {
	private final OSMFileHandler osmFileHandler;
	private final BlockingQueue<OSMMessage> queue;
	private final MapProcessor realProcessor;

	public ProducerConsumer(OSMFileHandler osmFileHandler, MapProcessor processor) {
		this.osmFileHandler = osmFileHandler;
		this.realProcessor = processor;
		queue = new ArrayBlockingQueue<>(10);
	}

	public boolean execute() {
		QueueProcessor processor = new QueueProcessor(queue, realProcessor);
		new Thread(() -> {
			osmFileHandler.process(processor);
		}).start();
		return realProcessor.consume(queue);
	}
}
