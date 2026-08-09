package com.tickpilot.mixin;

import java.util.function.BiConsumer;

import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times scheduled block and fluid ticks for SPEC FR-2 {@code SCHEDULED_TICKS}.
 *
 * <h2>Target</h2>
 * {@code LevelTicks.tick(JILjava/util/function/BiConsumer;)V}, verified against
 * {@code mappings.tiny} (intermediary {@code method_39377}) and {@code javap}.
 *
 * <p>{@code ServerLevel.tick} drives this twice per tick — once for {@code blockTicks} and once for
 * {@code fluidTicks}, both with a budget of 65536. Both land in the same category, as two sibling
 * frames, which is what FR-2 asks for: one number for scheduled ticks.
 *
 * <h2>Why a Fabric event will not do</h2>
 * No Fabric API event covers the scheduled tick phase at any granularity.
 *
 * <h2>Compatibility risk</h2>
 * Low. Lithium 1.21.1 has a {@code mixin.world.tick_scheduler} option, and it was worth checking
 * carefully because the names are close: its {@code LevelChunkTicksMixin} is an {@code @Overwrite}
 * of most of {@code LevelChunkTicks}, the <em>per-chunk storage</em>. It does not touch
 * {@code LevelTicks}, the level-wide scheduler this injects into. Verified against the
 * {@code 1.21.1} branch of {@code CaffeineMC/lithium-fabric}. Even if a mod did replace
 * {@code LevelTicks.tick}, two {@code @Inject}s at HEAD and RETURN coexist with anything that is
 * not an {@code @Overwrite}.
 *
 * <h2>Why no finer hook</h2>
 * Timing each individual scheduled tick would mean a hook inside {@code runCollectedTicks}, which
 * runs per scheduled block. At 65536 ticks per phase that is a {@code nanoTime()} pair per block —
 * a clear SPEC INV-6 and INV-10 violation for a category FR-2 only asks for one number for.
 */
@Mixin(LevelTicks.class)
public class LevelTicksMixin {

	@Inject(method = "tick(JILjava/util/function/BiConsumer;)V", at = @At("HEAD"))
	private void tickpilot$beginScheduledTicks(long gameTime, int budget,
			BiConsumer<BlockPos, ?> ticker, CallbackInfo ci) {
		ProfilerHook.begin(TickCategory.SCHEDULED_TICKS, null);
	}

	@Inject(method = "tick(JILjava/util/function/BiConsumer;)V", at = @At("RETURN"))
	private void tickpilot$endScheduledTicks(long gameTime, int budget,
			BiConsumer<BlockPos, ?> ticker, CallbackInfo ci) {
		ProfilerHook.end();
	}
}
