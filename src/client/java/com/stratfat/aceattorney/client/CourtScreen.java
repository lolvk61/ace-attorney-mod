package com.stratfat.aceattorney.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.stratfat.aceattorney.net.CourtActionC2SPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * Court Record GUI (open with the G key). Shows evidence and testimony,
 * with buttons for present/press/object, adding evidence and statements,
 * and judge controls. Actions go to the server as CourtActionC2SPayload.
 */
public class CourtScreen extends Screen {
	private static final int PANEL_W = 400;
	private static final int PANEL_H = 258;
	private static final int LOG_ROWS = 15;
	private static final int ROWS = 7;
	private static final int ROW_H = 11;
	private static final int LIST_TOP = 44;

	private static JsonObject state; // latest snapshot from the server

	private int left;
	private int top;
	private boolean addMode;
	private boolean logMode;
	private int logScroll;
	private int evOffset;
	private int stOffset;
	private int selEv = -1;
	private int selSt = -1; // global statement index (matches server numbering)
	private String selSpeaker; // null = witness list view

	private Button presentBtn;
	private Button pressBtn;
	private Button objectBtn;
	private Button editBtn;
	private int editingIndex = -1;
	private EditBox editBox;
	private EditBox nameBox;
	private EditBox descBox;
	private EditBox statementBox;
	private EditBox caseBox;
	private EditBox sayBox;

	public CourtScreen() {
		super(Component.translatable("gui.aceattorney.title"));
	}

	public static void acceptState(String json) {
		try {
			state = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		if (Minecraft.getInstance().screen instanceof CourtScreen screen) {
			screen.rebuild();
		}
	}

	public static void requestState() {
		send("{\"action\":\"request_state\"}");
	}

	private static void send(String json) {
		if (ClientPlayNetworking.canSend(CourtActionC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new CourtActionC2SPayload(json));
		}
	}

	private static void sendAction(String action, Object... kv) {
		JsonObject obj = new JsonObject();
		obj.addProperty("action", action);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			String key = (String) kv[i];
			Object value = kv[i + 1];
			if (value instanceof Number n) {
				obj.addProperty(key, n);
			} else if (value instanceof Boolean b) {
				obj.addProperty(key, b);
			} else {
				obj.addProperty(key, String.valueOf(value));
			}
		}
		send(obj.toString());
	}

	// ---------- layout ----------

