package com.tickpilot.zones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The distance table of SPEC FR-7 and the "no players is not the far zone" rule of AC-7, with the
 * player positions supplied by the test so no world is needed.
 */
class ZoneResolverTest {
	private static final int FULL = 32;
	private static final int REDUCED = 96;

	private ZoneResolver resolver() {
		return new ZoneResolver(FULL, REDUCED);
	}

	private ZoneResolver withPlayerAt(double x, double z) {
		ZoneResolver resolver = resolver();
		resolver.beginTick();
		resolver.addPlayer(x, z);
		return resolver;
	}

	@Test
	void anEmptyWorldIsNotFrozen() {
		ZoneResolver resolver = resolver();
		resolver.beginTick();

		assertFalse(resolver.hasPlayers());
		// AC-7: farms and chunk loaders must keep running while their owner is away. "Nobody is
		// near" and "nobody is here" are different facts.
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(0, 0));
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(10_000, -10_000));
	}

	@Test
	void aFreshResolverHasNoPlayersEitherWayRoundAndStillAnswersFull() {
		// beginTick has never been called: the answer must still be the safe one, not a crash and
		// not FROZEN.
		assertSame(ActivityZone.FULL, resolver().zoneForChunk(5, 5));
	}

	@Test
	void theChunkAPlayerStandsInIsFull() {
		ZoneResolver resolver = withPlayerAt(8.0, 8.0);

		assertTrue(resolver.hasPlayers());
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(0, 0));
	}

	@Test
	void theZonesFollowTheDistanceTable() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);

		// Distance is measured to the nearest point of the chunk, so chunk n starts at 16n blocks.
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(2, 0), "chunk starts 32 blocks out");
		assertSame(ActivityZone.REDUCED, resolver.zoneForChunk(3, 0), "48 blocks out");
		assertSame(ActivityZone.REDUCED, resolver.zoneForChunk(6, 0), "96 blocks out");
		assertSame(ActivityZone.FROZEN, resolver.zoneForChunk(7, 0), "112 blocks out");
	}

	@Test
	void theBoundariesAreInclusiveOnTheSafeSide() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);

		// Exactly at full_radius is still FULL, exactly at reduced_radius is still REDUCED: at the
		// boundary the answer that leaves the object alone wins.
		assertSame(ActivityZone.FULL, resolver.zoneForBlock(FULL, 0.0));
		assertSame(ActivityZone.REDUCED, resolver.zoneForBlock(REDUCED, 0.0));
	}

	@Test
	void distanceIsMeasuredToTheNearestPointOfTheChunkNotItsCentre() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);

		// Chunk 2 spans x 32..48. Its centre is 40 blocks out, which would make it REDUCED; its
		// near edge is exactly 32, which makes it FULL. The near edge is what counts, so that no
		// block in the chunk is ever placed in a farther zone than it belongs to.
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(2, 0));
	}

	@Test
	void heightIsIgnoredWhichAlwaysErrsTowardsFull() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);

		// Documented under-estimate: a mob far below the player in the same column counts as FULL.
		// Both of the resolver's approximations point the same way - towards leaving things alone.
		assertSame(ActivityZone.FULL, resolver.zoneForBlock(0.0, 0.0));
	}

	@Test
	void theNearestPlayerWins() {
		ZoneResolver resolver = resolver();
		resolver.beginTick();
		resolver.addPlayer(10_000.0, 10_000.0);
		resolver.addPlayer(0.0, 0.0);
		resolver.addPlayer(-10_000.0, -10_000.0);

		assertEquals(3, resolver.playerCount());
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(0, 0));
		assertSame(ActivityZone.FROZEN, resolver.zoneForChunk(50, 50));
	}

	@Test
	void negativeCoordinatesAreNotASpecialCase() {
		ZoneResolver resolver = withPlayerAt(-100.0, -100.0);

		assertSame(ActivityZone.FULL, resolver.zoneForBlock(-100.0, -100.0));
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(-7, -7));
		assertSame(ActivityZone.FROZEN, resolver.zoneForChunk(20, 20));
	}

	@Test
	void aPlayerMovingChangesTheAnswerOnTheNextTick() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);
		assertSame(ActivityZone.FROZEN, resolver.zoneForChunk(40, 0));

		resolver.beginTick();
		resolver.addPlayer(640.0, 0.0);

		// Same chunk, new tick, new answer: the cache is invalidated per tick, not per lookup.
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(40, 0));
	}

	@Test
	void aPlayerLeavingRestoresTheEmptyWorldRule() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);
		assertSame(ActivityZone.FROZEN, resolver.zoneForChunk(100, 100));

		resolver.beginTick();

		assertFalse(resolver.hasPlayers());
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(100, 100));
	}

	@Test
	void changingTheRadiiTakesEffectImmediately() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);
		assertSame(ActivityZone.REDUCED, resolver.zoneForChunk(4, 0));

		// /tickpilot reload with a wider full radius: the cached answers were computed against the
		// old one and must not survive.
		resolver.setRadii(128, 256);

		assertSame(ActivityZone.FULL, resolver.zoneForChunk(4, 0));
		assertEquals(128, resolver.fullRadius());
		assertEquals(256, resolver.reducedRadius());
	}

	@Test
	void nonsensicalRadiiAreRepairedRatherThanTrusted() {
		ZoneResolver resolver = new ZoneResolver(-5, -10);

		assertEquals(0, resolver.fullRadius());
		assertEquals(0, resolver.reducedRadius());

		// reduced below full would make the REDUCED zone an empty interval; it is raised instead.
		resolver.setRadii(64, 16);
		assertEquals(64, resolver.fullRadius());
		assertEquals(64, resolver.reducedRadius());
	}

	@Test
	void manyPlayersAreHandledWithoutLosingAny() {
		ZoneResolver resolver = resolver();
		resolver.beginTick();

		// More than the initial capacity, so the internal arrays have to grow mid-tick.
		for (int i = 0; i < 200; i++) {
			resolver.addPlayer(10_000.0 + i, 10_000.0);
		}

		resolver.addPlayer(0.0, 0.0);

		assertEquals(201, resolver.playerCount());
		assertSame(ActivityZone.FULL, resolver.zoneForChunk(0, 0), "the last player still counts");
	}

	@Test
	void theCacheNeverChangesAnAnswer() {
		ZoneResolver cached = resolver();
		cached.beginTick();
		cached.addPlayer(37.0, -91.0);

		// Far more distinct chunks than the cache has slots, so entries are evicted and recomputed
		// throughout. A direct-mapped cache may only ever cost a recomputation, never a wrong
		// answer, and this walks enough of the space to catch it if it did.
		for (int chunkX = -80; chunkX <= 80; chunkX++) {
			for (int chunkZ = -40; chunkZ <= 40; chunkZ++) {
				ZoneResolver fresh = resolver();
				fresh.beginTick();
				fresh.addPlayer(37.0, -91.0);

				assertSame(fresh.zoneForChunk(chunkX, chunkZ), cached.zoneForChunk(chunkX, chunkZ),
						"chunk " + chunkX + "/" + chunkZ);
			}
		}
	}

	@Test
	void repeatedLookupsOfTheSameChunkAgreeWithThemselves() {
		ZoneResolver resolver = withPlayerAt(0.0, 0.0);

		for (int i = 0; i < 100; i++) {
			assertSame(ActivityZone.REDUCED, resolver.zoneForChunk(4, 0));
		}
	}
}
