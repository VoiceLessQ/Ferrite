/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. No semantic changes from upstream
 * aside from package relocation.
 */
package me.apika.apikaprobe.redstone;

import java.util.AbstractQueue;
import java.util.Iterator;

/**
 * FIFO queue specialized for {@link WireNode}s. Singly-linked via each
 * node's {@code next_wire} field so insertion and removal cost zero
 * allocations — the queue piggybacks on the nodes themselves.
 *
 * <p>Unlike {@link PriorityQueue}, this one doesn't participate in
 * priority scheduling; the algorithm uses it for the uninterleaved
 * part of the traversal where FIFO order is sufficient.
 *
 * @author Space Walker (original, Mojmap)
 */
public class SimpleQueue extends AbstractQueue<WireNode> {

	private WireNode head;
	private WireNode tail;

	private int size;

	SimpleQueue() {
	}

	@Override
	public boolean offer(WireNode node) {
		if (node == null) {
			throw new NullPointerException();
		}

		if (tail == null) {
			head = tail = node;
		} else {
			tail.next_wire = node;
			tail = node;
		}

		size++;
		return true;
	}

	@Override
	public WireNode poll() {
		if (head == null) {
			return null;
		}

		WireNode node = head;
		WireNode next = node.next_wire;

		if (next == null) {
			head = tail = null;
		} else {
			node.next_wire = null;
			head = next;
		}

		size--;
		return node;
	}

	@Override
	public WireNode peek() {
		return head;
	}

	@Override
	public void clear() {
		for (WireNode node = head; node != null; ) {
			WireNode n = node;
			node = node.next_wire;
			n.next_wire = null;
		}

		head = null;
		tail = null;
		size = 0;
	}

	@Override
	public Iterator<WireNode> iterator() {
		return new SimpleIterator();
	}

	@Override
	public int size() {
		return size;
	}

	private class SimpleIterator implements Iterator<WireNode> {

		private WireNode curr;
		private WireNode next;

		private SimpleIterator() {
			next = head;
		}

		@Override
		public boolean hasNext() {
			if (next == null && curr != null) {
				next = curr.next_wire;
			}
			return next != null;
		}

		@Override
		public WireNode next() {
			curr = next;
			next = curr.next_wire;
			return curr;
		}
	}
}
