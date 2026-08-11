package com.tickpilot.chunk;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Keeps one {@link ChunkRelevance} per world and refills it once a tick (SPEC FR-10, AC-10).
 *
 * <p>The Minecraft-facing half of the chunk budget, exactly parallel to {@code ZoneTracker}:
 * everything that reads a game object happens here, on the server thread, once per tick, and what
 * crosses into the classifier is chunk coordinates (SPEC INV-1).
 *
 * <h2>Its own tick counter, not the world's game time</h2>
 * Region protections and budget lifts expire after a number of ticks. Game time is the obvious
 * source and the wrong one: {@code /tick freeze} stops it, and a frozen server would hold a lifted
 * budget and a set of protected regions open indefinitely. The counter here advances once per
 * server tick whatever the tick rate manager is doing (SPEC AC-1b).
 */
public final class ChunkBudgetTracker {
	/**
	 * How long a region stays protected after a vanilla ticket is taken out over it. 5 s at 20 TPS.
	 *
	 * <p>What it has to cover is the gap between a ticket being taken out and this tracker seeing
	 * the player at the destination, which is a single tick, plus however long the resulting
	 * generation burst takes. Five seconds is generous for the first and adequate for the second;
	 * if it is ever not enough, the suspected-block release in {@link ChunkBudget} is the backstop,
	 * which is why this number does not have to be conservative to the point of pinning whole
	 * regions open.
	 */
	public static final int REGION_PROTECTION_TICKS = 100;

	private final Map<Level, ChunkRelevance> relevance = new IdentityHashMap<>();

	private Level lastLevel;
	private ChunkRelevance lastRelevance;
	private long tick;

	/** @return the tracker's own monotonic tick counter */
	public long tick() {
		return tick;
	}

	/**
	 * Refills every world's player positions for this tick. Called once per tick from the tick
	 * listener, before anything asks for a classification.
	 *
	 * @param server the running server
	 */
	public void beginTick(MinecraftServer server) {
		tick++;

		int viewDistance = server.getPlayerList().getViewDistance();

		for (ServerLevel level : server.getAllLevels()) {
			ChunkRelevance world = relevanceFor(level);
			world.setViewDistance(viewDistance);
			world.beginTick();

			for (ServerPlayer player : level.players()) {
				// chunkPosition() returns the entity's stored ChunkPos, so this reads two ints and
				// allocates nothing (SPEC INV-6).
				ChunkPos pos = player.chunkPosition();
				world.addPlayer(pos.x, pos.z);
			}
		}
	}

	/**
	 * Marks a region as needed, because a vanilla ticket was taken out over it.
	 *
	 * @param level   the world the ticket is in
	 * @param pos     the chunk the ticket is centred on
	 * @param radius  the ticket's radius in chunks
	 * @param opClass what the region is needed for
	 */
	public void protect(ServerLevel level, ChunkPos pos, int radius, ChunkOpClass opClass) {
		relevanceFor(level).protect(pos.x, pos.z, radius, opClass, tick + REGION_PROTECTION_TICKS);
	}

	/**
	 * Classifies one chunk. Hot path.
	 *
	 * @param level  the world
	 * @param chunkX chunk x
	 * @param chunkZ chunk z
	 * @return the priority class of work on that chunk. A world this tracker has never seen returns
	 *         {@link ChunkOpClass#PLAYER_LOADING}: an unknown world is not an excuse to hold
	 *         anything back (SPEC INV-8)
	 */
	public ChunkOpClass classify(ServerLevel level, int chunkX, int chunkZ) {
		ChunkRelevance world = resolve(level);

		if (world == null) {
			return ChunkOpClass.PLAYER_LOADING;
		}

		return world.classify(chunkX, chunkZ, isForced(level, chunkX, chunkZ), tick);
	}

	/**
	 * @return whether the chunk is force-loaded. The empty check comes first because on almost every
	 *         server the set is empty, and then this costs one field read rather than a hash — the
	 *         same shape as {@code PolicyHook.isForceLoaded}
	 */
	private static boolean isForced(ServerLevel level, int chunkX, int chunkZ) {
		var forced = level.getForcedChunks();
		return !forced.isEmpty() && forced.contains(ChunkPos.asLong(chunkX, chunkZ));
	}

	private ChunkRelevance resolve(Level level) {
		if (level == lastLevel) {
			return lastRelevance;
		}

		ChunkRelevance world = relevance.get(level);

		if (world != null) {
			lastLevel = level;
			lastRelevance = world;
		}

		return world;
	}

	private ChunkRelevance relevanceFor(Level level) {
		ChunkRelevance world = relevance.get(level);

		if (world == null) {
			world = new ChunkRelevance();
			relevance.put(level, world);
		}

		lastLevel = level;
		lastRelevance = world;
		return world;
	}

	/** @return how many worlds this tracker holds a classifier for */
	public int trackedLevels() {
		return relevance.size();
	}

	/**
	 * Releases every classifier. Called when the server stops: the map keys are worlds, and holding
	 * one past its world would keep it alive (SPEC INV-7, AC-19).
	 */
	public void clear() {
		relevance.clear();
		lastLevel = null;
		lastRelevance = null;
		tick = 0L;
	}
}
