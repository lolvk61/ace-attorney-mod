package com.stratfat.aceattorney.court;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.stratfat.aceattorney.ModSounds;
import com.stratfat.aceattorney.ShoutType;
import com.stratfat.aceattorney.net.CourtStateS2CPayload;
import com.stratfat.aceattorney.net.DialogueS2CPayload;
import com.stratfat.aceattorney.net.ModNetworking;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * All court logic in one place. Called from commands, courtroom blocks and
 * the Court Record GUI (via CourtActionC2SPayload).
 *
 * Several sessions can run at once. An action applies to the session its
 * player takes part in; a player without a role is a spectator of the
 * session happening around them, if any. Output goes to that session's
 * audience only (see CourtManager).
 */
public final class CourtService {
	private static final java.time.format.DateTimeFormatter TIME_FORMAT =
			java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

	private CourtService() {
	}

	/** Add a line to the clerk's session protocol (who, when, what). */
	public static void protocol(CourtSession session, ServerPlayer actor, String text) {
		session.protocol().add(new CourtSession.LogEntry(
				java.time.LocalTime.now().format(TIME_FORMAT),
				actor.getGameProfile().name(), text));
		broadcastState(session, actor.level().getServer());
	}

	/** Keybind shouts of trial participants go on record. */
	public static void logShout(ServerPlayer player, ShoutType type) {
		CourtSession session = CourtManager.ofParticipant(player.getUUID());
		if (session != null) {
			protocol(session, player, "выкрикивает: " + type.name().replace('_', ' ') + "!");
		}
	}

	/** AA-style speech (/aa say and the GUI say row). */
	public static boolean say(ServerPlayer player, String text) {
		if (text.isBlank()) {
			return false;
		}
		ModNetworking.broadcastDialogue(player,
				new DialogueS2CPayload(player.getGameProfile().name(), text.trim(), 0), 32);
		CourtSession session = CourtManager.ofParticipant(player.getUUID());
		if (session != null) {
			protocol(session, player, "говорит: «" + text.trim() + "»");
		}
		return true;
	}

	// ---------- session ----------

	public static boolean start(ServerPlayer player) {
		return start(player, "");
	}

	public static boolean start(ServerPlayer player, String caseName) {
		return startAt(player, caseName, CourtManager.siteOf(player));
	}

