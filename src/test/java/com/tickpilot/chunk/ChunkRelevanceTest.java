package com.tickpilot.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SPEC AC-10: which of the five priority classes a chunk falls into.
 *
 * <p>This is where SPEC INV-8 is actually decided — the budget only refuses what this classifier
 * called optional — so the boundary cases get the attention: the corner of the square around a
 * player, the tick a protected region expires on, and two protections overlapping.
 */
class ChunkRelevanceTest {
	private static final int VIEW_DISTANCE = 10;

	/** The radius the classifier derives from a view distance of 10: 10 + 8 + 1. */
	private static final int RADIUS = VIEW_DISTANCE + ChunkRelevance.GENERATION_NEIGHBOURHOOD_CHUNKS + 1;

	private static ChunkRelevance withOnePlayerAtOrigin() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.setViewDistance(VIEW_DISTANCE);
		relevance.beginTick();
		relevance.addPlayer(0, 0);
		return relevance;
	}

	@Test
	void theRadiusCoversTheViewDistancePlusTheGenerationNeighbourhood() {
		ChunkRelevance relevance = withOnePlayerAtOrigin();

		assertEquals(RADIUS, relevance.playerRadiusChunks());
		assertEquals(19, RADIUS, "a view distance of 10 must protect 19 chunks, not 10");
	}

	@Test
	void chunksInsideThePlayerRadiusAreNeverOptional() {
		ChunkRelevance relevance = withOnePlayerAtOrigin();

		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(0, 0, false, 0L));
		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(RADIUS, 0, false, 0L));
		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(-RADIUS, 0, false, 0L));
		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(0, RADIUS, false, 0L));
	}

	/**
	 * The corner of the square is the case a Euclidean radius would get wrong, and Minecraft loads
	 * a square around a player, not a disc.
	 */
	@Test
	void theCornerOfTheSquareIsInside() {
		ChunkRelevance relevance = withOnePlayerAtOrigin();

		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(RADIUS, RADIUS, false, 0L));
		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(-RADIUS, -RADIUS, false, 0L));
		assertEquals(ChunkOpClass.REMOTE_GENERATION,
				relevance.classify(RADIUS + 1, RADIUS + 1, false, 0L));
	}

	@Test
	void aChunkOutsideEveryPlayerRadiusIsRemoteGeneration() {
		ChunkRelevance relevance = withOnePlayerAtOrigin();

		assertEquals(ChunkOpClass.REMOTE_GENERATION, relevance.classify(RADIUS + 1, 0, false, 0L));
		assertTrue(relevance.hasPlayers());
	}

	/** A world with nobody in it is background work, which is the first class to be held. */
	@Test
	void aWorldWithNoPlayersIsBackground() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.setViewDistance(VIEW_DISTANCE);
		relevance.beginTick();

		assertFalse(relevance.hasPlayers());
		assertEquals(ChunkOpClass.BACKGROUND, relevance.classify(500, 500, false, 0L));
	}

	@Test
	void aForceLoadedChunkIsNeverOptional() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.setViewDistance(VIEW_DISTANCE);
		relevance.beginTick();

		assertEquals(ChunkOpClass.FORCE_LOADED, relevance.classify(500, 500, true, 0L));
	}

	@Test
	void playersAreForgottenAtTheStartOfEachTick() {
		ChunkRelevance relevance = withOnePlayerAtOrigin();

		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(0, 0, false, 0L));

		relevance.beginTick();

		assertFalse(relevance.hasPlayers());
		assertEquals(ChunkOpClass.BACKGROUND, relevance.classify(0, 0, false, 0L));
	}

	@Test
	void thePlayerListGrowsPastItsInitialCapacity() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.setViewDistance(0);
		relevance.beginTick();

		for (int i = 0; i < 200; i++) {
			relevance.addPlayer(i * 1000, 0);
		}

		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(199_000, 0, false, 0L));
		assertEquals(ChunkOpClass.REMOTE_GENERATION, relevance.classify(500, 500, false, 0L));
	}

	// --- protected regions ---------------------------------------------------------------------

	@Test
	void aProtectedRegionCoversItsRadiusPlusTheGenerationNeighbourhood() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.beginTick();
		relevance.protect(1000, 1000, 1, ChunkOpClass.PLAYER_TELEPORT, 100L);

		int reach = 1 + ChunkRelevance.GENERATION_NEIGHBOURHOOD_CHUNKS;

		assertEquals(ChunkOpClass.PLAYER_TELEPORT, relevance.classify(1000, 1000, false, 0L));
		assertEquals(ChunkOpClass.PLAYER_TELEPORT,
				relevance.classify(1000 + reach, 1000 + reach, false, 0L));
		assertEquals(ChunkOpClass.BACKGROUND,
				relevance.classify(1000 + reach + 1, 1000, false, 0L));
	}

	@Test
	void aProtectedRegionExpires() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.beginTick();
		relevance.protect(1000, 1000, 0, ChunkOpClass.PLAYER_TELEPORT, 100L);

		assertEquals(ChunkOpClass.PLAYER_TELEPORT, relevance.classify(1000, 1000, false, 99L));
		assertEquals(ChunkOpClass.BACKGROUND, relevance.classify(1000, 1000, false, 100L));
	}

	@Test
	void overlappingRegionsReportTheStrongestOne() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.beginTick();
		relevance.protect(1000, 1000, 0, ChunkOpClass.FORCE_LOADED, 100L);
		relevance.protect(1000, 1000, 0, ChunkOpClass.PLAYER_LOADING, 100L);
		relevance.protect(1000, 1000, 0, ChunkOpClass.PLAYER_TELEPORT, 100L);

		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(1000, 1000, false, 0L));
	}

	@Test
	void aPlayerOutranksAnOverlappingWeakerRegion() {
		ChunkRelevance relevance = withOnePlayerAtOrigin();
		relevance.protect(0, 0, 0, ChunkOpClass.FORCE_LOADED, 100L);

		assertEquals(ChunkOpClass.PLAYER_LOADING, relevance.classify(0, 0, false, 0L));
	}

	/**
	 * The ring is bounded on purpose: every blocking {@code getChunk} takes out an UNKNOWN ticket,
	 * so an unbounded list would grow without limit on a busy server. Losing the oldest entry is
	 * the intended behaviour, and the newest must survive.
	 */
	@Test
	void theRegionRingKeepsTheNewestAndDropsTheOldest() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.beginTick();

		for (int i = 0; i < ChunkRelevance.MAX_REGIONS + 1; i++) {
			relevance.protect(i * 1000, 0, 0, ChunkOpClass.PLAYER_TELEPORT, 100L);
		}

		assertEquals(ChunkOpClass.BACKGROUND, relevance.classify(0, 0, false, 0L),
				"the oldest region must have been overwritten");
		assertEquals(ChunkOpClass.PLAYER_TELEPORT,
				relevance.classify(ChunkRelevance.MAX_REGIONS * 1000, 0, false, 0L));
	}

	/** Negative chunk coordinates must survive the packing used by the region ring. */
	@Test
	void negativeCoordinatesArePackedAndUnpackedFaithfully() {
		ChunkRelevance relevance = new ChunkRelevance();
		relevance.beginTick();
		relevance.protect(-30_000, -40_000, 0, ChunkOpClass.PLAYER_TELEPORT, 100L);

		assertEquals(ChunkOpClass.PLAYER_TELEPORT, relevance.classify(-30_000, -40_000, false, 0L));
		assertEquals(ChunkOpClass.BACKGROUND, relevance.classify(30_000, 40_000, false, 0L));
	}

	@Test
	void clearForgetsPlayersAndRegions() {
		ChunkRelevance relevance = withOnePlayerAtOrigin();
		relevance.protect(1000, 1000, 0, ChunkOpClass.PLAYER_TELEPORT, 100L);

		relevance.clear();

		assertFalse(relevance.hasPlayers());
		assertEquals(ChunkOpClass.BACKGROUND, relevance.classify(0, 0, false, 0L));
		assertEquals(ChunkOpClass.BACKGROUND, relevance.classify(1000, 1000, false, 0L));
	}
}
