package com.tickpilot.zones;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Keeps one {@link ZoneResolver} per world and refills it with that world's players once a tick
 * (SPEC FR-7).
 *
 * <p>The Minecraft-facing half of the zone system: everything that reads a game object happens
 * here, on the server thread, once per tick, and what crosses into {@link ZoneResolver} is
 * coordinates. That is what keeps the distance rules unit-testable and SPEC INV-1 satisfied by
 * construction.
 *
 * <h2>Per world, not per server</h2>
 * A player in the Nether is not near anything in the Overworld. Sharing one resolver between
 * dimensions would put every object in the Overworld into {@link ActivityZone#FULL} whenever
 * somebody stood at the same coordinates in another dimension.
 *
 * <h2>Hot path</h2>
 * {@link #zoneFor} is called once per candidate object per tick. The last world used is cached in
 * a field, so the lookup is a reference comparison during the run of ticks belonging to one world,
 * which is how the game ticks them — a map lookup happens only when the world changes.
 */
public final class ZoneTracker {
	private final Map<Level, ZoneResolver> resolvers = new IdentityHashMap<>();

	private int fullRadius;
	private int reducedRadius;

	private Level lastLevel;
	private ZoneResolver lastResolver;

	/**
	 * @param fullRadius    blocks within which an object always ticks in full
	 * @param reducedRadius blocks beyond which an object is in {@link ActivityZone#FROZEN}
	 */
	public ZoneTracker(int fullRadius, int reducedRadius) {
		this.fullRadius = fullRadius;
		this.reducedRadius = reducedRadius;
	}

	/**
	 * Applies new radii after {@code /tickpilot reload}, to every world at once.
	 *
	 * @param fullRadius    see {@link #ZoneTracker(int, int)}
	 * @param reducedRadius see {@link #ZoneTracker(int, int)}
	 */
	public void setRadii(int fullRadius, int reducedRadius) {
		this.fullRadius = fullRadius;
		this.reducedRadius = reducedRadius;

		for (ZoneResolver resolver : resolvers.values()) {
			resolver.setRadii(fullRadius, reducedRadius);
		}
	}

	/**
	 * Refills every world's player positions for this tick. Called once per tick from the tick
	 * listener, before anything asks for a zone.
	 *
	 * <p>Reads {@code ServerLevel.players()}, which is the world's own list, and copies two doubles
	 * out of each player. No game object is retained (SPEC INV-1).
	 *
	 * @param server the running server
	 */
	public void beginTick(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			ZoneResolver resolver = resolverFor(level);
			resolver.beginTick();

			for (ServerPlayer player : level.players()) {
				// Spectators are still players and still watching, so they count. A player being
				// asleep or in another dimension is already handled by which world they are in.
				resolver.addPlayer(player.getX(), player.getZ());
			}
		}
	}

	/**
	 * @param level  the world the object is in
	 * @param chunkX chunk x
	 * @param chunkZ chunk z
	 * @return the zone of that chunk, or {@link ActivityZone#FULL} for a world this tracker has
	 *         never seen — an unknown world is not an excuse to thin anything
	 */
	public ActivityZone zoneFor(Level level, int chunkX, int chunkZ) {
		if (level == lastLevel) {
			return lastResolver.zoneForChunk(chunkX, chunkZ);
		}

		ZoneResolver resolver = resolvers.get(level);

		if (resolver == null) {
			return ActivityZone.FULL;
		}

		lastLevel = level;
		lastResolver = resolver;
		return resolver.zoneForChunk(chunkX, chunkZ);
	}

	/**
	 * @param level the world
	 * @return whether that world has any players in it. {@code false} for an unknown world
	 */
	public boolean hasPlayers(Level level) {
		ZoneResolver resolver = resolvers.get(level);
		return resolver != null && resolver.hasPlayers();
	}

	/** @return how many worlds this tracker holds a resolver for */
	public int trackedLevels() {
		return resolvers.size();
	}

	private ZoneResolver resolverFor(Level level) {
		ZoneResolver resolver = resolvers.get(level);

		if (resolver == null) {
			resolver = new ZoneResolver(fullRadius, reducedRadius);
			resolvers.put(level, resolver);
		}

		return resolver;
	}

	/**
	 * Releases every resolver. Called when the server stops: the map keys are worlds, and holding
	 * one past its world would keep it alive (SPEC INV-7, AC-19).
	 */
	public void clear() {
		resolvers.clear();
		lastLevel = null;
		lastResolver = null;
	}
}
