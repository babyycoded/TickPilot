package com.tickpilot.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tickpilot.budget.LoadLevel;
import com.tickpilot.metrics.TickMetrics;
import com.tickpilot.metrics.TickMetricsSnapshot;
import com.tickpilot.profiler.TickCategory;

/**
 * Covers the SPEC FR-13 decision table without launching Minecraft (SPEC §8).
 *
 * <p>Two kinds of test live here. Most pin which recommendation a given state produces. The last
 * few pin the honesty rules of AC-13 themselves: that a quantified effect only ever appears with a
 * measured bound behind it, that every key the advisor can emit exists in the language file, and
 * that no recommendation text promises a multiple of anything.
 */
class ExplainAdvisorTest {
	private static final double TARGET = 40.0;
	private static final double CRITICAL = 50.0;
	private static final long ONE_MINUTE = TickMetrics.WINDOW_1M_NANOS;
	private static final long FIVE_MINUTES = 5L * ONE_MINUTE;

	@Test
	void aHealthyServerIsToldToDoNothing() {
		ExplainAdvisor.Recommendation recommendation = advise(healthy(), LoadLevel.NORMAL,
				ExplainAdvisor.Profile.none());

		assertEquals("command.tickpilot.explain.rec.healthy", recommendation.messageKey());
		assertEquals(ExplainAdvisor.Effect.NONE_NEEDED, recommendation.effect());
		assertTrue(recommendation.effectArgs().isEmpty(),
				"an unquantified effect must carry no number");
	}

	@Test
	void anOverBudgetServerWithNoSessionIsToldToProfileFirst() {
		ExplainAdvisor.Recommendation recommendation = advise(overloaded(), LoadLevel.CRITICAL,
				ExplainAdvisor.Profile.none());

		assertEquals("command.tickpilot.explain.rec.profile_first", recommendation.messageKey());
		assertEquals(ExplainAdvisor.Effect.MEASUREMENT, recommendation.effect());
	}

	@Test
	void blockEntityOverloadNamesTheTypeAndBoundsTheEffect() {
		// 6.10 of a 12.48 ms tick: under the 50 ms cap, so the effect is headroom, not TPS.
		ExplainAdvisor.Profile profile = new ExplainAdvisor.Profile(402L, true,
				TickCategory.BLOCK_ENTITIES, 7.42, 59.5, 12.48, "minecraft:hopper", 6.10, 2840.0);

		ExplainAdvisor.Recommendation recommendation = advise(overloaded(), LoadLevel.HIGH, profile);

		assertEquals("command.tickpilot.explain.rec.block_entity_type", recommendation.messageKey());
		assertEquals("minecraft:hopper", recommendation.args().get(0));
		assertEquals(ExplainAdvisor.Effect.BOUNDED_HEADROOM, recommendation.effect());
		// The bound is the type's own measured cost and its share of the measured tick.
		assertEquals(List.of("6.10", "48.88", "12.48"), recommendation.effectArgs());
	}

	@Test
	void aTickAboveTheTpsCapGetsTheTpsWordingInstead() {
		ExplainAdvisor.Profile profile = new ExplainAdvisor.Profile(400L, true,
				TickCategory.ENTITIES, 40.0, 61.5, 65.0, "minecraft:zombie", 30.0, 900.0);

		ExplainAdvisor.Recommendation recommendation = advise(overloaded(), LoadLevel.CRITICAL,
				profile);

		assertEquals("command.tickpilot.explain.rec.entity_type", recommendation.messageKey());
		assertEquals(ExplainAdvisor.Effect.BOUNDED_TPS, recommendation.effect());
	}

	@Test
	void chunkOverloadIsNeverGivenANumberForItsEffect() {
		ExplainAdvisor.Profile profile = new ExplainAdvisor.Profile(600L, true,
				TickCategory.CHUNK_OPS, 38.6, 71.2, 54.2, null, 0.0, 0.0);

		ExplainAdvisor.Recommendation recommendation = advise(overloaded(), LoadLevel.CRITICAL,
				profile);

		assertEquals("command.tickpilot.explain.rec.chunk_ops", recommendation.messageKey());
		assertEquals(ExplainAdvisor.Effect.UNKNOWN, recommendation.effect(),
				"chunk cost depends on where players move next and cannot be bounded in advance");
		assertTrue(recommendation.effectArgs().isEmpty());
	}

