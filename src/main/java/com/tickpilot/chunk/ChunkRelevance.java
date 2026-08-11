package com.tickpilot.chunk;

/**
 * Decides which of the SPEC AC-10 priority classes a chunk belongs to, for one world.
 *
 * <p>Pure: chunk coordinates in, a {@link ChunkOpClass} out. Nothing here knows what a player or a
 * ticket is, only where they were when the Minecraft-facing side last said so, which is what keeps
 * SPEC INV-1 satisfied by construction and lets the whole classification be unit-tested.
 *
 * <h2>Why the player radius is view distance plus a margin</h2>
 * A chunk at the edge of a player's view cannot be finished without its neighbours: full generation
 * pulls in a radius of up to {@value #GENERATION_NEIGHBOURHOOD_CHUNKS} chunks around whatever is
 * being generated. Classifying that neighbourhood as "far from players" and holding it back would
 * delay the very chunk the player is waiting for — INV-8 violated by arithmetic rather than by
 * intent. So the radius that counts as player-critical is the server's view distance plus that
 * neighbourhood plus one, and the same margin is added to every protected region.
 *
 * <h2>Distance is Chebyshev, not Euclidean</h2>
 * Because that is how Minecraft itself measures view distance: the loaded area around a player is a
 * square, not a disc. A Euclidean radius would leave the corners of the square unprotected.
 */
final class ChunkRelevance {
	/**
	 * How far chunk generation reaches around what it is generating. Structure starts and features
	 * are the widest steps in 1.21.1 and both stay inside 8 chunks.
	 */
	static final int GENERATION_NEIGHBOURHOOD_CHUNKS = 8;

	/**
	 * How many protected regions are remembered at once. A ring: the oldest entry is overwritten.
	 * Regions matter for the handful of ticks between a ticket being taken out and the chunk being
	 * ready, so losing the oldest is harmless, while an unbounded list would be a leak on a server
	 * where every blocking {@code getChunk} adds one.
	 */
	static final int MAX_REGIONS = 128;

	private final long[] regionPos = new long[MAX_REGIONS];
	private final int[] regionRadius = new int[MAX_REGIONS];
	private final long[] regionExpiry = new long[MAX_REGIONS];
	private final byte[] regionClass = new byte[MAX_REGIONS];
	private int regionCursor;
	private int regionsHeld;

	private int[] playerChunks = new int[32];
	private int playerCount;
	private int playerRadiusChunks = GENERATION_NEIGHBOURHOOD_CHUNKS + 1;

	/**
	 * Sets the radius, in chunks, within which a chunk counts as player-critical.
	 *
	 * @param viewDistanceChunks the server's view distance
	 */
	void setViewDistance(int viewDistanceChunks) {
		this.playerRadiusChunks = Math.max(0, viewDistanceChunks) + GENERATION_NEIGHBOURHOOD_CHUNKS + 1;
	}

	/** @return the radius in chunks within which a chunk counts as player-critical */
	int playerRadiusChunks() {
		return playerRadiusChunks;
	}

	/** Drops last tick's player positions. Called once per tick before they are re-added. */
	void beginTick() {
		playerCount = 0;
	}

	/**
	 * Records where one player is. Coordinates only; no player object crosses into this class.
	 *
	 * @param chunkX the player's chunk x
	 * @param chunkZ the player's chunk z
	 */
	void addPlayer(int chunkX, int chunkZ) {
		int index = playerCount * 2;

		if (index + 1 >= playerChunks.length) {
			int[] grown = new int[playerChunks.length * 2];
			System.arraycopy(playerChunks, 0, grown, 0, playerChunks.length);
			playerChunks = grown;
		}

		playerChunks[index] = chunkX;
		playerChunks[index + 1] = chunkZ;
		playerCount++;
	}

	/** @return whether this world had any players when it was last refreshed */
	boolean hasPlayers() {
		return playerCount > 0;
	}

