package com.tickpilot.command;

import java.util.List;
import java.util.Locale;

import com.tickpilot.budget.LoadLevel;
import com.tickpilot.metrics.TickMetrics;
import com.tickpilot.metrics.TickMetricsSnapshot;
import com.tickpilot.profiler.TickCategory;

/**
 * Turns the measurements into the single recommendation SPEC FR-13 asks for, plus an honest
 * estimate of what acting on it would buy.
 *
 * <h2>What this class is allowed to say</h2>
 * AC-13 asks for one concrete recommendation and forbids promises of magic. Two rules fall out
 * of that and are enforced by the shape of {@link Effect}:
 *
 * <ul>
 *   <li>An effect is quantified only when the number is an <em>upper bound derived from a
 *       measurement</em> — "at most this many ms, and only if the thing stops entirely". Anything
 *       that would need a prediction of how the cost is distributed across instances, or of where
 *       players will walk next, is {@link Effect#UNKNOWN}. There is no middle estimate, because a
 *       middle estimate would be a guess wearing a number.</li>
 *   <li>The bound is expressed against MSPT, and the TPS consequence is stated separately: below
 *       50 ms/tick the server is already at 20 TPS, so saving time there buys headroom and not
 *       throughput. Conflating the two is how "this will double your TPS" gets written.</li>
 * </ul>
 *
 * <h2>Why it takes no {@code MinecraftServer}</h2>
 * Everything it reads — {@link TickMetricsSnapshot}, {@link LoadLevel}, {@link TickCategory} and
 * the {@link Profile} record below — is already free of {@code net.minecraft}, so the whole
 * decision table is unit-tested without launching the game (SPEC §8). The caller turns the
 * returned keys into {@code Component}s; nothing here knows what a chat message is.
 */
public final class ExplainAdvisor {
	/**
	 * MSPT at which the server stops holding 20 TPS: one tick per 50 ms. Below it, removing work
	 * cannot raise TPS at all, which is the difference between "buys headroom" and "buys TPS".
	 */
	public static final double TPS_CAP_MSPT = 50.0;

	/** Session length suggested whenever the recommendation is "go and measure". */
	public static final int SUGGESTED_PROFILE_SECONDS = 60;

	private ExplainAdvisor() {
	}

	/**
	 * What the profiler knows, flattened to the few values the decision table reads.
	 *
	 * @param sessionTicks           ticks folded into the current session; 0 means nothing was
	 *                               ever profiled and no category claim may be made
	 * @param consistent             {@code false} when a profiler self-check counter is non-zero,
	 *                               i.e. the category numbers are not trustworthy
	 * @param dominant               costliest measured category, or {@code null} without a session
	 * @param dominantMsptPerTick    that category's mean cost per tick
	 * @param dominantSharePercent   its share of the measured tick
	 * @param totalMsptPerTick       mean whole tick over the session, the basis of the share
	 * @param topTypeId              costliest type inside {@code dominant}, or {@code null} when
	 *                               the category has no per-type attribution
	 * @param topTypeMsptPerTick     that type's mean cost per tick
	 * @param topTypeInstancesPerTick instances of it ticked per tick, averaged over the session
	 */
	public record Profile(
			long sessionTicks,
			boolean consistent,
			TickCategory dominant,
			double dominantMsptPerTick,
			double dominantSharePercent,
			double totalMsptPerTick,
			String topTypeId,
			double topTypeMsptPerTick,
			double topTypeInstancesPerTick) {

		/** @return a profile for a server that has never run a session */
		public static Profile none() {
			return new Profile(0L, true, null, 0.0, 0.0, 0.0, null, 0.0, 0.0);
		}

		/** @return {@code true} when a category claim may be made at all */
		public boolean hasSession() {
			return sessionTicks > 0L && dominant != null;
		}
	}

	/**
	 * The honesty classes an effect estimate may fall into. There are deliberately only five, and
	 * only the two bounded ones carry a number.
	 */
	public enum Effect {
		/** An upper bound, on a tick already under {@link ExplainAdvisor#TPS_CAP_MSPT}: headroom. */
		BOUNDED_HEADROOM("command.tickpilot.explain.effect.bounded_headroom"),

		/** An upper bound, on a tick above {@link ExplainAdvisor#TPS_CAP_MSPT}: TPS may recover. */
		BOUNDED_TPS("command.tickpilot.explain.effect.bounded_tps"),

		/** No honest number can be given. AC-13 names this an acceptable answer. */
		UNKNOWN("command.tickpilot.explain.effect.unknown"),

		/** The recommendation is to measure, so there is no effect on performance to estimate. */
		MEASUREMENT("command.tickpilot.explain.effect.measurement"),

		/** Nothing is being recommended, so there is nothing to estimate. */
		NONE_NEEDED("command.tickpilot.explain.effect.none_needed");

		private final String translationKey;

		Effect(String translationKey) {
			this.translationKey = translationKey;
		}

