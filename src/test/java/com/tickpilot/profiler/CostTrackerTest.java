package com.tickpilot.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Per-type aggregation for SPEC FR-3 and AC-3, without Minecraft. */
class CostTrackerTest {
	/** Stands in for an {@code EntityType}: a registry singleton compared by identity. */
	private record Type(String name) {
	}

	private static final Type ZOMBIE = new Type("zombie");
	private static final Type VILLAGER = new Type("villager");
	private static final Type HOPPER = new Type("hopper");

	private CostTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new CostTracker();
	}

	@Test
	void sumsTimeAndInvocationsPerType() {
		tracker.record(TickCategory.ENTITIES, ZOMBIE, 100L);
		tracker.record(TickCategory.ENTITIES, ZOMBIE, 300L);
		tracker.record(TickCategory.ENTITIES, VILLAGER, 50L);

		List<CostTracker.TypeCost> top = tracker.top(TickCategory.ENTITIES, 10);

		assertEquals(2, top.size());
		assertSame(ZOMBIE, top.get(0).key(), "the costliest type comes first");
		assertEquals(400L, top.get(0).totalNanos());
		assertEquals(2L, top.get(0).invocations());
		assertEquals(200.0, top.get(0).averageNanos());
		assertSame(VILLAGER, top.get(1).key());
	}

	@Test
	void manyCheapInstancesOutrankOneExpensiveOne() {
		// AC-3 ranks by total, which is the number that decides whether a type is worth acting on.
		for (int i = 0; i < 1_000; i++) {
			tracker.record(TickCategory.ENTITIES, ZOMBIE, 10L);
		}

		tracker.record(TickCategory.ENTITIES, VILLAGER, 5_000L);

		List<CostTracker.TypeCost> top = tracker.top(TickCategory.ENTITIES, 2);

		assertSame(ZOMBIE, top.get(0).key());
		assertEquals(10_000L, top.get(0).totalNanos());
		assertEquals(10.0, top.get(0).averageNanos(), "but the average still exposes the cheap one");
		assertEquals(5_000.0, top.get(1).averageNanos());
	}

	@Test
	void categoriesAreKeptApart() {
		tracker.record(TickCategory.ENTITIES, ZOMBIE, 100L);
		tracker.record(TickCategory.BLOCK_ENTITIES, HOPPER, 200L);

		assertEquals(1, tracker.trackedTypes(TickCategory.ENTITIES));
		assertEquals(1, tracker.trackedTypes(TickCategory.BLOCK_ENTITIES));
		assertSame(HOPPER, tracker.top(TickCategory.BLOCK_ENTITIES, 5).get(0).key());
	}

	@Test
	void categoriesWithoutPerTypeMeaningAreIgnored() {
		tracker.record(TickCategory.CHUNK_OPS, ZOMBIE, 100L);
		tracker.record(TickCategory.NETWORK, ZOMBIE, 100L);

		assertTrue(tracker.top(TickCategory.CHUNK_OPS, 5).isEmpty());
		assertEquals(0, tracker.trackedTypes(TickCategory.NETWORK));
	}

	@Test
	void theLimitIsRespected() {
		tracker.record(TickCategory.ENTITIES, ZOMBIE, 300L);
		tracker.record(TickCategory.ENTITIES, VILLAGER, 200L);
		tracker.record(TickCategory.ENTITIES, new Type("cow"), 100L);

		assertEquals(2, tracker.top(TickCategory.ENTITIES, 2).size());
		assertEquals(3, tracker.top(TickCategory.ENTITIES, 99).size());
		assertTrue(tracker.top(TickCategory.ENTITIES, 0).isEmpty());
	}

	@Test
	void anEmptyTrackerReportsNothing() {
		assertTrue(tracker.top(TickCategory.ENTITIES, 5).isEmpty());
		assertEquals(0, tracker.trackedTypes(TickCategory.ENTITIES));
	}

	@Test
	void resetClearsEverything() {
		tracker.record(TickCategory.ENTITIES, ZOMBIE, 100L);
		tracker.record(TickCategory.BLOCK_ENTITIES, HOPPER, 100L);

		tracker.reset();

		assertEquals(0, tracker.trackedTypes(TickCategory.ENTITIES));
		assertEquals(0, tracker.trackedTypes(TickCategory.BLOCK_ENTITIES));
		assertTrue(tracker.top(TickCategory.ENTITIES, 5).isEmpty());
	}

	@Test
	void keysAreComparedByIdentityNotEquality() {
		// Two records that are equal but distinct objects must stay distinct, because registry
		// entries are singletons and identity is what the hot path relies on.
		Type one = new Type("zombie");
		Type two = new Type("zombie");
		assertEquals(one, two, "the test premise: equal but not the same object");

		tracker.record(TickCategory.ENTITIES, one, 100L);
		tracker.record(TickCategory.ENTITIES, two, 100L);

		assertEquals(2, tracker.trackedTypes(TickCategory.ENTITIES));
	}

	@Test
	void aRepeatedTypeDoesNotAllocateANewCell() throws Exception {
		// INV-6: the per-type cell is created once and updated in place from then on.
		tracker.record(TickCategory.ENTITIES, ZOMBIE, 1L);
		Object cellBefore = cellFor(ZOMBIE);

		for (int i = 0; i < 10_000; i++) {
			tracker.record(TickCategory.ENTITIES, ZOMBIE, 1L);
		}

		assertSame(cellBefore, cellFor(ZOMBIE), "the counter cell must be reused, not replaced");
		assertEquals(10_001L, tracker.top(TickCategory.ENTITIES, 1).get(0).invocations());
	}

	@SuppressWarnings("unchecked")
	private Object cellFor(Object key) throws Exception {
		java.lang.reflect.Field field = CostTracker.class.getDeclaredField("entityCosts");
		field.setAccessible(true);
		return ((java.util.Map<Object, long[]>) field.get(tracker)).get(key);
	}
}
