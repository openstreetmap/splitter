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
