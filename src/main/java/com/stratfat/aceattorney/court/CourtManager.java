package com.stratfat.aceattorney.court;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public class CourtManager {
	private static CourtSession session;

	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> session = null);
	}

	public static CourtSession session() {
		return session;
	}

	public static boolean isActive() {
		return session != null;
	}

	public static CourtSession start(ServerPlayer judge) {
		session = new CourtSession(judge.getUUID());
		return session;
	}

	public static void end() {
		session = null;
	}

	public static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	public static void broadcastTitle(MinecraftServer server, Component title, Component subtitle, SoundEvent sound, float pitch) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
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
