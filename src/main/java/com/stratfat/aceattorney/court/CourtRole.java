package com.stratfat.aceattorney.court;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public enum CourtRole {
	JUDGE("judge", ChatFormatting.GOLD),
	DEFENSE("defense", ChatFormatting.BLUE),
	PROSECUTION("prosecution", ChatFormatting.RED),
	WITNESS("witness", ChatFormatting.GREEN),
	DEFENDANT("defendant", ChatFormatting.GRAY);

	private final String id;
	private final ChatFormatting color;

	CourtRole(String id, ChatFormatting color) {
		this.id = id;
		this.color = color;
	}

	public String id() {
		return id;
	}

	public Component displayName() {
		return Component.translatable("role.aceattorney." + id).withStyle(color);
	}
}
