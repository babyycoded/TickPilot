package com.tickpilot.budget;

/**
 * Turns smoothed MSPT into a {@link LoadLevel} without letting it chatter (SPEC FR-5, AC-5).
 *
 * <h2>Thresholds</h2>
 * With {@code target} and {@code critical} from the config (SPEC FR-15) and
 * {@code mid = target + (critical - target) * 0.5}:
 *
 * <pre>
 * NORMAL    avg &lt; target
 * ELEVATED  target &lt;= avg &lt; mid
 * HIGH      mid    &lt;= avg &lt; critical
 * CRITICAL  avg    &gt;= critical
 * </pre>
 *
 * The HIGH boundary is a fraction of the {@code target -> critical} interval rather than
 * "target + 25 %"; see SPEC §13 entry #7 for why the original formula collapsed the HIGH band
 * to nothing at the default values.
 *
 * <h2>Anti-chatter</h2>
 * Two independent mechanisms, both required by AC-5:
 * <ul>
 *   <li><b>Hysteresis.</b> Entering a level happens at its threshold; leaving it downwards needs
 *       MSPT to fall {@code hysteresisMspt} <em>below</em> that threshold, so a value sitting
 *       exactly on the boundary cannot toggle.</li>
 *   <li><b>Minimum hold time.</b> A level cannot be left downwards until it has been held for
 *       {@code minHoldMillis}.</li>
 * </ul>
 * Escalation is deliberately immediate: the input is already a smoothed average, so a single
 * slow tick cannot trigger it, and delaying the reaction to a server that is actually degrading
 * buys nothing. Chatter is impossible anyway, because getting back down is what is damped.
 *
 * <h2>Warm-up</h2>
 * For {@code warmupMillis} after construction the level is pinned at {@link LoadLevel#NORMAL} and
 * no transition is reported. The first tick of a freshly started server genuinely costs on the
 * order of a hundred milliseconds, and because the input is a 5 s average that one tick keeps the
 * average above {@code critical} for the next five seconds — so every single server start logged
 * {@code NORMAL -> CRITICAL} and then recovered, which is precisely the noise AC-16 exists to
 * prevent. The measurement is not touched: {@code TickMetrics} still records the slow tick and
 * {@code status} still shows it as the max. Only the <em>decision</em> waits until the smoothing
 * window is made of ticks from a server that has finished starting.
 *
 * <p>The window is longer than the 5 s the average needs, because a start is rarely slow for
 * exactly one tick. It applies to a newly created budget only — rebuilding one for a config
 * reload passes zero, since a running server is already warm.
 *
 * <h2>Why no {@code net.minecraft} import</h2>
 * Pure arithmetic and a clock value passed in by the caller, so the state machine is unit-tested
 * without launching the game (SPEC §8). {@code com.tickpilot.TickPilotTickListener} drives it.
 */
public final class TickBudget {
	/** Default target MSPT (SPEC FR-15). */
	public static final double DEFAULT_TARGET_MSPT = 40.0;

	/** Default critical MSPT (SPEC FR-15). */
	public static final double DEFAULT_CRITICAL_MSPT = 50.0;

	/** Where HIGH starts, as a fraction of the {@code target -> critical} interval. */
	public static final double HIGH_THRESHOLD_FRACTION = 0.5;

	/** How far below a threshold MSPT must fall before the level is left downwards. */
	public static final double DEFAULT_HYSTERESIS_MSPT = 2.0;

	/** How long a level is held before it may be left downwards. */
	public static final long DEFAULT_MIN_HOLD_MILLIS = 5_000L;

	/**
	 * How long after startup the level stays pinned at {@link LoadLevel#NORMAL}.
	 *
	 * <p>Twice the 5 s smoothing window: five seconds is the minimum for the startup spike to age
	 * out of the average, and the margin covers a start that is slow for more than one tick.
	 */
	public static final long DEFAULT_WARMUP_MILLIS = 10_000L;

	private final double targetMspt;
	private final double criticalMspt;
	private final double highMspt;
	private final double hysteresisMspt;
	private final long minHoldMillis;
	private final long warmupUntilMillis;

	private LoadLevel level = LoadLevel.NORMAL;
	private long levelSinceMillis;
	private boolean warmedUp;

	/**
	 * Creates a budget with the SPEC defaults, starting in {@link LoadLevel#NORMAL} and warming up
	 * for {@link #DEFAULT_WARMUP_MILLIS}.
	 *
	 * @param nowMillis current wall clock, used as the start of the initial hold period
	 */
	public TickBudget(long nowMillis) {
		this(DEFAULT_TARGET_MSPT, DEFAULT_CRITICAL_MSPT, DEFAULT_HYSTERESIS_MSPT,
				DEFAULT_MIN_HOLD_MILLIS, DEFAULT_WARMUP_MILLIS, nowMillis);
	}

