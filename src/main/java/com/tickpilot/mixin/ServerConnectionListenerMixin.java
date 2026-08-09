package com.tickpilot.mixin;

import com.tickpilot.profiler.ProfilerHook;
import com.tickpilot.profiler.TickCategory;

import net.minecraft.server.network.ServerConnectionListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times connection processing for SPEC FR-2 {@code NETWORK}.
 *
 * <h2>Target</h2>
 * {@code ServerConnectionListener.tick()V}, verified against {@code mappings.tiny} and
 * {@code javap}. Driven once per tick from {@code MinecraftServer.tickChildren} under the
 * {@code connection} profiler section.
 *
 * <h2>What this does not include, and why that is stated rather than fixed</h2>
 * {@code tickChildren} separately runs {@code serverPlayer.connection.chunkSender.sendNextChunks}
 * for every player, which is also network work. It is deliberately left out: it is per-player
 * server-level work, while {@code CHUNK_OPS} is per-level, and folding one into the other would
 * make the categories overlap in a way no reader could predict. It stays inside {@code OTHER},
 * which is documented in the README rather than quietly absorbed somewhere convenient.
 *
 * <h2>Why a Fabric event will not do</h2>
 * The networking API is about packet payloads and channel registration. It has no event around the
 * connection tick.
 *
 * <h2>Compatibility risk</h2>
 * Very low. Lithium does not touch networking at all — its mixin option list has no networking
 * package. Two {@code @Inject}s, no control flow touched.
 */
@Mixin(ServerConnectionListener.class)
public class ServerConnectionListenerMixin {

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void tickpilot$beginNetwork(CallbackInfo ci) {
		ProfilerHook.begin(TickCategory.NETWORK, null);
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void tickpilot$endNetwork(CallbackInfo ci) {
		ProfilerHook.end();
	}
}
