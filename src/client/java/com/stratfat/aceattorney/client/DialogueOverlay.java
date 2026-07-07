package com.stratfat.aceattorney.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.stratfat.aceattorney.ModSounds;
import com.stratfat.aceattorney.net.DialogueS2CPayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * Ace Attorney style dialogue box: dark blue panel at the bottom of the
 * screen, speaker name plate, typewriter text with blip sounds. Incoming
 * dialogues queue up and play one after another.
 */
public class DialogueOverlay {
	private static final long CHAR_MS = 35;      // typewriter speed
	private static final long BLIP_EVERY_MS = 70;
	private static final long HOLD_BASE_MS = 1500; // shown after text completes

	private static final int COLOR_BOX = 0xE60E1A38;
	private static final int COLOR_BORDER = 0xFFCBD6F0;
	private static final int COLOR_NAME_BG = 0xFF23408F;
	private static final int COLOR_TEXT = 0xFFF5F5F5;
	private static final int COLOR_TESTIMONY = 0xFF6CE8A8;

	private static final Deque<DialogueS2CPayload> QUEUE = new ArrayDeque<>();
	private static DialogueS2CPayload current;
	private static long startTime;
	private static long lastBlip;

	public static void enqueue(DialogueS2CPayload payload) {
		QUEUE.addLast(payload);
	}

	public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		long now = System.currentTimeMillis();

		if (current == null) {
			current = QUEUE.pollFirst();
			if (current == null) {
				return;
			}
			startTime = now;
			lastBlip = 0;
		}

		boolean testimony = current.statementNumber() > 0;
		String fullText = testimony
				? "№" + current.statementNumber() + ": " + current.text()
				: current.text();

		long elapsed = now - startTime;
		int totalChars = fullText.length();
		int visibleChars = (int) Math.min(totalChars, elapsed / CHAR_MS);
		boolean typing = visibleChars < totalChars;

		long holdMs = HOLD_BASE_MS + Math.min(totalChars * 25L, 4000);
		if (!typing && elapsed > totalChars * CHAR_MS + holdMs) {
			current = null;
			return;
		}

		if (typing && now - lastBlip >= BLIP_EVERY_MS) {
			lastBlip = now;
			Minecraft.getInstance().getSoundManager()
					.play(SimpleSoundInstance.forUI(ModSounds.DIALOGUE_BLIP, 1.8f, 0.35f));
		}

		Font font = Minecraft.getInstance().font;
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();

		int boxWidth = Math.min(360, screenWidth - 32);
		int textWidth = boxWidth - 16;

		String visibleText = fullText.substring(0, visibleChars);

		List<FormattedCharSequence> allLines = font.split(FormattedText.of(fullText), textWidth);
		List<FormattedCharSequence> lines = font.split(FormattedText.of(visibleText), textWidth);

		int boxHeight = allLines.size() * 10 + 12;
		int x = (screenWidth - boxWidth) / 2;
		int y = screenHeight - 58 - boxHeight;

		// name plate
		String name = current.speaker();
		int nameWidth = font.width(name) + 10;
		graphics.fill(x, y - 13, x + nameWidth, y, COLOR_NAME_BG);
		graphics.fill(x, y - 14, x + nameWidth, y - 13, COLOR_BORDER);
		graphics.drawString(font, name, x + 5, y - 10, 0xFFFFFFFF);

		// dialogue box
		graphics.fill(x, y, x + boxWidth, y + boxHeight, COLOR_BOX);
		graphics.fill(x, y, x + boxWidth, y + 1, COLOR_BORDER);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, COLOR_BORDER);
		graphics.fill(x, y, x + 1, y + boxHeight, COLOR_BORDER);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, COLOR_BORDER);

		int color = testimony ? COLOR_TESTIMONY : COLOR_TEXT;
		int lineY = y + 7;
		for (FormattedCharSequence line : lines) {
			graphics.drawString(font, line, x + 8, lineY, color);
			lineY += 10;
		}
	}
}
