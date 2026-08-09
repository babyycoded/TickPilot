package com.tickpilot.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The nesting arithmetic of SPEC FR-2 and AC-2, with a fake clock and no Minecraft.
 *
 * <p>These are the tests that matter for this phase: the profiler's whole reason to exist is that
 * the regions worth timing are nested inside one another, and a naive start/stop pair around each
 * of them would report a tick as costing more than it did.
 */
class TickProfilerTest {
	/** Stands in for an {@code EntityType} or a {@code BlockEntityType}: identity is all that matters. */
	private record Key(String name) {
	}

	private static final Key BOAT = new Key("boat");
	private static final Key PLAYER = new Key("player");
	private static final Key PARROT = new Key("parrot");
	private static final Key HOPPER = new Key("hopper");

	private TickProfiler profiler;
	private List<String> recorded;

	@BeforeEach
	void setUp() {
		profiler = new TickProfiler();
		recorded = new ArrayList<>();
		profiler.setCostSink((category, key, selfNanos) ->
				recorded.add(category + " " + ((Key) key).name() + " " + selfNanos));
		profiler.setEnabled(true);
		profiler.beginTick(0L);
	}

	// --- the basics -----------------------------------------------------------------------

	@Test
	void aSingleFrameChargesItsWholeElapsedTime() {
		profiler.begin(TickCategory.ENTITIES, null, 100L);
		profiler.end(400L);

		assertEquals(300L, profiler.tickNanos(TickCategory.ENTITIES));
		assertEquals(0, profiler.depth());
		assertTrue(profiler.isConsistent());
	}

	@Test
	void siblingFramesAddUp() {
		profiler.begin(TickCategory.ENTITIES, null, 0L);
		profiler.end(100L);
		profiler.begin(TickCategory.ENTITIES, null, 200L);
		profiler.end(250L);

		assertEquals(150L, profiler.tickNanos(TickCategory.ENTITIES));
	}

	// --- the double-count cases this class exists for -------------------------------------

	@Test
	void aPassengerIsNotCountedTwiceInsideItsVehicle() {
		// ServerLevel.tickNonPassenger(boat) spans 1000 ns, of which tickPassenger(player) is 400.
		profiler.begin(TickCategory.ENTITIES, BOAT, 0L);
		profiler.begin(TickCategory.ENTITIES, PLAYER, 300L);
		profiler.end(700L);
		profiler.end(1000L);

		// Naive start/stop on both would report 1400 for a region that took 1000.
		assertEquals(1000L, profiler.tickNanos(TickCategory.ENTITIES));
		assertEquals(List.of("ENTITIES player 400", "ENTITIES boat 600"), recorded);
	}

	@Test
	void nestedPassengersEachKeepTheirOwnSelfTime() {
		// boat -> player -> parrot, the tickPassenger recursion of ServerLevel.
		profiler.begin(TickCategory.ENTITIES, BOAT, 0L);
		profiler.begin(TickCategory.ENTITIES, PLAYER, 100L);
		profiler.begin(TickCategory.ENTITIES, PARROT, 200L);
		profiler.end(350L);
		profiler.end(500L);
		profiler.end(900L);

		assertEquals(900L, profiler.tickNanos(TickCategory.ENTITIES), "the outermost frame's span");
		assertEquals(List.of(
				"ENTITIES parrot 150",
				"ENTITIES player 250",
				"ENTITIES boat 500"), recorded);
	}

	@Test
	void aChildInAnotherCategoryIsSubtractedFromItsParent() {
		// ServerChunkCache.tick -> ServerLevel.tickChunk: RANDOM_TICKS sits inside CHUNK_OPS.
		profiler.begin(TickCategory.CHUNK_OPS, null, 0L);
		profiler.begin(TickCategory.RANDOM_TICKS, null, 200L);
		profiler.end(800L);
		profiler.end(1000L);

		assertEquals(600L, profiler.tickNanos(TickCategory.RANDOM_TICKS));
		assertEquals(400L, profiler.tickNanos(TickCategory.CHUNK_OPS), "1000 total minus 600 nested");
	}

	@Test
	void theBlockEntityShapeKeepsLoopOverheadOnTheOuterFrame() {
		// Level.tickBlockEntities -> BoundTickingBlockEntity.tick, twice.
		profiler.begin(TickCategory.BLOCK_ENTITIES, null, 0L);
		profiler.begin(TickCategory.BLOCK_ENTITIES, HOPPER, 10L);
		profiler.end(110L);
		profiler.begin(TickCategory.BLOCK_ENTITIES, HOPPER, 120L);
		profiler.end(320L);
		profiler.end(400L);

		assertEquals(400L, profiler.tickNanos(TickCategory.BLOCK_ENTITIES),
				"the category keeps the whole method, loop overhead included");
		// The outer frame carries no key, so its 100 ns of loop overhead lands in the category but
		// is charged to no block entity type - which is right: no type caused it.
		assertEquals(List.of(
				"BLOCK_ENTITIES hopper 100",
				"BLOCK_ENTITIES hopper 200"), recorded);
	}

