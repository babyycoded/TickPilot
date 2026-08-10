package com.tickpilot.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Counts what {@link TickPolicy} decided, without acting on any of it (SPEC FR-8, FR-9, INV-3).
 *
 * <h2>Why counting comes before doing</h2>
 * This is the diagnostic half of the entity and block entity policies. Every candidate object is
 * put through the policy and the verdict is tallied here; nothing is skipped, no control flow of
 * the game is touched. That turns the question "what would throttling do on this server" into a
 * number an operator can read <em>before</em> anything changes, and gives the second half a
 * measured baseline to be compared against instead of an expectation.
 *
 * <h2>Cost</h2>
 * Two array increments per object per tick, no allocation, no clock (SPEC INV-6). The arrays are
 * indexed by {@link ThrottleVerdict#ordinal()} and sized once.
 *
 * <p>Server thread only. No {@code net.minecraft} import.
 */
public final class PolicyDiagnostics {
	private final long[] entityVerdicts = new long[ThrottleVerdict.all().length];
	private final long[] blockEntityVerdicts = new long[ThrottleVerdict.all().length];

	private long entitiesSeen;
	private long blockEntitiesSeen;
	private long aiConsidered;
	private long aiSkipped;
	private long ticks;

	/**
	 * Tallies one entity's verdict. Hot path.
	 *
	 * @param verdict what the policy decided
	 */
	public void recordEntity(ThrottleVerdict verdict) {
		entityVerdicts[verdict.ordinal()]++;
		entitiesSeen++;
	}

	/**
	 * Tallies one block entity's verdict. Hot path.
	 *
	 * @param verdict what the policy decided
	 */
	public void recordBlockEntity(ThrottleVerdict verdict) {
		blockEntityVerdicts[verdict.ordinal()]++;
		blockEntitiesSeen++;
	}

	/**
	 * Tallies one AI decision — the only place where TickPilot actually changes what the game does
	 * (SPEC FR-8). Hot path.
	 *
	 * @param skipped whether this mob's AI step was skipped on this tick
	 */
	public void recordAiDecision(boolean skipped) {
		aiConsidered++;

		if (skipped) {
			aiSkipped++;
		}
	}

	/** @return mob AI steps that reached the thinning schedule, per tick */
	public double aiConsideredPerTick() {
		return perTick(aiConsidered);
	}

	/** @return mob AI steps actually skipped, per tick. The one number that is not hypothetical */
	public double aiSkippedPerTick() {
		return perTick(aiSkipped);
	}

	/** @return mob AI steps actually skipped in total */
	public long aiSkipped() {
		return aiSkipped;
	}

	/** Counts one tick, so the totals can be reported per tick rather than as raw sums. */
	public void onTick() {
		ticks++;
	}

	/** @return ticks counted since the last {@link #reset()} */
	public long ticks() {
		return ticks;
	}

	/** @return entity decisions taken in total */
	public long entitiesSeen() {
		return entitiesSeen;
	}

	/** @return block entity decisions taken in total */
	public long blockEntitiesSeen() {
		return blockEntitiesSeen;
	}

	/**
	 * @param verdict the verdict to count
	 * @return how many entity decisions ended in it
	 */
	public long entityCount(ThrottleVerdict verdict) {
		return entityVerdicts[verdict.ordinal()];
	}

	/**
	 * @param verdict the verdict to count
	 * @return how many block entity decisions ended in it
	 */
	public long blockEntityCount(ThrottleVerdict verdict) {
		return blockEntityVerdicts[verdict.ordinal()];
	}

	/** @return entity decisions per tick that reached an eligible verdict */
	public double eligibleEntitiesPerTick() {
		return perTick(entityVerdicts[ThrottleVerdict.ELIGIBLE_REDUCED.ordinal()]
				+ entityVerdicts[ThrottleVerdict.ELIGIBLE_FROZEN.ordinal()]);
	}

	/** @return block entity decisions per tick that reached an eligible verdict */
	public double eligibleBlockEntitiesPerTick() {
		return perTick(blockEntityVerdicts[ThrottleVerdict.ELIGIBLE_REDUCED.ordinal()]
				+ blockEntityVerdicts[ThrottleVerdict.ELIGIBLE_FROZEN.ordinal()]);
	}

	/**
	 * The verdict that stopped the most objects, ignoring the eligible ones.
	 *
	 * @param entities {@code true} for the entity tally, {@code false} for block entities
	 * @return the commonest blocking verdict, or {@code null} when nothing was counted
	 */
	public ThrottleVerdict dominantBlocker(boolean entities) {
		List<Blocker> blockers = blockersDescending(entities);
		return blockers.isEmpty() ? null : blockers.get(0).verdict();
	}

	/**
	 * One reason objects were not thinned, with how often it applied.
	 *
	 * @param verdict  the reason
	 * @param perTick  how many objects per tick it applied to
	 * @param total    how many objects it applied to in total
	 */
	public record Blocker(ThrottleVerdict verdict, double perTick, long total) {
	}

	/**
	 * Every reason objects were not thinned, commonest first.
	 *
	 * <p>The whole breakdown rather than only the top one, because the top one is often not the
	 * interesting one: "most of them were protected" is a fact an operator can do nothing with,
	 * while the line below it may be the one that says the allowlist is empty. Built on the command
	 * path, never per tick.
	 *
	 * @param entities {@code true} for the entity tally, {@code false} for block entities
	 * @return the blocking verdicts that occurred at least once, sorted by count descending
	 */
	public List<Blocker> blockersDescending(boolean entities) {
		long[] counts = entities ? entityVerdicts : blockEntityVerdicts;
		List<Blocker> blockers = new ArrayList<>();

		for (ThrottleVerdict verdict : ThrottleVerdict.all()) {
			if (verdict.isEligible()) {
				continue;
			}

			long count = counts[verdict.ordinal()];

			if (count > 0L) {
				blockers.add(new Blocker(verdict, perTick(count), count));
			}
		}

		blockers.sort(Comparator.comparingLong(Blocker::total).reversed());
		return blockers;
	}

	/** @return {@code true} when no object has been put through the policy yet */
	public boolean isEmpty() {
		return entitiesSeen == 0L && blockEntitiesSeen == 0L;
	}

	private double perTick(long total) {
		return ticks <= 0L ? 0.0 : (double) total / ticks;
	}

	/** Clears every tally. Called when the server stops and when a fresh measurement is wanted. */
	public void reset() {
		java.util.Arrays.fill(entityVerdicts, 0L);
		java.util.Arrays.fill(blockEntityVerdicts, 0L);
		entitiesSeen = 0L;
		blockEntitiesSeen = 0L;
		aiConsidered = 0L;
		aiSkipped = 0L;
		ticks = 0L;
	}
}
