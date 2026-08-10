package com.tickpilot.zones;

/**
 * Works out which {@link ActivityZone} a chunk is in, from the positions of the players in one
 * world (SPEC FR-7, AC-7).
 *
 * <h2>Per chunk, not per object</h2>
 * Answering per object would cost one distance computation per player per object per tick. The
 * answer is cached per chunk instead and computed at most once per chunk per tick, so the cost is
 * players × occupied chunks rather than players × objects, and the hot path is one array lookup
 * (SPEC INV-6). Nothing is allocated after construction.
 *
 * <h2>Two deliberate under-estimates</h2>
 * Both err towards {@link ActivityZone#FULL}, i.e. towards leaving things alone:
 * <ul>
 *   <li>The distance measured is from the player to the <em>nearest point of the chunk</em>, not
 *       to its centre. Every block in the chunk is therefore at least that far away, so no object
 *       is ever placed in a farther zone than it belongs to.</li>
 *   <li>Only the horizontal distance is used. A mob two hundred blocks below a player in the same
 *       chunk column counts as {@link ActivityZone#FULL}. Caching a per-chunk answer and taking
 *       height into account are not compatible, and of the two the safe direction is obvious.</li>
 * </ul>
 *
 * <h2>No players is not the far zone</h2>
 * With nobody in the world every chunk answers {@link ActivityZone#FULL}. SPEC AC-7 requires this:
 * a world with no players in it must keep ticking, or farms and chunk loaders stop working while
 * their owner is away. "Nobody is near" and "nobody is here" are different facts.
 *
 * <h2>Threading</h2>
 * Server thread only, one instance per world. No {@code net.minecraft} import: player positions
 * arrive as primitives, so the whole class is unit-tested without the game.
 */
public final class ZoneResolver {
	/**
	 * Cache slots. A direct-mapped table, so a collision costs a recomputation rather than a wrong
	 * answer; 1024 chunks is comfortably more than the loaded, occupied set of a normal server and
	 * costs about 13 KB per world.
	 */
	private static final int CACHE_SLOTS = 1024;
	private static final int CACHE_MASK = CACHE_SLOTS - 1;

	/** Fibonacci hashing constant, for spreading chunk keys across the table. */
	private static final long HASH_MIX = 0x9E3779B97F4A7C15L;

	private static final int INITIAL_PLAYER_CAPACITY = 16;

	private int fullRadius;
	private int reducedRadius;
	private long fullRadiusSq;
	private long reducedRadiusSq;

	private double[] playerX = new double[INITIAL_PLAYER_CAPACITY];
	private double[] playerZ = new double[INITIAL_PLAYER_CAPACITY];
	private int playerCount;

	private final long[] cacheKeys = new long[CACHE_SLOTS];
	private final byte[] cacheZones = new byte[CACHE_SLOTS];
	private final int[] cacheGeneration = new int[CACHE_SLOTS];

	/**
	 * Bumped by {@link #beginTick()}. Stamping entries rather than clearing the table keeps
	 * invalidation O(1) instead of O(slots) per tick.
	 */
	private int generation = 1;

	/**
	 * @param fullRadius    blocks within which an object always ticks in full
	 * @param reducedRadius blocks beyond which an object is in {@link ActivityZone#FROZEN}
	 */
	public ZoneResolver(int fullRadius, int reducedRadius) {
		setRadii(fullRadius, reducedRadius);
	}

	/**
	 * Applies new radii, e.g. after {@code /tickpilot reload}. Invalidates the cache, since every
	 * cached answer was computed against the old ones.
	 *
	 * @param fullRadius    see {@link #ZoneResolver(int, int)}
	 * @param reducedRadius see {@link #ZoneResolver(int, int)}
	 */
	public void setRadii(int fullRadius, int reducedRadius) {
		this.fullRadius = Math.max(0, fullRadius);
		this.reducedRadius = Math.max(this.fullRadius, reducedRadius);
		this.fullRadiusSq = (long) this.fullRadius * this.fullRadius;
		this.reducedRadiusSq = (long) this.reducedRadius * this.reducedRadius;
		invalidate();
	}

	/** @return the configured full radius in blocks */
	public int fullRadius() {
		return fullRadius;
	}

	/** @return the configured reduced radius in blocks */
	public int reducedRadius() {
		return reducedRadius;
	}

	/**
	 * Starts a new tick: drops the player list and invalidates every cached answer. Call once per
	 * world per tick, then {@link #addPlayer(double, double)} for each player.
	 */
	public void beginTick() {
		playerCount = 0;
		invalidate();
	}