	@Test
	void selfTimesAlwaysSumToTheOutermostSpan() {
		profiler.begin(TickCategory.CHUNK_OPS, null, 0L);
		profiler.begin(TickCategory.RANDOM_TICKS, null, 5L);
		profiler.begin(TickCategory.RANDOM_TICKS, null, 10L);
		profiler.end(40L);
		profiler.end(60L);
		profiler.begin(TickCategory.RANDOM_TICKS, null, 70L);
		profiler.end(90L);
		profiler.end(100L);

		long sum = profiler.tickNanos(TickCategory.CHUNK_OPS)
				+ profiler.tickNanos(TickCategory.RANDOM_TICKS);
		assertEquals(100L, sum, "AC-2: the categories may never exceed the region they came from");
	}

	// --- OTHER and the AC-2 sum rule --------------------------------------------------------

	@Test
	void otherIsWhatTotalHasLeftOver() {
		profiler.begin(TickCategory.ENTITIES, null, 0L);
		profiler.end(300L);
		profiler.begin(TickCategory.BLOCK_ENTITIES, null, 300L);
		profiler.end(500L);
		profiler.endTick(1200L);

		assertEquals(300L, profiler.sessionNanos(TickCategory.ENTITIES));
		assertEquals(200L, profiler.sessionNanos(TickCategory.BLOCK_ENTITIES));
		assertEquals(1200L, profiler.sessionNanos(TickCategory.TOTAL));
		assertEquals(700L, profiler.sessionNanos(TickCategory.OTHER));
		assertEquals(1L, profiler.sessionTicks());
		assertTrue(profiler.isConsistent());
	}

	@Test
	void aMeasuredOverrunIsCountedRatherThanHidden() {
		profiler.begin(TickCategory.ENTITIES, null, 0L);
		profiler.end(5_000L);
		profiler.endTick(1_000L);

		assertEquals(0L, profiler.sessionNanos(TickCategory.OTHER), "OTHER never goes negative");
		assertEquals(1L, profiler.overrunTicks());
		assertFalse(profiler.isConsistent(), "an overrun means a hook is wrong; do not report clean");
	}

	@Test
	void sessionTotalsAccumulateAcrossTicks() {
		profiler.begin(TickCategory.ENTITIES, null, 0L);
		profiler.end(100L);
		profiler.endTick(500L);

		profiler.beginTick(1_000L);
		profiler.begin(TickCategory.ENTITIES, null, 1_000L);
		profiler.end(1_300L);
		profiler.endTick(600L);

		assertEquals(400L, profiler.sessionNanos(TickCategory.ENTITIES));
		assertEquals(1_100L, profiler.sessionNanos(TickCategory.TOTAL));
		assertEquals(2L, profiler.sessionTicks());
		assertEquals(300L, profiler.tickNanos(TickCategory.ENTITIES), "still the last tick's view");

		profiler.beginTick(2_000L);
		assertEquals(0L, profiler.tickNanos(TickCategory.ENTITIES), "beginTick clears the per-tick view");
		assertEquals(400L, profiler.sessionNanos(TickCategory.ENTITIES), "but not the session");
	}

	// --- sampling on/off --------------------------------------------------------------------

	@Test
	void nothingIsRecordedWhileDisabled() {
		profiler.setEnabled(false);
		profiler.beginTick(0L);

		profiler.begin(TickCategory.ENTITIES, BOAT, 0L);
		profiler.end(1_000L);
		profiler.endTick(2_000L);

		assertEquals(0L, profiler.tickNanos(TickCategory.ENTITIES));
		assertEquals(0L, profiler.sessionTicks());
		assertTrue(recorded.isEmpty());
	}

	@Test
	void enablingTakesEffectOnlyAtATickBoundary() {
		// Flipping the flag mid-tick would leave a begin without its end and corrupt the stack.
		profiler.setEnabled(false);
		profiler.beginTick(0L);
		profiler.setEnabled(true);

		profiler.begin(TickCategory.ENTITIES, null, 0L);
		profiler.end(500L);

		assertFalse(profiler.isEnabled(), "still off for the rest of this tick");
		assertEquals(0L, profiler.tickNanos(TickCategory.ENTITIES));

		profiler.beginTick(1_000L);
		assertTrue(profiler.isEnabled());
		profiler.begin(TickCategory.ENTITIES, null, 1_000L);
		profiler.end(1_500L);
		assertEquals(500L, profiler.tickNanos(TickCategory.ENTITIES));
	}

	// --- misbehaving hooks --------------------------------------------------------------------

