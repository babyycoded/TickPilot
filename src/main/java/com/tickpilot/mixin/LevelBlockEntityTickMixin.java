package com.tickpilot.mixin;

import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the whole block entity phase for SPEC FR-2 {@code BLOCK_ENTITIES}.
 *
 * <h2>Target</h2>
 * {@code Level.tickBlockEntities()V}, verified against {@code mappings.tiny} (intermediary
 * {@code method_18471}) and {@code javap}. Declared on {@code Level} and not overridden by
 * {@code ServerLevel}, so {@code Level} is the only class it can be targeted on.
 *
 * <h2>Why a Fabric event will not do</h2>
 * {@code ServerBlockEntityEvents} has {@code BLOCK_ENTITY_LOAD} and {@code BLOCK_ENTITY_UNLOAD}
 * and nothing else. There is no per-tick event at any granularity below the world.
 *
 * <h2>What this measures that the per-type hook does not</h2>
 * The list walk itself: growing {@code blockEntityTickers} from the pending list, the iterator, the
 * {@code isRemoved} and {@code shouldTickBlocksAt} checks. On a server with tens of thousands of
 * tickers that is not noise. {@code LevelChunkBoundTickerMixin} charges the individual block
 * entities; the difference stays here, on this frame, because the profiler subtracts child frames
 * from their parent.
 *
 * <h2>Compatibility risk</h2>
 * Low, but this is the one method Lithium also touches. Its
 * {@code mixin.world.block_entity_ticking.chunk_tickable/LevelMixin} puts a {@code @Redirect} on
 * the {@code Level.shouldTickBlocksAt(BlockPos)Z} call <em>inside</em> this method — a different
 * instruction from the HEAD and RETURN this injects at, so the two do not contend. Verified
 * against the {@code 1.21.1} branch of {@code CaffeineMC/lithium-fabric}. Lithium's
 * {@code block_entity_ticking.sleeping.*} options make idle block entities stop ticking, which
 * changes the numbers honestly rather than breaking the hook.
 *
 * <h2>Client side</h2>
 * {@code ClientLevel} inherits this method and calls it from the render thread. Nothing is guarded
 * here because {@code ProfilerHook} compares the calling thread against the server thread that
 * parked the profiler, which rejects that call and any other off-thread one (SPEC INV-1).
 */
@Mixin(Level.class)
public class LevelBlockEntityTickMixin {

	@Inject(method = "tickBlockEntities()V", at = @At("HEAD"))
	private void tickpilot$beginBlockEntities(CallbackInfo ci) {
		ProfilerHook.begin(TickCategory.BLOCK_ENTITIES, null);
	}

	@Inject(method = "tickBlockEntities()V", at = @At("RETURN"))
	private void tickpilot$endBlockEntities(CallbackInfo ci) {
		ProfilerHook.end();
	}
}
