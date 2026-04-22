//! Phase 1 of the AC Rust core port (per docs/REDSTONE_PORT_PLAN.md).
//!
//! Library-only port of the Java priority queue used by AC's wire
//! handler. Goal: micro-benchmark Rust's queue vs Java's to answer
//! the gate question — "is JNI overhead survivable for AC's queue
//! shape on realistic node counts (≥1000)?"
//!
//! Java's `PriorityQueue` is intrusive — queue links live ON the Node
//! objects. Rust can't reach into Java objects, so this port is
//! non-intrusive: it receives a flat batch of `(id, priority)` pairs
//! and returns ids in priority order.
//!
//! Algorithm: counting sort over 16 priority buckets (signal levels
//! 0..=15), FIFO within each bucket. Same shape as Java's `tails`
//! index array. O(N + B) where B=16 is constant.
//!
//! Bench-only — not wired into the validator or any cascade path.

use std::collections::VecDeque;

const NUM_BUCKETS: usize = 16;

/// Non-intrusive priority queue. Items with higher priority drain
/// first; FIFO within the same priority.
pub struct PriorityQueue {
    /// One FIFO per priority value [0..16). Higher index = higher priority.
    buckets: [VecDeque<u32>; NUM_BUCKETS],
    /// Highest non-empty bucket index, or -1 if empty.
    head_priority: i32,
    size: usize,
}

impl PriorityQueue {
    pub fn new() -> Self {
        Self {
            buckets: std::array::from_fn(|_| VecDeque::new()),
            head_priority: -1,
            size: 0,
        }
    }

    pub fn with_capacity(per_bucket: usize) -> Self {
        Self {
            buckets: std::array::from_fn(|_| VecDeque::with_capacity(per_bucket)),
            head_priority: -1,
            size: 0,
        }
    }

    pub fn offer(&mut self, id: u32, priority: u8) {
        debug_assert!((priority as usize) < NUM_BUCKETS);
        let p = priority as usize;
        self.buckets[p].push_back(id);
        self.size += 1;
        if priority as i32 > self.head_priority {
            self.head_priority = priority as i32;
        }
    }

    pub fn poll(&mut self) -> Option<u32> {
        if self.head_priority < 0 {
            return None;
        }
        let p = self.head_priority as usize;
        let id = self.buckets[p].pop_front()?;
        self.size -= 1;
        if self.buckets[p].is_empty() {
            // Find next-highest non-empty bucket, or set to -1.
            let mut next = -1;
            for i in (0..p).rev() {
                if !self.buckets[i].is_empty() {
                    next = i as i32;
                    break;
                }
            }
            self.head_priority = next;
        }
        Some(id)
    }

    pub fn len(&self) -> usize { self.size }
    pub fn is_empty(&self) -> bool { self.size == 0 }

    pub fn clear(&mut self) {
        for b in &mut self.buckets {
            b.clear();
        }
        self.head_priority = -1;
        self.size = 0;
    }
}

impl Default for PriorityQueue {
    fn default() -> Self { Self::new() }
}

// ---- tests -----------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_poll_returns_none() {
        let mut q = PriorityQueue::new();
        assert_eq!(q.poll(), None);
        assert!(q.is_empty());
    }

    #[test]
    fn higher_priority_drains_first() {
        let mut q = PriorityQueue::new();
        q.offer(1, 5);
        q.offer(2, 10);
        q.offer(3, 5);
        assert_eq!(q.poll(), Some(2)); // priority 10 first
        assert_eq!(q.poll(), Some(1)); // priority 5 FIFO: 1 before 3
        assert_eq!(q.poll(), Some(3));
        assert_eq!(q.poll(), None);
    }

    #[test]
    fn fifo_within_same_priority() {
        let mut q = PriorityQueue::new();
        for i in 0..10 {
            q.offer(i, 7);
        }
        for expected in 0..10 {
            assert_eq!(q.poll(), Some(expected));
        }
        assert_eq!(q.poll(), None);
    }

    #[test]
    fn mixed_workload_stable_order() {
        let mut q = PriorityQueue::new();
        // offer in scrambled order across priorities
        let pairs = [(1, 3), (2, 7), (3, 3), (4, 10), (5, 7), (6, 3)];
        for (id, p) in pairs {
            q.offer(id, p);
        }
        // Drain order: 4 (p=10), 2,5 (p=7 FIFO), 1,3,6 (p=3 FIFO)
        assert_eq!(q.poll(), Some(4));
        assert_eq!(q.poll(), Some(2));
        assert_eq!(q.poll(), Some(5));
        assert_eq!(q.poll(), Some(1));
        assert_eq!(q.poll(), Some(3));
        assert_eq!(q.poll(), Some(6));
    }

    #[test]
    fn clear_resets_state() {
        let mut q = PriorityQueue::new();
        q.offer(1, 5);
        q.offer(2, 10);
        q.clear();
        assert!(q.is_empty());
        assert_eq!(q.poll(), None);
    }

    #[test]
    fn full_priority_range() {
        let mut q = PriorityQueue::new();
        for p in 0..16 {
            q.offer(p as u32, p);
        }
        // Drains highest-first
        for expected_p in (0..16u8).rev() {
            assert_eq!(q.poll(), Some(expected_p as u32));
        }
    }
}