	@Test
	void aMeasuredCategoryWithNoPerTypeDataNamesNoCulprit() {
		ExplainAdvisor.Profile profile = new ExplainAdvisor.Profile(300L, true,
				TickCategory.ENTITIES, 30.0, 60.0, 50.0, null, 0.0, 0.0);

		ExplainAdvisor.Recommendation recommendation = advise(overloaded(), LoadLevel.HIGH, profile);

		assertEquals("command.tickpilot.explain.rec.category_only", recommendation.messageKey());
		assertEquals(ExplainAdvisor.Effect.UNKNOWN, recommendation.effect());
	}

	@Test
	void spikesHappeningNowAreSeparatedFromSpikesThatAlreadyStopped() {
		// Average fine, 1 min p99 above critical: it is happening now.
		TickMetricsSnapshot now = snapshot(20.0, 3.0, 4.0, 80.0, 90.0, 120.0, 2_000_000_000L,
				FIVE_MINUTES, FIVE_MINUTES);
		assertTrue(ExplainAdvisor.spikingNow(now, CRITICAL));
		assertFalse(ExplainAdvisor.spikedBefore(now, CRITICAL));
		assertEquals("command.tickpilot.explain.rec.spikes_now",
				advise(now, LoadLevel.NORMAL, ExplainAdvisor.Profile.none()).messageKey());

		// Same server after it recovered: the short window is clean, the history is not.
		TickMetricsSnapshot recovered = snapshot(20.0, 3.0, 4.0, 6.0, 90.0, 120.0,
				180_000_000_000L, FIVE_MINUTES, FIVE_MINUTES);
		assertFalse(ExplainAdvisor.spikingNow(recovered, CRITICAL));
		assertTrue(ExplainAdvisor.spikedBefore(recovered, CRITICAL));
		assertEquals("command.tickpilot.explain.rec.past_spikes",
				advise(recovered, LoadLevel.NORMAL, ExplainAdvisor.Profile.none()).messageKey());
	}

	@Test
	void pastSpikesAreNotClaimedWhenBothWindowsHoldTheSameSamples() {
		// 40 s of uptime: the "history" pair is computed from the same samples as the 1 min pair,
		// so calling one of them the past would be an artefact of the window, not a finding.
		TickMetricsSnapshot young = snapshot(20.0, 3.0, 4.0, 6.0, 90.0, 120.0, 20_000_000_000L,
				40_000_000_000L, 40_000_000_000L);

		assertFalse(ExplainAdvisor.spikedBefore(young, CRITICAL));
		assertEquals("command.tickpilot.explain.rec.healthy",
				advise(young, LoadLevel.NORMAL, ExplainAdvisor.Profile.none()).messageKey());
	}

	@Test
	void aSingleSlowTickIsNotTreatedAsAHistoryOfSpikes() {
		// The startup tick at 128 ms is the maximum on almost every server. It is printed with its
		// age on its own line; it must not turn into a diagnosis on its own.
		TickMetricsSnapshot afterStartup = snapshot(20.0, 0.3, 0.4, 0.6, 3.2, 128.9,
				200_000_000_000L, FIVE_MINUTES, FIVE_MINUTES);

		assertFalse(ExplainAdvisor.spikedBefore(afterStartup, CRITICAL));
		assertEquals("command.tickpilot.explain.rec.healthy",
				advise(afterStartup, LoadLevel.NORMAL, ExplainAdvisor.Profile.none()).messageKey());
	}

	@Test
	void aShortUptimeDoesNotSuppressARealOverloadVerdict() {
		// 30 s old and already over budget. The output labels the window, but the diagnosis is
		// still given: a server dying thirty seconds after a start really is dying.
		TickMetricsSnapshot young = snapshot(11.0, 92.0, 90.0, 140.0, 150.0, 260.0,
				5_000_000_000L, 30_000_000_000L, 30_000_000_000L);
		ExplainAdvisor.Profile profile = new ExplainAdvisor.Profile(500L, true,
				TickCategory.ENTITIES, 60.0, 66.0, 91.0, "minecraft:zombie", 44.0, 1800.0);

		assertFalse(ExplainAdvisor.hasFullWindow(young));
		assertEquals("command.tickpilot.explain.rec.entity_type",
				advise(young, LoadLevel.CRITICAL, profile).messageKey());
	}

	@Test
	void nothingIsDiagnosedWhileTheTickRateIsModified() {
		ExplainAdvisor.Recommendation recommendation = ExplainAdvisor.advise(overloaded(),
				LoadLevel.CRITICAL, TARGET, CRITICAL, false, true, ExplainAdvisor.Profile.none());

		assertEquals("command.tickpilot.explain.rec.tick_rate", recommendation.messageKey());
		assertEquals(ExplainAdvisor.Effect.NONE_NEEDED, recommendation.effect());
	}

