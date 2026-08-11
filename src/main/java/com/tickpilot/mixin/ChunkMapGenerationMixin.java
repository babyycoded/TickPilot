package com.tickpilot.mixin;

import java.util.List;

import com.tickpilot.chunk.ChunkGenerationGate;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caps how much optional chunk generation starts per tick (SPEC FR-10, AC-10, INV-8).
 *
 * <h2>Target</h2>
 * {@code ChunkMap.runGenerationTasks()V}, verified against {@code mappings.tiny}
 * ({@code method_60446} family, intermediary {@code class_3898}) and {@code javap} on the named
 * 1.21.1 jar. Two {@code @Inject}s, HEAD and RETURN.
 *
 * <h2>Why here and nowhere else</h2>
 * The method is a three-liner: it walks {@code pendingGenerationTasks}, hands each task to
 * {@code runGenerationTask} — which only {@code tell}s the worldgen mailbox — and clears the list.
 * The list is vanilla's, filled by {@code scheduleGenerationTask} when a ticket promotes a chunk,
 * so what this Mixin caps is the rate at which new chunk generation pipelines are <em>started</em>.
 * Tasks that are not allowed yet are moved out of the list at HEAD and put back at RETURN. Nothing
 * is dropped, nothing is cancelled, and TickPilot keeps no queue of its own.
 *
 * <h2>Why not the ticket layer, which would be the obvious place</h2>
 * {@code DistanceManager.addTicket(long, Ticket)} is the single sink every chunk ticket passes
 * through, and delaying tickets there would be a far more direct implementation of FR-10. It is
 * also a way to hang the server: {@code ServerChunkCache.getChunk} takes out a
 * {@code TicketType.UNKNOWN} ticket and then blocks the server thread in
 * {@code MainThreadExecutor.managedBlock} until that exact chunk is ready (verified in the
 * byte&shy;code of both methods). A held ticket there is a thread that never comes back. Recorded
 * as SPEC §13 entry #19.
 *
 * <h2>Why holding a task back cannot deadlock</h2>
 * The blocking wait above spins {@code pollTask()}, which is
 * {@code ServerChunkCache$MainThreadExecutor.pollTask} → {@code runDistanceManagerUpdates} → this
 * method, about every 100 &micro;s ({@code BlockableEventLoop.waitForTasks} parks for 100 &micro;s,
 * it does not block indefinitely). So a blocked thread re-enters this Mixin thousands of times a
 * second, and {@code ChunkBudget} lifts its own cap after eight consecutive drains that dispatched
 * nothing. Independently of that, the chunk being waited for carries an {@code UNKNOWN} ticket,
 * which {@code ChunkBudgetHook} classifies as the highest priority there is and never holds back at
 * all. Two independent reasons, either of which is sufficient.
 *
 * <h2>Compatibility risk</h2>
 * Low to moderate. Two {@code @Inject}s at HEAD and RETURN of a method no 1.21.1 build of Lithium
 * touches — its chunk mixins are {@code mixin.chunk.*} (palette, serialisation, locking, entity
 * class groups) and none of them is {@code ChunkMap.runGenerationTasks}. The real risk is a second
 * mod that also reorders chunk generation; the feature is off by default (SPEC INV-3) and holds
 * nothing when it is off, so two such mods degrade rather than conflict.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGenerationMixin {
	@Shadow
	@Final
	private List<ChunkGenerationTask> pendingGenerationTasks;

	@Shadow
	@Final
	ServerLevel level;

	/**
	 * Per {@code ChunkMap}, so it dies with its world and nothing is held across a reload
	 * (SPEC INV-7, AC-19). Created lazily rather than in a field initialiser: a Mixin-merged
	 * initialiser runs in every constructor of the target, and lazily is one branch on a path that
	 * already does list work.
	 */
	@Unique
	private ChunkGenerationGate tickpilot$gate;

	@Inject(method = "runGenerationTasks()V", at = @At("HEAD"))
	private void tickpilot$holdBackOptionalGeneration(CallbackInfo ci) {
		tickpilot$gate().beforeDrain(level, pendingGenerationTasks);
	}

	@Inject(method = "runGenerationTasks()V", at = @At("RETURN"))
	private void tickpilot$releaseHeldGeneration(CallbackInfo ci) {
		tickpilot$gate().afterDrain(pendingGenerationTasks);
	}

	@Unique
	private ChunkGenerationGate tickpilot$gate() {
		if (tickpilot$gate == null) {
			tickpilot$gate = new ChunkGenerationGate();
		}

		return tickpilot$gate;
	}
}
