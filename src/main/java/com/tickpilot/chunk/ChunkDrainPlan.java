package com.tickpilot.chunk;

import java.util.List;

/**
 * Decides which of a batch of pending chunk operations run now and which wait, and splits the batch
 * accordingly (SPEC AC-10).
 *
 * <p>Split out of {@link ChunkGenerationGate} so that the only part of the chunk gate with a
 * decision in it can be unit-tested: this class names no Minecraft type, and
 * {@link #compact(List, byte[], int, List)} is generic precisely so a test can run it over a list of
 * strings. What is left in the gate is reading a {@code ChunkPos} off each task and calling these
 * two methods.
 *
 * <p>The batch is described by a {@code byte[]} of {@link ChunkOpClass#ordinal()} values rather than
 * by an array of enums or a list of records, because this runs on every chunk generation drain —
 * hundreds of times a tick on a server with time to spare — and must not allocate (SPEC INV-6).
 */
final class ChunkDrainPlan {
	/** Marker written over a class when the operation may run now. */
	static final byte ALLOWED = -1;

	/** Marker written over a class when the operation waits for a later drain. */
	static final byte HELD = -2;

	private ChunkDrainPlan() {
	}

	/**
	 * Puts every operation in the batch through the budget, in SPEC AC-10 priority order, and
	 * replaces its class in {@code classes} with {@link #ALLOWED} or {@link #HELD}.
	 *
	 * <p>The order is the whole point of doing this in five passes instead of one. Walking the batch
	 * once, in list order, would let a background operation near the front consume the allowance
	 * that an operation nearer a player needed — the priority list of AC-10 would be written down
	 * and then not applied.
	 *
	 * @param classes each operation's {@link ChunkOpClass#ordinal()}; overwritten with the markers
	 * @param size    how much of {@code classes} is in use
	 * @param budget  the budget to consult
	 * @return how many operations may run now
	 */
	static int decide(byte[] classes, int size, ChunkBudget budget) {
		int allowed = 0;

		for (ChunkOpClass opClass : ChunkOpClass.all()) {
			byte ordinal = (byte) opClass.ordinal();

			for (int i = 0; i < size; i++) {
				if (classes[i] != ordinal) {
					continue;
				}

				if (budget.allow(opClass)) {
					classes[i] = ALLOWED;
					allowed++;
				} else {
					classes[i] = HELD;
				}
			}
		}

		return allowed;
	}

	/**
	 * Splits the batch in place: {@code items} is left holding only what may run now, and everything
	 * else is appended to {@code heldOut}. Both keep their original relative order, so nothing is
	 * reordered relative to how Minecraft queued it.
	 *
	 * @param items    the batch, mutated in place
	 * @param classes  the markers {@link #decide} wrote
	 * @param size     how many entries of {@code items} the markers describe
	 * @param heldOut  receives everything not allowed, appended
	 */
	static <T> void compact(List<T> items, byte[] classes, int size, List<T> heldOut) {
		int write = 0;

		for (int i = 0; i < size; i++) {
			T item = items.get(i);

			if (classes[i] == ALLOWED) {
				items.set(write++, item);
			} else {
				heldOut.add(item);
			}
		}

		if (write < size) {
			items.subList(write, size).clear();
		}
	}
}
