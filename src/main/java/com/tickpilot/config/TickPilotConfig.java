package com.tickpilot.config;

import java.util.List;

/**
 * An immutable, already-validated snapshot of {@code config/tickpilot.toml} (SPEC FR-15).
 *
 * <p>Every instance that leaves {@link ConfigLoader} satisfies the invariants the validator
 * enforces, so consumers may use the values directly without re-checking them:
 *
 * <ul>
 *   <li>{@code 0 < targetMspt < criticalMspt}, {@code reserveMspt >= 0}, all finite;</li>
 *   <li>{@code 0 < fullRadius < reducedRadius};</li>
 *   <li>every interval, limit and buffer size is at least 1;</li>
 *   <li>{@code logSlowOperationsAboveMs > 0} and finite;</li>
 *   <li>{@code defaultMode} is one of the three {@link AdaptiveMode} constants;</li>
 *   <li>every list is immutable and free of blank entries.</li>
 * </ul>
 *
 * <p>No {@code net.minecraft} import: the file is read as text and the result is plain data, so
 * the whole config subsystem is unit-tested without launching the game (SPEC §8).
 *
 * @param targetMspt                       ms per tick at which the load level leaves NORMAL
 * @param criticalMspt                     ms per tick at which the load level becomes CRITICAL
 * @param reserveMspt                      ms per tick the scheduler keeps free
 * @param fullRadius                       blocks within which an object always ticks in full
 * @param reducedRadius                    blocks beyond which an object may be thinned the most
 * @param minEntityUpdateIntervalTicks     smallest entity update interval, in ticks
 * @param minBlockEntityUpdateIntervalTicks smallest block entity update interval, in ticks
 * @param enableAdaptiveMode               whether adaptive behaviour is enabled at all
 * @param defaultMode                      intervention mode the server starts in
 * @param maxDeferredTasks                 hard cap on the scheduler queue
 * @param enableChunkBudget                whether the chunk operation cap applies at all
 * @param maxChunkOperationsPerTick        cap on optional chunk operations per tick
 * @param profileBufferSize                number of samples the profiler retains
 * @param logSlowOperationsAboveMs         threshold above which a slow operation is logged
 * @param samplingEnabled                  whether the sampling profiler starts enabled
 * @param singleplayerEnabled              whether TickPilot runs on an integrated server
 * @param clientHudEnabled                 whether the optional client HUD is drawn
 * @param integratedServerOptimizations    whether optimizations apply on an integrated server
 * @param safeCompatibilityMode            {@code true} forces {@link AdaptiveMode#STRICT}
 * @param excludedEntityIds                entity ids TickPilot ignores entirely
 * @param excludedBlockEntityIds           block entity ids TickPilot ignores entirely
 * @param excludedModIds                   mod namespaces TickPilot ignores entirely
 * @param throttleAllowlist                the only types that may ever be thinned (SPEC INV-5)
 * @param throttleDenylist                 types never touched; outranks the allowlist
 */
