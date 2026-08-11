package com.tickpilot.mixin;

import com.tickpilot.chunk.ChunkBudgetHook;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the regions a player is waiting for, so the chunk budget never holds them back
 * (SPEC AC-10 priorities 1 to 3, INV-8).
 *
 * <h2>Target</h2>
 * {@code ServerChunkCache.addRegionTicket(TicketType, ChunkPos, int, Object)}, verified against
 * {@code mappings.tiny} ({@code method_17297}) and {@code javap} on the named 1.21.1 jar. One
 * {@code @Inject} at HEAD which reads its arguments and returns.
 *
 * <h2>Why</h2>
 * The vanilla {@code TicketType} constants are, in full, {@code START}, {@code DRAGON},
 * {@code PLAYER}, {@code FORCED}, {@code PORTAL}, {@code POST_TELEPORT} and {@code UNKNOWN} — and
 * that list is exactly the first three priority classes of AC-10. Recording the region a vanilla
 * ticket covers is therefore the most direct statement of INV-8 available: a chunk being loaded
 * because a player joined, teleported, walked through a portal, or because the server thread is
 * blocked on it, is protected by the same rule that recognises the ticket.
 *
 * <p>{@code POST_TELEPORT} is the reason this Mixin exists at all. A teleport takes the ticket out
 * <em>before</em> the player's position moves, so for the remainder of that tick the destination is
 * far from every player TickPilot can see, and distance alone would classify it as optional.
 *
 * <p>Ticket types another mod defined are deliberately not recorded. That is not an oversight: a
 * mod's own ticket is what AC-10 calls a background task, and it is the only thing on a 1.21.1
 * server that this feature has to limit.
 *
 * <h2>Why not a Fabric event</h2>
 * {@code ServerChunkEvents} fires on load, generate and unload — after the decision this Mixin
 * needs to observe, and only for chunks that actually finish. There is no ticket event.
 *
 * <h2>Compatibility risk</h2>
 * Very low. A read-only {@code @Inject} at HEAD that mutates no argument and cancels nothing.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheTicketMixin {
	@Shadow
	@Final
	ServerLevel level;

	@Inject(method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;"
			+ "Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V", at = @At("HEAD"))
	private void tickpilot$noteRegionTicket(TicketType<?> type, ChunkPos pos, int radius,
			Object value, CallbackInfo ci) {
		ChunkBudgetHook.onRegionTicket(level, type, pos, radius);
	}
}
