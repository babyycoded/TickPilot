package com.tickpilot.mixin;

import java.util.function.BooleanSupplier;

import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.server.level.ServerChunkCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times chunk management for SPEC FR-2 {@code CHUNK_OPS}.
 *
 * <h2>Target</h2>
 * {@code ServerChunkCache.tick(Ljava/util/function/BooleanSupplier;Z)V}, verified against
 * {@code mappings.tiny} (intermediary {@code method_12127} family) and {@code javap}.
 *
 * <p>The method covers ticket purging, distance manager updates, {@code tickChunks} (which is
 * natural spawning plus per-chunk environment ticking), {@code chunkMap.tick} and the unload pass.
 * That is the whole of what FR-2 means by chunk operations.
 *
 * <h2>Why the number is not simply this method's wall time</h2>
 * {@code tickChunks} calls {@code ServerLevel.tickChunk} for every chunk, and that is timed
 * separately as {@code RANDOM_TICKS} by {@code ServerLevelChunkTickMixin}. Left alone, the two
 * categories would each claim the same nanoseconds and their sum would exceed TOTAL, which AC-2
 * forbids. The profiler subtracts every child frame from its parent before charging anything, so
 * what this frame reports is chunk management <em>excluding</em> the environment ticking nested
 * inside it. This is the third nesting case in the mod and the reason the profiler is built the
 * way it is.
 *
 * <h2>Why a Fabric event will not do</h2>
 * {@code ServerChunkEvents} fires on load, generate, unload and level-type change. Those are
 * lifecycle notifications about individual chunks, not a span around the phase, and they cannot be
 * summed into a per-tick cost.
 *
 * <h2>Compatibility risk</h2>
 * Low. Two {@code @Inject}s at HEAD and RETURN. Lithium 1.21.1 has no mixin on
 * {@code ServerChunkCache}; its chunk work is {@code mixin.chunk.*} — palette, serialisation,
 * locking and entity class groups — none of which is this class. Verified against the
 * {@code 1.21.1} branch of {@code CaffeineMC/lithium-fabric}.
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheMixin {

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"))
	private void tickpilot$beginChunkOps(BooleanSupplier hasTimeLeft, boolean tickChunks,
			CallbackInfo ci) {
		ProfilerHook.begin(TickCategory.CHUNK_OPS, null);
	}

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("RETURN"))
	private void tickpilot$endChunkOps(BooleanSupplier hasTimeLeft, boolean tickChunks,
			CallbackInfo ci) {
		ProfilerHook.end();
	}
}
