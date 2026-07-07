package com.stratfat.aceattorney;

import com.stratfat.aceattorney.court.CourtRole;
import com.stratfat.aceattorney.court.CourtService;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Courtroom furniture. Right-click to interact: the judge's bench starts a
 * session (or bangs the gavel), the other seats claim the matching role.
 * Sneak-click passes through so blocks can still be placed against them.
 */
public class ModBlocks {
	public static Block JUDGE_BENCH;
	public static Block WITNESS_STAND;
	public static Block DEFENSE_BENCH;
	public static Block PROSECUTION_BENCH;

	public static void init() {
		JUDGE_BENCH = register("judge_bench");
		WITNESS_STAND = register("witness_stand");
		DEFENSE_BENCH = register("defense_bench");
		PROSECUTION_BENCH = register("prosecution_bench");

		UseBlockCallback.EVENT.register(ModBlocks::onUseBlock);
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		BlockState state = level.getBlockState(hit.getBlockPos());
		boolean ours = state.is(JUDGE_BENCH) || state.is(WITNESS_STAND)
				|| state.is(DEFENSE_BENCH) || state.is(PROSECUTION_BENCH);
		if (!ours) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (state.is(JUDGE_BENCH)) {
			CourtService.judgeBenchUsed(serverPlayer);
		} else if (state.is(WITNESS_STAND)) {
			CourtService.claimRole(serverPlayer, CourtRole.WITNESS);
		} else if (state.is(DEFENSE_BENCH)) {
			CourtService.claimRole(serverPlayer, CourtRole.DEFENSE);
		} else {
			CourtService.claimRole(serverPlayer, CourtRole.PROSECUTION);
		}
		return InteractionResult.SUCCESS;
	}

	private static Block register(String name) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, AceAttorney.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
				new Block(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.WOOD).setId(blockKey)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, AceAttorney.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}
}
