package com.stratfat.aceattorney;

import com.stratfat.aceattorney.command.CourtCommand;
import com.stratfat.aceattorney.court.CourtManager;
import com.stratfat.aceattorney.net.ModNetworking;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AceAttorney implements ModInitializer {
	public static final String MOD_ID = "aceattorney";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModSounds.init();
		ModBlocks.init();
		ModItems.init();
		ModNetworking.init();
		CourtCommand.init();
		CourtManager.init();
		LOGGER.info("Ace Attorney loaded. The court is now in session.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