	/**
	 * Adds one player to this tick's set.
	 *
	 * @param x player x
	 * @param z player z
	 */
	public void addPlayer(double x, double z) {
		if (playerCount == playerX.length) {
			grow();
		}

		playerX[playerCount] = x;
		playerZ[playerCount] = z;
		playerCount++;
	}

	/** @return how many players were added for this tick */
	public int playerCount() {
		return playerCount;
	}

	/** @return {@code true} when this world has at least one player in it (SPEC AC-7) */
	public boolean hasPlayers() {
		return playerCount > 0;
	}

	/**
	 * @param chunkX chunk x
	 * @param chunkZ chunk z
	 * @return the zone that chunk is in. Hot path: a hit is one hash, one array read and two
	 *         comparisons
	 */
	public ActivityZone zoneForChunk(int chunkX, int chunkZ) {
		if (playerCount == 0) {
			// AC-7: an empty world is not a frozen one.
			return ActivityZone.FULL;
		}

		long key = key(chunkX, chunkZ);
		int slot = slotFor(key);

		if (cacheGeneration[slot] == generation && cacheKeys[slot] == key) {
			return ZONES[cacheZones[slot]];
		}

		ActivityZone zone = computeChunkZone(chunkX, chunkZ);
		cacheGeneration[slot] = generation;
		cacheKeys[slot] = key;
		cacheZones[slot] = (byte) zone.ordinal();
		return zone;
	}

	/**
	 * @param blockX block x
	 * @param blockZ block z
	 * @return the zone of the chunk containing that block
	 */
	public ActivityZone zoneForBlock(double blockX, double blockZ) {
		return zoneForChunk(Math.floorDiv((int) Math.floor(blockX), 16),
				Math.floorDiv((int) Math.floor(blockZ), 16));
	}

	/**
	 * The uncached computation, and the definition of what the cache holds.
	 *
	 * <p>Distance is measured to the nearest point of the chunk: the player's coordinates are
	 * clamped into the chunk's own bounds, which is what makes the result a lower bound for every
	 * block in it.
	 */
	private ActivityZone computeChunkZone(int chunkX, int chunkZ) {
		double minX = chunkX * 16.0;
		double maxX = minX + 16.0;
		double minZ = chunkZ * 16.0;
		double maxZ = minZ + 16.0;

		double nearestSq = Double.MAX_VALUE;

		for (int i = 0; i < playerCount; i++) {
			double dx = axisDistance(playerX[i], minX, maxX);
			double dz = axisDistance(playerZ[i], minZ, maxZ);
			double distanceSq = dx * dx + dz * dz;

			if (distanceSq < nearestSq) {
				nearestSq = distanceSq;

				if (nearestSq <= fullRadiusSq) {
					// Cannot get any closer than the closest zone; stop walking the player list.
					return ActivityZone.FULL;
				}
			}
		}

		if (nearestSq <= fullRadiusSq) {
			return ActivityZone.FULL;
		}

		return nearestSq <= reducedRadiusSq ? ActivityZone.REDUCED : ActivityZone.FROZEN;
	}

	/** @return how far {@code value} lies outside {@code [min, max]}, or 0 when inside it */
	private static double axisDistance(double value, double min, double max) {
		if (value < min) {
			return min - value;
		}

		return value > max ? value - max : 0.0;
	}

	private void invalidate() {
		generation++;

		if (generation == 0) {
			// Wrapped after 2^32 ticks - about seven years of uptime. Clearing the stamps costs one
			// pass and makes the wrap harmless rather than a source of one stale tick.
			java.util.Arrays.fill(cacheGeneration, 0);
			generation = 1;
		}
	}

	private void grow() {
		double[] grownX = new double[playerX.length * 2];
		double[] grownZ = new double[playerZ.length * 2];
		System.arraycopy(playerX, 0, grownX, 0, playerCount);
		System.arraycopy(playerZ, 0, grownZ, 0, playerCount);
		playerX = grownX;
		playerZ = grownZ;
	}

	private static long key(int chunkX, int chunkZ) {
		return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
	}

	private static int slotFor(long key) {
		long mixed = key * HASH_MIX;
		return (int) (mixed >>> 40) & CACHE_MASK;
	}

	/** Cached to avoid {@code values()} cloning an array on every cache hit. */
	private static final ActivityZone[] ZONES = ActivityZone.values();
}
