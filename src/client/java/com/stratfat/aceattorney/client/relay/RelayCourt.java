package com.stratfat.aceattorney.client.relay;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.stratfat.aceattorney.AceAttorney;
import com.stratfat.aceattorney.ModSounds;
import com.stratfat.aceattorney.ShoutType;
import com.stratfat.aceattorney.client.CourtScreen;
import com.stratfat.aceattorney.client.DialogueOverlay;
import com.stratfat.aceattorney.client.ProtocolExporter;
import com.stratfat.aceattorney.client.ShoutOverlay;
import com.stratfat.aceattorney.court.CourtRole;
import com.stratfat.aceattorney.net.DialogueS2CPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Court state in client-only mode. Mirrors CourtService: the same rules,
 * chat lines, titles, dialogues and protocol, but driven by relay events.
 * Every modded client validates each event against the sender's role, so
 * all of them reach the same state without a server-side authority.
 */
public final class RelayCourt {
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	/** Validation result for malformed events: rejected without a message. */
	private static final String MALFORMED = "";
	private static final double SHOUT_RADIUS = 64.0;
	private static final double SAY_RADIUS = 32.0;
	private static final long SHOUT_COOLDOWN_MS = 2000;
	private static final long SYNC_REQUEST_INTERVAL_MS = 30_000;
	private static final long SNAPSHOT_INTERVAL_MS = 10_000;
	/** After asking for a sync, give the answer time to arrive before allowing a new session. */
	private static final long SYNC_WAIT_MS = 4000;
	private static final Set<String> NEEDS_SESSION = Set.of(
			"end", "verdict", "role", "ev", "present", "st", "edit", "play", "press", "object");

	private record Evidence(String name, String desc, String submitter) {
	}

	private record Statement(String speaker, String text) {
	}

	private record LogEntry(String time, String actor, String text) {
	}

	private static final class Session {
		String judge;
		final int number;
		final String caseName;
		final Map<String, CourtRole> roles = new LinkedHashMap<>();
		final List<Evidence> evidence = new ArrayList<>();
		final List<Statement> testimony = new ArrayList<>();
		final List<LogEntry> protocol = new ArrayList<>();

		Session(String judge, int number, String caseName) {
			this.judge = judge;
			this.number = number;
			this.caseName = caseName;
		}

		boolean isJudge(String name) {
			return roles.get(name) == CourtRole.JUDGE;
		}

		boolean hasJudge() {
			return roles.containsValue(CourtRole.JUDGE);
		}
	}

	private static Session session;
	private static Session pending; // snapshot being received for a late joiner
	private static String pendingFrom;
	private static JsonArray caseLog = new JsonArray();
	private static Path caseLogFile;
	private static int nextNumber = 1;
	private static boolean syncAsked;
	private static long lastSyncRequest;
	private static long lastSnapshot;
	private static long lastOwnShout;
	private static final Map<String, Long> LAST_SHOUT = new HashMap<>();

	private RelayCourt() {
	}

	// ---------- lifecycle ----------

	public static void onJoin() {
		reset();
		loadCaseLog();
		refreshScreen();
	}

	public static void reset() {
		session = null;
		pending = null;
		pendingFrom = null;
		syncAsked = false;
		lastSyncRequest = 0;
		lastSnapshot = 0;
		LAST_SHOUT.clear();
	}

	// ---------- local player actions ----------

	public static void sendShout(ShoutType type) {
		long now = System.currentTimeMillis();
		if (now - lastOwnShout < SHOUT_COOLDOWN_MS) {
			return;
		}
		lastOwnShout = now;
		RelayChannel.send(RelayMessage.of("shout", shoutText(type), type.name().toLowerCase(Locale.ROOT)));
	}

	public static void say(String text) {
		String clean = RelayMessage.sanitize(text);
		if (!clean.isEmpty()) {
			RelayChannel.send(RelayMessage.of("say", truncate(clean, 200)));
		}
	}

