package uk.me.parabola.splitter;

import java.util.List;

/**
 * @author Gerd Petermann
 *
 */
public class OSMMessage {
	public enum Type {ELEMENTS, BOUNDS, END_MAP}; 

	// either el or bounds must be null
	List<Element> elements;
	Area bounds;
	Type type;

	public OSMMessage(List<Element> elements) {
		this.elements = elements;
		type = Type.ELEMENTS;
	}

	public OSMMessage(Area bounds) {
		this.bounds = bounds;
		type = Type.BOUNDS;
	}

	public OSMMessage() {
		type = Type.END_MAP;
	}

}
