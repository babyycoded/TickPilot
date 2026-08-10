package com.tickpilot.policy;

/**
 * Decides which ticks an object actually runs on once the policy has permitted thinning
 * (SPEC FR-8, FR-15 {@code min_entity_update_interval_ticks}, §13 entry #17).
 *
 * <h2>Staggered, not synchronised</h2>
 * An object runs when {@code (gameTime + phase) % interval == 0}, where the phase comes from the
 * object itself. The obvious alternative, {@code gameTime % interval == 0}, would put every thinned
 * object of every type on the <em>same</em> tick: four times the work on one tick in four and
 * nothing on the other three.
 *
 * <p>That is not a hypothetical. Phase 7 measured it: 666 deferred tasks that shared a deadline all
 * ran in one tick, ~0.7 s of work, TPS 17.95, and the spike was invisible in MSPT. Spreading by
 * phase removes it by construction rather than by hoping the objects happen to be out of step.
 *
 * <p>Vanilla does the same thing in the very method this feeds: {@code Mob.serverAiStep} re-evaluates
 * goals on {@code (tickCount + getId()) % 2}, staggered by entity id for exactly this reason.
 *
 * <h2>Absolute game time, not a per-object counter</h2>
 * The clock is the world's, so an object that stops being thinned resumes on the same grid instead
 * of restarting its own count, and two objects with the same phase always agree. It also means the
 * pattern is analysable from outside: an operator can say which ticks a given entity runs on.
 *
 * <p>No {@code net.minecraft} import; both inputs are primitives.
 */
public final class ThinningSchedule {
	private ThinningSchedule() {
	}

	/**
	 * @param interval how many ticks apart the object should run. 1 or less means every tick
	 * @return {@code true} when this interval thins anything at all. The SPEC FR-15 default is 1,
	 *         so by default this is {@code false} and nothing is ever skipped (SPEC INV-3)
	 */
	public static boolean thins(int interval) {
		return interval > 1;
	}

	/**
	 * @param gameTime the world's game time, i.e. an absolute tick counter
	 * @param phase    a stable per-object value; the entity id is what the mod uses
	 * @param interval ticks between runs, from the config
	 * @return {@code true} when the object runs on this tick, {@code false} when it is skipped
	 */
	public static boolean runsOnTick(long gameTime, int phase, int interval) {
		if (!thins(interval)) {
			return true;
		}

		// Math.floorMod, not %: game time is always positive in practice but a phase from an entity
		// id need not be, and a negative remainder would silently make the condition unreachable.
		return Math.floorMod(gameTime + phase, interval) == 0;
	}

	/**
	 * The share of ticks an object actually runs on, for reporting.
	 *
	 * @param interval ticks between runs
	 * @return a fraction between 0 and 1; 1 when nothing is thinned
	 */
	public static double runFraction(int interval) {
		return thins(interval) ? 1.0 / interval : 1.0;
	}
}
