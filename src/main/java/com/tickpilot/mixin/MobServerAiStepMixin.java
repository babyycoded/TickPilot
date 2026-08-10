package com.tickpilot.mixin;

import com.tickpilot.policy.PolicyHook;

import net.minecraft.world.entity.Mob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Thins mob AI for allowlisted types (SPEC FR-8, AC-8, INV-5).
 *
 * <h2>Target</h2>
 * {@code net.minecraft.world.entity.Mob.serverAiStep()V}, verified against {@code mappings.tiny}
 * (intermediary {@code method_6023}) and against {@code javap} on the jar the project compiles
 * against, where it is declared {@code protected final void serverAiStep()}. {@code final} prevents
 * overriding, not injection.
 *
 * <h2>Why this method and not the entity tick</h2>
 * Read off the decompiled 1.21.1 source, the call shape is:
 *
 * <pre>
 * LivingEntity.aiStep():   ... movement damping ...
 *                          if (isImmobile()) { jumping = false; xxa = 0; zza = 0; }
 *                          else if (isEffectiveAi()) serverAiStep();
 *                          ... jump, travel, physics, collisions ...
 * Mob.serverAiStep():      noActionTime++; sensing.tick();
 *                          targetSelector/goalSelector tick; navigation.tick();
 *                          customServerAiStep();       // brains live here
 *                          moveControl/lookControl/jumpControl tick
 * </pre>
 *
 * So cancelling here removes AI decisions and the movement controllers, and nothing else. Physics,
 * {@code travel()}, collisions, riding, leashes and fall damage are in the caller and still run
 * every tick — as do breeding ({@code Animal.aiStep}, {@code inLove--}) and growth
 * ({@code AgeableMob.aiStep}, {@code setAge}), which is what makes passive animals viable candidates
 * at all. AC-8 forbids changing the frequency of the full {@code Entity.tick()}; this is the widest
 * hook that does not.
 *
 * <p>The two hooks one level up were rejected for cause. {@code ServerLevel.tickNonPassenger}
 * performs {@code setOldPosAndRot()} and {@code tickCount++} <em>before</em> {@code entity.tick()},
 * so cancelling it would stop the entity's own tick counter and break interpolation, and by the same
 * source its passengers are ticked inside it, so cancelling would silently stop a whole rider chain.
 * {@code Entity.tick} and {@code LivingEntity.aiStep} are exactly what AC-8 names.
 *
 * <h2>Why not a Fabric event</h2>
 * There is none. {@code fabric-lifecycle-events-v1} 2.6.0 offers entity load, unload and equipment
 * change — lifecycle only, nothing per tick and nothing cancellable. MX-1 is satisfied: an event was
 * looked for and does not exist.
 *
 * <h2>What a skip actually looks like</h2>
 * {@code moveControl.tick()} is what writes {@code xxa}/{@code zza}, and {@code travel()} keeps
 * running, so a thinned mob does not freeze — it <b>coasts on its last input</b>. Squids are the
 * clearest case: their heading comes from a goal and their propulsion from {@code aiStep}, so a
 * thinned squid keeps swimming in a straight line. This is a behaviour change, it is why the feature
 * is off by default, allowlist-only, and why each type is compared against vanilla on a live server
 * before it is recommended anywhere.
 *
 * <h2>Compatibility risk</h2>
 * Moderate, and higher than any other hook in this mod, because it is the only one that cancels.
 * Mitigations: it is a single {@code @Inject} at HEAD with {@code cancellable = true} and no
 * {@code @Overwrite} or {@code @Redirect} (MX-3); it does nothing at all unless an operator both
 * raises {@code min_entity_update_interval_ticks} above 1 and puts the type on
 * {@code throttle_allowlist}, neither of which is a default; and STRICT mode disables it outright.
 *
 * <p>Lithium 0.15.4 does mixin {@code Mob} — {@code entity/inactive_navigations/MobMixin} — but its
 * constant pool references only {@code level()}, {@code startRiding}, {@code getNavigation} and
 * {@code getPath}, verified by reading the class out of the shipped jar. It does not touch
 * {@code serverAiStep}, so there is no shared instruction. Its {@code collections/goals/GoalSelectorMixin}
 * is one reason {@code GoalSelector.tick} was rejected as an alternative target.
 *
 * <h2>Why no safer hook exists</h2>
 * Thinning AI needs a place where AI, and only AI, happens. Above this method sits physics; below it
 * sit the individual goals, whose own {@code tick()} runs many times per mob per tick and would cost
 * more in hook overhead than it could save (SPEC INV-6).
 */
@Mixin(Mob.class)
public abstract class MobServerAiStepMixin {

	@Inject(method = "serverAiStep()V", at = @At("HEAD"), cancellable = true)
	private void tickpilot$maybeSkipAi(CallbackInfo ci) {
		if (PolicyHook.shouldSkipAi((Mob) (Object) this)) {
			ci.cancel();
		}
	}
}