	@Override
	protected void init() {
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		// docked to the right edge so the world stays visible
		left = Math.max(8, width - PANEL_W - 8);
		top = (height - PANEL_H) / 2;
		selEv = Math.min(selEv, evidenceCount() - 1);
		selSt = Math.min(selSt, testimonyCount() - 1);

		if (logMode) {
			if (logCount() > LOG_ROWS) {
				addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
					logScroll = Math.max(0, logScroll - 1);
				}).bounds(left + PANEL_W - 22, top + 22, 14, 12).build());
				addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
					logScroll = Math.min(Math.max(0, logCount() - LOG_ROWS), logScroll + 1);
				}).bounds(left + PANEL_W - 22, top + 22 + LOG_ROWS * 12 - 12, 14, 12).build());
			}
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.back"), b -> {
				logMode = false;
				rebuild();
			}).bounds(left + PANEL_W / 2 - 50, top + PANEL_H - 26, 100, 18).build());
			return;
		}

		if (!addMode) {
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.log"), b -> {
				logMode = true;
				logScroll = 0;
				rebuild();
			}).bounds(left + PANEL_W - 68, top + 4, 60, 14).build());
		}

		if (!isActive()) {
			caseBox = new EditBox(font, left + PANEL_W / 2 - 100, top + PANEL_H / 2 - 32, 200, 18,
					Component.translatable("gui.aceattorney.case_hint"));
			caseBox.setMaxLength(40);
			addRenderableWidget(caseBox);
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.start"),
					b -> sendAction("start", "case", caseBox.getValue().trim()))
					.bounds(left + PANEL_W / 2 - 80, top + PANEL_H / 2 - 6, 160, 20).build());
			return;
		}

		if (editingIndex >= 0) {
			if (editingIndex >= testimonyCount()) {
				editingIndex = -1;
			} else {
				String current = state.getAsJsonArray("testimony").get(editingIndex)
						.getAsJsonObject().get("text").getAsString();
				editBox = new EditBox(font, left + 50, top + 80, 300, 18, Component.translatable("gui.aceattorney.statement_hint"));
				editBox.setMaxLength(200);
				editBox.setValue(current);
				addRenderableWidget(editBox);
				final int idx = editingIndex;
				addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.save"), b -> {
					if (!editBox.getValue().isBlank()) {
						sendAction("edit_statement", "index", idx + 1, "text", editBox.getValue().trim());
						editingIndex = -1;
						rebuild();
					}
				}).bounds(left + 50, top + 120, 150, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.back"), b -> {
					editingIndex = -1;
					rebuild();
				}).bounds(left + 210, top + 120, 100, 20).build());
				return;
			}
		}

		if (addMode) {
			nameBox = new EditBox(font, left + 50, top + 60, 300, 18, Component.translatable("gui.aceattorney.name"));
			nameBox.setMaxLength(60);
			addRenderableWidget(nameBox);
			descBox = new EditBox(font, left + 50, top + 100, 300, 18, Component.translatable("gui.aceattorney.desc"));
			descBox.setMaxLength(200);
			addRenderableWidget(descBox);
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.add_evidence.confirm"), b -> {
				if (!nameBox.getValue().isBlank()) {
					sendAction("add_evidence", "name", nameBox.getValue().trim(),
							"desc", descBox.getValue().isBlank() ? "—" : descBox.getValue().trim());
					addMode = false;
					rebuild();
				}
			}).bounds(left + 50, top + 140, 190, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.back"), b -> {
				addMode = false;
				rebuild();
			}).bounds(left + 250, top + 140, 100, 20).build());
			return;
		}

		// scroll buttons
		if (evidenceCount() > ROWS) {
			addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
				evOffset = Math.max(0, evOffset - 1);
			}).bounds(left + 178, top + 28, 14, 12).build());
			addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
				evOffset = Math.min(Math.max(0, evidenceCount() - ROWS), evOffset + 1);
			}).bounds(left + 178, top + LIST_TOP + ROWS * ROW_H - 12, 14, 12).build());
		}
		if (rightListSize() > ROWS) {
			addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
				stOffset = Math.max(0, stOffset - 1);
			}).bounds(left + PANEL_W - 22, top + 28, 14, 12).build());
			addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
				stOffset = Math.min(Math.max(0, rightListSize() - ROWS), stOffset + 1);
			}).bounds(left + PANEL_W - 22, top + LIST_TOP + ROWS * ROW_H - 12, 14, 12).build());
		}
		if (selSpeaker != null) {
			addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				selSpeaker = null;
				selSt = -1;
				stOffset = 0;
				rebuild();
			}).bounds(left + PANEL_W - 40, top + LIST_TOP - 14, 14, 12).build());
		}

		editBtn = addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.edit"), b -> {
			if (selSt >= 0 && canEditSelected()) {
				editingIndex = selSt;
				rebuild();
			}
		}).bounds(left + PANEL_W - 74, top + 123, 66, 13).build());

		// action row
		presentBtn = addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.present"),
				b -> {
					if (selEv >= 0) {
						sendAction("present", "index", selEv + 1);
					}
				}).bounds(left + 8, top + 158, 92, 18).build());
		pressBtn = addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.press"),
				b -> {
					if (selSt >= 0) {
						sendAction("press", "index", selSt + 1);
					}
				}).bounds(left + 104, top + 158, 92, 18).build());
		objectBtn = addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.object"),
				b -> {
					if (selSt >= 0) {
						if (selEv >= 0) {
							sendAction("object", "statement", selSt + 1, "evidence", selEv + 1);
						} else {
							sendAction("object", "statement", selSt + 1);
						}
					}
				}).bounds(left + 200, top + 158, 92, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.add_evidence"), b -> {
			addMode = true;
			rebuild();
		}).bounds(left + 296, top + 158, 96, 18).build());

		// statement row — only the witness and the defendant testify
		String myRole = state.get("yourRole").getAsString();
		boolean canTestify = myRole.equals("witness") || myRole.equals("defendant");
		if (canTestify) {
			statementBox = new EditBox(font, left + 8, top + 180, 268, 18, Component.translatable("gui.aceattorney.statement_hint"));
			statementBox.setMaxLength(200);
			addRenderableWidget(statementBox);
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.add_statement"), b -> {
				if (!statementBox.getValue().isBlank()) {
					sendAction("add_statement", "text", statementBox.getValue().trim());
					statementBox.setValue("");
				}
			}).bounds(left + 280, top + 180, 60, 18).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.play_testimony"),
				b -> sendAction("play_testimony")).bounds(left + 344, top + 180, 48, 18).build());

		// say row (like /aa say)
		sayBox = new EditBox(font, left + 8, top + 204, 300, 18, Component.translatable("gui.aceattorney.say_hint"));
		sayBox.setMaxLength(200);
		addRenderableWidget(sayBox);
		addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.say"), b -> {
			if (!sayBox.getValue().isBlank()) {
				sendAction("say", "text", sayBox.getValue().trim());
				sayBox.setValue("");
			}
		}).bounds(left + 312, top + 204, 80, 18).build());

		// judge row
		if (isJudge()) {
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.guilty").withStyle(s -> s.withColor(0xFF5555)),
					b -> sendAction("verdict", "guilty", true)).bounds(left + 8, top + 226, 110, 18).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.not_guilty").withStyle(s -> s.withColor(0x55FF7A)),
					b -> sendAction("verdict", "guilty", false)).bounds(left + 122, top + 226, 110, 18).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.end"),
					b -> sendAction("end")).bounds(left + 236, top + 226, 156, 18).build());
		}
	}

	private static int logCount() {
		return state != null && state.has("log") ? state.getAsJsonArray("log").size() : 0;
	}

	// ---------- state helpers ----------

	private static boolean isActive() {
		return state != null && state.has("active") && state.get("active").getAsBoolean();
	}

	private boolean isJudge() {
		return isActive() && "judge".equals(state.get("yourRole").getAsString());
	}

	/** The selected statement can be edited by its author and by the judge. */
	private boolean canEditSelected() {
		if (selSt < 0 || selSt >= testimonyCount()) {
			return false;
		}
		if (isJudge()) {
			return true;
		}
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		String speaker = state.getAsJsonArray("testimony").get(selSt).getAsJsonObject().get("speaker").getAsString();
		return speaker.equals(player.getGameProfile().name());
	}

	private static int evidenceCount() {
		return isActive() ? state.getAsJsonArray("evidence").size() : 0;
	}

	private static int testimonyCount() {
		return isActive() ? state.getAsJsonArray("testimony").size() : 0;
	}

	/** Unique speakers who gave statements, in order of first appearance. */
	private static List<String> speakers() {
		List<String> list = new ArrayList<>();
		if (!isActive()) {
			return list;
		}
		JsonArray testimony = state.getAsJsonArray("testimony");
		for (var el : testimony) {
			String s = el.getAsJsonObject().get("speaker").getAsString();
			if (!list.contains(s)) {
				list.add(s);
			}
		}
		return list;
	}

	/** Global statement indices belonging to one speaker. */
	private static List<Integer> statementsOf(String speaker) {
		List<Integer> indices = new ArrayList<>();
		if (!isActive()) {
			return indices;
		}
		JsonArray testimony = state.getAsJsonArray("testimony");
		for (int i = 0; i < testimony.size(); i++) {
			if (testimony.get(i).getAsJsonObject().get("speaker").getAsString().equals(speaker)) {
				indices.add(i);
			}
		}
		return indices;
	}

	private int rightListSize() {
		return selSpeaker == null ? speakers().size() : statementsOf(selSpeaker).size();
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		if (isActive() && !addMode && !logMode && editingIndex < 0 && event.button() == 0) {
			int listY = top + LIST_TOP;
			if (mouseY >= listY && mouseY < listY + ROWS * ROW_H) {
				int row = (int) ((mouseY - listY) / ROW_H);
				if (mouseX >= left + 8 && mouseX < left + 176) {
					int idx = evOffset + row;
					if (idx < evidenceCount()) {
						selEv = (selEv == idx) ? -1 : idx;
						return true;
					}
				} else if (mouseX >= left + 204 && mouseX < left + PANEL_W - 26) {
					int idx = stOffset + row;
					if (selSpeaker == null) {
						List<String> speakers = speakers();
						if (idx < speakers.size()) {
							selSpeaker = speakers.get(idx);
							selSt = -1;
							stOffset = 0;
							rebuild();
							return true;
						}
					} else {
						List<Integer> indices = statementsOf(selSpeaker);
						if (idx < indices.size()) {
							int global = indices.get(idx);
							selSt = (selSt == global) ? -1 : global;
							return true;
						}
					}
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------- rendering ----------

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// no super call: skip vanilla blur/dim so the world stays visible
		graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xB0101826);
		graphics.fill(left, top, left + PANEL_W, top + 1, 0xFFB8C4E0);
		graphics.fill(left, top + PANEL_H - 1, left + PANEL_W, top + PANEL_H, 0xFFB8C4E0);
		graphics.fill(left, top, left + 1, top + PANEL_H, 0xFFB8C4E0);
		graphics.fill(left + PANEL_W - 1, top, left + PANEL_W, top + PANEL_H, 0xFFB8C4E0);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (presentBtn != null) {
			boolean participant = isActive() && !state.get("yourRole").getAsString().isEmpty();
			presentBtn.active = participant && selEv >= 0;
			pressBtn.active = participant && selSt >= 0;
			objectBtn.active = participant && selSt >= 0;
			if (editBtn != null) {
				editBtn.active = participant && canEditSelected();
			}
		}

		super.render(graphics, mouseX, mouseY, partialTick);

		if (logMode) {
			renderLog(graphics);
			return;
		}

		String caseName = isActive() && state.has("case") ? state.get("case").getAsString() : "";
		int caseNumber = isActive() && state.has("caseNumber") ? state.get("caseNumber").getAsInt() : 0;
		Component header;
		if (!isActive()) {
			header = title;
		} else if (caseName.isBlank()) {
			header = Component.translatable("gui.aceattorney.title_case_nameless", caseNumber);
		} else {
			header = Component.translatable("gui.aceattorney.title_case", caseNumber, caseName);
		}
		graphics.drawCenteredString(font, header, left + PANEL_W / 2, top + 6, 0xFFFFD75E);

		if (!isActive()) {
			graphics.drawCenteredString(font, Component.translatable("gui.aceattorney.no_session"),
					left + PANEL_W / 2, top + PANEL_H / 2 - 52, 0xFFAAAAAA);
			return;
		}

		String judge = state.get("judge").getAsString();
		String role = state.get("yourRole").getAsString();
		Component roleName = role.isEmpty()
				? Component.translatable("gui.aceattorney.role.none")
				: Component.translatable("role.aceattorney." + role);
		graphics.drawCenteredString(font,
				Component.translatable("gui.aceattorney.status", judge, roleName),
				left + PANEL_W / 2, top + 18, 0xFFAAB4CC);

		if (editingIndex >= 0) {
			graphics.drawString(font, Component.translatable("gui.aceattorney.edit_title", editingIndex + 1),
					left + 50, top + 66, 0xFFDDDDDD);
			return;
		}

		if (addMode) {
			graphics.drawString(font, Component.translatable("gui.aceattorney.name"), left + 50, top + 50, 0xFFDDDDDD);
			graphics.drawString(font, Component.translatable("gui.aceattorney.desc"), left + 50, top + 90, 0xFFDDDDDD);
			return;
		}

		graphics.drawString(font, Component.translatable("gui.aceattorney.evidence"), left + 8, top + LIST_TOP - 10, 0xFFFFE080);
		Component testimonyHeader = selSpeaker == null
				? Component.translatable("gui.aceattorney.testimony")
				: Component.literal(selSpeaker + ":");
		graphics.drawString(font, testimonyHeader, left + 204, top + LIST_TOP - 10, 0xFF7CE8A8);

		JsonArray evidence = state.getAsJsonArray("evidence");
		for (int row = 0; row < ROWS; row++) {
			int idx = evOffset + row;
			if (idx >= evidence.size()) {
				break;
			}
			int y = top + LIST_TOP + row * ROW_H;
			if (idx == selEv) {
				graphics.fill(left + 6, y - 1, left + 176, y + ROW_H - 1, 0x50FFFFFF);
			}
			String name = (idx + 1) + ". " + evidence.get(idx).getAsJsonObject().get("name").getAsString();
			graphics.drawString(font, font.plainSubstrByWidth(name, 164), left + 8, y, 0xFFFFE080);
		}

		JsonArray testimony = state.getAsJsonArray("testimony");
		if (selSpeaker == null) {
			// level 1: who has testified
			List<String> speakers = speakers();
			for (int row = 0; row < ROWS; row++) {
				int idx = stOffset + row;
				if (idx >= speakers.size()) {
					break;
				}
				int y = top + LIST_TOP + row * ROW_H;
				String name = speakers.get(idx);
				String line = "▸ " + name + " (" + statementsOf(name).size() + ")";
				graphics.drawString(font, font.plainSubstrByWidth(line, 158), left + 204, y, 0xFF7CE8A8);
			}
		} else {
			// level 2: statements of the selected speaker (global numbering)
			List<Integer> indices = statementsOf(selSpeaker);
			for (int row = 0; row < ROWS; row++) {
				int idx = stOffset + row;
				if (idx >= indices.size()) {
					break;
				}
				int global = indices.get(idx);
				int y = top + LIST_TOP + row * ROW_H;
				if (global == selSt) {
					graphics.fill(left + 202, y - 1, left + PANEL_W - 26, y + ROW_H - 1, 0x50FFFFFF);
				}
				String text = (global + 1) + ". " + testimony.get(global).getAsJsonObject().get("text").getAsString();
				graphics.drawString(font, font.plainSubstrByWidth(text, 158), left + 204, y, 0xFF7CE8A8);
			}
		}

		// detail panel: selected evidence or statement
		String detail = null;
		if (selEv >= 0 && selEv < evidence.size()) {
			JsonObject e = evidence.get(selEv).getAsJsonObject();
			detail = e.get("name").getAsString() + " — " +e.get("desc").getAsString()
					+ " (" + e.get("submitter").getAsString() + ")";
		} else if (selSt >= 0 && selSt < testimony.size()) {
			JsonObject s = testimony.get(selSt).getAsJsonObject();
			detail = "№" + (selSt + 1) + " (" + s.get("speaker").getAsString() + "): " + s.get("text").getAsString();
		}
		if (detail != null) {
			List<FormattedCharSequence> lines = new ArrayList<>(font.split(FormattedText.of(detail), PANEL_W - 96));
			int y = top + 126;
			for (int i = 0; i < Math.min(3, lines.size()); i++) {
				graphics.drawString(font, lines.get(i), left + 8, y, 0xFFDDDDDD);
				y += 10;
			}
		}
	}

	private void renderLog(GuiGraphics graphics) {
		graphics.drawCenteredString(font, Component.translatable("gui.aceattorney.log_title"),
				left + PANEL_W / 2, top + 6, 0xFFFFD75E);
		int count = logCount();
		if (count == 0) {
			graphics.drawCenteredString(font, Component.translatable("gui.aceattorney.log_empty"),
					left + PANEL_W / 2, top + PANEL_H / 2 - 10, 0xFFAAAAAA);
			return;
		}
		JsonArray log = state.getAsJsonArray("log");
		for (int row = 0; row < LOG_ROWS; row++) {
			int idx = count - 1 - logScroll - row; // latest first
			if (idx < 0) {
				break;
			}
			JsonObject r = log.get(idx).getAsJsonObject();
			String verdict = r.get("verdict").getAsString();
			String verdictText = switch (verdict) {
				case "guilty" -> I18n.get("court.aceattorney.verdict.guilty");
				case "not_guilty" -> I18n.get("court.aceattorney.verdict.not_guilty");
				default -> I18n.get("court.aceattorney.verdict.dismissed");
			};
			int color = switch (verdict) {
				case "guilty" -> 0xFFFF7070;
				case "not_guilty" -> 0xFF70E890;
				default -> 0xFFAAAAAA;
			};
			String name = r.get("name").getAsString();
			String line = "№" + r.get("number").getAsInt()
					+ (name.isBlank() ? "" : " «" + name + "»")
					+ " — " + verdictText
					+ " • " + r.get("judge").getAsString()
					+ " • " + r.get("date").getAsString();
			graphics.drawString(font, font.plainSubstrByWidth(line, PANEL_W - 40),
					left + 8, top + 22 + row * 12, color);
		}
	}
}
