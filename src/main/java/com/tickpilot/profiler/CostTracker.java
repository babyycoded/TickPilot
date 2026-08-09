package com.tickpilot.profiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates profiler self time by type for SPEC FR-3 and AC-3.
 *
 * <h2>Allocation</h2>
 * One {@code long[2]} per type, allocated the first time that type is ever seen and reused for the
 * life of the session. A tick that only touches types already seen allocates nothing:
 * {@link IdentityHashMap#get} does not allocate, and the counters are updated in place (SPEC
 * INV-6). Identity comparison is correct here because the keys are registry singletons —
 * {@code EntityType} and {@code BlockEntityType} instances — and it is cheaper than equality.
 *
 * <h2>Why the keys stay {@link Object}</h2>
 * So this class, like the rest of {@code com.tickpilot.profiler}, imports nothing from
 * {@code net.minecraft} and is unit-tested without the game. Turning a key into a readable name
 * needs a registry lookup and builds a string, so it happens in the command that prints the
 * report, never in the tick loop.
 *
 * <h2>Lifetime</h2>
 * Owned by {@code TickPilotServerState} and cleared by {@link #reset()} when a profiling session
 * ends, so nothing survives into another session or another world (SPEC AC-3, INV-7).
 */
public final class CostTracker implements CostSink {
	private static final int TOTAL_NANOS = 0;
	private static final int INVOCATIONS = 1;

	private final Map<Object, long[]> entityCosts = new IdentityHashMap<>();
	private final Map<Object, long[]> blockEntityCosts = new IdentityHashMap<>();

	/**
	 * One type's share of a category.
	 *
	 * @param key         the registry object the cost was charged to
	 * @param totalNanos  summed self time over the session
	 * @param invocations how many times the type was ticked over the session
	 */
	public record TypeCost(Object key, long totalNanos, long invocations) {
		/** @return mean self time of a single tick of one instance, in nanoseconds */
		public double averageNanos() {
			return invocations == 0L ? 0.0 : (double) totalNanos / invocations;
		}
	}

	@Override
	public void record(TickCategory category, Object key, long selfNanos) {
		Map<Object, long[]> costs = costsFor(category);

		if (costs == null) {
			return;
		}

		long[] cell = costs.get(key);

		if (cell == null) {
			// Once per type per session, never per tick.
			cell = new long[2];
			costs.put(key, cell);
		}

		cell[TOTAL_NANOS] += selfNanos;
		cell[INVOCATIONS]++;
	}

	/**
	 * @param category    {@link TickCategory#ENTITIES} or {@link TickCategory#BLOCK_ENTITIES}
	 * @param limit       how many rows to return
	 * @return the costliest types, most expensive first; empty for any other category
	 */
	public List<TypeCost> top(TickCategory category, int limit) {
		Map<Object, long[]> costs = costsFor(category);

		if (costs == null || costs.isEmpty() || limit <= 0) {
			return List.of();
		}

		List<TypeCost> rows = new ArrayList<>(costs.size());

		for (Map.Entry<Object, long[]> entry : costs.entrySet()) {
			rows.add(new TypeCost(entry.getKey(), entry.getValue()[TOTAL_NANOS],
					entry.getValue()[INVOCATIONS]));
		}

		rows.sort(Comparator.comparingLong(TypeCost::totalNanos).reversed());
		return List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
	}

	/** @return how many distinct types the category has seen this session */
	public int trackedTypes(TickCategory category) {
		Map<Object, long[]> costs = costsFor(category);
		return costs == null ? 0 : costs.size();
	}

	/** Drops everything. Called when a profiling session ends (SPEC AC-3). */
	public void reset() {
		entityCosts.clear();
		blockEntityCosts.clear();
	}

	private Map<Object, long[]> costsFor(TickCategory category) {
		return switch (category) {
			case ENTITIES -> entityCosts;
			case BLOCK_ENTITIES -> blockEntityCosts;
			default -> null;
		};
	}
}
