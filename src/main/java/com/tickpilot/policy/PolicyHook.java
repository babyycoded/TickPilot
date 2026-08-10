package com.tickpilot.policy;

import com.tickpilot.budget.LoadLevel;
import com.tickpilot.config.AdaptiveMode;
import com.tickpilot.zones.ActivityZone;
import com.tickpilot.zones.ZoneTracker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * What the Mixins call to have one object put through {@link TickPolicy} and counted
 * (SPEC FR-8, FR-9).
 *
 * <h2>This is the diagnostic half and it changes nothing</h2>
 * Every method here <em>returns void</em>. The verdict is tallied and thrown away; no tick is
 * skipped, no control flow of the game is touched. That is deliberate and is the first half of
 * SPEC INV-3 applied to the order of work: measure what thinning would do, in the real world, with
 * real numbers, before anything is allowed to do it.
 *
 * <h2>Why a static park, and why it is not global state</h2>
 * Same reasoning as {@code ProfilerHook} and SPEC §13 entry #5: a Mixin inside {@code ServerLevel}
 * has no cheap route back to the per-server state, so the tick listener parks what is needed for
 * the duration of a tick and clears it at the end. Between ticks, and between worlds, every field
 * here is {@code null} or a default.
 *
 * <h2>Thread safety</h2>
 * {@link #owner} is compared on every call, exactly as in {@code ProfilerHook}. On a singleplayer
 * client the render thread runs {@code ClientLevel.tickBlockEntities} through the same Mixin; the
 * comparison rejects it, so no client object is ever read here (SPEC INV-1).
 *
 * <h2>Cost when nothing is parked</h2>
 * One static read and a null check. When parked: a zone lookup (a hash and an array read), a few
 * boolean tests on the object, and two array increments. No allocation, no clock (SPEC INV-6).
 */
public final class PolicyHook {
	private static volatile PolicyDiagnostics diagnostics;
	private static volatile ZoneTracker zones;
	private static volatile TypeLists lists;
	private static volatile Thread owner;

	private static AdaptiveMode mode = AdaptiveMode.STRICT;
	private static LoadLevel load = LoadLevel.NORMAL;
	private static boolean adaptiveEnabled;
	private static int entityInterval = 1;

	private PolicyHook() {
	}

	/**
	 * Parks everything the decision needs for the current tick. Called from the tick listener on
	 * the server thread.
	 *
	 * @param diagnostics   where verdicts are tallied
	 * @param zones         the per-world zone tracker, already refilled for this tick
	 * @param lists         the operator's resolved id lists
	 * @param mode          the mode actually in force, including the compatibility override
	 * @param load          the load level held this tick
	 * @param adaptiveEnabled the {@code enable_adaptive_mode} flag
	 * @param entityInterval  {@code min_entity_update_interval_ticks}; 1 thins nothing
	 */
	public static void attach(PolicyDiagnostics diagnostics, ZoneTracker zones, TypeLists lists,
			AdaptiveMode mode, LoadLevel load, boolean adaptiveEnabled, int entityInterval) {
		PolicyHook.mode = mode;
		PolicyHook.load = load;
		PolicyHook.adaptiveEnabled = adaptiveEnabled;
		PolicyHook.entityInterval = entityInterval;
		PolicyHook.zones = zones;
		PolicyHook.lists = lists;
		PolicyHook.owner = Thread.currentThread();
		// Written last: it is the field every hook tests first, so nothing can observe a
		// half-populated park.
		PolicyHook.diagnostics = diagnostics;
	}

	/** Unparks whatever {@link #attach} parked. Must run even if the tick threw. */
	public static void detach() {
		diagnostics = null;
		zones = null;
		lists = null;
		owner = null;
	}

	/** @return {@code true} when nothing is parked; the expected state between ticks */
	public static boolean isDetached() {
		return diagnostics == null;
	}

	/**
	 * Puts one entity through the policy and counts the verdict. Hot path.
	 *
	 * <p>Called from the same injector that opens the profiler's frame, never from a second Mixin
	 * on the same instruction: two injectors at one HEAD have no defined order between them, and
	 * when this half grows the ability to cancel, an ordering where the profiler's frame is opened
	 * and then the tick is cancelled would leave that frame unclosed.
	 *
	 * @param entity the entity about to be ticked
	 */
	public static void recordEntity(Entity entity) {
		PolicyDiagnostics tally = diagnostics;

		if (tally == null || Thread.currentThread() != owner) {
			return;
		}

		// Players are never candidates for anything, and counting them would put a handful of
		// TICK_PROTECTED rows into a tally that is meant to describe the load.
		if (entity instanceof Player) {
			return;
		}

		Level level = entity.level();
		ChunkPos chunk = entity.chunkPosition();
		ActivityZone zone = zones.zoneFor(level, chunk.x, chunk.z);

		tally.recordEntity(TickPolicy.decide(zone, mode, load, adaptiveEnabled,
				lists.isAllowlisted(entity.getType()), lists.isDenylisted(entity.getType()),
				isProtected(entity, level, chunk)));
	}

	/**
	 * Puts one block entity through the policy and counts the verdict. Hot path.
	 *
	 * @param blockEntity the block entity about to be ticked
	 */
	public static void recordBlockEntity(BlockEntity blockEntity) {
		PolicyDiagnostics tally = diagnostics;

		if (tally == null || Thread.currentThread() != owner) {
			return;
		}

		Level level = blockEntity.getLevel();

		if (level == null) {
			return;
		}

		BlockPos pos = blockEntity.getBlockPos();
		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;
		ActivityZone zone = zones.zoneFor(level, chunkX, chunkZ);
		boolean protectedObject = lists.isExcluded(blockEntity.getType())
				|| isForceLoaded(level, chunkX, chunkZ);

		tally.recordBlockEntity(TickPolicy.decide(zone, mode, load, adaptiveEnabled,
				lists.isAllowlisted(blockEntity.getType()),
				lists.isDenylisted(blockEntity.getType()), protectedObject));
	}

	/**
	 * Whether this mob's AI step should be skipped on this tick — the one call in TickPilot that
	 * changes what the game does (SPEC FR-8, AC-8).
	 *
	 * <p>What a skip costs the mob is bounded by where vanilla calls the method: goals, navigation,
	 * sensing and the movement controllers. Its physics, {@code travel()}, riding, leash, breeding
	 * and growth are in {@code LivingEntity.aiStep} and {@code Animal.aiStep}, which are the
	 * <em>caller</em> and keep running every tick. That is what makes this hook satisfy AC-8's "never
	 * change the frequency of the full Entity.tick()" while a hook one level up would not.
	 *
	 * <p>Five conditions must all hold, and the first four are the same policy every diagnostic
	 * count went through:
	 * <ol>
	 *   <li>the thinning interval is above 1, i.e. the operator asked for thinning at all;</li>
	 *   <li>the mob is not protected, not denylisted, and is on the operator's allowlist;</li>
	 *   <li>the zone, the mode and the load level all permit it;</li>
	 *   <li>the mob has no attack target — a mob mid-combat is pathing towards something a player
	 *       can see, and thinning that is visible in a way no config value asked for;</li>
	 *   <li>the staggered schedule says this is not its tick.</li>
	 * </ol>
	 *
	 * @param mob the mob about to run its AI step
	 * @return {@code true} to skip the AI step
	 */
	public static boolean shouldSkipAi(Mob mob) {
		PolicyDiagnostics tally = diagnostics;

		if (tally == null || Thread.currentThread() != owner) {
			return false;
		}

		if (!ThinningSchedule.thins(entityInterval)) {
			// The shipped default. Nothing is counted either: an interval of 1 means this feature
			// is switched off, not that it decided against every mob.
			return false;
		}

		Level level = mob.level();
		ChunkPos chunk = mob.chunkPosition();
		ThrottleVerdict verdict = TickPolicy.decide(zones.zoneFor(level, chunk.x, chunk.z), mode,
				load, adaptiveEnabled, lists.isAllowlisted(mob.getType()),
				lists.isDenylisted(mob.getType()), isProtected(mob, level, chunk));

		if (!verdict.isEligible() || mob.getTarget() != null) {
			tally.recordAiDecision(false);
			return false;
		}

		boolean skip = !ThinningSchedule.runsOnTick(level.getGameTime(), mob.getId(),
				entityInterval);
		tally.recordAiDecision(skip);
		return skip;
	}

	/**
	 * The per-object protections of SPEC AC-7 and INV-8, in the order they are cheapest to test.
	 *
	 * <p>Each one is a case where thinning would break something a player can see, and none of them
	 * is negotiable from the config:
	 * <ul>
	 *   <li>riding or ridden — a passenger's tick is driven by its vehicle's, so thinning either
	 *       breaks both;</li>
	 *   <li>on a lead — the leash pulls the entity every tick, and a thinned entity would drift;</li>
	 *   <li>persistent or named — somebody deliberately kept this one;</li>
	 *   <li>always-ticking — vanilla's own marker for entities that must never be skipped;</li>
	 *   <li>excluded by the operator, by id or by mod namespace;</li>
	 *   <li>in a force-loaded chunk — the operator loaded it precisely so it would keep running.</li>
	 * </ul>
	 */
	private static boolean isProtected(Entity entity, Level level, ChunkPos chunk) {
		if (entity.isPassenger() || entity.isVehicle() || entity.isAlwaysTicking()
				|| entity.hasCustomName()) {
			return true;
		}

		if (entity instanceof Leashable leashable && leashable.isLeashed()) {
			return true;
		}

		if (entity instanceof Mob mob && mob.isPersistenceRequired()) {
			return true;
		}

		return lists.isExcluded(entity.getType()) || isForceLoaded(level, chunk.x, chunk.z);
	}

	/**
	 * @return whether the chunk is force-loaded. The empty check comes first because on almost
	 *         every server the set is empty, and then this costs one field read rather than a hash
	 */
	private static boolean isForceLoaded(Level level, int chunkX, int chunkZ) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return false;
		}

		var forced = serverLevel.getForcedChunks();
		return !forced.isEmpty() && forced.contains(ChunkPos.asLong(chunkX, chunkZ));
	}
}
