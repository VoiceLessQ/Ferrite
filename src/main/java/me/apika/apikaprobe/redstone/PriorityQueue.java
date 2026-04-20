/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. Only change from upstream:
 *   Redstone.SIGNAL_MIN / SIGNAL_MAX -> WireConstants.SIGNAL_MIN / MAX
 */
package me.apika.apikaprobe.redstone;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Priority queue specialized for the wire update algorithm. Nodes are
 * sorted by {@link Node#priority()} highest-first; within the same
 * priority, FIFO. Implementation is a doubly-linked list with an index
 * array mapping each priority value to its last node, so inserts at
 * any priority are O(1) amortized.
 *
 * <p>Nodes carry their own {@code prev_node} / {@code next_node} links
 * so membership tests cost O(1) without a separate set.
 *
 * @author Space Walker (original, Mojmap)
 */
public class PriorityQueue extends AbstractQueue<Node> {

	private static final int OFFSET = -WireConstants.SIGNAL_MIN;

	/** Last node for each priority value. */
	private final Node[] tails;

	private Node head;
	private Node tail;

	private int size;

	PriorityQueue() {
		this.tails = new Node[(WireConstants.SIGNAL_MAX + OFFSET) + 1];
	}

	@Override
	public boolean offer(Node node) {
		if (node == null) {
			throw new NullPointerException();
		}

		int priority = node.priority();

		if (contains(node)) {
			if (node.priority == priority) {
				// already queued with this priority; exit
				return false;
			} else {
				// already queued with a different priority; move it
				move(node, priority);
			}
		} else {
			insert(node, priority);
		}

		return true;
	}

	@Override
	public Node poll() {
		if (head == null) {
			return null;
		}

		Node node = head;
		Node next = node.next_node;

		if (next == null) {
			clear(); // reset the tails array
		} else {
			if (node.priority != next.priority) {
				// If the head is also a tail, its entry in the array can
				// be cleared — no previous node shares this priority.
				tails[node.priority + OFFSET] = null;
			}

			node.next_node = null;
			next.prev_node = null;
			head = next;

			size--;
		}

		return node;
	}

	@Override
	public Node peek() {
		return head;
	}

	@Override
	public void clear() {
		for (Node node = head; node != null; ) {
			Node n = node;
			node = node.next_node;

			n.prev_node = null;
			n.next_node = null;
		}

		Arrays.fill(tails, null);

		head = null;
		tail = null;

		size = 0;
	}

	@Override
	public Iterator<Node> iterator() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int size() {
		return size;
	}

	public boolean contains(Node node) {
		return node == head || node.prev_node != null;
	}

	private void move(Node node, int priority) {
		remove(node);
		insert(node, priority);
	}

	private void remove(Node node) {
		Node prev = node.prev_node;
		Node next = node.next_node;

		if (node == tail || node.priority != next.priority) {
			// Assign a new tail for this node's priority.
			if (node == head || node.priority != prev.priority) {
				// No other node shares this priority; clear.
				tails[node.priority + OFFSET] = null;
			} else {
				// The previous node in the queue becomes the tail.
				tails[node.priority + OFFSET] = prev;
			}
		}

		if (node == head) {
			head = next;
		} else {
			prev.next_node = next;
		}
		if (node == tail) {
			tail = prev;
		} else {
			next.prev_node = prev;
		}

		node.prev_node = null;
		node.next_node = null;

		size--;
	}

	private void insert(Node node, int priority) {
		node.priority = priority;

		// Sorted by priority highest→lowest; same priority is FIFO.
		if (head == null) {
			// First element.
			head = tail = node;
		} else if (priority > head.priority) {
			linkHead(node);
		} else if (priority <= tail.priority) {
			linkTail(node);
		} else {
			// Neither head nor tail — findPrev is guaranteed non-null.
			linkAfter(findPrev(node), node);
		}

		tails[priority + OFFSET] = node;

		size++;
	}

	private void linkHead(Node node) {
		node.next_node = head;
		head.prev_node = node;
		head = node;
	}

	private void linkTail(Node node) {
		tail.next_node = node;
		node.prev_node = tail;
		tail = node;
	}

	private void linkAfter(Node prev, Node node) {
		linkBetween(prev, node, prev.next_node);
	}

	private void linkBetween(Node prev, Node node, Node next) {
		prev.next_node = node;
		node.prev_node = prev;

		node.next_node = next;
		next.prev_node = node;
	}

	private Node findPrev(Node node) {
		Node prev = null;

		for (int i = node.priority + OFFSET; i < tails.length; i++) {
			prev = tails[i];
			if (prev != null) {
				break;
			}
		}

		return prev;
	}
}