	/**
	 * Marks a region as needed by somebody, for a limited number of ticks.
	 *
	 * @param chunkX      centre chunk x
	 * @param chunkZ      centre chunk z
	 * @param radius      the ticket's own radius in chunks; the generation neighbourhood is added
	 * @param opClass     what the region is needed for
	 * @param expiryTick  the game time at which the protection stops applying
	 */
	void protect(int chunkX, int chunkZ, int radius, ChunkOpClass opClass, long expiryTick) {
		regionPos[regionCursor] = pack(chunkX, chunkZ);
		regionRadius[regionCursor] = Math.max(0, radius) + GENERATION_NEIGHBOURHOOD_CHUNKS;
		regionExpiry[regionCursor] = expiryTick;
		regionClass[regionCursor] = (byte) opClass.ordinal();
		regionCursor = (regionCursor + 1) % MAX_REGIONS;

		if (regionsHeld < MAX_REGIONS) {
			regionsHeld++;
		}
	}

	/**
	 * Classifies one chunk. Hot path: a bounded scan over primitives, no allocation (SPEC INV-6).
	 *
	 * @param chunkX   the chunk x
	 * @param chunkZ   the chunk z
	 * @param forced   whether the chunk is inside a force-loaded region
	 * @param gameTime the current game time, for expiring regions
	 * @return the highest-priority class that applies; never {@code null}
	 */
	ChunkOpClass classify(int chunkX, int chunkZ, boolean forced, long gameTime) {
		// Cheapest and commonest first: on a normal server almost every chunk being generated is
		// one a player is walking towards.
		for (int i = 0; i < playerCount; i++) {
			int index = i * 2;

			if (within(playerChunks[index], playerChunks[index + 1], chunkX, chunkZ,
					playerRadiusChunks)) {
				return ChunkOpClass.PLAYER_LOADING;
			}
		}

		ChunkOpClass region = regionClassOf(chunkX, chunkZ, gameTime);

		if (region != null) {
			return region;
		}

		if (forced) {
			return ChunkOpClass.FORCE_LOADED;
		}

		// A world nobody is in is background work by definition; a world with players in it, but
		// none of them near this chunk, is remote generation. Both are optional, and the ordering
		// between them is what decides which is held first when the budget is short.
		return hasPlayers() ? ChunkOpClass.REMOTE_GENERATION : ChunkOpClass.BACKGROUND;
	}

	/**
	 * @return the strongest live protection covering this chunk, or {@code null}. Strongest and not
	 *         first-found: two regions can overlap, and reporting the weaker one would understate
	 *         why the chunk was let through
	 */
	private ChunkOpClass regionClassOf(int chunkX, int chunkZ, long gameTime) {
		ChunkOpClass best = null;

		for (int i = 0; i < regionsHeld; i++) {
			if (gameTime >= regionExpiry[i]) {
				continue;
			}

			long packed = regionPos[i];

			if (!within(unpackX(packed), unpackZ(packed), chunkX, chunkZ, regionRadius[i])) {
				continue;
			}

			ChunkOpClass opClass = ChunkOpClass.all()[regionClass[i]];

			if (best == null || opClass.ordinal() < best.ordinal()) {
				best = opClass;
			}
		}

		return best;
	}

	/** Chebyshev containment, matching how Minecraft sizes the square of chunks around a player. */
	private static boolean within(int centreX, int centreZ, int chunkX, int chunkZ, int radius) {
		return Math.abs(centreX - chunkX) <= radius && Math.abs(centreZ - chunkZ) <= radius;
	}

	private static long pack(int chunkX, int chunkZ) {
		return (chunkX & 0xFFFF_FFFFL) | ((long) chunkZ << 32);
	}

	private static int unpackX(long packed) {
		return (int) packed;
	}

	private static int unpackZ(long packed) {
		return (int) (packed >> 32);
	}

	/** Forgets every player and every region. Called when the server stops (SPEC AC-19). */
	void clear() {
		playerCount = 0;
		regionCursor = 0;
		regionsHeld = 0;
	}
}