	@Test
	void nothingIsDiagnosedDuringWarmUp() {
		ExplainAdvisor.Recommendation recommendation = ExplainAdvisor.advise(overloaded(),
				LoadLevel.NORMAL, TARGET, CRITICAL, true, false, ExplainAdvisor.Profile.none());

		assertEquals("command.tickpilot.explain.rec.warming_up", recommendation.messageKey());
	}

	@Test
	void anInconsistentProfilerSuppressesEveryCategoryVerdict() {
		ExplainAdvisor.Profile broken = new ExplainAdvisor.Profile(402L, false,
				TickCategory.BLOCK_ENTITIES, 7.42, 59.5, 12.48, "minecraft:hopper", 6.10, 2840.0);

		ExplainAdvisor.Recommendation recommendation = advise(overloaded(), LoadLevel.HIGH, broken);

		assertEquals("command.tickpilot.explain.rec.inconsistent", recommendation.messageKey());
		assertEquals(ExplainAdvisor.Effect.NONE_NEEDED, recommendation.effect());
	}

	@Test
	void everyMeasuredCategoryProducesARecommendation() {
		Set<TickCategory> covered = EnumSet.noneOf(TickCategory.class);

		for (TickCategory category : TickCategory.all()) {
			ExplainAdvisor.Profile profile = new ExplainAdvisor.Profile(400L, true, category,
					30.0, 60.0, 50.0, null, 0.0, 0.0);

			ExplainAdvisor.Recommendation recommendation = advise(overloaded(), LoadLevel.HIGH,
					profile);

			assertTrue(recommendation.messageKey().startsWith("command.tickpilot.explain.rec."),
					category + " produced " + recommendation.messageKey());
			covered.add(category);
		}

		assertEquals(TickCategory.all().length, covered.size());
	}

	/**
	 * The AC-13 honesty rule, enforced rather than reviewed: a number in an effect estimate is only
	 * allowed when it came from a measurement.
	 */
	@Test
	void onlyTheTwoBoundedEffectsCarryNumbers() {
		for (ExplainAdvisor.Effect effect : ExplainAdvisor.Effect.values()) {
			boolean bounded = effect == ExplainAdvisor.Effect.BOUNDED_HEADROOM
					|| effect == ExplainAdvisor.Effect.BOUNDED_TPS;
			String text = translation(effect.translationKey());

			assertEquals(bounded, text.contains("%s"),
					effect + " must " + (bounded ? "" : "not ") + "take arguments: " + text);

			if (bounded) {
				assertTrue(text.contains("at most"),
						effect + " must state its number as an upper bound: " + text);
			}
		}
	}

	@Test
	void everyKeyTheAdvisorCanEmitExistsInTheLanguageFile() throws IOException {
		String lang = languageFile();
		List<String> missing = new ArrayList<>();

		for (ExplainAdvisor.Effect effect : ExplainAdvisor.Effect.values()) {
			if (!lang.contains('"' + effect.translationKey() + '"')) {
				missing.add(effect.translationKey());
			}
		}

		for (String key : emittableMessageKeys()) {
			if (!lang.contains('"' + key + '"')) {
				missing.add(key);
			}
		}

		assertTrue(missing.isEmpty(), "keys with no entry in en_us.json: " + missing);
	}

	/**
	 * No recommendation may promise a multiple. AC-13 bans "makes your server twice as fast"
	 * wording, and the cheapest way to keep it banned is to fail the build if it appears.
	 */
	@Test
	void noRecommendationPromisesAMultiple() {
		List<String> offenders = new ArrayList<>();
		List<String> banned = List.of("faster", "speed up", "speeds up", "boost", "x2", "2x",
				"double the", "guarantee", "will fix", "will improve", "significantly");

		for (String key : emittableMessageKeys()) {
			String text = translation(key).toLowerCase(Locale.ROOT);

			for (String phrase : banned) {
				if (text.contains(phrase)) {
					offenders.add(key + " contains '" + phrase + "'");
				}
			}
		}

		for (ExplainAdvisor.Effect effect : ExplainAdvisor.Effect.values()) {
			String text = translation(effect.translationKey()).toLowerCase(Locale.ROOT);

			for (String phrase : banned) {
				if (text.contains(phrase)) {
					offenders.add(effect + " contains '" + phrase + "'");
				}
			}
		}

		assertTrue(offenders.isEmpty(), "AC-13 forbids promises of magic: " + offenders);
	}

