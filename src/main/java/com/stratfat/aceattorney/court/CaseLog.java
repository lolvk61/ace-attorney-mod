package com.stratfat.aceattorney.court;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.stratfat.aceattorney.AceAttorney;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Persistent log of all concluded trials. Stored per world as
 * aceattorney_case_log.json in the world root, so it survives restarts.
 */
public final class CaseLog {
	public record CaseRecord(int number, String name, String judge, String verdict, String date, JsonArray protocol) {
	}

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final List<CaseRecord> RECORDS = new ArrayList<>();
	private static Path file;

	private CaseLog() {
	}

	public static void load(MinecraftServer server) {
		RECORDS.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("aceattorney_case_log.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonArray array = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
			for (var el : array) {
				JsonObject o = el.getAsJsonObject();
				RECORDS.add(new CaseRecord(
						o.get("number").getAsInt(),
						o.get("name").getAsString(),
						o.get("judge").getAsString(),
						o.get("verdict").getAsString(),
						o.get("date").getAsString(),
						o.has("protocol") ? o.getAsJsonArray("protocol") : new JsonArray()));
			}
		} catch (Exception e) {
			AceAttorney.LOGGER.warn("Could not read case log {}", file, e);
		}
	}

	public static List<CaseRecord> records() {
		return RECORDS;
	}

	public static int nextNumber() {
		return RECORDS.isEmpty() ? 1 : RECORDS.get(RECORDS.size() - 1).number() + 1;
	}

	public static void append(int number, String name, String judge, String verdict, List<CourtSession.LogEntry> protocol) {
		JsonArray protocolJson = new JsonArray();
		for (CourtSession.LogEntry entry : protocol) {
			JsonObject o = new JsonObject();
			o.addProperty("time", entry.time());
			o.addProperty("actor", entry.actor());
			o.addProperty("text", entry.text());
			protocolJson.add(o);
		}
		RECORDS.add(new CaseRecord(number, name, judge, verdict, LocalDate.now().format(DATE_FORMAT), protocolJson));
		save();
	}

	private static void save() {
		if (file == null) {
			return;
		}
		JsonArray array = new JsonArray();
		for (CaseRecord r : RECORDS) {
			JsonObject o = new JsonObject();
			o.addProperty("number", r.number());
			o.addProperty("name", r.name());
			o.addProperty("judge", r.judge());
			o.addProperty("verdict", r.verdict());
			o.addProperty("date", r.date());
			o.add("protocol", r.protocol());
			array.add(o);
		}
		try {
			Files.writeString(file, array.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AceAttorney.LOGGER.warn("Could not save case log {}", file, e);
		}
	}

	/** Summary for the GUI journal — protocols stay in the file to keep packets small. */
	public static JsonArray toJson() {
		JsonArray array = new JsonArray();
		for (CaseRecord r : RECORDS) {
			JsonObject o = new JsonObject();
			o.addProperty("number", r.number());
			o.addProperty("name", r.name());
			o.addProperty("judge", r.judge());
			o.addProperty("verdict", r.verdict());
			o.addProperty("date", r.date());
			array.add(o);
		}
		return array;
	}
}
