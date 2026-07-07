package com.stratfat.aceattorney.client;

import java.util.Random;

import com.stratfat.aceattorney.AceAttorney;
import com.stratfat.aceattorney.ModSounds;
import com.stratfat.aceattorney.ShoutType;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public class ShoutOverlay {
	private static final Identifier TEX_OBJECTION = AceAttorney.id("textures/gui/shout_objection.png");
	private static final Identifier TEX_HOLD_IT = AceAttorney.id("textures/gui/shout_hold_it.png");
	private static final Identifier TEX_TAKE_THAT = AceAttorney.id("textures/gui/shout_take_that.png");

	private static final int TEX_WIDTH = 512;
	private static final int TEX_HEIGHT = 256;
	private static final long DURATION_MS = 1600;
	private static final long SHAKE_MS = 350;

	private static final Random RANDOM = new Random();

	private static long showUntil = 0;
	private static ShoutType type;
	private static String speaker = "";

	public static void show(ShoutType shoutType, String speakerName) {
		type = shoutType;
		speaker = speakerName;
		showUntil = System.currentTimeMillis() + DURATION_MS;

		SoundEvent sound = switch (shoutType) {
			case OBJECTION -> ModSounds.SHOUT_OBJECTION;
			case HOLD_IT -> ModSounds.SHOUT_HOLD_IT;
			case TAKE_THAT -> ModSounds.SHOUT_TAKE_THAT;
		};
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f));
	}

	public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		long now = System.currentTimeMillis();
		if (now >= showUntil || type == null) {
			return;
		}
		long remaining = showUntil - now;
		long elapsed = DURATION_MS - remaining;

		Identifier texture = switch (type) {
			case OBJECTION -> TEX_OBJECTION;
			case HOLD_IT -> TEX_HOLD_IT;
			case TAKE_THAT -> TEX_TAKE_THAT;
		};

		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();

		int width = Math.min((int) (screenWidth * 0.55), 360);
		int height = width / 2;

		int x = (screenWidth - width) / 2;
		int y = (screenHeight - height) / 2 - screenHeight / 10;

		if (elapsed < SHAKE_MS) {
			x += RANDOM.nextInt(9) - 4;
			y += RANDOM.nextInt(9) - 4;
		}

		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y,
				0.0f, 0.0f, width, height, TEX_WIDTH, TEX_HEIGHT, TEX_WIDTH, TEX_HEIGHT);

		if (!speaker.isEmpty()) {
			graphics.drawCenteredString(Minecraft.getInstance().font, speaker,
					screenWidth / 2, y + height + 6, 0xFFFFFFFF);
		}
	}
}
