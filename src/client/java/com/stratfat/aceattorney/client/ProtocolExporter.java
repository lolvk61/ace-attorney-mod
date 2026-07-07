package com.stratfat.aceattorney.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.stratfat.aceattorney.AceAttorney;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** Saves an exported case protocol as a text file in the game folder. */
public class ProtocolExporter {

	public static void save(String json) {
		Minecraft minecraft = Minecraft.getInstance();
		try {
			JsonObject data = JsonParser.parseString(json).getAsJsonObject();
			int number = data.get("number").getAsInt();
			String name = data.get("name").getAsString();
			String verdict = switch (data.get("verdict").getAsString()) {
				case "guilty" -> I18n.get("court.aceattorney.verdict.guilty");
				case "not_guilty" -> I18n.get("court.aceattorney.verdict.not_guilty");
				case "in_progress" -> I18n.get("gui.aceattorney.in_progress");
				default -> I18n.get("court.aceattorney.verdict.dismissed");
			};

			StringBuilder text = new StringBuilder();
			text.append("ПРОТОКОЛ СУДЕБНОГО ЗАСЕДАНИЯ\r\n");
			text.append("Дело №").append(number);
			if (!name.isBlank()) {
				text.append(" «").append(name).append("»");
			}
			text.append("\r\n");
			text.append("Дата: ").append(data.get("date").getAsString())
					.append(" | Судья: ").append(data.get("judge").getAsString())
					.append(" | Вердикт: ").append(verdict).append("\r\n");
			text.append("--------------------------------------------------\r\n");
			JsonArray protocol = data.getAsJsonArray("protocol");
			for (var el : protocol) {
				JsonObject entry = el.getAsJsonObject();
				text.append("[").append(entry.get("time").getAsString()).append("] ")
						.append(entry.get("actor").getAsString()).append(" ")
						.append(entry.get("text").getAsString()).append("\r\n");
			}

			Path dir = minecraft.gameDirectory.toPath().resolve("aceattorney_protocols");
			Files.createDirectories(dir);
			String safeName = name.isBlank() ? "" : "_" + name.replaceAll("[^\\p{L}\\p{N}]+", "_");
			Path file = dir.resolve("delo_" + number + safeName + ".txt");
			Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));

			if (minecraft.player != null) {
				minecraft.player.displayClientMessage(
						Component.translatable("chat.aceattorney.protocol_exported",
								Component.literal(file.getFileName().toString())
										.withStyle(style -> style
												.withColor(ChatFormatting.AQUA)
												.withUnderlined(true)
												.withClickEvent(new ClickEvent.OpenFile(file)))),
						false);
			}
		} catch (Exception e) {
			AceAttorney.LOGGER.warn("Could not export protocol", e);
			if (minecraft.player != null) {
				minecraft.player.displayClientMessage(
						Component.translatable("chat.aceattorney.protocol_export_failed").withStyle(ChatFormatting.RED), false);
			}
		}
	}
}