	/** Every recommendation key the decision table can return, gathered by driving it. */
	private static List<String> emittableMessageKeys() {
		List<String> keys = new ArrayList<>();

		keys.add(ExplainAdvisor.advise(overloaded(), LoadLevel.CRITICAL, TARGET, CRITICAL, false,
				true, ExplainAdvisor.Profile.none()).messageKey());
		keys.add(ExplainAdvisor.advise(overloaded(), LoadLevel.NORMAL, TARGET, CRITICAL, true,
				false, ExplainAdvisor.Profile.none()).messageKey());
		keys.add(advise(overloaded(), LoadLevel.HIGH, new ExplainAdvisor.Profile(10L, false,
				TickCategory.ENTITIES, 1.0, 1.0, 2.0, null, 0.0, 0.0)).messageKey());
		keys.add(advise(overloaded(), LoadLevel.CRITICAL, ExplainAdvisor.Profile.none()).messageKey());
		keys.add(advise(healthy(), LoadLevel.NORMAL, ExplainAdvisor.Profile.none()).messageKey());
		keys.add(advise(snapshot(20.0, 3.0, 4.0, 80.0, 90.0, 120.0, 2_000_000_000L, FIVE_MINUTES,
				FIVE_MINUTES), LoadLevel.NORMAL, ExplainAdvisor.Profile.none()).messageKey());
		keys.add(advise(snapshot(20.0, 3.0, 4.0, 6.0, 90.0, 120.0, 180_000_000_000L, FIVE_MINUTES,
				FIVE_MINUTES), LoadLevel.NORMAL, ExplainAdvisor.Profile.none()).messageKey());

		for (TickCategory category : TickCategory.all()) {
			keys.add(advise(overloaded(), LoadLevel.HIGH, new ExplainAdvisor.Profile(400L, true,
					category, 30.0, 60.0, 50.0, null, 0.0, 0.0)).messageKey());
			keys.add(advise(overloaded(), LoadLevel.HIGH, new ExplainAdvisor.Profile(400L, true,
					category, 30.0, 60.0, 50.0, "minecraft:hopper", 20.0, 100.0)).messageKey());
		}

		return keys;
	}

	private static ExplainAdvisor.Recommendation advise(TickMetricsSnapshot metrics,
			LoadLevel level, ExplainAdvisor.Profile profile) {
		return ExplainAdvisor.advise(metrics, level, TARGET, CRITICAL, false, false, profile);
	}

	/** 20 TPS, well inside budget, no spikes anywhere. */
	private static TickMetricsSnapshot healthy() {
		return snapshot(20.0, 1.2, 1.5, 2.4, 3.2, 8.1, 120_000_000_000L, FIVE_MINUTES,
				FIVE_MINUTES);
	}

	/** Over the critical threshold on every window. */
	private static TickMetricsSnapshot overloaded() {
		return snapshot(14.5, 68.0, 66.0, 95.0, 110.0, 180.0, 30_000_000_000L, FIVE_MINUTES,
				FIVE_MINUTES);
	}

	private static TickMetricsSnapshot snapshot(double tps, double avg5s, double avg1m,
			double p99Short, double p99History, double maxMspt, long maxAgeNanos,
			long retainedSpanNanos, long uptimeNanos) {
		return new TickMetricsSnapshot(tps, avg5s, avg5s, avg1m, avg1m, p99Short * 0.8, p99Short,
				p99History * 0.8, p99History, maxMspt, maxAgeNanos, retainedSpanNanos, 6000L, 6000,
				uptimeNanos);
	}

	private static String translation(String key) {
		try {
			String lang = languageFile();
			int start = lang.indexOf('"' + key + '"');
			assertTrue(start >= 0, key + " has no entry in en_us.json");
			int valueStart = lang.indexOf('"', lang.indexOf(':', start)) + 1;
			int valueEnd = valueStart;

			while (lang.charAt(valueEnd) != '"' || lang.charAt(valueEnd - 1) == '\\') {
				valueEnd++;
			}

			return lang.substring(valueStart, valueEnd);
		} catch (IOException e) {
			throw new AssertionError("en_us.json is not readable", e);
		}
	}

	private static String languageFile() throws IOException {
		try (InputStream stream = ExplainAdvisorTest.class
				.getResourceAsStream("/assets/tickpilot/lang/en_us.json")) {
			assertTrue(stream != null, "en_us.json must be on the classpath");
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
