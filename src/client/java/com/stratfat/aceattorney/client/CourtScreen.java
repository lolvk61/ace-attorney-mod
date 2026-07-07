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
	private static final int PANEL_H = 236;
	private static final int ROWS = 7;
	private static final int ROW_H = 11;
	private static final int LIST_TOP = 44;

	private static JsonObject state; // latest snapshot from the server

	private int left;
	private int top;
	private boolean addMode;
	private int evOffset;
	private int stOffset;
	private int selEv = -1;
	private int selSt = -1;

	private Button presentBtn;
	private Button pressBtn;
	private Button objectBtn;
	private EditBox nameBox;
	private EditBox descBox;
	private EditBox statementBox;
	private EditBox caseBox;

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
		if (testimonyCount() > ROWS) {
			addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
				stOffset = Math.max(0, stOffset - 1);
			}).bounds(left + PANEL_W - 22, top + 28, 14, 12).build());
			addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
				stOffset = Math.min(Math.max(0, testimonyCount() - ROWS), stOffset + 1);
			}).bounds(left + PANEL_W - 22, top + LIST_TOP + ROWS * ROW_H - 12, 14, 12).build());
		}

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

		// statement row
		statementBox = new EditBox(font, left + 8, top + 180, 268, 18, Component.translatable("gui.aceattorney.statement_hint"));
		statementBox.setMaxLength(200);
		addRenderableWidget(statementBox);
		addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.add_statement"), b -> {
			if (!statementBox.getValue().isBlank()) {
				sendAction("add_statement", "text", statementBox.getValue().trim());
				statementBox.setValue("");
			}
		}).bounds(left + 280, top + 180, 60, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.play_testimony"),
				b -> sendAction("play_testimony")).bounds(left + 344, top + 180, 48, 18).build());

		// judge row
		if (isJudge()) {
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.guilty").withStyle(s -> s.withColor(0xFF5555)),
					b -> sendAction("verdict", "guilty", true)).bounds(left + 8, top + 204, 110, 18).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.not_guilty").withStyle(s -> s.withColor(0x55FF7A)),
					b -> sendAction("verdict", "guilty", false)).bounds(left + 122, top + 204, 110, 18).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.aceattorney.end"),
					b -> sendAction("end")).bounds(left + 236, top + 204, 156, 18).build());
		}
	}

	// ---------- state helpers ----------

	private static boolean isActive() {
		return state != null && state.has("active") && state.get("active").getAsBoolean();
	}

	private boolean isJudge() {
		return isActive() && "judge".equals(state.get("yourRole").getAsString());
	}

	private static int evidenceCount() {
		return isActive() ? state.getAsJsonArray("evidence").size() : 0;
	}

	private static int testimonyCount() {
		return isActive() ? state.getAsJsonArray("testimony").size() : 0;
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		if (isActive() && !addMode && event.button() == 0) {
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
					if (idx < testimonyCount()) {
						selSt = (selSt == idx) ? -1 : idx;
						return true;
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
		}

		super.render(graphics, mouseX, mouseY, partialTick);

		String caseName = isActive() && state.has("case") ? state.get("case").getAsString() : "";
		Component header = caseName.isBlank()
				? title
				: Component.translatable("gui.aceattorney.title_case", caseName);
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

		if (addMode) {
			graphics.drawString(font, Component.translatable("gui.aceattorney.name"), left + 50, top + 50, 0xFFDDDDDD);
			graphics.drawString(font, Component.translatable("gui.aceattorney.desc"), left + 50, top + 90, 0xFFDDDDDD);
			return;
		}

		graphics.drawString(font, Component.translatable("gui.aceattorney.evidence"), left + 8, top + LIST_TOP - 10, 0xFFFFE080);
		graphics.drawString(font, Component.translatable("gui.aceattorney.testimony"), left + 204, top + LIST_TOP - 10, 0xFF7CE8A8);

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
		for (int row = 0; row < ROWS; row++) {
			int idx = stOffset + row;
			if (idx >= testimony.size()) {
				break;
			}
			int y = top + LIST_TOP + row * ROW_H;
			if (idx == selSt) {
				graphics.fill(left + 202, y - 1, left + PANEL_W - 26, y + ROW_H - 1, 0x50FFFFFF);
			}
			String text = (idx + 1) + ". " + testimony.get(idx).getAsJsonObject().get("text").getAsString();
			graphics.drawString(font, font.plainSubstrByWidth(text, 158), left + 204, y, 0xFF7CE8A8);
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
			List<FormattedCharSequence> lines = new ArrayList<>(font.split(FormattedText.of(detail), PANEL_W - 16));
			int y = top + 126;
			for (int i = 0; i < Math.min(3, lines.size()); i++) {
				graphics.drawString(font, lines.get(i), left + 8, y, 0xFFDDDDDD);
				y += 10;
			}
		}
	}
}
