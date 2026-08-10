package com.tickpilot.mixin;

import com.tickpilot.policy.PolicyHook;
import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times entity ticking for SPEC FR-2 {@code ENTITIES} and FR-3 per-type costs.
 *
 * <h2>Target</h2>
 * {@code ServerLevel.tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V} and
 * {@code ServerLevel.tickPassenger(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V},
 * both verified against {@code mappings.tiny} (intermediary {@code method_18762} and
 * {@code method_18763}) and against {@code javap} on the jar the project compiles against.
 *
 * <h2>Why not a Fabric event</h2>
 * There is none. The whole of {@code fabric-lifecycle-events-v1} 2.6.0 offers
 * {@code ServerEntityEvents.ENTITY_LOAD}, {@code ENTITY_UNLOAD} and {@code EQUIPMENT_CHANGE} —
 * lifecycle only, nothing per tick. {@code ServerTickEvents} stops at whole-world granularity.
 * MX-1 is satisfied: the event was looked for and does not exist.
 *
 * <h2>Why both methods, and why that does not double count</h2>
 * The call shape, read off the decompiled 1.21.1 source, is:
 *
 * <pre>
 * tickNonPassenger(e):  e.tick();      for (p : e.getPassengers()) tickPassenger(e, p);
 * tickPassenger(v, p):  p.rideTick();  for (n : p.getPassengers()) tickPassenger(p, n);
 * </pre>
 *
 * {@code tickNonPassenger} is <em>not</em> recursive — the recursion lives entirely in
 * {@code tickPassenger} — and it has exactly one caller in the whole game,
 * {@code ServerLevel.tick} line 412, which skips any entity that has a vehicle. So a frame is
 * opened once per top-level entity and once per passenger at every depth.
 *
 * <p>A parent's wall time includes its passengers', so adding the two spans up would overstate the
 * category. {@code TickProfiler} subtracts child time from parent time before charging anything,
 * which both keeps the category total equal to the real span and puts a passenger's cost on the
 * passenger's own type instead of on the boat it is riding.
 *
 * <h2>Compatibility risk</h2>
 * Low. Two {@code @Inject}s at HEAD and RETURN, no control flow touched, nothing cancelled. Lithium
 * 1.21.1 does not mixin the entity tick dispatch at all — its entity work is collisions, navigation
 * and lookup ({@code mixin.entity.*}, {@code mixin.chunk.entity_class_groups}) — so there is no
 * shared target. Per SPEC MX-3 no {@code @Overwrite} and no {@code @Redirect} is used anywhere;
 * {@code @Redirect} is what would actually collide with Lithium (§13 entry #12).
 *
 * <h2>What it does besides timing</h2>
 * The HEAD injector also hands the entity to {@code PolicyHook}, which decides what the throttling
 * policies of SPEC FR-8 <em>would</em> do with it and counts the answer. Nothing is skipped and no
 * control flow is touched: the decision is tallied and discarded. Only top-level entities are put
 * through it — a passenger is protected from thinning in any case, and its tick is driven by its
 * vehicle's.
 *
 * <h2>Why no safer hook exists</h2>
 * Timing the whole entity phase from {@code ServerLevel.tick} would give the category total with
 * one hook, but FR-3 needs per-type attribution, which is only available where the individual
 * entity is in scope. These two methods are the narrowest place where it is.
 */
@Mixin(ServerLevel.class)
public class ServerLevelEntityTickMixin {

	@Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
	private void tickpilot$beginNonPassenger(Entity entity, CallbackInfo ci) {
		ProfilerHook.begin(TickCategory.ENTITIES, entity.getType());
		// Same injector rather than a second Mixin on the same instruction, deliberately: two
		// injectors at one HEAD have no defined order between them, and once this call can cancel
		// the tick, an order that opened the profiler's frame first would leave it unclosed.
		PolicyHook.recordEntity(entity);
	}

	@Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
	private void tickpilot$endNonPassenger(Entity entity, CallbackInfo ci) {
		ProfilerHook.end();
	}

	@Inject(
			method = "tickPassenger(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V",
			at = @At("HEAD"))
	private void tickpilot$beginPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
		// The passenger, not the vehicle: FR-3 must blame the thing that actually cost the time.
		ProfilerHook.begin(TickCategory.ENTITIES, passenger.getType());
	}

	@Inject(
			method = "tickPassenger(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V",
			at = @At("RETURN"))
	private void tickpilot$endPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
		ProfilerHook.end();
	}
}