	@Test
	void anOverfullStackStaysBalancedAndKeepsTheTimeInTheParent() {
		int extra = 4;

		for (int i = 0; i < TickProfiler.MAX_DEPTH + extra; i++) {
			profiler.begin(TickCategory.ENTITIES, null, i);
		}

		assertEquals(TickProfiler.MAX_DEPTH + extra, profiler.depth());
		assertEquals(extra, profiler.droppedFrames());

		for (int i = 0; i < TickProfiler.MAX_DEPTH + extra; i++) {
			profiler.end(1_000L);
		}

		assertEquals(0, profiler.depth(), "every begin must still find its end");
		assertEquals(0L, profiler.unbalancedEnds());
		// The outermost frame started at 0 and closed at 1000; dropped frames inflate a parent's
		// self time rather than vanishing, so the category total is still the real span.
		assertEquals(1_000L, profiler.tickNanos(TickCategory.ENTITIES));
	}

	@Test
	void anEndWithoutABeginIsCountedNotThrown() {
		profiler.end(100L);

		assertEquals(1L, profiler.unbalancedEnds());
		assertFalse(profiler.isConsistent());
		assertEquals(0, profiler.depth());
	}

	@Test
	void aFrameLeftOpenByAThrowingTickIsDroppedAtTheNextTick() {
		profiler.begin(TickCategory.ENTITIES, null, 0L);
		// ... the tick throws here, end() never runs.
		profiler.endTick(1_000L);
		profiler.beginTick(2_000L);

		assertEquals(0, profiler.depth(), "the next tick must not inherit a corrupt stack");
		assertTrue(profiler.abandonedFrames() > 0L);
	}

	@Test
	void aClockThatGoesBackwardsCannotProduceNegativeTime() {
		profiler.begin(TickCategory.ENTITIES, null, 500L);
		profiler.end(100L);

		assertEquals(0L, profiler.tickNanos(TickCategory.ENTITIES));
	}

	// --- availability (AC-2: n/a, not zero) -----------------------------------------------

	@Test
	void categoriesAreUnavailableUntilAHookClaimsThem() {
		TickProfiler fresh = new TickProfiler();

		for (TickCategory category : TickCategory.all()) {
			assertFalse(fresh.isAvailable(category), category + " must start unavailable");
		}

		fresh.markAvailable(TickCategory.ENTITIES);
		assertTrue(fresh.isAvailable(TickCategory.ENTITIES));
		assertFalse(fresh.isAvailable(TickCategory.CHUNK_OPS));

		fresh.markUnavailable(TickCategory.ENTITIES);
		assertFalse(fresh.isAvailable(TickCategory.ENTITIES), "a failed hook withdraws its category");
	}

	@Test
	void resetSessionClearsTotalsButKeepsAvailability() {
		profiler.markAvailable(TickCategory.ENTITIES);
		profiler.begin(TickCategory.ENTITIES, null, 0L);
		profiler.end(100L);
		profiler.endTick(200L);

		profiler.resetSession();

		assertEquals(0L, profiler.sessionNanos(TickCategory.ENTITIES));
		assertEquals(0L, profiler.sessionNanos(TickCategory.TOTAL));
		assertEquals(0L, profiler.sessionTicks());
		assertTrue(profiler.isAvailable(TickCategory.ENTITIES));
		assertTrue(profiler.isConsistent());
	}

	// --- INV-6 ------------------------------------------------------------------------------

	@Test
	void theFrameStackIsAllocatedOnceAndReused() throws Exception {
		// INV-6 forbids per-entity, per-tick allocation. The stack arrays are the only state the
		// hot path writes to, so this asserts they are the same objects after a heavy run.
		String[] arrayFields = {"frameStartNanos", "frameChildNanos", "frameCategory", "frameKey",
				"tickNanos", "sessionNanos"};
		Object[] before = new Object[arrayFields.length];

		for (int i = 0; i < arrayFields.length; i++) {
			before[i] = read(profiler, arrayFields[i]);
		}

		for (int tick = 0; tick < 100; tick++) {
			profiler.beginTick(tick * 1_000L);

			for (int entity = 0; entity < 500; entity++) {
				profiler.begin(TickCategory.ENTITIES, BOAT, 0L);
				profiler.begin(TickCategory.ENTITIES, PLAYER, 1L);
				profiler.end(2L);
				profiler.end(3L);
			}

			profiler.endTick(10_000L);
		}

		for (int i = 0; i < arrayFields.length; i++) {
			assertSame(before[i], read(profiler, arrayFields[i]),
					arrayFields[i] + " was reallocated; the hot path must reuse it");
		}

		assertTrue(profiler.isConsistent());
		assertEquals(100L, profiler.sessionTicks());
	}

	@Test
	void aClosedFrameDoesNotKeepItsKeyAlive() throws Exception {
		// INV-7: nothing may outlive the world. A stale entity reference parked in the stack would.
		profiler.begin(TickCategory.ENTITIES, BOAT, 0L);
		profiler.end(100L);

		Object[] keys = (Object[]) read(profiler, "frameKey");

		for (Object key : keys) {
			assertSame(null, key, "the stack must not hold a reference after the frame closed");
		}
	}

	private static Object read(TickProfiler target, String fieldName) throws Exception {
		Field field = TickProfiler.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(target);
	}
}
