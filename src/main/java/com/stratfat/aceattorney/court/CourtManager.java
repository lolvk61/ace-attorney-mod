package com.stratfat.aceattorney.court;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * All active court sessions on the server. Several may run at once as long
 * as they are at least {@link Site#RADIUS} blocks apart. What a session says
 * (chat lines, titles, dialogues, GUI state) reaches only its audience: its
 * participants wherever they are, and everyone within that radius of it.
 */
public class CourtManager {
	private static final List<CourtSession> SESSIONS = new ArrayList<>();

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(CaseLog::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
	}

	// ---------- sessions ----------

	public static List<CourtSession> sessions() {
		return SESSIONS;
	}

	/** The session in which this player holds a role (a player is in at most one). */
	public static CourtSession ofParticipant(UUID player) {
		for (CourtSession session : SESSIONS) {
			if (session.isParticipant(player)) {
				return session;
			}
		}
		return null;
	}

	/** The session closest to the site within the exclusion radius, or null. */
	public static CourtSession nearest(Site site) {
		return site.nearest(SESSIONS, CourtSession::site);
	}

	/** The session a player is part of, or else the one happening around them (as a spectator). */
	public static CourtSession viewedBy(ServerPlayer player) {
		CourtSession own = ofParticipant(player.getUUID());
		return own != null ? own : nearest(siteOf(player));
	}

	/** Case numbers never repeat: after the highest one in the journal or among running sessions. */
	public static int nextCaseNumber() {
		int highest = CaseLog.highestNumber();
		for (CourtSession session : SESSIONS) {
			highest = Math.max(highest, session.caseNumber());
		}
		return highest + 1;
	}

	public static CourtSession start(ServerPlayer judge, Site site) {
		CourtSession session = new CourtSession(judge.getUUID(), site);
		session.setCaseNumber(nextCaseNumber());
		SESSIONS.add(session);
		return session;
	}

	public static void remove(CourtSession session) {
		SESSIONS.remove(session);
	}

	/** A session nobody is online for must not block its surroundings forever. */
	public static boolean isAbandoned(CourtSession session, MinecraftServer server) {
		for (UUID id : session.roles().keySet()) {
			if (server.getPlayerList().getPlayer(id) != null) {
				return false;
			}
		}
		return true;
	}

	// ---------- sites ----------

	public static Site siteOf(ServerPlayer player) {
		return new Site(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ());
	}

	public static Site siteOf(ServerLevel level, BlockPos pos) {
		var center = pos.getCenter();
		return new Site(level.dimension().identifier().toString(), center.x, center.y, center.z);
	}

	// ---------- audience ----------

	/** Online players who should see what happens in the session. */
	public static List<ServerPlayer> audience(CourtSession session, MinecraftServer server) {
		List<ServerPlayer> result = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (session.isParticipant(player.getUUID()) || session.site().isNear(siteOf(player))) {
				result.add(player);
			}
		}
		return result;
	}

	public static void broadcast(CourtSession session, MinecraftServer server, Component message) {
		for (ServerPlayer player : audience(session, server)) {
			player.sendSystemMessage(message);
		}
	}

	public static void broadcastTitle(CourtSession session, MinecraftServer server, Component title,
			Component subtitle, SoundEvent sound, float pitch) {
		for (ServerPlayer player : audience(session, server)) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 10));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
			if (subtitle != null) {
				player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			}
			if (sound != null) {
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
						sound, SoundSource.PLAYERS, 1.0f, pitch);
			}
		}
	}
}
