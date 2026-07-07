package com.stratfat.aceattorney.net;

import com.stratfat.aceattorney.AceAttorney;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Ace Attorney style dialogue box. statementNumber > 0 marks a testimony
 * statement (rendered in green with its number), 0 is normal speech.
 */
public record DialogueS2CPayload(String speaker, String text, int statementNumber) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DialogueS2CPayload> TYPE =
			new CustomPacketPayload.Type<>(AceAttorney.id("dialogue"));

	public static final StreamCodec<ByteBuf, DialogueS2CPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, DialogueS2CPayload::speaker,
			ByteBufCodecs.STRING_UTF8, DialogueS2CPayload::text,
			ByteBufCodecs.VAR_INT, DialogueS2CPayload::statementNumber,
			DialogueS2CPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
