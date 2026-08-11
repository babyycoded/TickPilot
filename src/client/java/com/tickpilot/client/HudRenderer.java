package com.tickpilot.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tickpilot.TickPilot;
import com.tickpilot.budget.LoadLevel;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Draws the optional HUD (SPEC FR-20).
 *
 * <h2>No Mixin</h2>
 * SPEC MX-1 says to look for a Fabric event first. {@code HudRenderCallback} exists in
 * fabric-rendering-v1 5.1.0 — the version this project actually resolves, verified with
 * {@code javap} on the remapped jar rather than assumed — with the signature
 * {@code onHudRender(GuiGraphics, DeltaTracker)}. So {@code tickpilot.client.mixins.json} stays
 * empty and the mod adds no client Mixin at all.
 *
 * <h2>What this method is allowed to do</h2>
 * Read one {@code volatile} reference and draw text. It performs no lookup into the server, holds
 * no game object between frames, and cannot fail into the render loop: everything is wrapped, and
 * a failure switches the HUD off for the session rather than spamming a log once a frame
 * (SPEC INV-9, AC-16).
 *
 * <h2>The four times it draws nothing</h2>
 * <ul>
 *   <li>no snapshot — the main menu, or a client connected to somebody else's server. This is the
 *       last clause of SPEC AC-19, and it is a plain null check rather than a query into a server
 *       that does not exist;</li>
 *   <li>{@code F3} is up — the debug overlay owns the top-left corner and TickPilot does not fight
 *       it for the space;</li>
 *   <li>{@code hideGui}, i.e. the player pressed F1 and wants a clean screen;</li>
 *   <li>no client instance or font yet, during start-up.</li>
 * </ul>
 */
final class HudRenderer {
	private static final int MARGIN = 4;
	private static final int PADDING = 2;
	private static final int BACKDROP = 0x90000000;
	private static final int DEFAULT_COLOUR = 0xFFFFFFFF;

	private static volatile boolean failed;

	private HudRenderer() {
	}

	/** Subscribes the renderer. Called once from the client entrypoint. */
	static void register() {
		HudRenderCallback.EVENT.register(HudRenderer::onHudRender);
	}

	private static void onHudRender(GuiGraphics graphics, DeltaTracker deltaTracker) {
		if (failed) {
			return;
		}

		try {
			draw(graphics);
		} catch (Throwable t) {
			// Once, and then never again this session: this runs every frame, so a log line per
			// failure would bury the server log within a second (SPEC AC-16, INV-9).
			failed = true;
			TickPilot.LOGGER.warn("TickPilot HUD failed and is now off for this session; the "
					+ "server is unaffected", t);
		}
	}

	private static void draw(GuiGraphics graphics) {
		HudSnapshot snapshot = HudState.current();

		if (snapshot == null) {
			// No integrated server: the main menu, or a client on somebody else's server. Nothing
			// to say, and nothing is asked of the server to find that out (SPEC AC-19).
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client == null || client.font == null || client.options == null
				|| client.options.hideGui) {
			return;
		}

		if (client.getDebugOverlay() != null && client.getDebugOverlay().showDebugScreen()) {
			return;
		}

		List<Component> lines = lines(snapshot);
		Font font = client.font;
		int width = 0;

		for (Component line : lines) {
			width = Math.max(width, font.width(line));
		}

		int height = lines.size() * font.lineHeight;

		graphics.fill(MARGIN - PADDING, MARGIN - PADDING, MARGIN + width + PADDING,
				MARGIN + height + PADDING, BACKDROP);

		int y = MARGIN;

		for (Component line : lines) {
			graphics.drawString(font, line, MARGIN, y, DEFAULT_COLOUR);
			y += font.lineHeight;
		}
	}

	/**
	 * The lines of SPEC FR-20: TPS, MSPT, mode, deferred tasks and the main load source.
	 *
	 * <p>Built fresh per frame. That is a handful of small objects at frame rate, which is ordinary
	 * for client-side rendering and is not the hot path SPEC INV-6 is about — that rule is about the
	 * server's per-tick and per-entity work, and nothing here runs on the server thread.
	 */
	private static List<Component> lines(HudSnapshot snapshot) {
		List<Component> lines = new ArrayList<>(5);

		lines.add(Component.translatable("hud.tickpilot.tps",
				Component.literal(format(snapshot.tps())).withStyle(tpsColour(snapshot.tps())),
				format(snapshot.msptLast()), format(snapshot.msptAvg5s())));

		lines.add(Component.translatable("hud.tickpilot.mode",
				Component.translatable(snapshot.mode().translationKey()),
				Component.translatable(snapshot.loadLevel().translationKey())
						.withStyle(levelColour(snapshot.loadLevel()))));

		if (!snapshot.adaptiveEnabled()) {
			lines.add(Component.translatable("hud.tickpilot.adaptive_off")
					.withStyle(ChatFormatting.GRAY));
		}

		lines.add(Component.translatable("hud.tickpilot.deferred", snapshot.deferredQueued())
				.withStyle(ChatFormatting.GRAY));

		// FR-4 keeps deep profiling off unless somebody asked for it, so most of the time there is
		// genuinely no main cost to report. Saying so is the honest answer; inventing one from the
		// categories that happen to be zero would not be (SPEC AC-2).
		if (snapshot.dominant() == null) {
			lines.add(Component.translatable("hud.tickpilot.main_cost_none")
					.withStyle(ChatFormatting.DARK_GRAY));
		} else {
			lines.add(Component.translatable("hud.tickpilot.main_cost",
					Component.translatable(snapshot.dominant().translationKey()),
					format(snapshot.dominantSharePercent()))
					.withStyle(ChatFormatting.GRAY));
		}

		// SPEC AC-1b: a low TPS that vanilla was told to produce is not a problem to report.
		if (snapshot.tickRateModified()) {
			lines.add(Component.translatable("hud.tickpilot.tickrate")
					.withStyle(ChatFormatting.YELLOW));
		}

		return lines;
	}

	private static ChatFormatting tpsColour(double tps) {
		if (tps >= 19.5) {
			return ChatFormatting.GREEN;
		}

		return tps >= 15.0 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	private static ChatFormatting levelColour(LoadLevel level) {
		return switch (level) {
			case NORMAL -> ChatFormatting.GREEN;
			case ELEVATED -> ChatFormatting.YELLOW;
			case HIGH -> ChatFormatting.GOLD;
			case CRITICAL -> ChatFormatting.RED;
		};
	}

	/**
	 * {@link Locale#ROOT} for the same reason the commands use it: the number must not pick up the
	 * client's locale separator while the surrounding text comes from a translation file.
	 */
	private static String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
