package com.stratfat.aceattorney.court;

import net.minecraft.world.item.ItemStack;

public record Evidence(String name, String description, ItemStack stack, String submitter) {
}
