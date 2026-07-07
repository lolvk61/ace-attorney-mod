package com.stratfat.aceattorney.net;

import com.stratfat.aceattorney.AceAttorney;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: a case protocol to be saved as a text file. JSON encoded. */
public record ProtocolExportS2CPayload(String json) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ProtocolExportS2CPayload> TYPE =
			new CustomPacketPayload.Type<>(AceAttorney.id("protocol_export"));

	public static final StreamCodec<ByteBuf, ProtocolExportS2CPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ProtocolExportS2CPayload::json, ProtocolExportS2CPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