	/**
	 * @param targetMspt     MSPT at which ELEVATED begins; must be positive
	 * @param criticalMspt   MSPT at which CRITICAL begins; must be greater than {@code targetMspt}
	 * @param hysteresisMspt margin a threshold must be undershot by before dropping a level;
	 *                       must not be negative
	 * @param minHoldMillis  minimum time a level is held before it may be left downwards;
	 *                       must not be negative
	 * @param warmupMillis   how long the level stays pinned at {@link LoadLevel#NORMAL} after
	 *                       {@code nowMillis}; must not be negative. Pass 0 when the server is
	 *                       already running, e.g. when rebuilding for a config reload.
	 * @param nowMillis      current wall clock
	 * @throws IllegalArgumentException if the thresholds do not describe an ordered, non-empty
	 *                                  set of bands. Config validation (SPEC AC-15) rejects such
	 *                                  values before they reach this constructor.
	 */
	public TickBudget(double targetMspt, double criticalMspt, double hysteresisMspt,
			long minHoldMillis, long warmupMillis, long nowMillis) {
		if (!(targetMspt > 0.0)) {
			throw new IllegalArgumentException("target_mspt must be positive, got " + targetMspt);
		}

		if (!(criticalMspt > targetMspt)) {
			throw new IllegalArgumentException(
					"critical_mspt (" + criticalMspt + ") must be greater than target_mspt (" + targetMspt + ")");
		}

		if (!(hysteresisMspt >= 0.0)) {
			throw new IllegalArgumentException("hysteresis must not be negative, got " + hysteresisMspt);
		}

		if (minHoldMillis < 0L) {
			throw new IllegalArgumentException("min hold must not be negative, got " + minHoldMillis);
		}

		if (warmupMillis < 0L) {
			throw new IllegalArgumentException("warmup must not be negative, got " + warmupMillis);
		}

		this.targetMspt = targetMspt;
		this.criticalMspt = criticalMspt;
		this.highMspt = targetMspt + (criticalMspt - targetMspt) * HIGH_THRESHOLD_FRACTION;
		this.hysteresisMspt = hysteresisMspt;
		this.minHoldMillis = minHoldMillis;
		this.levelSinceMillis = nowMillis;
		this.warmupUntilMillis = nowMillis + warmupMillis;
		this.warmedUp = warmupMillis == 0L;
	}

	/**
	 * Feeds one smoothed MSPT reading in. Called once per tick; allocates only on an actual
	 * transition (SPEC INV-6).
	 *
	 * @param avgMspt   smoothed average MSPT, normally the 5 s window of {@code TickMetrics}
	 * @param nowMillis current wall clock, monotonically non-decreasing across calls
	 * @return the transition that just happened, or {@code null} if the level did not change.
	 *         The caller logs it once (SPEC AC-5); nothing here logs.
	 */
	public LoadLevelTransition update(double avgMspt, long nowMillis) {
		if (!warmedUp) {
			if (nowMillis < warmupUntilMillis) {
				// Pinned at NORMAL, and no transition to report: a starting server is not a
				// loaded one, and saying so once per start is worse than saying nothing.
				return null;
			}

			// Latched, so a clock that steps backwards cannot put the budget back into warm-up.
			// levelSinceMillis is deliberately left alone: NORMAL really has been held since
			// construction, and nothing sits below it for the hold time to protect anyway.
			warmedUp = true;
		}

		LoadLevel raw = levelFor(avgMspt);
		LoadLevel next;

		if (raw.ordinal() > level.ordinal()) {
			next = raw;
		} else if (raw.ordinal() < level.ordinal()) {
			if (nowMillis - levelSinceMillis < minHoldMillis) {
				return null;
			}

			// Re-evaluate with every threshold lowered by the hysteresis margin. This both keeps
			// a value hovering on the boundary from dropping a level and stops it from falling
			// two levels at once through a band it has not really left.
			next = levelForWithHysteresis(avgMspt);

			if (next.ordinal() >= level.ordinal()) {
				return null;
			}
		} else {
			return null;
		}

		LoadLevel previous = level;
		level = next;
		levelSinceMillis = nowMillis;
		return new LoadLevelTransition(previous, next, avgMspt, nowMillis);
	}

	/** @return the level currently held */
	public LoadLevel level() {
		return level;
	}

	/**
	 * @param nowMillis current wall clock
	 * @return how long the current level has been held, in milliseconds
	 */
	public long heldForMillis(long nowMillis) {
		return nowMillis - levelSinceMillis;
	}

	/**
	 * @param nowMillis current wall clock
	 * @return {@code true} while the level is pinned at {@link LoadLevel#NORMAL} because the
	 *         server has only just started. {@code status} says so out loud, so a pinned NORMAL
	 *         is never mistaken for a measured one.
	 */
	public boolean isWarmingUp(long nowMillis) {
		return !warmedUp && nowMillis < warmupUntilMillis;
	}

	/**
	 * @param nowMillis current wall clock
	 * @return milliseconds until the level starts tracking the measurements, or 0 if it already does
	 */
	public long warmupRemainingMillis(long nowMillis) {
		return isWarmingUp(nowMillis) ? warmupUntilMillis - nowMillis : 0L;
	}

	/** @return MSPT at which ELEVATED begins */
	public double targetMspt() {
		return targetMspt;
	}

	/** @return MSPT at which HIGH begins */
	public double highMspt() {
		return highMspt;
	}

	/** @return MSPT at which CRITICAL begins */
	public double criticalMspt() {
		return criticalMspt;
	}

	/**
	 * The level {@code avgMspt} maps to on the raw SPEC FR-5 table, ignoring hysteresis and hold
	 * time. Exposed for tests and for {@code /tickpilot explain}.
	 */
	public LoadLevel levelFor(double avgMspt) {
		if (avgMspt >= criticalMspt) {
			return LoadLevel.CRITICAL;
		}

		if (avgMspt >= highMspt) {
			return LoadLevel.HIGH;
		}

		if (avgMspt >= targetMspt) {
			return LoadLevel.ELEVATED;
		}

		return LoadLevel.NORMAL;
	}

	private LoadLevel levelForWithHysteresis(double avgMspt) {
		if (avgMspt >= criticalMspt - hysteresisMspt) {
			return LoadLevel.CRITICAL;
		}

		if (avgMspt >= highMspt - hysteresisMspt) {
			return LoadLevel.HIGH;
		}

		if (avgMspt >= targetMspt - hysteresisMspt) {
			return LoadLevel.ELEVATED;
		}

		return LoadLevel.NORMAL;
	}
}
