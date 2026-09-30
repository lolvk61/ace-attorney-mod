package com.stratfat.aceattorney.net;

import com.stratfat.aceattorney.AceAttorney;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Court event carried through the AceAttorneyRelay server plugin. The same
 * channel is used in both directions: clients send it with an empty sender
 * (the plugin fills in the real one), the plugin forwards it to every client
 * that registered the channel, i.e. every player who has the mod.
 */
public record RelayPayload(String sender, String uuid, String data) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RelayPayload> TYPE =
			new CustomPacketPayload.Type<>(AceAttorney.id("relay"));

	public static final StreamCodec<ByteBuf, RelayPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, RelayPayload::sender,
			ByteBufCodecs.STRING_UTF8, RelayPayload::uuid,
			ByteBufCodecs.STRING_UTF8, RelayPayload::data,
			RelayPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
