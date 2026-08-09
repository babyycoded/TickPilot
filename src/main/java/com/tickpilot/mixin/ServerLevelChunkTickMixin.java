package com.tickpilot.mixin;

import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times chunk environment ticking for SPEC FR-2 {@code RANDOM_TICKS}.
 *
 * <h2>Target</h2>
 * {@code ServerLevel.tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V}, verified against
 * {@code mappings.tiny} (intermediary {@code method_18203}) and {@code javap}.
 *
 * <h2>The category is wider than its name, on purpose</h2>
 * Reading the method: it does {@code thunder}, then {@code iceandsnow} and
 * {@code tickPrecipitation}, and only then {@code tickBlocks} with the actual
 * {@code BlockState.randomTick} and {@code FluidState.randomTick} calls. So this measures all
 * per-chunk environment work, not only random ticks.
 *
 * <p>Narrowing it to the {@code randomTick} calls alone would mean a hook inside the block loop,
 * which runs {@code randomTickSpeed} times per section per chunk — a {@code nanoTime()} pair per
 * block. That breaks SPEC INV-6 and blows the INV-10 overhead cap for a distinction nobody acts
 * on. The category is therefore reported under a display name that matches what it contains
 * ("Chunk environment") and the mismatch with the FR-2 name is recorded in SPEC §13.
 *
 * <h2>Nesting</h2>
 * This runs inside {@code ServerChunkCache.tick}, which {@code ServerChunkCacheMixin} times as
 * {@code CHUNK_OPS}. The profiler subtracts this frame from that one, so the two categories do not
 * both claim the same nanoseconds (SPEC AC-2).
 *
 * <h2>Compatibility risk</h2>
 * Low. Lithium 1.21.1 has {@code mixin.world.chunk_ticking}, but the package contains only
 * {@code spread_ice} — a targeted change inside the method, not a replacement of it. Verified
 * against the {@code 1.21.1} branch of {@code CaffeineMC/lithium-fabric}.
 */
@Mixin(ServerLevel.class)
public class ServerLevelChunkTickMixin {

	@Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("HEAD"))
	private void tickpilot$beginChunkTick(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		ProfilerHook.begin(TickCategory.RANDOM_TICKS, null);
	}

	@Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("RETURN"))
	private void tickpilot$endChunkTick(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		ProfilerHook.end();
	}
}
