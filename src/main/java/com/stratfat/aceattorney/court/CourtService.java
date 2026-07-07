package com.stratfat.aceattorney.court;

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
 */
public final class CourtService {
	private CourtService() {
	}

	// ---------- session ----------

	public static boolean start(ServerPlayer player) {
		return start(player, "");
	}

	public static boolean start(ServerPlayer player, String caseName) {
		if (CourtManager.isActive()) {
			fail(player, "court.aceattorney.already_active");
			return false;
		}
		CourtSession session = CourtManager.start(player);
		session.setCaseName(caseName);
		session.setCaseNumber(CaseLog.nextNumber());
		if (!session.caseName().isEmpty()) {
			CourtManager.broadcast(player.level().getServer(),
					Component.translatable("court.aceattorney.case", session.caseNumber(),
							Component.literal(session.caseName()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
		} else {
			CourtManager.broadcast(player.level().getServer(),
					Component.translatable("court.aceattorney.case_number", session.caseNumber()));
		}
		CourtManager.broadcastTitle(player.level().getServer(),
				Component.translatable("court.aceattorney.session_start").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
				Component.translatable("court.aceattorney.session_start.sub", player.getDisplayName()),
				ModSounds.GAVEL, 1.0f);
		CourtManager.broadcast(player.level().getServer(),
				Component.translatable("court.aceattorney.hint_roles").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		broadcastState(player.level().getServer());
		return true;
	}

	public static boolean end(ServerPlayer player) {
		if (!requireSession(player)) {
			return false;
		}
		if (!isJudgeOrOp(player)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		logCase(player, "dismissed");
		CourtManager.end();
		CourtManager.broadcast(player.level().getServer(),
				Component.translatable("court.aceattorney.session_end").withStyle(ChatFormatting.GOLD));
		broadcastState(player.level().getServer());
		return true;
	}

	public static boolean verdict(ServerPlayer player, boolean guilty) {
		if (!requireSession(player)) {
			return false;
		}
		if (!isJudgeOrOp(player)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		Component title = guilty
				? Component.translatable("court.aceattorney.verdict.guilty").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
				: Component.translatable("court.aceattorney.verdict.not_guilty").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
		CourtManager.broadcastTitle(player.level().getServer(), title,
				Component.translatable("court.aceattorney.verdict.sub"),
				ModSounds.GAVEL, guilty ? 0.8f : 1.2f);
		logCase(player, guilty ? "guilty" : "not_guilty");
		CourtManager.end();
		broadcastState(player.level().getServer());
		return true;
	}

	private static void logCase(ServerPlayer anyPlayer, String verdict) {
		CourtSession session = CourtManager.session();
		if (session == null) {
			return;
		}
		ServerPlayer judge = anyPlayer.level().getServer().getPlayerList().getPlayer(session.judge());
		CaseLog.append(session.caseNumber(), session.caseName(),
				judge != null ? judge.getGameProfile().name() : "?", verdict);
	}

	// ---------- roles ----------

	/** Self-assign a role by clicking a courtroom block or a GUI button. */
	public static boolean claimRole(ServerPlayer player, CourtRole role) {
		CourtSession session = CourtManager.session();
		if (session == null) {
			fail(player, "court.aceattorney.no_session_hint_block");
			return false;
		}
		if (role == CourtRole.JUDGE && !session.isJudge(player.getUUID())) {
			ServerPlayer judge = player.level().getServer().getPlayerList().getPlayer(session.judge());
			fail(player, "court.aceattorney.judge_taken");
			if (judge != null) {
				player.sendSystemMessage(Component.literal("  → " + judge.getGameProfile().name()).withStyle(ChatFormatting.GRAY));
			}
			return false;
		}
		if (session.roles().get(player.getUUID()) == role) {
			return true; // already in that seat
		}
		session.setRole(player.getUUID(), role);
		CourtManager.broadcast(player.level().getServer(),
				Component.translatable("court.aceattorney.role_assigned", player.getDisplayName(), role.displayName()));
		broadcastState(player.level().getServer());
		return true;
	}

	/** Judge assigns a role to someone else (command path). */
	public static boolean setRole(ServerPlayer executor, ServerPlayer target, CourtRole role) {
		if (!requireSession(executor)) {
			return false;
		}
		if (!isJudgeOrOp(executor)) {
			fail(executor, "court.aceattorney.judge_only");
			return false;
		}
		CourtManager.session().setRole(target.getUUID(), role);
		CourtManager.broadcast(executor.level().getServer(),
				Component.translatable("court.aceattorney.role_assigned", target.getDisplayName(), role.displayName()));
		broadcastState(executor.level().getServer());
		return true;
	}

	// ---------- evidence ----------

	public static boolean addEvidence(ServerPlayer player, String name, String description) {
		if (!requireParticipant(player)) {
			return false;
		}
		ItemStack held = player.getMainHandItem().copy();
		CourtManager.session().evidence().add(new Evidence(name, description, held, player.getGameProfile().name()));
		CourtManager.broadcast(player.level().getServer(),
				Component.translatable("court.aceattorney.evidence_added",
						player.getDisplayName(),
						Component.literal(name).withStyle(ChatFormatting.YELLOW)));
		broadcastState(player.level().getServer());
		return true;
	}

	public static boolean removeEvidence(ServerPlayer player, int index) {
		if (!requireSession(player)) {
			return false;
		}
		if (!isJudgeOrOp(player)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		CourtSession session = CourtManager.session();
		if (index < 1 || index > session.evidence().size()) {
			fail(player, "court.aceattorney.no_such_evidence");
			return false;
		}
		Evidence removed = session.evidence().remove(index - 1);
		player.sendSystemMessage(Component.translatable("court.aceattorney.evidence_removed", removed.name()));
		broadcastState(player.level().getServer());
		return true;
	}

	public static boolean present(ServerPlayer player, int index) {
		if (!requireParticipant(player)) {
			return false;
		}
		CourtSession session = CourtManager.session();
		if (index < 1 || index > session.evidence().size()) {
			fail(player, "court.aceattorney.no_such_evidence");
			return false;
		}
		Evidence e = session.evidence().get(index - 1);
		ModNetworking.broadcastShout(player, ShoutType.TAKE_THAT);
		CourtManager.broadcast(player.level().getServer(),
				Component.translatable("court.aceattorney.evidence_presented",
						player.getDisplayName(),
						Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
		CourtManager.broadcast(player.level().getServer(),
				Component.literal("  «" + e.description() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		return true;
	}

	// ---------- testimony ----------

	public static boolean addStatement(ServerPlayer player, String text) {
		if (!requireParticipant(player)) {
			return false;
		}
		CourtSession session = CourtManager.session();
		session.testimony().add(new CourtSession.Statement(player.getGameProfile().name(), text));
		int number = session.testimony().size();
		player.sendSystemMessage(Component.translatable("court.aceattorney.statement_added", number));
		broadcastState(player.level().getServer());
		return true;
	}

	public static boolean clearTestimony(ServerPlayer player) {
		if (!requireSession(player)) {
			return false;
		}
		if (!isJudgeOrOp(player)) {
			fail(player, "court.aceattorney.judge_only");
			return false;
		}
		CourtManager.session().testimony().clear();
		player.sendSystemMessage(Component.translatable("court.aceattorney.testimony_cleared"));
		broadcastState(player.level().getServer());
		return true;
	}

	public static boolean playTestimony(ServerPlayer player) {
		if (!requireSession(player)) {
			return false;
		}
		CourtSession session = CourtManager.session();
		if (session.testimony().isEmpty()) {
			fail(player, "court.aceattorney.testimony_empty");
			return false;
		}
		MinecraftServer server = player.level().getServer();
		CourtManager.broadcastTitle(server,
				Component.translatable("court.aceattorney.testimony_title").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
				null, null, 1.0f);
		int i = 1;
		for (CourtSession.Statement s : session.testimony()) {
			ModNetworking.broadcastDialogueGlobal(server, new DialogueS2CPayload(s.speaker(), s.text(), i++));
		}
		return true;
	}

	public static boolean press(ServerPlayer player, int index) {
		if (!requireParticipant(player)) {
			return false;
		}
		CourtSession session = CourtManager.session();
		CourtRole role = session.roles().get(player.getUUID());
		if (role != CourtRole.DEFENSE && role != CourtRole.DEFENDANT) {
			fail(player, "court.aceattorney.press_defense_only");
			return false;
		}
		if (index < 1 || index > session.testimony().size()) {
			fail(player, "court.aceattorney.no_such_statement");
			return false;
		}
		CourtSession.Statement s = session.testimony().get(index - 1);
		ModNetworking.broadcastShout(player, ShoutType.HOLD_IT);
		CourtManager.broadcast(player.level().getServer(),
				Component.translatable("court.aceattorney.press", player.getDisplayName(), index));
		ModNetworking.broadcastDialogueGlobal(player.level().getServer(),
				new DialogueS2CPayload(s.speaker(), s.text(), index));
		return true;
	}

	public static boolean object(ServerPlayer player, int statementIndex, int evidenceIndex) {
		if (!requireParticipant(player)) {
			return false;
		}
		CourtSession session = CourtManager.session();
		if (statementIndex < 1 || statementIndex > session.testimony().size()) {
			fail(player, "court.aceattorney.no_such_statement");
			return false;
		}
		if (evidenceIndex > session.evidence().size()) {
			fail(player, "court.aceattorney.no_such_evidence");
			return false;
		}
		CourtSession.Statement s = session.testimony().get(statementIndex - 1);
		ModNetworking.broadcastShout(player, ShoutType.OBJECTION);
		ModNetworking.broadcastDialogueGlobal(player.level().getServer(),
				new DialogueS2CPayload(s.speaker(), s.text(), statementIndex));
		if (evidenceIndex > 0) {
			Evidence e = session.evidence().get(evidenceIndex - 1);
			CourtManager.broadcast(player.level().getServer(),
					Component.translatable("court.aceattorney.objection_evidence",
							player.getDisplayName(),
							Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
							statementIndex));
			CourtManager.broadcast(player.level().getServer(),
					Component.literal("  «" + e.description() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		} else {
			CourtManager.broadcast(player.level().getServer(),
					Component.translatable("court.aceattorney.objection_plain",
							player.getDisplayName(), statementIndex));
		}
		return true;
	}

	// ---------- courtroom blocks ----------

	public static void judgeBenchUsed(ServerPlayer player) {
		CourtSession session = CourtManager.session();
		if (session == null) {
			start(player);
			return;
		}
		if (session.isJudge(player.getUUID())) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					ModSounds.GAVEL, SoundSource.PLAYERS, 1.0f, 1.0f);
			CourtManager.broadcast(player.level().getServer(),
					Component.translatable("chat.aceattorney.order", player.getDisplayName()));
		} else {
			fail(player, "court.aceattorney.session_running");
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
				case "verdict" -> verdict(player, obj.get("guilty").getAsBoolean());
				case "claim_role" -> claimRole(player, CourtRole.valueOf(obj.get("role").getAsString().toUpperCase()));
				case "add_evidence" -> addEvidence(player, obj.get("name").getAsString(), obj.get("desc").getAsString());
				case "present" -> present(player, obj.get("index").getAsInt());
				case "add_statement" -> addStatement(player, obj.get("text").getAsString());
				case "play_testimony" -> playTestimony(player);
				case "press" -> press(player, obj.get("index").getAsInt());
				case "object" -> object(player, obj.get("statement").getAsInt(),
						obj.has("evidence") ? obj.get("evidence").getAsInt() : 0);
				case "say" -> {
					String text = obj.get("text").getAsString().trim();
					if (!text.isEmpty()) {
						ModNetworking.broadcastDialogue(player,
								new DialogueS2CPayload(player.getGameProfile().name(), text, 0), 32);
					}
				}
				default -> {
				}
			}
		} catch (Exception e) {
			// malformed packet from a modified client — ignore
		}
	}

	// ---------- state sync ----------

	public static void sendState(ServerPlayer player) {
		ServerPlayNetworking.send(player, new CourtStateS2CPayload(buildState(player).toString()));
	}

	public static void broadcastState(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			sendState(p);
		}
	}

	private static JsonObject buildState(ServerPlayer viewer) {
		JsonObject root = new JsonObject();
		CourtSession session = CourtManager.session();
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
		return root;
	}

	// ---------- helpers ----------

	private static boolean requireSession(ServerPlayer player) {
		if (!CourtManager.isActive()) {
			fail(player, "court.aceattorney.no_session");
			return false;
		}
		return true;
	}

	private static boolean requireParticipant(ServerPlayer player) {
		if (!requireSession(player)) {
			return false;
		}
		if (!CourtManager.session().isParticipant(player.getUUID())) {
			fail(player, "court.aceattorney.not_participant");
			return false;
		}
		return true;
	}

	private static boolean isJudgeOrOp(ServerPlayer player) {
		CourtSession session = CourtManager.session();
		if (session != null && session.isJudge(player.getUUID())) {
			return true;
		}
		return player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static void fail(ServerPlayer player, String key) {
		player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.RED));
	}
}