		/** @return the translation key for this estimate's wording */
		public String translationKey() {
			return translationKey;
		}
	}

	/**
	 * The one recommendation of AC-13, as translation keys and their arguments.
	 *
	 * @param messageKey  key of the recommendation text
	 * @param args        arguments for {@code messageKey}, already formatted as strings
	 * @param effect      how honestly the effect can be quantified
	 * @param effectArgs  arguments for {@link Effect#translationKey()}; empty for the unquantified
	 *                    classes
	 */
	public record Recommendation(String messageKey, List<Object> args, Effect effect,
			List<Object> effectArgs) {

		/** Defensive copies, so a caller cannot rewrite a recommendation after it was made. */
		public Recommendation {
			args = List.copyOf(args);
			effectArgs = List.copyOf(effectArgs);
		}
	}

	/**
	 * Picks the single recommendation for the current state of the server.
	 *
	 * <p>The order of the branches is the order of the questions a person would ask: is the
	 * reading meaningful at all, is it trustworthy, is anything actually wrong, and only then what
	 * to do about it.
	 *
	 * @param metrics          the tick metrics at the moment the command ran
	 * @param level            the load level currently held (SPEC FR-5)
	 * @param targetMspt       MSPT at which the server leaves its budget
	 * @param criticalMspt     MSPT at which a tick counts as a spike
	 * @param warmingUp        whether the load level is still pinned by the warm-up window
	 * @param tickRateModified whether {@code /tick freeze} or {@code /tick rate} is in force
	 * @param profile          what the profiler knows; {@link Profile#none()} if nothing
	 * @return the recommendation, never {@code null}
	 */
	public static Recommendation advise(TickMetricsSnapshot metrics, LoadLevel level,
			double targetMspt, double criticalMspt, boolean warmingUp, boolean tickRateModified,
			Profile profile) {
		// 1. Is the reading about server load at all?
		if (tickRateModified) {
			return plain("command.tickpilot.explain.rec.tick_rate", Effect.NONE_NEEDED);
		}

		if (warmingUp) {
			return plain("command.tickpilot.explain.rec.warming_up", Effect.NONE_NEEDED);
		}

		// 2. Is it trustworthy? A non-zero self-check means a hook is wrong, so every category
		// number below would be a lie told with confidence.
		if (profile.sessionTicks() > 0L && !profile.consistent()) {
			return plain("command.tickpilot.explain.rec.inconsistent", Effect.NONE_NEEDED);
		}

		// 3. Is anything wrong right now? The 5 s average is the same input the load level uses,
		// so explain and status cannot disagree about whether the server is over budget.
		boolean overBudget = metrics.avgMspt5s() >= targetMspt
				|| level.ordinal() >= LoadLevel.ELEVATED.ordinal();

		if (overBudget) {
			if (!profile.hasSession()) {
				return new Recommendation("command.tickpilot.explain.rec.profile_first",
						List.of(SUGGESTED_PROFILE_SECONDS), Effect.MEASUREMENT, List.of());
			}

			return forCategory(profile);
		}

		// 4. Nothing is wrong on average. Spikes are a separate question, and the two percentile
		// windows exist precisely to keep "it is happening now" apart from "it happened earlier".
		if (spikingNow(metrics, criticalMspt)) {
			return new Recommendation("command.tickpilot.explain.rec.spikes_now",
					List.of(format(metrics.avgMspt5s()), format(metrics.p99Mspt1m()),
							SUGGESTED_PROFILE_SECONDS),
					Effect.MEASUREMENT, List.of());
		}

		if (spikedBefore(metrics, criticalMspt)) {
			return new Recommendation("command.tickpilot.explain.rec.past_spikes",
					List.of(format(metrics.p99MsptHistory()), SUGGESTED_PROFILE_SECONDS),
					Effect.MEASUREMENT, List.of());
		}

		return new Recommendation("command.tickpilot.explain.rec.healthy",
				List.of(format(metrics.avgMspt5s()), format(targetMspt)),
				Effect.NONE_NEEDED, List.of());
	}

	/**
	 * Whether spikes are happening now: the 1 min p99 is at or above the critical threshold, i.e.
	 * one tick in a hundred over the last minute was as slow as a CRITICAL server.
	 *
	 * @param metrics      the metrics to read
	 * @param criticalMspt the configured critical threshold
	 */
	public static boolean spikingNow(TickMetricsSnapshot metrics, double criticalMspt) {
		return metrics.p99Mspt1m() >= criticalMspt;
	}