public record TickPilotConfig(
		double targetMspt,
		double criticalMspt,
		double reserveMspt,
		int fullRadius,
		int reducedRadius,
		int minEntityUpdateIntervalTicks,
		int minBlockEntityUpdateIntervalTicks,
		boolean enableAdaptiveMode,
		AdaptiveMode defaultMode,
		int maxDeferredTasks,
		boolean enableChunkBudget,
		int maxChunkOperationsPerTick,
		int profileBufferSize,
		double logSlowOperationsAboveMs,
		boolean samplingEnabled,
		boolean singleplayerEnabled,
		boolean clientHudEnabled,
		boolean integratedServerOptimizations,
		boolean safeCompatibilityMode,
		List<String> excludedEntityIds,
		List<String> excludedBlockEntityIds,
		List<String> excludedModIds,
		List<String> throttleAllowlist,
		List<String> throttleDenylist) {

	/** Defaults exactly as printed in the SPEC FR-15 schema. */
	public static final double DEFAULT_TARGET_MSPT = 40.0;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final double DEFAULT_CRITICAL_MSPT = 50.0;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final double DEFAULT_RESERVE_MSPT = 10.0;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final int DEFAULT_FULL_RADIUS = 32;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final int DEFAULT_REDUCED_RADIUS = 96;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final int DEFAULT_MIN_ENTITY_UPDATE_INTERVAL_TICKS = 1;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final int DEFAULT_MIN_BLOCK_ENTITY_UPDATE_INTERVAL_TICKS = 1;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final boolean DEFAULT_ENABLE_ADAPTIVE_MODE = true;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final AdaptiveMode DEFAULT_MODE = AdaptiveMode.BALANCED;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final int DEFAULT_MAX_DEFERRED_TASKS = 10_000;
	/**
	 * Off, because SPEC INV-3 requires every contentious optimization to be off by default and to
	 * have a flag of its own. FR-15 ships {@code max_chunk_operations_per_tick} without such a flag,
	 * so this key is an addition to the schema — SPEC §13 entry #19.
	 */
	public static final boolean DEFAULT_ENABLE_CHUNK_BUDGET = false;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final int DEFAULT_MAX_CHUNK_OPERATIONS_PER_TICK = 8;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final int DEFAULT_PROFILE_BUFFER_SIZE = 1200;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final double DEFAULT_LOG_SLOW_OPERATIONS_ABOVE_MS = 2.0;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final boolean DEFAULT_SAMPLING_ENABLED = false;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final boolean DEFAULT_SINGLEPLAYER_ENABLED = true;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final boolean DEFAULT_CLIENT_HUD_ENABLED = false;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final boolean DEFAULT_INTEGRATED_SERVER_OPTIMIZATIONS = true;
	/** @see #DEFAULT_TARGET_MSPT */
	public static final boolean DEFAULT_SAFE_COMPATIBILITY_MODE = false;

	private static final TickPilotConfig DEFAULTS = new TickPilotConfig(
			DEFAULT_TARGET_MSPT,
			DEFAULT_CRITICAL_MSPT,
			DEFAULT_RESERVE_MSPT,
			DEFAULT_FULL_RADIUS,
			DEFAULT_REDUCED_RADIUS,
			DEFAULT_MIN_ENTITY_UPDATE_INTERVAL_TICKS,
			DEFAULT_MIN_BLOCK_ENTITY_UPDATE_INTERVAL_TICKS,
			DEFAULT_ENABLE_ADAPTIVE_MODE,
			DEFAULT_MODE,
			DEFAULT_MAX_DEFERRED_TASKS,
			DEFAULT_ENABLE_CHUNK_BUDGET,
			DEFAULT_MAX_CHUNK_OPERATIONS_PER_TICK,
			DEFAULT_PROFILE_BUFFER_SIZE,
			DEFAULT_LOG_SLOW_OPERATIONS_ABOVE_MS,
			DEFAULT_SAMPLING_ENABLED,
			DEFAULT_SINGLEPLAYER_ENABLED,
			DEFAULT_CLIENT_HUD_ENABLED,
			DEFAULT_INTEGRATED_SERVER_OPTIMIZATIONS,
			DEFAULT_SAFE_COMPATIBILITY_MODE,
			List.of(), List.of(), List.of(), List.of(), List.of());

	/** Copies the lists so a caller cannot mutate a config after it has been validated. */
	public TickPilotConfig {
		excludedEntityIds = List.copyOf(excludedEntityIds);
		excludedBlockEntityIds = List.copyOf(excludedBlockEntityIds);
		excludedModIds = List.copyOf(excludedModIds);
		throttleAllowlist = List.copyOf(throttleAllowlist);
		throttleDenylist = List.copyOf(throttleDenylist);
	}

	/**
	 * @return the SPEC FR-15 defaults. This is what runs when the file is missing, unreadable or
	 *         unparseable (SPEC AC-15).
	 */
	public static TickPilotConfig defaults() {
		return DEFAULTS;
	}

	/**
	 * The mode actually in force, which is not always {@link #defaultMode()}: the SPEC FR-15
	 * schema defines {@code safe_compatibility_mode = true} as forcing {@link AdaptiveMode#STRICT}.
	 *
	 * @return the mode the policies must obey
	 */
	public AdaptiveMode effectiveMode() {
		return safeCompatibilityMode ? AdaptiveMode.STRICT : defaultMode;
	}
}
