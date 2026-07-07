package com.stratfat.aceattorney.net;

import com.stratfat.aceattorney.AceAttorney;
import com.stratfat.aceattorney.ShoutType;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ShoutS2CPayload(ShoutType shout, String speaker) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShoutS2CPayload> TYPE =
			new CustomPacketPayload.Type<>(AceAttorney.id("shout_display"));

	public static final StreamCodec<ByteBuf, ShoutS2CPayload> CODEC = StreamCodec.composite(
			ShoutType.STREAM_CODEC, ShoutS2CPayload::shout,
			ByteBufCodecs.STRING_UTF8, ShoutS2CPayload::speaker,
			ShoutS2CPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