	/**
	 * Whether spikes happened earlier but not now.
	 *
	 * <p>Two guards, both load-bearing. The claim is only made when the retained history is
	 * genuinely longer than the short window — otherwise both percentile pairs are computed from
	 * the same samples and presenting them as past-versus-present would be an artefact, not a
	 * finding. And the trigger is the history p99 rather than the maximum, because the single
	 * slowest tick of almost any server is its startup tick at ~120 ms; that outlier is printed
	 * with its age on its own line and does not need to become a diagnosis.
	 *
	 * @param metrics      the metrics to read
	 * @param criticalMspt the configured critical threshold
	 */
	public static boolean spikedBefore(TickMetricsSnapshot metrics, double criticalMspt) {
		return metrics.retainedSpanNanos() > TickMetrics.WINDOW_1M_NANOS
				&& !spikingNow(metrics, criticalMspt)
				&& metrics.p99MsptHistory() >= criticalMspt;
	}

	/**
	 * Whether the server has been up long enough for the window the verdict rests on to mean what
	 * it says. Below this the numbers are still printed — a server dying thirty seconds after
	 * start is really dying — but the output says what they cover.
	 *
	 * @param metrics the metrics to read
	 */
	public static boolean hasFullWindow(TickMetricsSnapshot metrics) {
		return metrics.covers(TickMetrics.WINDOW_1M_NANOS);
	}

	/**
	 * The recommendation for an over-budget server whose dominant category is known.
	 *
	 * <p>Only the two categories with per-type attribution can name a culprit and therefore bound
	 * an effect. The rest get a direction to look in and an explicit {@link Effect#UNKNOWN}: there
	 * is nothing measured to compute a bound from, and inventing one is exactly what AC-13
	 * forbids.
	 */
	private static Recommendation forCategory(Profile profile) {
		String share = format(profile.dominantSharePercent());
		String cost = format(profile.dominantMsptPerTick());

		return switch (profile.dominant()) {
			case ENTITIES, BLOCK_ENTITIES -> byType(profile);
			case CHUNK_OPS -> new Recommendation("command.tickpilot.explain.rec.chunk_ops",
					List.of(cost, share), Effect.UNKNOWN, List.of());
			case SCHEDULED_TICKS -> new Recommendation(
					"command.tickpilot.explain.rec.scheduled_ticks", List.of(cost, share),
					Effect.UNKNOWN, List.of());
			case RANDOM_TICKS -> new Recommendation("command.tickpilot.explain.rec.random_ticks",
					List.of(cost, share), Effect.UNKNOWN, List.of());
			case NETWORK -> new Recommendation("command.tickpilot.explain.rec.network",
					List.of(cost, share), Effect.UNKNOWN, List.of());
			case SAVING -> new Recommendation("command.tickpilot.explain.rec.saving",
					List.of(cost, share, profile.sessionTicks()), Effect.UNKNOWN, List.of());
			case OTHER -> new Recommendation("command.tickpilot.explain.rec.other",
					List.of(cost, share), Effect.UNKNOWN, List.of());
			// TOTAL is never a dominant category: it is the whole tick, not a part of it.
			case TOTAL -> plain("command.tickpilot.explain.rec.unattributed", Effect.UNKNOWN);
		};
	}

	/**
	 * Names the costliest type and bounds what removing it could save.
	 *
	 * <p>The bound is the type's own measured cost. It holds only if every instance of it stops
	 * ticking, and the wording says so; nothing is claimed about removing half of them, because
	 * how the cost is spread across instances was never measured.
	 */
	private static Recommendation byType(Profile profile) {
		if (profile.topTypeId() == null) {
			// Category measured, per-type attribution not available: bound the category instead of
			// naming a type that was never identified.
			return new Recommendation("command.tickpilot.explain.rec.category_only",
					List.of(format(profile.dominantMsptPerTick()),
							format(profile.dominantSharePercent())),
					Effect.UNKNOWN, List.of());
		}

		String messageKey = profile.dominant() == TickCategory.ENTITIES
				? "command.tickpilot.explain.rec.entity_type"
				: "command.tickpilot.explain.rec.block_entity_type";

		double savedMspt = profile.topTypeMsptPerTick();
		double totalMspt = profile.totalMsptPerTick();
		double sharePercent = totalMspt > 0.0 ? savedMspt / totalMspt * 100.0 : 0.0;

		Effect effect = totalMspt >= TPS_CAP_MSPT ? Effect.BOUNDED_TPS : Effect.BOUNDED_HEADROOM;

		return new Recommendation(messageKey,
				List.of(profile.topTypeId(), format(savedMspt),
						format(profile.topTypeInstancesPerTick())),
				effect,
				List.of(format(savedMspt), format(sharePercent), format(totalMspt)));
	}

	private static Recommendation plain(String messageKey, Effect effect) {
		return new Recommendation(messageKey, List.of(), effect, List.of());
	}

	/**
	 * Formats a metric for display.
	 *
	 * <p>Deliberately a private copy rather than a call into {@code TickPilotCommand}: touching
	 * that class from here would drag {@code net.minecraft} into the advisor's tests, which is the
	 * one thing this package boundary exists to prevent. {@link Locale#ROOT} so the decimal
	 * separator cannot follow the server's locale.
	 */
	private static String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