	/**
	 * Opens a session at the given site (the player's position, or the judge's
	 * bench). Refused if another session is within {@link Site#RADIUS} blocks.
	 */
	private static boolean startAt(ServerPlayer player, String caseName, Site site) {
		MinecraftServer server = player.level().getServer();
		closeAbandonedNear(site, server);
		// starting a new session means leaving the old one, which closes it if the player is alone in it
		CourtSession previous = CourtManager.ofParticipant(player.getUUID());
		List<CourtSession> others = new ArrayList<>(CourtManager.sessions());
		if (previous != null && previous.roles().size() == 1) {
			others.remove(previous);
		}
		CourtSession neighbour = site.nearest(others, CourtSession::site);
		if (neighbour != null) {
			player.sendSystemMessage(Component.translatable("court.aceattorney.range_conflict",
					neighbour.caseNumber(), (int) Math.ceil(site.distanceTo(neighbour.site())), (int) Site.RADIUS)
					.withStyle(ChatFormatting.RED));
			return false;
		}
		if (previous != null) {
			leaveSession(player, previous, "покидает заседание (открывает другое дело)");
		}
		CourtSession session = CourtManager.start(player, site);
		session.setCaseName(caseName);
		if (!session.caseName().isEmpty()) {
			CourtManager.broadcast(session, server,
					Component.translatable("court.aceattorney.case", session.caseNumber(),
							Component.literal(session.caseName()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
		} else {
			CourtManager.broadcast(session, server,
					Component.translatable("court.aceattorney.case_number", session.caseNumber()));
		}
		CourtManager.broadcastTitle(session, server,
				Component.translatable("court.aceattorney.session_start").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
				Component.translatable("court.aceattorney.session_start.sub", player.getDisplayName()),
				ModSounds.GAVEL, 1.0f);
		CourtManager.broadcast(session, server,
				Component.translatable("court.aceattorney.hint_roles").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		protocol(session, player, "открывает заседание по делу №" + session.caseNumber()
				+ (session.caseName().isEmpty() ? "" : " «" + session.caseName() + "»"));
		return true;
	}

	public static boolean end(ServerPlayer player) {
		CourtSession session = requireSession(player);
		if (session == null) {
			return false;
		}
		if (!isJudgeOrOp(player, session)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		protocol(session, player, "закрывает заседание без вердикта");
		CourtManager.broadcast(session, server,
				Component.translatable("court.aceattorney.session_end").withStyle(ChatFormatting.GOLD));
		close(session, server, "dismissed");
		return true;
	}

	public static boolean verdict(ServerPlayer player, boolean guilty) {
		CourtSession session = requireSession(player);
		if (session == null) {
			return false;
		}
		if (!isJudgeOrOp(player, session)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		Component title = guilty
				? Component.translatable("court.aceattorney.verdict.guilty").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
				: Component.translatable("court.aceattorney.verdict.not_guilty").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
		CourtManager.broadcastTitle(session, server, title,
				Component.translatable("court.aceattorney.verdict.sub"),
				ModSounds.GAVEL, guilty ? 0.8f : 1.2f);
		protocol(session, player, "выносит вердикт: " + (guilty ? "ВИНОВЕН" : "НЕВИНОВЕН"));
		close(session, server, guilty ? "guilty" : "not_guilty");
		return true;
	}

	/** Records the case in the journal, removes the session and refreshes everyone who was in it. */
	private static void close(CourtSession session, MinecraftServer server, String verdict) {
		List<ServerPlayer> audience = CourtManager.audience(session, server);
		ServerPlayer judge = server.getPlayerList().getPlayer(session.judge());
		CaseLog.append(session.caseNumber(), session.caseName(),
				judge != null ? judge.getGameProfile().name() : "?", verdict, session.protocol());
		CourtManager.remove(session);
		for (ServerPlayer player : audience) {
			sendState(player);
		}
	}

	/** Sessions nobody is online for within reach of a new one are closed so they cannot block it. */
	private static void closeAbandonedNear(Site site, MinecraftServer server) {
		for (CourtSession session : new ArrayList<>(CourtManager.sessions())) {
			if (session.site().isNear(site) && CourtManager.isAbandoned(session, server)) {
				session.protocol().add(new CourtSession.LogEntry(java.time.LocalTime.now().format(TIME_FORMAT),
						"—", "заседание закрыто автоматически: никого из участников нет в сети"));
				CourtManager.broadcast(session, server,
						Component.translatable("court.aceattorney.abandoned_closed", session.caseNumber()).withStyle(ChatFormatting.GRAY));
				close(session, server, "dismissed");
			}
		}
	}

	// ---------- roles ----------

	/** Self-assign a role from the GUI or a command: in the player's own session, else the one nearby. */
	public static boolean claimRole(ServerPlayer player, CourtRole role) {
		CourtSession session = CourtManager.viewedBy(player);
		return claimRole(player, role, session);
	}

	/** Self-assign a role by clicking a courtroom block: the session is the one around that block. */
	public static boolean claimRole(ServerPlayer player, CourtRole role, Site block) {
		return claimRole(player, role, CourtManager.nearest(block));
	}

	private static boolean claimRole(ServerPlayer player, CourtRole role, CourtSession session) {
		if (session == null) {
			fail(player, "court.aceattorney.no_session_hint_block");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (role == CourtRole.JUDGE) {
			if (session.hasJudge() && !session.isJudge(player.getUUID())) {
				ServerPlayer judge = server.getPlayerList().getPlayer(session.judge());
				fail(player, "court.aceattorney.judge_taken");
				if (judge != null) {
					player.sendSystemMessage(Component.literal("  → " + judge.getGameProfile().name()).withStyle(ChatFormatting.GRAY));
				}
				return false;
			}
		}
		if (session.roles().get(player.getUUID()) == role) {
			return true; // already in that seat
		}
		CourtSession previous = CourtManager.ofParticipant(player.getUUID());
		if (previous != null && previous != session) {
			leaveSession(player, previous, "покидает заседание (занимает место в деле №" + session.caseNumber() + ")");
		}
		if (role == CourtRole.JUDGE) {
			session.setJudge(player.getUUID());
		}
		session.setRole(player.getUUID(), role);
		CourtManager.broadcast(session, server,
				Component.translatable("court.aceattorney.role_assigned", player.getDisplayName(), role.displayName()));
		protocol(session, player, "занимает место: " + role.displayName().getString());
		return true;
	}

	/** Judge assigns a role to someone else (command path). */
	public static boolean setRole(ServerPlayer executor, ServerPlayer target, CourtRole role) {
		CourtSession session = requireSession(executor);
		if (session == null) {
			return false;
		}
		if (!isJudgeOrOp(executor, session)) {
			fail(executor, "court.aceattorney.judge_only");
			return false;
		}
		CourtSession elsewhere = CourtManager.ofParticipant(target.getUUID());
		if (elsewhere != null && elsewhere != session) {
			fail(executor, "court.aceattorney.target_elsewhere");
			return false;
		}
		session.setRole(target.getUUID(), role);
		if (role == CourtRole.JUDGE) {
			session.setJudge(target.getUUID());
		}
		MinecraftServer server = executor.level().getServer();
		CourtManager.broadcast(session, server,
				Component.translatable("court.aceattorney.role_assigned", target.getDisplayName(), role.displayName()));
		protocol(session, executor, "назначает " + target.getGameProfile().name() + " на роль: " + role.displayName().getString());
		return true;
	}

	/** Leave the session you hold a role in. When the last participant leaves, the session is closed. */
	public static boolean leave(ServerPlayer player) {
		CourtSession session = CourtManager.ofParticipant(player.getUUID());
		if (session == null) {
			fail(player, "court.aceattorney.not_participant");
			return false;
		}
		player.sendSystemMessage(Component.translatable("court.aceattorney.left_you", session.caseNumber()));
		leaveSession(player, session, "покидает заседание");
		return true;
	}

	private static void leaveSession(ServerPlayer player, CourtSession session, String protocolText) {
		MinecraftServer server = player.level().getServer();
		protocol(session, player, protocolText);
		session.roles().remove(player.getUUID());
		CourtManager.broadcast(session, server, Component.translatable("court.aceattorney.left", player.getDisplayName()));
		if (session.roles().isEmpty()) {
			CourtManager.broadcast(session, server,
					Component.translatable("court.aceattorney.session_empty", session.caseNumber()).withStyle(ChatFormatting.GRAY));
			close(session, server, "dismissed");
		} else {
			broadcastState(session, server);
		}
		sendState(player);
	}

	// ---------- evidence ----------

	public static boolean addEvidence(ServerPlayer player, String name, String description) {
		CourtSession session = requireParticipant(player);
		if (session == null) {
			return false;
		}
		ItemStack held = player.getMainHandItem().copy();
		session.evidence().add(new Evidence(name, description, held, player.getGameProfile().name()));
		CourtManager.broadcast(session, player.level().getServer(),
				Component.translatable("court.aceattorney.evidence_added",
						player.getDisplayName(),
						Component.literal(name).withStyle(ChatFormatting.YELLOW)));
		protocol(session, player, "приобщает улику «" + name + "»: " + description);
		return true;
	}

	public static boolean removeEvidence(ServerPlayer player, int index) {
		CourtSession session = requireSession(player);
		if (session == null) {
			return false;
		}
		if (!isJudgeOrOp(player, session)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		if (index < 1 || index > session.evidence().size()) {
			fail(player, "court.aceattorney.no_such_evidence");
			return false;
		}
		Evidence removed = session.evidence().remove(index - 1);
		player.sendSystemMessage(Component.translatable("court.aceattorney.evidence_removed", removed.name()));
		protocol(session, player, "изымает улику «" + removed.name() + "»");
		return true;
	}

	public static boolean present(ServerPlayer player, int index) {
		CourtSession session = requireParticipant(player);
		if (session == null) {
			return false;
		}
		if (index < 1 || index > session.evidence().size()) {
			fail(player, "court.aceattorney.no_such_evidence");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		Evidence e = session.evidence().get(index - 1);
		ModNetworking.broadcastShout(player, ShoutType.TAKE_THAT);
		CourtManager.broadcast(session, server,
				Component.translatable("court.aceattorney.evidence_presented",
						player.getDisplayName(),
						Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
		CourtManager.broadcast(session, server,
				Component.literal("  «" + e.description() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		protocol(session, player, "предъявляет улику «" + e.name() + "»");
		return true;
	}

	// ---------- testimony ----------

	public static boolean addStatement(ServerPlayer player, String text) {
		CourtSession session = requireParticipant(player);
		if (session == null) {
			return false;
		}
		CourtRole role = session.roles().get(player.getUUID());
		if (role != CourtRole.WITNESS && role != CourtRole.DEFENDANT) {
			fail(player, "court.aceattorney.testimony_witness_only");
			return false;
		}
		session.testimony().add(new CourtSession.Statement(player.getGameProfile().name(), text));
		int number = session.testimony().size();
		player.sendSystemMessage(Component.translatable("court.aceattorney.statement_added", number));
		protocol(session, player, "даёт показание №" + number + ": «" + text + "»");
		return true;
	}

	/** Amend a statement: allowed for its author and for the judge. */
	public static boolean editStatement(ServerPlayer player, int index, String text) {
		CourtSession session = requireParticipant(player);
		if (session == null) {
			return false;
		}
		if (index < 1 || index > session.testimony().size()) {
			fail(player, "court.aceattorney.no_such_statement");
			return false;
		}
		CourtSession.Statement old = session.testimony().get(index - 1);
		boolean author = old.speaker().equals(player.getGameProfile().name());
		if (!author && !isJudgeOrOp(player, session)) {
			fail(player, "court.aceattorney.edit_not_allowed");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		session.testimony().set(index - 1, new CourtSession.Statement(old.speaker(), text));
		CourtManager.broadcast(session, server,
				Component.translatable("court.aceattorney.statement_edited", player.getDisplayName(), index));
		protocol(session, player, "изменяет показание №" + index + ": «" + old.text() + "» → «" + text + "»");
		sendDialogue(session, server, new DialogueS2CPayload(old.speaker(), text, index));
		return true;
	}

	public static boolean clearTestimony(ServerPlayer player) {
		CourtSession session = requireSession(player);
		if (session == null) {
			return false;
		}
		if (!isJudgeOrOp(player, session)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		session.testimony().clear();
		player.sendSystemMessage(Component.translatable("court.aceattorney.testimony_cleared"));
		protocol(session, player, "очищает список показаний");
		return true;
	}

	public static boolean playTestimony(ServerPlayer player) {
		CourtSession session = requireSession(player);
		if (session == null) {
			return false;
		}
		if (session.testimony().isEmpty()) {
			fail(player, "court.aceattorney.testimony_empty");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		CourtManager.broadcastTitle(session, server,
				Component.translatable("court.aceattorney.testimony_title").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
				null, null, 1.0f);
		int i = 1;
		for (CourtSession.Statement s : session.testimony()) {
			sendDialogue(session, server, new DialogueS2CPayload(s.speaker(), s.text(), i++));
		}
		protocol(session, player, "оглашает показания (" + session.testimony().size() + " шт.)");
		return true;
	}

	public static boolean press(ServerPlayer player, int index) {
		CourtSession session = requireParticipant(player);
		if (session == null) {
			return false;
		}
		CourtRole role = session.roles().get(player.getUUID());
		if (role != CourtRole.DEFENSE && role != CourtRole.DEFENDANT) {
			fail(player, "court.aceattorney.press_defense_only");
			return false;
		}
		if (index < 1 || index > session.testimony().size()) {
			fail(player, "court.aceattorney.no_such_statement");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		CourtSession.Statement s = session.testimony().get(index - 1);
		ModNetworking.broadcastShout(player, ShoutType.HOLD_IT);
		CourtManager.broadcast(session, server,
				Component.translatable("court.aceattorney.press", player.getDisplayName(), index));
		sendDialogue(session, server, new DialogueS2CPayload(s.speaker(), s.text(), index));
		protocol(session, player, "давит на показание №" + index + " («" + s.text() + "»)");
		return true;
	}

	public static boolean object(ServerPlayer player, int statementIndex, int evidenceIndex) {
		CourtSession session = requireParticipant(player);
		if (session == null) {
			return false;
		}
		if (statementIndex < 1 || statementIndex > session.testimony().size()) {
			fail(player, "court.aceattorney.no_such_statement");
			return false;
		}
		if (evidenceIndex > session.evidence().size()) {
			fail(player, "court.aceattorney.no_such_evidence");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		CourtSession.Statement s = session.testimony().get(statementIndex - 1);
		ModNetworking.broadcastShout(player, ShoutType.OBJECTION);
		sendDialogue(session, server, new DialogueS2CPayload(s.speaker(), s.text(), statementIndex));
		if (evidenceIndex > 0) {
			Evidence e = session.evidence().get(evidenceIndex - 1);
			CourtManager.broadcast(session, server,
					Component.translatable("court.aceattorney.objection_evidence",
							player.getDisplayName(),
							Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
							statementIndex));
			CourtManager.broadcast(session, server,
					Component.literal("  «" + e.description() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		} else {
			CourtManager.broadcast(session, server,
					Component.translatable("court.aceattorney.objection_plain",
							player.getDisplayName(), statementIndex));
		}
		protocol(session, player, "заявляет протест против показания №" + statementIndex
				+ (evidenceIndex > 0 ? " с уликой «" + session.evidence().get(evidenceIndex - 1).name() + "»" : ""));
		return true;
	}

	private static void sendDialogue(CourtSession session, MinecraftServer server, DialogueS2CPayload payload) {
		for (ServerPlayer player : CourtManager.audience(session, server)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	// ---------- courtroom blocks ----------

	/** The judge's bench at {@code bench}: opens a session there, bangs the gavel, or takes a vacant seat. */
	public static void judgeBenchUsed(ServerPlayer player, Site bench) {
		MinecraftServer server = player.level().getServer();
		CourtSession session = CourtManager.nearest(bench);
		if (session != null && CourtManager.isAbandoned(session, server)) {
			closeAbandonedNear(bench, server);
			session = null;
		}
		if (session == null) {
			startAt(player, "", bench);
			return;
		}
		if (session.isJudge(player.getUUID())) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					ModSounds.GAVEL, SoundSource.PLAYERS, 1.0f, 1.0f);
			CourtManager.broadcast(session, server,
					Component.translatable("chat.aceattorney.order", player.getDisplayName()));
		} else if (!session.hasJudge()) {
			// the judge seat is vacant (e.g. the judge clicked another bench by accident)
			claimRole(player, CourtRole.JUDGE, session);
		} else {
			fail(player, "court.aceattorney.judge_taken");
		}
	}

	// ---------- GUI actions (JSON over CourtActionC2SPayload) ----------

	public static void handleAction(ServerPlayer player, String json) {
		try {
			JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
			String action = obj.get("action").getAsString();
			switch (action) {
				case "request_state" -> sendState(player);
				case "start" -> start(player, obj.has("case") ? obj.get("case").getAsString() : "");
				case "end" -> end(player);
				case "leave" -> leave(player);
				case "verdict" -> verdict(player, obj.get("guilty").getAsBoolean());
				case "claim_role" -> claimRole(player, CourtRole.valueOf(obj.get("role").getAsString().toUpperCase()));
				case "add_evidence" -> addEvidence(player, obj.get("name").getAsString(), obj.get("desc").getAsString());
				case "present" -> present(player, obj.get("index").getAsInt());
				case "add_statement" -> addStatement(player, obj.get("text").getAsString());
				case "edit_statement" -> editStatement(player, obj.get("index").getAsInt(), obj.get("text").getAsString());
				case "play_testimony" -> playTestimony(player);
				case "press" -> press(player, obj.get("index").getAsInt());
				case "object" -> object(player, obj.get("statement").getAsInt(),
						obj.has("evidence") ? obj.get("evidence").getAsInt() : 0);
				case "say" -> say(player, obj.get("text").getAsString());
				case "export_protocol" -> exportProtocol(player, obj.has("number") ? obj.get("number").getAsInt() : 0);
				default -> {
				}
			}
		} catch (Exception e) {
			// malformed packet from a modified client — ignore
		}
	}

	/**
	 * Send a case protocol to the player for saving as a text file.
	 * number == 0 — the live protocol of the player's session: clerk (or op) only.
	 * number > 0 — a concluded case from the log: anyone may export.
	 */
	public static boolean exportProtocol(ServerPlayer player, int number) {
		JsonObject export = new JsonObject();
		if (number <= 0) {
			CourtSession session = requireSession(player);
			if (session == null) {
				return false;
			}
			boolean clerk = session.roles().get(player.getUUID()) == CourtRole.CLERK;
			if (!clerk && !isJudgeOrOp(player, session)) {
				fail(player, "court.aceattorney.export_clerk_only");
				return false;
			}
			export.addProperty("number", session.caseNumber());
			export.addProperty("name", session.caseName());
			ServerPlayer judge = player.level().getServer().getPlayerList().getPlayer(session.judge());
			export.addProperty("judge", judge != null ? judge.getGameProfile().name() : "?");
			export.addProperty("verdict", "in_progress");
			export.addProperty("date", java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));
			export.add("protocol", protocolJson(session));
		} else {
			CaseLog.CaseRecord record = CaseLog.records().stream()
					.filter(r -> r.number() == number).findFirst().orElse(null);
			if (record == null) {
				fail(player, "court.aceattorney.no_such_case");
				return false;
			}
			export.addProperty("number", record.number());
			export.addProperty("name", record.name());
			export.addProperty("judge", record.judge());
			export.addProperty("verdict", record.verdict());
			export.addProperty("date", record.date());
			export.add("protocol", record.protocol());
		}
		ServerPlayNetworking.send(player, new com.stratfat.aceattorney.net.ProtocolExportS2CPayload(export.toString()));
		return true;
	}

	private static JsonArray protocolJson(CourtSession session) {
		JsonArray protocol = new JsonArray();
		for (CourtSession.LogEntry entry : session.protocol()) {
			JsonObject je = new JsonObject();
			je.addProperty("time", entry.time());
			je.addProperty("actor", entry.actor());
			je.addProperty("text", entry.text());
			protocol.add(je);
		}
		return protocol;
	}

	// ---------- state sync ----------

	public static void sendState(ServerPlayer player) {
		ServerPlayNetworking.send(player, new CourtStateS2CPayload(buildState(player).toString()));
	}

	/** Refreshes the GUI of everyone who can see the session. */
	public static void broadcastState(CourtSession session, MinecraftServer server) {
		for (ServerPlayer p : CourtManager.audience(session, server)) {
			sendState(p);
		}
	}

	private static JsonObject buildState(ServerPlayer viewer) {
		JsonObject root = new JsonObject();
		CourtSession session = CourtManager.viewedBy(viewer);
		root.addProperty("active", session != null);
		root.add("log", CaseLog.toJson());
		if (session == null) {
			return root;
		}
		ServerPlayer judge = viewer.level().getServer().getPlayerList().getPlayer(session.judge());
		root.addProperty("judge", judge != null ? judge.getGameProfile().name() : "?");
		root.addProperty("case", session.caseName());
		root.addProperty("caseNumber", session.caseNumber());
		CourtRole viewerRole = session.roles().get(viewer.getUUID());
		root.addProperty("yourRole", viewerRole != null ? viewerRole.id() : "");

		JsonArray evidence = new JsonArray();
		for (Evidence e : session.evidence()) {
			JsonObject je = new JsonObject();
			je.addProperty("name", e.name());
			je.addProperty("desc", e.description());
			je.addProperty("submitter", e.submitter());
			evidence.add(je);
		}
		root.add("evidence", evidence);

		JsonArray testimony = new JsonArray();
		for (CourtSession.Statement s : session.testimony()) {
			JsonObject js = new JsonObject();
			js.addProperty("speaker", s.speaker());
			js.addProperty("text", s.text());
			testimony.add(js);
		}
		root.add("testimony", testimony);

		// full protocol goes only to the court clerk
		if (viewerRole == CourtRole.CLERK) {
			root.add("protocol", protocolJson(session));
		}
		return root;
	}

	// ---------- helpers ----------

	/** The session the player is part of or watching, or null (after telling them). */
	private static CourtSession requireSession(ServerPlayer player) {
		CourtSession session = CourtManager.viewedBy(player);
		if (session == null) {
			fail(player, "court.aceattorney.no_session");
		}
		return session;
	}

	/** The session the player holds a role in, or null (after telling them). */
	private static CourtSession requireParticipant(ServerPlayer player) {
		CourtSession session = CourtManager.ofParticipant(player.getUUID());
		if (session == null) {
			fail(player, CourtManager.viewedBy(player) == null ? "court.aceattorney.no_session" : "court.aceattorney.not_participant");
		}
		return session;
	}

	private static boolean isJudgeOrOp(ServerPlayer player, CourtSession session) {
		if (session.isJudge(player.getUUID())) {
			return true;
		}
		return player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static void fail(ServerPlayer player, String key) {
		player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.RED));
	}
}
