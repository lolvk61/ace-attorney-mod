package com.stratfat.aceattorney;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public enum ShoutType {
	OBJECTION,
	HOLD_IT,
	TAKE_THAT;

	public static final StreamCodec<ByteBuf, ShoutType> STREAM_CODEC =
			ByteBufCodecs.BYTE.map(b -> ShoutType.values()[b], t -> (byte) t.ordinal());
}
