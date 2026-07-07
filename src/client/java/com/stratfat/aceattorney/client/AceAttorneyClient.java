package com.stratfat.aceattorney.client;

import com.stratfat.aceattorney.AceAttorney;
import com.stratfat.aceattorney.ShoutType;
import com.stratfat.aceattorney.net.ShoutC2SPayload;
import com.stratfat.aceattorney.net.ShoutS2CPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;

import org.lwjgl.glfw.GLFW;

public class AceAttorneyClient implements ClientModInitializer {
	private static KeyMapping objectionKey;
	private static KeyMapping holdItKey;
	private static KeyMapping takeThatKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(AceAttorney.id("main"));
		objectionKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.aceattorney.objection", GLFW.GLFW_KEY_O, category));
		holdItKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.aceattorney.hold_it", GLFW.GLFW_KEY_H, category));
		takeThatKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.aceattorney.take_that", GLFW.GLFW_KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (objectionKey.consumeClick()) {
				sendShout(ShoutType.OBJECTION);
			}
			while (holdItKey.consumeClick()) {
				sendShout(ShoutType.HOLD_IT);
			}
			while (takeThatKey.consumeClick()) {
				sendShout(ShoutType.TAKE_THAT);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(ShoutS2CPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> ShoutOverlay.show(payload.shout(), payload.speaker()));
		});

		HudElementRegistry.addLast(AceAttorney.id("shout_overlay"), ShoutOverlay::render);
	}

	private static void sendShout(ShoutType type) {
		if (ClientPlayNetworking.canSend(ShoutC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new ShoutC2SPayload(type));
		}
	}
}
