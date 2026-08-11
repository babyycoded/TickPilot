package com.tickpilot.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * What the chunk Mixins call to reach the per-server chunk budget (SPEC FR-10, AC-10).
 *
 * <h2>Why a static park, and why it is not global state</h2>
 * Same reasoning as {@code ProfilerHook} and {@code PolicyHook}, recorded as SPEC §13 entry #5: a
 * Mixin inside {@code ChunkMap} has no cheap route back to the per-server state, so the tick
 * listener parks what is needed for the duration of a tick and clears it at the end. Between ticks,
 * and between worlds, every field here is {@code null}.
 *
 * <h2>Thread safety</h2>
 * {@link #owner} is compared on every call. Chunk generation dispatch happens on the server thread;
 * a call arriving from anywhere else is refused rather than answered, which for a gate means the
 * work goes through unimpeded (SPEC INV-1, INV-8).
 *
 * <h2>Cost when the feature is off</h2>
 * One static read and a null check. Nothing is classified and nothing is counted until an operator
 * sets {@code enable_chunk_budget} (SPEC INV-3, INV-10).
 */
public final class ChunkBudgetHook {
	private static volatile ChunkBudget budget;
	private static volatile ChunkBudgetTracker tracker;
	private static volatile Thread owner;

	private ChunkBudgetHook() {
	}

	/**
	 * Parks the budget for the current tick. Called from the tick listener on the server thread.
	 *
	 * @param budget  the per-server budget
	 * @param tracker the per-server chunk classifier, already refilled for this tick
	 */
	public static void attach(ChunkBudget budget, ChunkBudgetTracker tracker) {
		ChunkBudgetHook.tracker = tracker;
		ChunkBudgetHook.owner = Thread.currentThread();
		// Written last: it is the field every hook tests first, so nothing can observe a
		// half-populated park.
		ChunkBudgetHook.budget = budget;
	}

	/** Unparks whatever {@link #attach} parked. Must run even if the tick threw. */
	public static void detach() {
		budget = null;
		tracker = null;
		owner = null;
	}

	/** @return {@code true} when nothing is parked; the expected state between ticks */
	public static boolean isDetached() {
		return budget == null;
	}

	/**
	 * @return the parked budget when it is switched on and this is the server thread, otherwise
	 *         {@code null}. The single test every caller makes before doing any work
	 */
	static ChunkBudget active() {
		ChunkBudget parked = budget;

		if (parked == null || Thread.currentThread() != owner || !parked.isEnabled()) {
			return null;
		}

		return parked;
	}

	/** @return the parked classifier; only meaningful after {@link #active()} returned non-null */
	static ChunkBudgetTracker tracker() {
		return tracker;
	}

	/**
	 * Records that a vanilla ticket was taken out over a region, so chunk work there is never held
	 * back (SPEC AC-10 priorities 1 to 3, INV-8).
	 *
	 * <p>Called from the {@code ServerChunkCache.addRegionTicket} Mixin. Ticket types that did not
	 * come from Minecraft are deliberately not protected: they are another mod's background loading,
	 * which is exactly what AC-10 calls priority 5.
	 *
	 * @param level  the world the ticket is in
	 * @param type   the ticket type
	 * @param pos    the chunk the ticket is centred on
	 * @param radius the ticket's radius in chunks
	 */
	public static void onRegionTicket(ServerLevel level, TicketType<?> type, ChunkPos pos,
			int radius) {
		if (active() == null) {
			return;
		}

		ChunkOpClass opClass = classOf(type);

		if (opClass != null) {
			tracker.protect(level, pos, radius, opClass);
		}
	}

	/**
	 * Maps a vanilla ticket type onto the SPEC AC-10 priority it represents.
	 *
	 * <p>Identity comparison against the constants of {@code TicketType}, which are the whole of
	 * what vanilla 1.21.1 defines: {@code START}, {@code DRAGON}, {@code PLAYER}, {@code FORCED},
	 * {@code PORTAL}, {@code POST_TELEPORT}, {@code UNKNOWN} (verified against {@code mappings.tiny}
	 * and {@code javap}). {@code UNKNOWN} counts as the highest priority of all, not the lowest its
	 * name suggests: it is the ticket {@code ServerChunkCache.getChunk} takes out while the server
	 * thread is blocked in {@code managedBlock} waiting for the result.
	 *
	 * @return the class to protect the region as, or {@code null} for a ticket type another mod
	 *         defined
	 */
	private static ChunkOpClass classOf(TicketType<?> type) {
		if (type == TicketType.UNKNOWN || type == TicketType.PLAYER || type == TicketType.DRAGON) {
			return ChunkOpClass.PLAYER_LOADING;
		}

		if (type == TicketType.POST_TELEPORT || type == TicketType.PORTAL
				|| type == TicketType.START) {
			return ChunkOpClass.PLAYER_TELEPORT;
		}

		if (type == TicketType.FORCED) {
			return ChunkOpClass.FORCE_LOADED;
		}

		return null;
	}
}
