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
import com.stratfat.aceattorney.court.Site;
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
 *
 * Several sessions can run at once, at least {@link Site#RADIUS} blocks
 * apart (the start event carries its position, so every client judges the
 * distance the same way). A session's chat, titles and dialogues are shown
 * only to its audience: its participants, and players within that radius.
 */
public final class RelayCourt {
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final double SHOUT_RADIUS = 64.0;
	private static final double SAY_RADIUS = 32.0;
	private static final long SHOUT_COOLDOWN_MS = 2000;
	private static final long SYNC_REQUEST_INTERVAL_MS = 30_000;
	private static final long SNAPSHOT_INTERVAL_MS = 10_000;
	/** After asking for a sync, give the answer time to arrive before allowing a new session. */
	private static final long SYNC_WAIT_MS = 4000;
	private static final Set<String> NEEDS_SESSION = Set.of(
			"end", "verdict", "role", "ev", "present", "st", "edit", "play", "press", "object", "leave");

	/** Why an action is refused: a lang key with arguments; {@link #MALFORMED} is dropped silently. */
	private record Failure(String key, Object... args) {
		static final Failure MALFORMED = new Failure("");
	}

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
		final Site site;
		final Map<String, CourtRole> roles = new LinkedHashMap<>();
		final List<Evidence> evidence = new ArrayList<>();
		final List<Statement> testimony = new ArrayList<>();
		final List<LogEntry> protocol = new ArrayList<>();

		Session(String judge, int number, String caseName, Site site) {
			this.judge = judge;
			this.number = number;
			this.caseName = caseName;
			this.site = site;
		}

		boolean isJudge(String name) {
			return roles.get(name) == CourtRole.JUDGE;
		}

		boolean hasJudge() {
			return roles.containsValue(CourtRole.JUDGE);
		}
	}

	/** Running sessions by case number. */
	private static final Map<Integer, Session> SESSIONS = new LinkedHashMap<>();
	/** Snapshots being received for a late joiner, by the player sending them. */
	private static final Map<String, Session> PENDING = new HashMap<>();
	private static final Map<Integer, Long> LAST_SNAPSHOT = new HashMap<>();
	private static final Map<String, Long> LAST_SHOUT = new HashMap<>();
	private static JsonArray caseLog = new JsonArray();
	private static Path caseLogFile;
	private static boolean syncAsked;
	private static long lastSyncRequest;
	private static long lastOwnShout;

	private RelayCourt() {
	}

	// ---------- lifecycle ----------

	public static void onJoin() {
		reset();
		loadCaseLog();
		refreshScreen();
	}

	public static void reset() {
		SESSIONS.clear();
		PENDING.clear();
		LAST_SNAPSHOT.clear();
		LAST_SHOUT.clear();
		syncAsked = false;
		lastSyncRequest = 0;
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
				if (!syncAsked) {
					syncAsked = true;
					requestSync();
				}
				refreshScreen();
			}
			case "start" -> {
				Site here = mySite();
				if (here != null) {
					submit(RelayMessage.of("start", truncate(str(obj, "case"), 40), nextCaseNumber(),
							here.dimension(), Math.round(here.x()), Math.round(here.y()), Math.round(here.z())));
				}
			}
			case "end" -> submit(RelayMessage.of("end", I18n.get("court.aceattorney.session_end")));
			case "leave" -> submit(RelayMessage.of("leave", ""));
			case "verdict" -> {
				boolean guilty = obj.has("guilty") && obj.get("guilty").getAsBoolean();
				submit(RelayMessage.of("verdict",
						I18n.get(guilty ? "court.aceattorney.verdict.guilty" : "court.aceattorney.verdict.not_guilty"),
						guilty ? "guilty" : "not_guilty"));
			}
			case "claim_role" -> {
				CourtRole role = parseRole(str(obj, "role"));
				Session target = viewSession();
				if (role != null && target == null) {
					fail(new Failure("court.aceattorney.no_session_hint_block"));
				} else if (role != null) {
					submit(RelayMessage.of("role", role.displayName().getString(), role.id(), target.number));
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
			fail(new Failure("chat.aceattorney.relay_syncing"));
			return;
		}
		Failure failure = validate(myName(), message);
		if (failure != null) {
			fail(failure);
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
		Failure failure = validate(sender, m);
		if (failure != null) {
			if (m.op().equals("start")) {
				// the sender may simply not know about the session next to it: tell it
				maybeSendSnapshot(sender);
			} else if (sessionOf(sender) == null && NEEDS_SESSION.contains(m.op())) {
				// the sender is in a session we have not heard of (we joined late)
				requestSync();
			}
			return;
		}
		apply(sender, senderId, m);
		refreshScreen();
	}

	/** Returns why the action is not allowed, {@link Failure#MALFORMED}, or null if it is allowed. */
	private static Failure validate(String actor, RelayMessage m) {
		Session own = sessionOf(actor);
		return switch (m.op()) {
			case "shout" -> shoutType(m) == null ? Failure.MALFORMED : null;
			case "say" -> m.payload().isBlank() ? Failure.MALFORMED : null;
			case "start" -> {
				Site site = parseSite(m, 1);
				if (site == null || m.intArg(0) < 1) {
					yield Failure.MALFORMED;
				}
				for (Session s : SESSIONS.values()) {
					// starting a new session leaves the old one, which closes it if the player is alone in it
					if (s == own && own.roles.size() == 1) {
						continue;
					}
					if (s.site.isNear(site) && !isAbandoned(s)) {
						yield new Failure("court.aceattorney.range_conflict", s.number,
								(int) Math.ceil(site.distanceTo(s.site)), (int) Site.RADIUS);
					}
				}
				yield null;
			}
			case "end" -> requireJudge(own, actor);
			case "verdict" -> {
				Failure failure = requireJudge(own, actor);
				if (failure != null) {
					yield failure;
				}
				yield m.arg(0).equals("guilty") || m.arg(0).equals("not_guilty") ? null : Failure.MALFORMED;
			}
			case "role" -> {
				CourtRole role = parseRole(m.arg(0));
				if (role == null) {
					yield Failure.MALFORMED;
				}
				Session target = SESSIONS.get(m.intArg(1));
				if (target == null) {
					yield new Failure("court.aceattorney.no_session");
				}
				yield role == CourtRole.JUDGE && target.hasJudge() && !target.isJudge(actor)
						? new Failure("court.aceattorney.judge_taken") : null;
			}
			case "leave" -> own == null ? notParticipant() : null;
			case "ev" -> {
				Failure failure = requireParticipant(own);
				yield failure != null ? failure : (splitEvidence(m.payload())[0].isBlank() ? Failure.MALFORMED : null);
			}
			case "present" -> {
				Failure failure = requireParticipant(own);
				yield failure != null ? failure
						: inRange(m.intArg(0), own.evidence.size()) ? null : new Failure("court.aceattorney.no_such_evidence");
			}
			case "st" -> {
				Failure failure = requireParticipant(own);
				if (failure != null) {
					yield failure;
				}
				CourtRole role = own.roles.get(actor);
				if (role != CourtRole.WITNESS && role != CourtRole.DEFENDANT) {
					yield new Failure("court.aceattorney.testimony_witness_only");
				}
				yield m.payload().isBlank() ? Failure.MALFORMED : null;
			}
			case "edit" -> {
				Failure failure = requireParticipant(own);
				if (failure != null) {
					yield failure;
				}
				int index = m.intArg(0);
				if (!inRange(index, own.testimony.size())) {
					yield new Failure("court.aceattorney.no_such_statement");
				}
				boolean author = own.testimony.get(index - 1).speaker().equals(actor);
				if (!author && !own.isJudge(actor)) {
					yield new Failure("court.aceattorney.edit_not_allowed");
				}
				yield m.payload().isBlank() ? Failure.MALFORMED : null;
			}
			case "play" -> {
				Failure failure = requireParticipant(own);
				if (failure != null) {
					yield failure;
				}
				yield own.testimony.isEmpty() ? new Failure("court.aceattorney.testimony_empty") : null;
			}
			case "press" -> {
				Failure failure = requireParticipant(own);
				if (failure != null) {
					yield failure;
				}
				CourtRole role = own.roles.get(actor);
				if (role != CourtRole.DEFENSE && role != CourtRole.DEFENDANT) {
					yield new Failure("court.aceattorney.press_defense_only");
				}
				yield inRange(m.intArg(0), own.testimony.size()) ? null : new Failure("court.aceattorney.no_such_statement");
			}
			case "object" -> {
				Failure failure = requireParticipant(own);
				if (failure != null) {
					yield failure;
				}
				if (!inRange(m.intArg(0), own.testimony.size())) {
					yield new Failure("court.aceattorney.no_such_statement");
				}
				yield m.args().size() < 2 || inRange(m.intArg(1), own.evidence.size())
						? null : new Failure("court.aceattorney.no_such_evidence");
			}
			default -> Failure.MALFORMED;
		};
	}

	private static void apply(String actor, UUID actorId, RelayMessage m) {
		Session own = sessionOf(actor);
		switch (m.op()) {
			case "shout" -> {
				long now = System.currentTimeMillis();
				Long last = LAST_SHOUT.get(actor);
				if (last != null && now - last < SHOUT_COOLDOWN_MS - 500) {
					return;
				}
				LAST_SHOUT.put(actor, now);
				ShoutType type = shoutType(m);
				// a participant's shout belongs to their trial; anyone else is heard by those nearby
				if (own != null ? inAudience(own) : near(actorId, SHOUT_RADIUS)) {
					ShoutOverlay.show(type, actor);
				}
				if (own != null) {
					protocol(own, actor, "выкрикивает: " + shoutText(type));
				}
			}
			case "say" -> {
				if (near(actorId, SAY_RADIUS)) {
					DialogueOverlay.enqueue(new DialogueS2CPayload(actor, m.payload(), 0));
				}
				if (own != null) {
					protocol(own, actor, "говорит: «" + m.payload() + "»");
				}
			}
			case "start" -> {
				Site site = parseSite(m, 1);
				closeAbandonedNear(site);
				if (own != null) {
					leaveSession(own, actor, "покидает заседание (открывает другое дело)");
				}
				int number = m.intArg(0);
				if (SESSIONS.containsKey(number)) {
					number = nextCaseNumber(); // two sessions picked the same number at once
				}
				Session session = new Session(actor, number, m.payload(), site);
				session.roles.put(actor, CourtRole.JUDGE);
				SESSIONS.put(number, session);
				if (session.caseName.isEmpty()) {
					chat(session, Component.translatable("court.aceattorney.case_number", number));
				} else {
					chat(session, Component.translatable("court.aceattorney.case", number,
							Component.literal(session.caseName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
				}
				title(session, Component.translatable("court.aceattorney.session_start").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
						Component.translatable("court.aceattorney.session_start.sub", actor));
				gavel(session, 1.0f);
				chat(session, Component.translatable("court.aceattorney.hint_roles_relay").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				protocol(session, actor, "открывает заседание по делу №" + number
						+ (session.caseName.isEmpty() ? "" : " «" + session.caseName + "»"));
			}
			case "end" -> {
				protocol(own, actor, "закрывает заседание без вердикта");
				chat(own, Component.translatable("court.aceattorney.session_end").withStyle(ChatFormatting.GOLD));
				close(own, "dismissed");
			}
			case "verdict" -> {
				boolean guilty = m.arg(0).equals("guilty");
				title(own, guilty
								? Component.translatable("court.aceattorney.verdict.guilty").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
								: Component.translatable("court.aceattorney.verdict.not_guilty").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
						Component.translatable("court.aceattorney.verdict.sub"));
				gavel(own, guilty ? 0.8f : 1.2f);
				protocol(own, actor, "выносит вердикт: " + (guilty ? "ВИНОВЕН" : "НЕВИНОВЕН"));
				close(own, guilty ? "guilty" : "not_guilty");
			}
			case "role" -> {
				CourtRole role = parseRole(m.arg(0));
				Session target = SESSIONS.get(m.intArg(1));
				if (target.roles.get(actor) == role) {
					return;
				}
				if (own != null && own != target) {
					leaveSession(own, actor, "покидает заседание (занимает место в деле №" + target.number + ")");
				}
				if (role == CourtRole.JUDGE) {
					target.judge = actor;
				}
				target.roles.put(actor, role);
				chat(target, Component.translatable("court.aceattorney.role_assigned", actor, role.displayName()));
				protocol(target, actor, "занимает место: " + role.displayName().getString());
			}
			case "leave" -> {
				if (actor.equals(myName())) {
					chatNow(Component.translatable("court.aceattorney.left_you", own.number));
				}
				leaveSession(own, actor, "покидает заседание");
			}
			case "ev" -> {
				String[] parts = splitEvidence(m.payload());
				own.evidence.add(new Evidence(parts[0], parts[1], actor));
				chat(own, Component.translatable("court.aceattorney.evidence_added", actor,
						Component.literal(parts[0]).withStyle(ChatFormatting.YELLOW)));
				protocol(own, actor, "приобщает улику «" + parts[0] + "»: " + parts[1]);
			}
			case "present" -> {
				Evidence e = own.evidence.get(m.intArg(0) - 1);
				if (inAudience(own)) {
					ShoutOverlay.show(ShoutType.TAKE_THAT, actor);
				}
				chat(own, Component.translatable("court.aceattorney.evidence_presented", actor,
						Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
				chat(own, Component.literal("  «" + e.desc() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				protocol(own, actor, "предъявляет улику «" + e.name() + "»");
			}
			case "st" -> {
				own.testimony.add(new Statement(actor, m.payload()));
				int number = own.testimony.size();
				if (actor.equals(myName())) {
					chatNow(Component.translatable("court.aceattorney.statement_added", number));
				}
				protocol(own, actor, "даёт показание №" + number + ": «" + m.payload() + "»");
			}
			case "edit" -> {
				int index = m.intArg(0);
				Statement old = own.testimony.get(index - 1);
				own.testimony.set(index - 1, new Statement(old.speaker(), m.payload()));
				chat(own, Component.translatable("court.aceattorney.statement_edited", actor, index));
				dialogue(own, new DialogueS2CPayload(old.speaker(), m.payload(), index));
				protocol(own, actor, "изменяет показание №" + index + ": «" + old.text() + "» → «" + m.payload() + "»");
			}
			case "play" -> {
				title(own, Component.translatable("court.aceattorney.testimony_title").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), null);
				int i = 1;
				for (Statement s : own.testimony) {
					dialogue(own, new DialogueS2CPayload(s.speaker(), s.text(), i++));
				}
				protocol(own, actor, "оглашает показания (" + own.testimony.size() + " шт.)");
			}
			case "press" -> {
				int index = m.intArg(0);
				Statement s = own.testimony.get(index - 1);
				if (inAudience(own)) {
					ShoutOverlay.show(ShoutType.HOLD_IT, actor);
				}
				chat(own, Component.translatable("court.aceattorney.press", actor, index));
				dialogue(own, new DialogueS2CPayload(s.speaker(), s.text(), index));
				protocol(own, actor, "давит на показание №" + index + " («" + s.text() + "»)");
			}
			case "object" -> {
				int index = m.intArg(0);
				Statement s = own.testimony.get(index - 1);
				if (inAudience(own)) {
					ShoutOverlay.show(ShoutType.OBJECTION, actor);
				}
				dialogue(own, new DialogueS2CPayload(s.speaker(), s.text(), index));
				if (m.args().size() >= 2) {
					Evidence e = own.evidence.get(m.intArg(1) - 1);
					chat(own, Component.translatable("court.aceattorney.objection_evidence", actor,
							Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), index));
					chat(own, Component.literal("  «" + e.desc() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
					protocol(own, actor, "заявляет протест против показания №" + index + " с уликой «" + e.name() + "»");
				} else {
					chat(own, Component.translatable("court.aceattorney.objection_plain", actor, index));
					protocol(own, actor, "заявляет протест против показания №" + index);
				}
			}
			default -> {
			}
		}
	}

	// ---------- leaving, closing, abandoned sessions ----------

	private static void leaveSession(Session session, String actor, String protocolText) {
		protocol(session, actor, protocolText);
		session.roles.remove(actor);
		chat(session, Component.translatable("court.aceattorney.left", actor));
		if (session.roles.isEmpty()) {
			chat(session, Component.translatable("court.aceattorney.session_empty", session.number).withStyle(ChatFormatting.GRAY));
			close(session, "dismissed");
		}
	}

	/** Records the case in the local journal and forgets the session. */
	private static void close(Session session, String verdict) {
		appendCase(session, verdict);
		SESSIONS.remove(session.number);
	}

	/** Sessions nobody is online for within reach of a new one are closed so they cannot block it. */
	private static void closeAbandonedNear(Site site) {
		for (Session session : new ArrayList<>(SESSIONS.values())) {
			if (session.site.isNear(site) && isAbandoned(session)) {
				session.protocol.add(new LogEntry(LocalTime.now().format(TIME_FORMAT),
						"—", "заседание закрыто автоматически: никого из участников нет в сети"));
				chat(session, Component.translatable("court.aceattorney.abandoned_closed", session.number).withStyle(ChatFormatting.GRAY));
				close(session, "dismissed");
			}
		}
	}

	private static boolean isAbandoned(Session session) {
		Set<String> online = onlineNames();
		for (String name : session.roles.keySet()) {
			if (online.contains(name)) {
				return false;
			}
		}
		return true;
	}

	// ---------- late joiners: sync request and snapshots ----------

	private static void requestSync() {
		long now = System.currentTimeMillis();
		if (now - lastSyncRequest < SYNC_REQUEST_INTERVAL_MS) {
			return;
		}
		lastSyncRequest = now;
		RelayChannel.send(RelayMessage.of("sync?", ""));
	}

	/**
	 * For each session one client answers: its judge if online, otherwise the
	 * first online participant (never the requester itself). Everyone sees the
	 * same tab list, so everyone agrees on who that is.
	 */
	private static void maybeSendSnapshot(String requester) {
		String me = myName();
		Set<String> online = onlineNames();
		long now = System.currentTimeMillis();
		for (Session session : new ArrayList<>(SESSIONS.values())) {
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
			Long last = LAST_SNAPSHOT.get(session.number);
			if (!me.equals(responder) || (last != null && now - last < SNAPSHOT_INTERVAL_MS)) {
				continue;
			}
			LAST_SNAPSHOT.put(session.number, now);
			sendSnapshot(session);
		}
	}

	private static void sendSnapshot(Session session) {
		Site site = session.site;
		RelayChannel.send(RelayMessage.of("snap", session.caseName, session.number, session.judge,
				site.dimension(), Math.round(site.x()), Math.round(site.y()), Math.round(site.z())));
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
		Site site = parseSite(m, 2);
		if (number < 1 || judge.isEmpty() || site == null) {
			return;
		}
		Session known = SESSIONS.get(number);
		if (known != null && known.judge.equals(judge)) {
			PENDING.remove(sender); // already in sync
			return;
		}
		PENDING.put(sender, new Session(judge, number, m.payload(), site));
	}

	private static void onSnapshotLine(String sender, RelayMessage m) {
		Session pending = PENDING.get(sender);
		if (pending == null) {
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
		Session session = PENDING.remove(sender);
		if (session == null) {
			return;
		}
		SESSIONS.put(session.number, session);
		protocol(session, myName(), "подключается к заседанию (более ранние записи протокола недоступны)");
		if (inAudience(session)) {
			chatNow(Component.translatable("chat.aceattorney.relay_synced", session.number, session.judge)
					.withStyle(ChatFormatting.GRAY));
		}
		refreshScreen();
	}

	// ---------- state for the Court Record GUI ----------

	private static void refreshScreen() {
		CourtScreen.acceptState(buildState().toString());
	}

	private static JsonObject buildState() {
		JsonObject root = new JsonObject();
		Session session = viewSession();
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
			Session session = sessionOf(myName());
			if (session == null) {
				fail(new Failure(SESSIONS.isEmpty() ? "court.aceattorney.no_session" : "court.aceattorney.not_participant"));
				return;
			}
			CourtRole role = session.roles.get(myName());
			if (role != CourtRole.CLERK && role != CourtRole.JUDGE) {
				fail(new Failure("court.aceattorney.export_clerk_only"));
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
				fail(new Failure("court.aceattorney.no_such_case"));
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
		if (!Files.exists(caseLogFile)) {
			return;
		}
		try {
			caseLog = JsonParser.parseString(Files.readString(caseLogFile, StandardCharsets.UTF_8)).getAsJsonArray();
		} catch (Exception e) {
			AceAttorney.LOGGER.warn("Could not read relay case log {}", caseLogFile, e);
			caseLog = new JsonArray();
		}
	}

	private static void appendCase(Session session, String verdict) {
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

	/** Case numbers never repeat: after the highest one in the journal or among running sessions. */
	private static int nextCaseNumber() {
		int highest = 0;
		for (var el : caseLog) {
			highest = Math.max(highest, el.getAsJsonObject().get("number").getAsInt());
		}
		for (Session session : SESSIONS.values()) {
			highest = Math.max(highest, session.number);
		}
		return highest + 1;
	}

	// ---------- sessions and places ----------

	/** The session in which the player holds a role (a player is in at most one). */
	private static Session sessionOf(String name) {
		for (Session session : SESSIONS.values()) {
			if (session.roles.containsKey(name)) {
				return session;
			}
		}
		return null;
	}

	/** The session the local player is part of, or else the one happening around them. */
	private static Session viewSession() {
		Session own = sessionOf(myName());
		if (own != null) {
			return own;
		}
		Site here = mySite();
		return here == null ? null : here.nearest(SESSIONS.values(), s -> s.site);
	}

	/** Whether the local player should see what the session says: its participants and those nearby. */
	private static boolean inAudience(Session session) {
		if (session.roles.containsKey(myName())) {
			return true;
		}
		Site here = mySite();
		return here != null && here.isNear(session.site);
	}

	private static Site mySite() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return null;
		}
		return new Site(mc.level.dimension().identifier().toString(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
	}

	/** Reads a site from the arguments: dimension, x, y, z starting at {@code from}. */
	private static Site parseSite(RelayMessage m, int from) {
		String dimension = m.arg(from);
		if (dimension.isEmpty()) {
			return null;
		}
		try {
			return new Site(dimension, Double.parseDouble(m.arg(from + 1)),
					Double.parseDouble(m.arg(from + 2)), Double.parseDouble(m.arg(from + 3)));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---------- helpers ----------

	private static Failure requireParticipant(Session own) {
		if (own != null) {
			return null;
		}
		return notParticipant();
	}

	private static Failure notParticipant() {
		return new Failure(SESSIONS.isEmpty() ? "court.aceattorney.no_session" : "court.aceattorney.not_participant");
	}

	private static Failure requireJudge(Session own, String actor) {
		if (own == null) {
			return notParticipant();
		}
		return own.isJudge(actor) ? null : new Failure("court.aceattorney.judge_only");
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
		Session own = sessionOf(myName());
		return own != null && inRange(index, own.evidence.size()) ? own.evidence.get(index - 1).name() : "";
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

	private static void protocol(Session session, String actor, String text) {
		session.protocol.add(new LogEntry(LocalTime.now().format(TIME_FORMAT), actor, text));
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

	// ---------- output: only the session's audience sees it ----------

	private static void chat(Session session, Component message) {
		if (inAudience(session)) {
			chatNow(message);
		}
	}

	private static void chatNow(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(message, false);
		}
	}

	private static void fail(Failure failure) {
		if (failure.key().isEmpty()) {
			return;
		}
		chatNow(Component.translatable(failure.key(), failure.args()).withStyle(ChatFormatting.RED));
	}

	private static void title(Session session, Component title, Component subtitle) {
		if (!inAudience(session)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setTimes(5, 50, 10);
		mc.gui.setSubtitle(subtitle != null ? subtitle : Component.empty());
		mc.gui.setTitle(title);
	}

	private static void gavel(Session session, float pitch) {
		if (inAudience(session)) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.GAVEL, pitch, 1.0f));
		}
	}

	private static void dialogue(Session session, DialogueS2CPayload payload) {
		if (inAudience(session)) {
			DialogueOverlay.enqueue(payload);
		}
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
