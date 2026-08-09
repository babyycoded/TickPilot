package com.tickpilot.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Times the autosave for SPEC FR-2 {@code SAVING}.
 *
 * <h2>Target</h2>
 * The {@code MinecraftServer.saveEverything(ZZZ)Z} call inside
 * {@code MinecraftServer.tickServer(Ljava/util/function/BooleanSupplier;)V}, verified against
 * {@code mappings.tiny} and {@code javap}.
 *
 * <h2>Why the call site and not the method</h2>
 * {@code saveEverything} is also called on shutdown, from {@code /save-all}, and by any mod that
 * wants a save. None of those is part of a tick's cost, and counting them would put a manual
 * {@code /save-all} into the tick budget as if the server had done it to itself. Wrapping the one
 * invocation the autosave block makes is the only way to time exactly the save that a tick caused.
 *
 * <h2>Why {@code @WrapOperation} here and {@code @Inject} everywhere else</h2>
 * This is the only hook that needs to bracket a single instruction rather than a whole method, and
 * {@code @Inject} cannot do that. {@code @Redirect} could, and is forbidden by SPEC MX-3 precisely
 * because it takes exclusive ownership of the instruction and would hard-conflict with any other
 * mod wrapping the same call (§13 entry #12). {@code @WrapOperation} chains instead of colliding.
 * MixinExtras 0.5.4 needs no new dependency: it is nested inside {@code fabric-loader-0.19.3.jar}
 * and is on the compile classpath transitively.
 *
 * <h2>Compatibility risk</h2>
 * Low. The wrapped operation is always called exactly once and its result is returned unchanged,
 * so behaviour is identical with profiling on or off. Lithium does not touch
 * {@code MinecraftServer}.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerSaveMixin {

	@WrapOperation(
			method = "tickServer(Ljava/util/function/BooleanSupplier;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/MinecraftServer;saveEverything(ZZZ)Z"))
	private boolean tickpilot$timeAutosave(MinecraftServer server, boolean suppressLog,
			boolean flush, boolean forced, Operation<Boolean> original) {
		ProfilerHook.begin(TickCategory.SAVING, null);

		try {
			return original.call(server, suppressLog, flush, forced);
		} finally {
			// finally, not a plain sequence: a save that throws must not leave the frame open.
			ProfilerHook.end();
		}
	}
}
