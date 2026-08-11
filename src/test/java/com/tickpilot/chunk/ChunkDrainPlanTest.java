package com.tickpilot.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * SPEC AC-10: the priority order, applied to a batch.
 *
 * <p>This is the part of the chunk gate with a decision in it. The rest of the gate reads a
 * {@code ChunkPos} off each task, which needs a running game and is covered by the manual scenario
 * in README.md instead.
 */
class ChunkDrainPlanTest {
	private static ChunkBudget capped(int max) {
		ChunkBudget budget = new ChunkBudget();
		budget.configure(true, true, max);
		budget.beginTick(1L);
		return budget;
	}

	private static byte[] batch(ChunkOpClass... classes) {
		// Deliberately longer than the batch: the real array is reused between drains and keeps
		// stale bytes past `size`, which decide() must not look at.
		byte[] out = new byte[classes.length + 8];

		for (int i = 0; i < classes.length; i++) {
			out[i] = (byte) classes[i].ordinal();
		}

		for (int i = classes.length; i < out.length; i++) {
			out[i] = (byte) ChunkOpClass.BACKGROUND.ordinal();
		}

		return out;
	}

	/**
	 * The reason the batch is walked once per class instead of once in list order: a background
	 * task at the front must not eat the allowance a nearer one needed.
	 */
	@Test
	void theAllowanceGoesToTheHighestPriorityWorkWhereverItSitsInTheBatch() {
		ChunkOpClass[] order = {
				ChunkOpClass.BACKGROUND,
				ChunkOpClass.BACKGROUND,
				ChunkOpClass.REMOTE_GENERATION,
		};
		byte[] classes = batch(order);
		ChunkBudget budget = capped(1);

		assertEquals(1, ChunkDrainPlan.decide(classes, order.length, budget));

		// The one slot went to the remote-generation task at the back, not to the background task
		// at the front.
		assertEquals(ChunkDrainPlan.HELD, classes[0]);
		assertEquals(ChunkDrainPlan.HELD, classes[1]);
		assertEquals(ChunkDrainPlan.ALLOWED, classes[2]);
	}

	@Test
	void everyProtectedClassIsAllowedBeforeTheAllowanceIsTouched() {
		ChunkOpClass[] order = {
				ChunkOpClass.REMOTE_GENERATION,
				ChunkOpClass.FORCE_LOADED,
				ChunkOpClass.PLAYER_LOADING,
				ChunkOpClass.PLAYER_TELEPORT,
		};
		byte[] classes = batch(order);
		ChunkBudget budget = capped(0);

		assertEquals(3, ChunkDrainPlan.decide(classes, order.length, budget));

		assertEquals(ChunkDrainPlan.HELD, classes[0]);
		assertEquals(ChunkDrainPlan.ALLOWED, classes[1]);
		assertEquals(ChunkDrainPlan.ALLOWED, classes[2]);
		assertEquals(ChunkDrainPlan.ALLOWED, classes[3]);
		assertTrue(budget.stats().heldPlayerCritical() == false);
	}

	@Test
	void decideIgnoresWhateverIsLeftInTheArrayPastTheBatch() {
		ChunkOpClass[] order = { ChunkOpClass.PLAYER_LOADING };
		byte[] classes = batch(order);
		ChunkBudget budget = capped(0);

		assertEquals(1, ChunkDrainPlan.decide(classes, order.length, budget));
		assertEquals(1L, budget.stats().dispatched());
		assertEquals(0L, budget.stats().held(), "the stale tail must not be counted");
	}

	// --- splitting the batch --------------------------------------------------------------------

	@Test
	void compactKeepsAllowedItemsAndMovesTheRestOutInOrder() {
		List<String> items = new ArrayList<>(List.of("a", "b", "c", "d", "e"));
		byte[] classes = {
				ChunkDrainPlan.ALLOWED, ChunkDrainPlan.HELD, ChunkDrainPlan.ALLOWED,
				ChunkDrainPlan.HELD, ChunkDrainPlan.HELD,
		};
		List<String> held = new ArrayList<>();

		ChunkDrainPlan.compact(items, classes, 5, held);

		assertEquals(List.of("a", "c"), items);
		assertEquals(List.of("b", "d", "e"), held);
	}

	@Test
	void compactHandlesAllAllowedAndAllHeld() {
		List<String> allAllowed = new ArrayList<>(List.of("a", "b"));
		List<String> held = new ArrayList<>();
		ChunkDrainPlan.compact(allAllowed, new byte[] { ChunkDrainPlan.ALLOWED,
				ChunkDrainPlan.ALLOWED }, 2, held);

		assertEquals(List.of("a", "b"), allAllowed);
		assertTrue(held.isEmpty());

		List<String> allHeld = new ArrayList<>(List.of("a", "b"));
		ChunkDrainPlan.compact(allHeld, new byte[] { ChunkDrainPlan.HELD, ChunkDrainPlan.HELD }, 2,
				held);

		assertTrue(allHeld.isEmpty());
		assertEquals(List.of("a", "b"), held);
	}

	/** Nothing may be lost between the two lists: what goes in comes out, once, somewhere. */
	@Test
	void nothingIsLostOrDuplicated() {
		for (int mask = 0; mask < 1 << 6; mask++) {
			List<String> items = new ArrayList<>(
					List.of("0", "1", "2", "3", "4", "5"));
			byte[] classes = new byte[6];

			for (int i = 0; i < 6; i++) {
				classes[i] = (mask >> i & 1) == 1 ? ChunkDrainPlan.ALLOWED : ChunkDrainPlan.HELD;
			}

			List<String> held = new ArrayList<>();
			ChunkDrainPlan.compact(items, classes, 6, held);

			List<String> rejoined = new ArrayList<>(items);
			rejoined.addAll(held);
			rejoined.sort(String::compareTo);

			assertEquals(List.of("0", "1", "2", "3", "4", "5"), rejoined, "mask " + mask);
		}
	}
}
