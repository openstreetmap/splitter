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

import java.util.ArrayList;
import java.util.List;

/**
 * @author Steve Ratcliffe
 */
public class Relation extends Element {
	private final static short IS_MP     = 0x01; 
	private final static short ON_LOOP   = 0x02; 
	private final static short HAS_NODES = 0x04; 
	private final static short HAS_WAYS  = 0x08; 
	private final static short HAS_RELS  = 0x10; 
	private final static short IS_JUST_PARENT = 0x20; 
	
	private final List<Member> members = new ArrayList<Member>();
	
	private int multiTileWriterIndex = -1;
	private int visitId;
	private short flags; 	// flags for the MultiTileProcessor

	public void set(long id) {
		setId(id);
	}
	
	public void clearTags(){
		tags = null;
	}
	
	@Override
	public void reset() {
		super.reset();
		members.clear();
		flags = 0;
		multiTileWriterIndex = -1;
	}

	public void addMember(String type, long ref, String role) {
		Member mem = new Member(type, ref, role);
		members.add(mem);
		if ("nodes".equals(type))
			flags |= HAS_NODES;
		if ("way".equals(type))
			flags |= HAS_WAYS;
		if ("relation".equals(type))
			flags |= HAS_RELS;
		
	}

	public List<Member> getMembers() {
		return members;
	}

	public boolean isOnLoop() {
		return (flags & ON_LOOP) != 0; 
	}

	public void markOnLoop() {
		this.flags |= ON_LOOP;
	}

	public int getMultiTileWriterIndex() {
		return multiTileWriterIndex;
	}

	public void setMultiTileWriterIndex(int multiTileWriterIndex) {
		this.multiTileWriterIndex = multiTileWriterIndex;
	}

	public boolean hasNodeMembers() {
		return (flags & HAS_NODES) != 0;
	}
	public boolean hasWayMembers() {
		return (flags & HAS_WAYS) != 0;
	}
	public boolean hasRelMembers() {
		return (flags & HAS_RELS) != 0;
	}

	public boolean wasAddedAsParent() {
		return (flags & IS_JUST_PARENT) != 0;
	}

	public void setAddedAsParent() {
		this.flags |= IS_JUST_PARENT;
	}

	public boolean isMultiPolygon() {
		return (flags & IS_MP) != 0; 
	}

	public void markAsMultiPolygon() {
		this.flags |= IS_MP;
	}

	public int getVisitId() {
		return visitId;
	}

	public void setVisitId(int visitId) {
		this.visitId = visitId;
	}

	static class Member {
		private String type;
		private long ref;
		private String role;

		Member(String type, long ref, String role) {
			this.type = type.intern();
			this.ref = ref;
			this.role = role.intern();
		}

		public String getType() {
			return type;
		}

		public long getRef() {
			return ref;
		}

		public String getRole() {
			return role;
		}
	}
}