	/** Court Record GUI actions (same JSON as CourtActionC2SPayload in server mode). */
	public static void handleAction(String json) {
		JsonObject obj;
		try {
			obj = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		switch (str(obj, "action")) {
			case "request_state" -> {
				if (session == null && !syncAsked) {
					syncAsked = true;
					requestSync();
				}
				refreshScreen();
			}
			case "start" -> submit(RelayMessage.of("start", truncate(str(obj, "case"), 40), nextNumber));
			case "end" -> submit(RelayMessage.of("end", I18n.get("court.aceattorney.session_end")));
			case "verdict" -> {
				boolean guilty = obj.has("guilty") && obj.get("guilty").getAsBoolean();
				submit(RelayMessage.of("verdict",
						I18n.get(guilty ? "court.aceattorney.verdict.guilty" : "court.aceattorney.verdict.not_guilty"),
						guilty ? "guilty" : "not_guilty"));
			}
			case "claim_role" -> {
				CourtRole role = parseRole(str(obj, "role"));
				if (role != null) {
					submit(RelayMessage.of("role", role.displayName().getString(), role.id()));
				}
			}
			case "add_evidence" -> submit(RelayMessage.of("ev",
					str(obj, "name").replace('|', '/') + " | " + str(obj, "desc")));
			case "present" -> {
				int index = num(obj, "index");
				submit(RelayMessage.of("present", evidenceName(index), index));
			}
			case "add_statement" -> submit(RelayMessage.of("st", truncate(str(obj, "text"), 200)));
			case "edit_statement" -> submit(RelayMessage.of("edit", truncate(str(obj, "text"), 200), num(obj, "index")));
			case "play_testimony" -> submit(RelayMessage.of("play", I18n.get("court.aceattorney.testimony_title")));
			case "press" -> submit(RelayMessage.of("press", "HOLD IT!", num(obj, "index")));
			case "object" -> {
				int statement = num(obj, "statement");
				submit(obj.has("evidence")
						? RelayMessage.of("object", "OBJECTION!", statement, num(obj, "evidence"))
						: RelayMessage.of("object", "OBJECTION!", statement));
			}
			case "say" -> say(str(obj, "text"));
			case "export_protocol" -> export(obj.has("number") ? num(obj, "number") : 0);
			default -> {
			}
		}
	}

	/** Validates locally first so the player gets feedback instead of a silently dropped event. */
	private static void submit(RelayMessage message) {
		if (message.op().equals("start") && System.currentTimeMillis() - lastSyncRequest < SYNC_WAIT_MS) {
			fail("chat.aceattorney.relay_syncing");
			return;
		}
		String failure = validate(myName(), message);
		if (failure != null) {
			if (!failure.isEmpty()) {
				fail(failure);
			}
			return;
		}
		RelayChannel.send(message);
	}

	// ---------- incoming events ----------

	public static void onEvent(String sender, UUID senderId, RelayMessage m) {
		AceAttorney.LOGGER.debug("Relay event from {}: {}", sender, m);
		switch (m.op()) {
			case "sync?" -> {
				maybeSendSnapshot(sender);
				return;
			}
			case "snap" -> {
				onSnapshotStart(sender, m);
				return;
			}
			case "snap-role", "snap-ev", "snap-st" -> {
				onSnapshotLine(sender, m);
				return;
			}
			case "snap-end" -> {
				onSnapshotEnd(sender);
				return;
			}
			default -> {
			}
		}
		if (m.op().equals("start") && session != null) {
			// an outsider missed our session and tried to open their own
			if (!session.roles.containsKey(sender)) {
				maybeSendSnapshot(sender);
			}
			return;
		}
		String failure = validate(sender, m);
		if (failure != null) {
			if (session == null && NEEDS_SESSION.contains(m.op())) {
				requestSync();
			}
			return;
		}
		apply(sender, senderId, m);
		refreshScreen();
	}

	/** Returns a lang key describing why the action is not allowed, MALFORMED, or null if allowed. */
	private static String validate(String actor, RelayMessage m) {
		return switch (m.op()) {
			case "shout" -> shoutType(m) == null ? MALFORMED : null;
			case "say" -> m.payload().isBlank() ? MALFORMED : null;
			case "start" -> session != null ? "court.aceattorney.already_active" : null;
			case "end" -> requireJudge(actor);
			case "verdict" -> {
				String failure = requireJudge(actor);
				if (failure != null) {
					yield failure;
				}
				yield m.arg(0).equals("guilty") || m.arg(0).equals("not_guilty") ? null : MALFORMED;
			}
			case "role" -> {
				if (session == null) {
					yield "court.aceattorney.no_session";
				}
				CourtRole role = parseRole(m.arg(0));
				if (role == null) {
					yield MALFORMED;
				}
				yield role == CourtRole.JUDGE && session.hasJudge() && !session.isJudge(actor)
						? "court.aceattorney.judge_taken" : null;
			}
			case "ev" -> {
				String failure = requireParticipant(actor);
				yield failure != null ? failure : (splitEvidence(m.payload())[0].isBlank() ? MALFORMED : null);
			}
			case "present" -> {
				String failure = requireParticipant(actor);
				yield failure != null ? failure
						: inRange(m.intArg(0), session.evidence.size()) ? null : "court.aceattorney.no_such_evidence";
			}
			case "st" -> {
				String failure = requireParticipant(actor);
				if (failure != null) {
					yield failure;
				}
				CourtRole role = session.roles.get(actor);
				if (role != CourtRole.WITNESS && role != CourtRole.DEFENDANT) {
					yield "court.aceattorney.testimony_witness_only";
				}
				yield m.payload().isBlank() ? MALFORMED : null;
			}
			case "edit" -> {
				String failure = requireParticipant(actor);
				if (failure != null) {
					yield failure;
				}
				int index = m.intArg(0);
				if (!inRange(index, session.testimony.size())) {
					yield "court.aceattorney.no_such_statement";
				}
				boolean author = session.testimony.get(index - 1).speaker().equals(actor);
				if (!author && !session.isJudge(actor)) {
					yield "court.aceattorney.edit_not_allowed";
				}
				yield m.payload().isBlank() ? MALFORMED : null;
			}
			case "play" -> {
				if (session == null) {
					yield "court.aceattorney.no_session";
				}
				yield session.testimony.isEmpty() ? "court.aceattorney.testimony_empty" : null;
			}
			case "press" -> {
				String failure = requireParticipant(actor);
				if (failure != null) {
					yield failure;
				}
				CourtRole role = session.roles.get(actor);
				if (role != CourtRole.DEFENSE && role != CourtRole.DEFENDANT) {
					yield "court.aceattorney.press_defense_only";
				}
				yield inRange(m.intArg(0), session.testimony.size()) ? null : "court.aceattorney.no_such_statement";
			}
			case "object" -> {
				String failure = requireParticipant(actor);
				if (failure != null) {
					yield failure;
				}
				if (!inRange(m.intArg(0), session.testimony.size())) {
					yield "court.aceattorney.no_such_statement";
				}
				yield m.args().size() < 2 || inRange(m.intArg(1), session.evidence.size())
						? null : "court.aceattorney.no_such_evidence";
			}
			default -> MALFORMED;
		};
	}

	private static void apply(String actor, UUID actorId, RelayMessage m) {
		switch (m.op()) {
			case "shout" -> {
				long now = System.currentTimeMillis();
				Long last = LAST_SHOUT.get(actor);
				if (last != null && now - last < SHOUT_COOLDOWN_MS - 500) {
					return;
				}
				LAST_SHOUT.put(actor, now);
				ShoutType type = shoutType(m);
				if (near(actorId, SHOUT_RADIUS)) {
					ShoutOverlay.show(type, actor);
				}
				if (session != null && session.roles.containsKey(actor)) {
					protocol(actor, "выкрикивает: " + shoutText(type));
				}
			}
			case "say" -> {
				if (near(actorId, SAY_RADIUS)) {
					DialogueOverlay.enqueue(new DialogueS2CPayload(actor, m.payload(), 0));
				}
				if (session != null && session.roles.containsKey(actor)) {
					protocol(actor, "говорит: «" + m.payload() + "»");
				}
			}
			case "start" -> {
				int number = Math.max(1, m.intArg(0));
				session = new Session(actor, number, m.payload());
				session.roles.put(actor, CourtRole.JUDGE);
				nextNumber = Math.max(nextNumber, number + 1);
				if (session.caseName.isEmpty()) {
					chat(Component.translatable("court.aceattorney.case_number", number));
				} else {
					chat(Component.translatable("court.aceattorney.case", number,
							Component.literal(session.caseName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
				}
				title(Component.translatable("court.aceattorney.session_start").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
						Component.translatable("court.aceattorney.session_start.sub", actor));
				gavel(1.0f);
				chat(Component.translatable("court.aceattorney.hint_roles_relay").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				protocol(actor, "открывает заседание по делу №" + number
						+ (session.caseName.isEmpty() ? "" : " «" + session.caseName + "»"));
			}
			case "end" -> {
				protocol(actor, "закрывает заседание без вердикта");
				appendCase("dismissed");
				chat(Component.translatable("court.aceattorney.session_end").withStyle(ChatFormatting.GOLD));
				session = null;
			}
			case "verdict" -> {
				boolean guilty = m.arg(0).equals("guilty");
				title(guilty
								? Component.translatable("court.aceattorney.verdict.guilty").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
								: Component.translatable("court.aceattorney.verdict.not_guilty").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
						Component.translatable("court.aceattorney.verdict.sub"));
				gavel(guilty ? 0.8f : 1.2f);
				protocol(actor, "выносит вердикт: " + (guilty ? "ВИНОВЕН" : "НЕВИНОВЕН"));
				appendCase(guilty ? "guilty" : "not_guilty");
				session = null;
			}
			case "role" -> {
				CourtRole role = parseRole(m.arg(0));
				if (session.roles.get(actor) == role) {
					return;
				}
				if (role == CourtRole.JUDGE) {
					session.judge = actor;
				}
				session.roles.put(actor, role);
				chat(Component.translatable("court.aceattorney.role_assigned", actor, role.displayName()));
				protocol(actor, "занимает место: " + role.displayName().getString());
			}
			case "ev" -> {
				String[] parts = splitEvidence(m.payload());
				session.evidence.add(new Evidence(parts[0], parts[1], actor));
				chat(Component.translatable("court.aceattorney.evidence_added", actor,
						Component.literal(parts[0]).withStyle(ChatFormatting.YELLOW)));
				protocol(actor, "приобщает улику «" + parts[0] + "»: " + parts[1]);
			}
			case "present" -> {
				Evidence e = session.evidence.get(m.intArg(0) - 1);
				ShoutOverlay.show(ShoutType.TAKE_THAT, actor);
				chat(Component.translatable("court.aceattorney.evidence_presented", actor,
						Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
				chat(Component.literal("  «" + e.desc() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				protocol(actor, "предъявляет улику «" + e.name() + "»");
			}
			case "st" -> {
				session.testimony.add(new Statement(actor, m.payload()));
				int number = session.testimony.size();
				if (actor.equals(myName())) {
					chat(Component.translatable("court.aceattorney.statement_added", number));
				}
				protocol(actor, "даёт показание №" + number + ": «" + m.payload() + "»");
			}
			case "edit" -> {
				int index = m.intArg(0);
				Statement old = session.testimony.get(index - 1);
				session.testimony.set(index - 1, new Statement(old.speaker(), m.payload()));
				chat(Component.translatable("court.aceattorney.statement_edited", actor, index));
				DialogueOverlay.enqueue(new DialogueS2CPayload(old.speaker(), m.payload(), index));
				protocol(actor, "изменяет показание №" + index + ": «" + old.text() + "» → «" + m.payload() + "»");
			}
			case "play" -> {
				title(Component.translatable("court.aceattorney.testimony_title").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), null);
				int i = 1;
				for (Statement s : session.testimony) {
					DialogueOverlay.enqueue(new DialogueS2CPayload(s.speaker(), s.text(), i++));
				}
				protocol(actor, "оглашает показания (" + session.testimony.size() + " шт.)");
			}
			case "press" -> {
				int index = m.intArg(0);
				Statement s = session.testimony.get(index - 1);
				ShoutOverlay.show(ShoutType.HOLD_IT, actor);
				chat(Component.translatable("court.aceattorney.press", actor, index));
				DialogueOverlay.enqueue(new DialogueS2CPayload(s.speaker(), s.text(), index));
				protocol(actor, "давит на показание №" + index + " («" + s.text() + "»)");
			}
			case "object" -> {
				int index = m.intArg(0);
				Statement s = session.testimony.get(index - 1);
				ShoutOverlay.show(ShoutType.OBJECTION, actor);
				DialogueOverlay.enqueue(new DialogueS2CPayload(s.speaker(), s.text(), index));
				if (m.args().size() >= 2) {
					Evidence e = session.evidence.get(m.intArg(1) - 1);
					chat(Component.translatable("court.aceattorney.objection_evidence", actor,
							Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), index));
					chat(Component.literal("  «" + e.desc() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
					protocol(actor, "заявляет протест против показания №" + index + " с уликой «" + e.name() + "»");
				} else {
					chat(Component.translatable("court.aceattorney.objection_plain", actor, index));
					protocol(actor, "заявляет протест против показания №" + index);
				}
			}
			default -> {
			}
		}
	}

	// ---------- late joiners: sync request and snapshot ----------

	private static void requestSync() {
		long now = System.currentTimeMillis();
		if (now - lastSyncRequest < SYNC_REQUEST_INTERVAL_MS) {
			return;
		}
		lastSyncRequest = now;
		RelayChannel.send(RelayMessage.of("sync?", ""));
	}

	/**
	 * One client answers: the judge if online, otherwise the first online
	 * participant (never the requester itself). Everyone sees the same tab
	 * list, so everyone agrees on who that is.
	 */
	private static void maybeSendSnapshot(String requester) {
		if (session == null || requester.equals(myName())) {
			return;
		}
		Set<String> online = onlineNames();
		List<String> candidates = new ArrayList<>();
		candidates.add(session.judge);
		candidates.addAll(session.roles.keySet());
		String responder = null;
		for (String name : candidates) {
			if (!name.equals(requester) && online.contains(name)) {
				responder = name;
				break;
			}
		}
		long now = System.currentTimeMillis();
		if (!myName().equals(responder) || now - lastSnapshot < SNAPSHOT_INTERVAL_MS) {
			return;
		}
		lastSnapshot = now;
		RelayChannel.send(RelayMessage.of("snap", session.caseName, session.number, session.judge));
		session.roles.forEach((name, role) -> RelayChannel.send(RelayMessage.of("snap-role", name, role.id())));
		for (Evidence e : session.evidence) {
			RelayChannel.send(RelayMessage.of("snap-ev", e.name() + " | " + e.desc(), e.submitter()));
		}
		for (Statement s : session.testimony) {
			RelayChannel.send(RelayMessage.of("snap-st", s.text(), s.speaker()));
		}
		RelayChannel.send(RelayMessage.of("snap-end", ""));
	}

	private static void onSnapshotStart(String sender, RelayMessage m) {
		int number = m.intArg(0);
		String judge = m.arg(1);
		if (number < 1 || judge.isEmpty()) {
			return;
		}
		if (session != null && session.number == number && session.judge.equals(judge)) {
			pending = null; // already in sync
			pendingFrom = null;
			return;
		}
		pending = new Session(judge, number, m.payload());
		pendingFrom = sender;
	}

	private static void onSnapshotLine(String sender, RelayMessage m) {
		if (pending == null || !sender.equals(pendingFrom)) {
			return;
		}
		switch (m.op()) {
			case "snap-role" -> {
				CourtRole role = parseRole(m.arg(0));
				if (role != null && !m.payload().isEmpty()) {
					pending.roles.put(m.payload(), role);
				}
			}
			case "snap-ev" -> {
				String[] parts = splitEvidence(m.payload());
				pending.evidence.add(new Evidence(parts[0], parts[1], m.arg(0)));
			}
			case "snap-st" -> pending.testimony.add(new Statement(m.arg(0), m.payload()));
			default -> {
			}
		}
	}

	private static void onSnapshotEnd(String sender) {
		if (pending == null || !sender.equals(pendingFrom)) {
			return;
		}
		session = pending;
		pending = null;
		pendingFrom = null;
		nextNumber = Math.max(nextNumber, session.number + 1);
		protocol(myName(), "подключается к заседанию (более ранние записи протокола недоступны)");
		chat(Component.translatable("chat.aceattorney.relay_synced", session.number, session.judge)
				.withStyle(ChatFormatting.GRAY));
		refreshScreen();
	}

	// ---------- state for the Court Record GUI ----------

	private static void refreshScreen() {
		CourtScreen.acceptState(buildState().toString());
	}

	private static JsonObject buildState() {
		JsonObject root = new JsonObject();
		root.addProperty("active", session != null);
		JsonArray summary = new JsonArray();
		for (var el : caseLog) {
			JsonObject record = el.getAsJsonObject().deepCopy();
			record.remove("protocol");
			summary.add(record);
		}
		root.add("log", summary);
		if (session == null) {
			return root;
		}
		root.addProperty("judge", session.judge);
		root.addProperty("case", session.caseName);
		root.addProperty("caseNumber", session.number);
		CourtRole myRole = session.roles.get(myName());
		root.addProperty("yourRole", myRole != null ? myRole.id() : "");

		JsonArray evidence = new JsonArray();
		for (Evidence e : session.evidence) {
			JsonObject je = new JsonObject();
			je.addProperty("name", e.name());
			je.addProperty("desc", e.desc());
			je.addProperty("submitter", e.submitter());
			evidence.add(je);
		}
		root.add("evidence", evidence);

		JsonArray testimony = new JsonArray();
		for (Statement s : session.testimony) {
			JsonObject js = new JsonObject();
			js.addProperty("speaker", s.speaker());
			js.addProperty("text", s.text());
			testimony.add(js);
		}
		root.add("testimony", testimony);

		if (myRole == CourtRole.CLERK) {
			root.add("protocol", protocolJson(session.protocol));
		}
		return root;
	}

	private static JsonArray protocolJson(List<LogEntry> entries) {
		JsonArray array = new JsonArray();
		for (LogEntry entry : entries) {
			JsonObject je = new JsonObject();
			je.addProperty("time", entry.time());
			je.addProperty("actor", entry.actor());
			je.addProperty("text", entry.text());
			array.add(je);
		}
		return array;
	}

	// ---------- protocol export and the local case log ----------

	private static void export(int number) {
		JsonObject export;
		if (number <= 0) {
			if (session == null) {
				fail("court.aceattorney.no_session");
				return;
			}
			CourtRole role = session.roles.get(myName());
			if (role != CourtRole.CLERK && role != CourtRole.JUDGE) {
				fail("court.aceattorney.export_clerk_only");
				return;
			}
			export = new JsonObject();
			export.addProperty("number", session.number);
			export.addProperty("name", session.caseName);
			export.addProperty("judge", session.judge);
			export.addProperty("verdict", "in_progress");
			export.addProperty("date", LocalDate.now().format(DATE_FORMAT));
			export.add("protocol", protocolJson(session.protocol));
		} else {
			export = null;
			for (var el : caseLog) {
				if (el.getAsJsonObject().get("number").getAsInt() == number) {
					export = el.getAsJsonObject(); // the latest record wins if numbers repeat
				}
			}
			if (export == null) {
				fail("court.aceattorney.no_such_case");
				return;
			}
		}
		ProtocolExporter.save(export.toString());
	}

	/** Cases are logged per server, in the client's config folder. */
	private static void loadCaseLog() {
		Minecraft mc = Minecraft.getInstance();
		ServerData server = mc.getCurrentServer();
		String key = (server != null ? server.ip : "unknown").replaceAll("[^A-Za-z0-9._-]", "_");
		caseLogFile = mc.gameDirectory.toPath().resolve("config").resolve("aceattorney")
				.resolve("relay_cases").resolve(key + ".json");
		caseLog = new JsonArray();
		nextNumber = 1;
		if (!Files.exists(caseLogFile)) {
			return;
		}
		try {
			caseLog = JsonParser.parseString(Files.readString(caseLogFile, StandardCharsets.UTF_8)).getAsJsonArray();
			for (var el : caseLog) {
				nextNumber = Math.max(nextNumber, el.getAsJsonObject().get("number").getAsInt() + 1);
			}
		} catch (Exception e) {
			AceAttorney.LOGGER.warn("Could not read relay case log {}", caseLogFile, e);
			caseLog = new JsonArray();
		}
	}

	private static void appendCase(String verdict) {
		JsonObject record = new JsonObject();
		record.addProperty("number", session.number);
		record.addProperty("name", session.caseName);
		record.addProperty("judge", session.judge);
		record.addProperty("verdict", verdict);
		record.addProperty("date", LocalDate.now().format(DATE_FORMAT));
		record.add("protocol", protocolJson(session.protocol));
		caseLog.add(record);
		if (caseLogFile == null) {
			return;
		}
		try {
			Files.createDirectories(caseLogFile.getParent());
			Files.writeString(caseLogFile, caseLog.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			AceAttorney.LOGGER.warn("Could not save relay case log {}", caseLogFile, e);
		}
	}

	// ---------- helpers ----------

	private static String requireParticipant(String actor) {
		if (session == null) {
			return "court.aceattorney.no_session";
		}
		return session.roles.containsKey(actor) ? null : "court.aceattorney.not_participant";
	}

	private static String requireJudge(String actor) {
		if (session == null) {
			return "court.aceattorney.no_session";
		}
		return session.isJudge(actor) ? null : "court.aceattorney.judge_only";
	}

	private static boolean inRange(int index, int size) {
		return index >= 1 && index <= size;
	}

	private static String[] splitEvidence(String payload) {
		int sep = payload.indexOf(" | ");
		if (sep < 0) {
			return new String[] {payload.trim(), "—"};
		}
		String desc = payload.substring(sep + 3).trim();
		return new String[] {payload.substring(0, sep).trim(), desc.isEmpty() ? "—" : desc};
	}

	private static String evidenceName(int index) {
		return session != null && inRange(index, session.evidence.size()) ? session.evidence.get(index - 1).name() : "";
	}

	private static CourtRole parseRole(String id) {
		for (CourtRole role : CourtRole.values()) {
			if (role.id().equalsIgnoreCase(id)) {
				return role;
			}
		}
		return null;
	}

	private static ShoutType shoutType(RelayMessage m) {
		try {
			return ShoutType.valueOf(m.arg(0).toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String shoutText(ShoutType type) {
		return switch (type) {
			case OBJECTION -> "OBJECTION!";
			case HOLD_IT -> "HOLD IT!";
			case TAKE_THAT -> "TAKE THAT!";
		};
	}

	private static void protocol(String actor, String text) {
		if (session != null) {
			session.protocol.add(new LogEntry(LocalTime.now().format(TIME_FORMAT), actor, text));
		}
	}

	private static boolean near(UUID id, double radius) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return false;
		}
		if (mc.player.getUUID().equals(id)) {
			return true;
		}
		Player other = mc.level.getPlayerByUUID(id);
		return other != null && other.distanceTo(mc.player) <= radius;
	}

	private static Set<String> onlineNames() {
		Set<String> names = new HashSet<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				names.add(info.getProfile().name());
			}
		}
		return names;
	}

	private static String myName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null ? mc.player.getGameProfile().name() : "";
	}

	private static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(message, false);
		}
	}

	private static void fail(String key) {
		chat(Component.translatable(key).withStyle(ChatFormatting.RED));
	}

	private static void title(Component title, Component subtitle) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setTimes(5, 50, 10);
		mc.gui.setSubtitle(subtitle != null ? subtitle : Component.empty());
		mc.gui.setTitle(title);
	}

	private static void gavel(float pitch) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.GAVEL, pitch, 1.0f));
	}

	private static String str(JsonObject obj, String key) {
		return obj.has(key) ? obj.get(key).getAsString() : "";
	}

	private static int num(JsonObject obj, String key) {
		try {
			return obj.has(key) ? obj.get(key).getAsInt() : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	private static String truncate(String text, int max) {
		return text.length() > max ? text.substring(0, max) : text;
	}
}
