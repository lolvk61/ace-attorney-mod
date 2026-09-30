package com.stratfat.aceattorney.relay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Forwards Ace Attorney court events between players who have the client
 * mod. A client sends an event on the "aceattorney:relay" channel; the
 * plugin stamps it with the real sender and delivers it to every player who
 * registered that channel, i.e. everybody with the mod. Players without the
 * mod never receive anything and nothing is written to chat.
 *
 * Wire format (Minecraft string: VarInt byte length + UTF-8), both directions:
 * sender, uuid, data. Clients leave sender and uuid empty; the plugin
 * overwrites them, so a client cannot impersonate another player.
 */
public final class AceAttorneyRelayPlugin extends JavaPlugin implements PluginMessageListener, Listener {
	static final String CHANNEL = "aceattorney:relay";

	/** The mod keeps event payloads under ~700 characters; anything larger is not from the mod. */
	private static final int MAX_DATA_LENGTH = 2000;
	private static final int MAX_EVENTS_PER_WINDOW = 300;
	private static final long WINDOW_MILLIS = 5000;

	private final Map<UUID, RateWindow> rates = new HashMap<>();

	private static final class RateWindow {
		long start;
		int count;
	}

	@Override
	public void onEnable() {
		getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
		getServer().getPluginManager().registerEvents(this, this);
		getLogger().info("Relaying Ace Attorney events on " + CHANNEL);
	}

	@Override
	public void onDisable() {
		getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
		getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
		rates.clear();
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		rates.remove(event.getPlayer().getUniqueId());
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!CHANNEL.equals(channel) || isRateLimited(player)) {
			return;
		}
		String data = readData(message);
		if (data == null || data.length() > MAX_DATA_LENGTH) {
			return;
		}
		byte[] out = encode(player.getName(), player.getUniqueId().toString(), data);
		for (Player target : getServer().getOnlinePlayers()) {
			if (target.getListeningPluginChannels().contains(CHANNEL)) {
				target.sendPluginMessage(this, CHANNEL, out);
			}
		}
	}

	private boolean isRateLimited(Player player) {
		long now = System.currentTimeMillis();
		RateWindow window = rates.computeIfAbsent(player.getUniqueId(), id -> new RateWindow());
		if (now - window.start > WINDOW_MILLIS) {
			window.start = now;
			window.count = 0;
		}
		return ++window.count > MAX_EVENTS_PER_WINDOW;
	}

	/** Reads the third string (data) of a client message, or null if it is malformed. */
	private static String readData(byte[] message) {
		int[] pos = {0};
		String sender = readString(message, pos);
		String uuid = readString(message, pos);
		String data = readString(message, pos);
		if (sender == null || uuid == null || data == null || pos[0] != message.length) {
			return null;
		}
		return data;
	}

	private static String readString(byte[] buf, int[] pos) {
		int length = 0;
		for (int shift = 0; ; shift += 7) {
			if (shift >= 35 || pos[0] >= buf.length) {
				return null;
			}
			int b = buf[pos[0]++];
			length |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
		}
		if (length < 0 || pos[0] + length > buf.length) {
			return null;
		}
		String value = new String(buf, pos[0], length, StandardCharsets.UTF_8);
		pos[0] += length;
		return value;
	}

	private static byte[] encode(String... strings) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (String s : strings) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			int length = bytes.length;
			while ((length & ~0x7F) != 0) {
				out.write((length & 0x7F) | 0x80);
				length >>>= 7;
			}
			out.write(length);
			out.writeBytes(bytes);
		}
		return out.toByteArray();
	}
}
