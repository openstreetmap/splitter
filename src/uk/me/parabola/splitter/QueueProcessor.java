package uk.me.parabola.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class QueueProcessor extends AbstractMapProcessor {
	private final BlockingQueue<OSMMessage> queue;
	private final MapProcessor realProcessor;
	
	public boolean skipTags(){
		return realProcessor.skipTags();
	}
	public boolean skipNodes(){
		return realProcessor.skipNodes();
	}
	public boolean skipWays(){
		return realProcessor.skipWays();
	}
	public boolean skipRels(){
		return realProcessor.skipRels();
	}


	public QueueProcessor(BlockingQueue<OSMMessage> queue, MapProcessor realProcessor) {
		this.queue = queue;
		this.realProcessor = realProcessor;
	}

	public void boundTag(Area bounds){
		addToQueue(bounds);
	}

	public void processNode(Node n){
		addToQueue(n);
	}

	public void processWay(Way w){
		addToQueue(w);

	}
	
	public void processRelation(Relation r) {
		addToQueue(r);
	}

	public boolean endMap(){
		try {
			flush();
			queue.put(new OSMMessage());
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		return true;
	}

	public int getPhase() {
		return realProcessor.getPhase(); // TODO
	}

	/** number of OSM elements to collect before adding them to the queue */
	private static final int NUM_STAGING = 1000;
	List<Element> staging = new ArrayList<>(NUM_STAGING);
	
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
