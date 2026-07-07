package com.stratfat.aceattorney.net;

import com.stratfat.aceattorney.AceAttorney;
import com.stratfat.aceattorney.ShoutType;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ShoutC2SPayload(ShoutType shout) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShoutC2SPayload> TYPE =
			new CustomPacketPayload.Type<>(AceAttorney.id("shout"));

	public static final StreamCodec<ByteBuf, ShoutC2SPayload> CODEC =
			StreamCodec.composite(ShoutType.STREAM_CODEC, ShoutC2SPayload::shout, ShoutC2SPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
