package com.tickpilot.mixin;

import com.tickpilot.policy.PolicyHook;
import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Charges each block entity tick to its own {@code BlockEntityType} for SPEC FR-3.
 *
 * <h2>Target, and why this exact class</h2>
 * {@code net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity.tick()V}. Targeted by
 * string because the class is package-private, so there is no class literal to use.
 *
 * <p>The obvious target would have been the {@code TickingBlockEntity} interface, and it would
 * have been wrong. Reading {@code LevelChunk.updateBlockEntityTicker} line 651, what actually goes
 * into {@code Level.blockEntityTickers} is a {@code RebindableTickingBlockEntityWrapper}, whose
 * {@code tick()} is a one-line delegate to the {@code BoundTickingBlockEntity} it wraps. Every
 * ticking block entity therefore passes through <em>two</em> {@code TickingBlockEntity.tick()}
 * frames per tick, and hooking the interface would have doubled every measurement. Hooking the
 * wrapper would additionally have caught {@code NULL_TICKER}, the empty implementation that
 * unloaded wrappers are rebound to. {@code BoundTickingBlockEntity} is the leaf: entered exactly
 * once per ticking block entity per tick, and the only one of the three that knows which block
 * entity it is.
 *
 * <h2>Why the field and not getType()</h2>
 * {@code BoundTickingBlockEntity.getType()} is
 * {@code BlockEntityType.getKey(blockEntity.getType()).toString()} — it builds a String on every
 * call. Calling that once per block entity per tick would violate SPEC INV-6 on its own. The
 * shadowed field gives the {@code BlockEntityType} instance, which is a registry singleton, so
 * identity is a valid aggregation key and costs nothing.
 *
 * <h2>What it does besides timing</h2>
 * The HEAD injector also hands the block entity to {@code PolicyHook}, which works out what SPEC
 * FR-9 <em>would</em> decide about it and counts the answer. Nothing is skipped; the verdict is
 * tallied and discarded.
 *
 * <h2>Compatibility risk</h2>
 * Low. Two {@code @Inject}s, no control flow touched. Lithium 1.21.1 does not mixin this class;
 * its nearest options ({@code block_entity_ticking.sleeping.*}) work by removing block entities
 * from the ticking list, which this hook simply never sees.
 *
 * <h2>Why no safer hook exists</h2>
 * Per-type attribution needs the individual block entity in scope, and this is the innermost frame
 * where it is. The alternative — timing only the phase total in
 * {@code LevelBlockEntityTickMixin} — cannot answer FR-3 at all.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public class LevelChunkBoundTickerMixin {

	@Shadow
	@Final
	private BlockEntity blockEntity;

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void tickpilot$beginBoundTick(CallbackInfo ci) {
		ProfilerHook.begin(TickCategory.BLOCK_ENTITIES, blockEntity.getType());
		// One injector, not two on the same instruction: see PolicyHook.recordEntity for why the
		// ordering between two of them would become a correctness problem later.
		PolicyHook.recordBlockEntity(blockEntity);
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void tickpilot$endBoundTick(CallbackInfo ci) {
		ProfilerHook.end();
	}
}
