package com.stratfat.aceattorney.net;

import com.stratfat.aceattorney.AceAttorney;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** GUI → server court action, JSON encoded (see CourtService.handleAction). */
public record CourtActionC2SPayload(String json) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CourtActionC2SPayload> TYPE =
			new CustomPacketPayload.Type<>(AceAttorney.id("court_action"));

	public static final StreamCodec<ByteBuf, CourtActionC2SPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8, CourtActionC2SPayload::json, CourtActionC2SPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
