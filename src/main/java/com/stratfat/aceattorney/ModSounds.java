package com.stratfat.aceattorney;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public class ModSounds {
	public static SoundEvent SHOUT_OBJECTION;
	public static SoundEvent SHOUT_HOLD_IT;
	public static SoundEvent SHOUT_TAKE_THAT;
	public static SoundEvent GAVEL;
	public static SoundEvent MAGATAMA;

	public static void init() {
		SHOUT_OBJECTION = register("shout.objection");
		SHOUT_HOLD_IT = register("shout.hold_it");
		SHOUT_TAKE_THAT = register("shout.take_that");
		GAVEL = register("gavel");
		MAGATAMA = register("magatama");
	}

	private static SoundEvent register(String name) {
		Identifier id = AceAttorney.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}
}
