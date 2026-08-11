package com.tickpilot.chunk;

/**
 * The five priority classes SPEC AC-10 orders chunk operations by, in that order.
 *
 * <p>{@link #ordinal()} <em>is</em> the priority: a lower ordinal is served first, and the two
 * highest ordinals are the only ones a budget may ever hold back. Nothing in this package sorts by
 * anything else, so re-ordering these constants changes the behaviour of the whole subsystem — that
 * is deliberate, and it is why the priority is expressed as the declaration order rather than as a
 * field somebody could forget to keep in step.
 *
 * <p>Classes 1 to 3 are the SPEC INV-8 guarantee written down: a chunk a player is waiting for, a
 * teleport destination, and a force-loaded region are never delayed by TickPilot, whatever the
 * config says and however loaded the server is.
 */
public enum ChunkOpClass {
	/**
	 * A chunk somebody needs right now: inside a player's view radius, or covered by a vanilla
	 * ticket taken out to load it — including {@code UNKNOWN}, which is what
	 * {@code ServerChunkCache.getChunk} takes out while the server thread blocks on the result.
	 */
	PLAYER_LOADING("tickpilot.chunkop.player_loading"),

	/** A teleport, portal or world-start destination the player is being moved to. */
	PLAYER_TELEPORT("tickpilot.chunkop.player_teleport"),

	/** Inside a force-loaded region: the operator loaded it precisely so it would keep working. */
	FORCE_LOADED("tickpilot.chunkop.force_loaded"),

	/** Generation far from every player in a world that has players. Optional. */
	REMOTE_GENERATION("tickpilot.chunkop.remote_generation"),

	/** Generation in a world with no players in it at all. Optional, and the first to be held. */
	BACKGROUND("tickpilot.chunkop.background");

	private static final ChunkOpClass[] ALL = values();

	private final String translationKey;

	ChunkOpClass(String translationKey) {
		this.translationKey = translationKey;
	}

	/**
	 * @return whether operations of this class may be held back for a later tick. Only the last two
	 *         classes are optional; the first three are the SPEC INV-8 guarantee
	 */
	public boolean isOptional() {
		return this == REMOTE_GENERATION || this == BACKGROUND;
	}

	/** @return the {@code en_us.json} key naming this class in command output */
	public String translationKey() {
		return translationKey;
	}

	/**
	 * @return every class in priority order. Shared array, never handed to anything that mutates —
	 *         the alternative is a defensive copy per call on a path {@code status} walks
	 */
	public static ChunkOpClass[] all() {
		return ALL;
	}
}
