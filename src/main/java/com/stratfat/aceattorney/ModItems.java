package com.stratfat.aceattorney;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ModItems {
	public static Item ATTORNEY_BADGE;
	public static Item MAGATAMA;
	public static Item GAVEL;

	public static void init() {
		ATTORNEY_BADGE = register("attorney_badge", new Item.Properties().stacksTo(1));
		MAGATAMA = register("magatama", new Item.Properties().stacksTo(1));
		GAVEL = register("gavel", new Item.Properties().stacksTo(1));

		ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, AceAttorney.id("main"));
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tabKey, FabricItemGroup.builder()
				.title(Component.translatable("itemGroup.aceattorney.main"))
				.icon(() -> new ItemStack(ATTORNEY_BADGE))
				.displayItems((params, output) -> {
					output.accept(ATTORNEY_BADGE);
					output.accept(MAGATAMA);
					output.accept(GAVEL);
					output.accept(ModBlocks.JUDGE_BENCH);
					output.accept(ModBlocks.WITNESS_STAND);
					output.accept(ModBlocks.DEFENSE_BENCH);
					output.accept(ModBlocks.PROSECUTION_BENCH);
				})
				.build());

		UseItemCallback.EVENT.register(ModItems::onUseItem);
	}

	private static InteractionResult onUseItem(Player player, Level level, net.minecraft.world.InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (stack.is(ATTORNEY_BADGE)) {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				broadcastNearby(serverPlayer, 32,
						Component.translatable("chat.aceattorney.badge_shown", serverPlayer.getDisplayName()));
				level.playSound(null, player.getX(), player.getY(), player.getZ(),
						net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.4f);
				player.getCooldowns().addCooldown(stack, 40);
			}
			return InteractionResult.SUCCESS;
		}

		if (stack.is(MAGATAMA)) {
			if (!level.isClientSide()) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(),
						ModSounds.MAGATAMA, SoundSource.PLAYERS, 1.0f, 0.8f);
				player.displayClientMessage(Component.translatable("chat.aceattorney.magatama"), true);
				player.getCooldowns().addCooldown(stack, 60);
			}
			return InteractionResult.SUCCESS;
		}

		if (stack.is(GAVEL)) {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(),
						ModSounds.GAVEL, SoundSource.PLAYERS, 1.0f, 1.0f);
				broadcastNearby(serverPlayer, 48,
						Component.translatable("chat.aceattorney.order", serverPlayer.getDisplayName()));
				player.getCooldowns().addCooldown(stack, 20);
			}
			return InteractionResult.SUCCESS;
		}

		return InteractionResult.PASS;
	}

	private static void broadcastNearby(ServerPlayer source, double radius, Component message) {
		for (ServerPlayer other : source.level().getServer().getPlayerList().getPlayers()) {
			if (other.level() == source.level() && other.distanceTo(source) <= radius) {
				other.sendSystemMessage(message);
			}
		}
	}

	private static Item register(String name, Item.Properties props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, AceAttorney.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(props.setId(key)));
	}
}
