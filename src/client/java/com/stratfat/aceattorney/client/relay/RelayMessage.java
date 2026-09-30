package com.stratfat.aceattorney.client.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One court event on the relay channel: an operation, a few short
 * arguments and a free-text payload, serialised as a small JSON object.
 * Everything received from other clients is treated as untrusted input.
 */
public record RelayMessage(String op, List<String> args, String payload) {
	private static final Pattern OP_PATTERN = Pattern.compile("[a-z?-]{1,16}");
	private static final int MAX_ARGS = 4;
	private static final int MAX_ARG_LENGTH = 64;
	private static final int MAX_PAYLOAD_LENGTH = 600;

	public static RelayMessage of(String op, String payload, Object... args) {
		List<String> list = new ArrayList<>();
		for (Object arg : args) {
			list.add(String.valueOf(arg));
		}
		return new RelayMessage(op, list, payload == null ? "" : payload);
	}

	public String encode() {
		JsonObject json = new JsonObject();
		json.addProperty("op", op);
		JsonArray array = new JsonArray();
		args.forEach(array::add);
		json.add("a", array);
		json.addProperty("p", payload);
		return json.toString();
	}

	/** Parses an incoming event, or returns null if it is malformed. */
	public static RelayMessage decode(String data) {
		try {
			JsonObject json = JsonParser.parseString(data).getAsJsonObject();
			String op = json.get("op").getAsString();
			if (!OP_PATTERN.matcher(op).matches()) {
				return null;
			}
			List<String> args = new ArrayList<>();
			for (var el : json.getAsJsonArray("a")) {
				if (args.size() >= MAX_ARGS) {
					return null;
				}
				args.add(clean(el.getAsString(), MAX_ARG_LENGTH));
			}
			String payload = json.has("p") ? clean(json.get("p").getAsString(), MAX_PAYLOAD_LENGTH) : "";
			return new RelayMessage(op, args, payload);
		} catch (Exception e) {
			return null;
		}
	}

	public String arg(int i) {
		return i < args.size() ? args.get(i) : "";
	}

	/** Integer argument, or -1 if missing or malformed. */
	public int intArg(int i) {
		try {
			return Integer.parseInt(arg(i));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String clean(String text, int max) {
		String clean = sanitize(text);
		return clean.length() > max ? clean.substring(0, max) : clean;
	}

	/** Strips the section sign (it would format text) and control characters. */
	public static String sanitize(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\n' || c == '\r' || c == '\t') {
				sb.append(' ');
			} else if (c != '§' && c >= ' ' && c != 127) {
				sb.append(c);
			}
		}
		return sb.toString().trim();
	}
}
