package com.stratfat.aceattorney.client.relay;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import com.stratfat.aceattorney.ModItems;
import com.stratfat.aceattorney.client.CourtScreen;
import com.stratfat.aceattorney.net.CourtActionC2SPayload;
import com.stratfat.aceattorney.net.RelayPayload;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;

/**
 * Client-only mode: on a server without the mod, court events travel
 * between modded clients through the AceAttorneyRelay server plugin. The
 * plugin forwards each event to every player who registered the channel
 * (only players with this mod do), so nothing ever appears in chat. The
 * plugin delivers events to everybody in one order, so every client applies
 * the same stream and ends up with the same court state.
 */
public final class RelayChannel {
	/** Keep bursts (a snapshot for a late joiner) well under the server's packet limiter. */
	private static final int SENDS_PER_TICK = 10;
	private static final Deque<String> OUTBOX = new ArrayDeque<>();
	private static boolean relayStarted;

	private RelayChannel() {
	}

	/** True on a multiplayer server that has neither the mod nor a way to reach us. */
	public static boolean isRemoteWithoutMod() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null
				&& !mc.hasSingleplayerServer()
				&& !ClientPlayNetworking.canSend(CourtActionC2SPayload.TYPE);
	}

	/** True when court events can travel through the relay plugin. */
	public static boolean isActive() {
		return isRemoteWithoutMod() && ClientPlayNetworking.canSend(RelayPayload.TYPE);
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(RelayPayload.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(RelayChannel::refreshMode));
		// the server tells us which channels it understands shortly after joining
		C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> client.execute(RelayChannel::refreshMode));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			OUTBOX.clear();
			relayStarted = false;
			ModItems.hideContent = false;
			RelayCourt.reset();
			CourtScreen.resetState();
		}));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			for (int i = 0; i < SENDS_PER_TICK && !OUTBOX.isEmpty(); i++) {
				if (!isActive()) {
					OUTBOX.clear();
					break;
				}
				ClientPlayNetworking.send(new RelayPayload("", "", OUTBOX.pollFirst()));
			}
		});

		// "/aa say" is a server command; with the plugin (which has no commands) handle it here
		ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
			if (isActive() && command.startsWith("aa say ")) {
				RelayCourt.say(command.substring("aa say ".length()));
				return false;
			}
			return true;
		});
	}

	/** Re-evaluates what the server supports; called on join and whenever it announces channels. */
	private static void refreshMode() {
		// items from this mod do not exist on a server without it: hide them from the creative tab
		ModItems.hideContent = isRemoteWithoutMod();
		if (isActive() && !relayStarted) {
			relayStarted = true;
			RelayCourt.onJoin();
		}
	}

	/** Queues an event for the plugin. */
	public static void send(RelayMessage message) {
		if (isActive()) {
			OUTBOX.addLast(message.encode());
		}
	}

	private static void receive(RelayPayload payload) {
		if (!isActive() || payload.sender().isEmpty()) {
			return;
		}
		RelayMessage message = RelayMessage.decode(payload.data());
		if (message == null) {
			return;
		}
		UUID id;
		try {
			id = UUID.fromString(payload.uuid());
		} catch (IllegalArgumentException e) {
			return;
		}
		RelayCourt.onEvent(payload.sender(), id, message);
	}
}
