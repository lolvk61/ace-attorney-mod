package com.stratfat.aceattorney.net;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.stratfat.aceattorney.ShoutType;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.level.ServerPlayer;

public class ModNetworking {
	public static final double SHOUT_RADIUS = 64.0;
	private static final long SHOUT_COOLDOWN_MS = 2000;
	private static final Map<UUID, Long> LAST_SHOUT = new HashMap<>();

	public static void init() {
		PayloadTypeRegistry.playC2S().register(ShoutC2SPayload.TYPE, ShoutC2SPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ShoutS2CPayload.TYPE, ShoutS2CPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ShoutC2SPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> handleShout(player, payload.shout()));
		});
	}

	private static void handleShout(ServerPlayer player, ShoutType shout) {
		long now = System.currentTimeMillis();
		Long last = LAST_SHOUT.get(player.getUUID());
		if (last != null && now - last < SHOUT_COOLDOWN_MS) {
			return;
		}
		LAST_SHOUT.put(player.getUUID(), now);
		broadcastShout(player, shout);
	}

	public static void broadcastShout(ServerPlayer source, ShoutType shout) {
		ShoutS2CPayload payload = new ShoutS2CPayload(shout, source.getGameProfile().name());
		for (ServerPlayer other : source.level().getServer().getPlayerList().getPlayers()) {
			if (other.level() == source.level() && other.distanceTo(source) <= SHOUT_RADIUS) {
				ServerPlayNetworking.send(other, payload);
			}
		}
	}
}
