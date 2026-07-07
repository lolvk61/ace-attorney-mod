package com.stratfat.aceattorney.net;

import com.stratfat.aceattorney.AceAttorney;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client snapshot of the court session, JSON encoded. */
public record CourtStateS2CPayload(String json) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CourtStateS2CPayload> TYPE =
			new CustomPacketPayload.Type<>(AceAttorney.id("court_state"));

	public static final StreamCodec<ByteBuf, CourtStateS2CPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8, CourtStateS2CPayload::json, CourtStateS2CPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
