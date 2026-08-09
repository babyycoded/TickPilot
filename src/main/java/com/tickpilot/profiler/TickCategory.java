package com.tickpilot.profiler;

/**
 * The tick cost categories of SPEC FR-2.
 *
 * <p>{@link #TOTAL} is measured by {@code TickMetrics} around the whole tick and is not produced
 * by the profiler. {@link #OTHER} is never measured either: it is what is left of TOTAL once every
 * measured category has been subtracted, which is exactly what AC-2 asks for.
 *
 * <p>A category with no safe injection point stays unavailable and is reported as {@code n/a}
 * rather than as zero (AC-2). {@link TickProfiler#isAvailable} is the authority on that.
 */
public enum TickCategory {
	/** Whole tick. Owned by {@code TickMetrics}, not by the profiler. */
	TOTAL,

	/** Entity ticking, including the passengers of each entity. */
	ENTITIES,

	/** Block entity ticking, including the cost of walking the ticker list. */
	BLOCK_ENTITIES,

	/** Scheduled block and fluid ticks. */
	SCHEDULED_TICKS,

	/** Chunk environment ticking: random block and fluid ticks, ice and snow, lightning, rain. */
	RANDOM_TICKS,

	/** Chunk loading, unloading, ticket and distance management, mob spawning. */
	CHUNK_OPS,

	/** Autosave. */
	SAVING,

	/** Connection processing. */
	NETWORK,

	/** TOTAL minus everything measured. Derived, never timed. */
	OTHER;

	/** Cached to avoid the defensive copy {@code values()} makes on every call. */
	private static final TickCategory[] VALUES = values();

	/** Number of categories, for sizing the accumulator arrays. */
	public static final int COUNT = VALUES.length;

	/** @return the shared array of constants; callers must not modify it */
	public static TickCategory[] all() {
		return VALUES;
	}

	/** @return the translation key for this category's display name */
	public String translationKey() {
		return "tickpilot.category." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
