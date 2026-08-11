package com.tickpilot.chunk;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Holds part of one {@code ChunkMap}'s pending chunk generation back for a later drain
 * (SPEC FR-10, AC-10).
 *
 * <p>One instance per {@code ChunkMap}, owned by the Mixin, so it dies with its world and holds no
 * state across worlds (SPEC INV-7). Everything it can hold is a task Minecraft itself created and
 * would have dispatched a moment later.
 *
 * <h2>Nothing is dropped, and there is no queue of our own</h2>
 * {@code ChunkMap.pendingGenerationTasks} is vanilla's own list: it fills it in
 * {@code scheduleGenerationTask} and empties it in {@code runGenerationTasks}. This class moves the
 * tasks it will not allow yet out of that list before vanilla drains it and puts them straight back
 * afterwards, at the front, so they are first in line next time. There is no second queue, no
 * deadline to miss and no task that can be lost — the worst case is a task dispatched a few ticks
 * late, and the two releases in {@link ChunkBudget} bound how late.
 *
 * <h2>Order of dispatch is the SPEC AC-10 priority order</h2>
 * Every pending task is classified once into a {@code byte[]}, and {@link ChunkDrainPlan} then
 * applies the budget to that array in priority order. Classifying once and scanning five times
 * beats classifying five times, because a classification walks the players and the protected
 * regions while a scan is a byte compare (SPEC INV-6). The decision itself lives in
 * {@link ChunkDrainPlan} rather than here so that it can be unit-tested without the game.
 */
public final class ChunkGenerationGate {
	private final List<ChunkGenerationTask> held = new ArrayList<>();

	private byte[] classes = new byte[64];

	/**
	 * Moves everything the budget will not allow yet out of {@code pending}. Called at the head of
	 * {@code ChunkMap.runGenerationTasks}.
	 *
	 * @param level   the world this chunk map belongs to
	 * @param pending vanilla's pending task list, mutated in place
	 */
	public void beforeDrain(ServerLevel level, List<ChunkGenerationTask> pending) {
		// Belt and braces: a drain that threw would never have reached afterDrain, and switching the
		// feature off must release whatever was held immediately rather than at the next lift.
		if (!held.isEmpty()) {
			pending.addAll(0, held);
			held.clear();
		}

		ChunkBudget budget = ChunkBudgetHook.active();

		if (budget == null || pending.isEmpty()) {
			return;
		}

		ChunkBudgetTracker tracker = ChunkBudgetHook.tracker();
		budget.beginDrain();

		int size = pending.size();

		if (classes.length < size) {
			classes = new byte[Math.max(size, classes.length * 2)];
		}

		for (int i = 0; i < size; i++) {
			ChunkPos pos = pending.get(i).getCenter().getPos();
			classes[i] = (byte) tracker.classify(level, pos.x, pos.z).ordinal();
		}

		int allowed = ChunkDrainPlan.decide(classes, size, budget);
		ChunkDrainPlan.compact(pending, classes, size, held);

		// A drain that moved even one task forward is not stalled, whatever it held back. Only a run
		// of drains that dispatched nothing at all while work waits looks like a blocked server
		// thread, and only its duration tells that apart from an idle server polling between ticks.
		boolean workPending = !held.isEmpty();
		budget.endDrain(allowed > 0, workPending, workPending ? System.nanoTime() : 0L);
	}

	/**
	 * Puts whatever was held back into the list vanilla has just emptied. Called at the return of
	 * {@code ChunkMap.runGenerationTasks}.
	 *
	 * @param pending vanilla's pending task list, mutated in place
	 */
	public void afterDrain(List<ChunkGenerationTask> pending) {
		if (held.isEmpty()) {
			return;
		}

		pending.addAll(held);
		held.clear();
	}
}
