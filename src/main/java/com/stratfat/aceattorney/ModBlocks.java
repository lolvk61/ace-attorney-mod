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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Courtroom furniture. Right-click to interact: the judge's bench starts a
 * session (or bangs the gavel), the other seats claim the matching role.
 * Sneak-click passes through so blocks can still be placed against them.
 */
public class ModBlocks {
	// front panel towards -Z (north); matches the block models
	private static final VoxelShape SHAPE_BENCH = Shapes.or(
			Block.box(0, 10, 0, 16, 13, 16),
			Block.box(0, 0, 0, 16, 10, 3),
			Block.box(1, 0, 13, 3, 10, 15),
			Block.box(13, 0, 13, 15, 10, 15));
	private static final VoxelShape SHAPE_JUDGE = Shapes.or(
			Block.box(0, 0, 0, 16, 14, 4),
			Block.box(0, 14, 0, 16, 16, 9),
			Block.box(1, 0, 12, 3, 14, 14),
			Block.box(13, 0, 12, 15, 14, 14));
	private static final VoxelShape SHAPE_STAND = Shapes.or(
			Block.box(2, 0, 2, 14, 12, 14),
			Block.box(0, 12, 0, 16, 15, 16));

	public static Block JUDGE_BENCH;
	public static Block WITNESS_STAND;
	public static Block DEFENSE_BENCH;
	public static Block PROSECUTION_BENCH;
	public static Block DEFENDANT_BENCH;
	public static Block CLERK_BENCH;

	public static void init() {
		JUDGE_BENCH = register("judge_bench", SHAPE_JUDGE);
		WITNESS_STAND = register("witness_stand", SHAPE_STAND);
		DEFENSE_BENCH = register("defense_bench", SHAPE_BENCH);
		PROSECUTION_BENCH = register("prosecution_bench", SHAPE_BENCH);
		DEFENDANT_BENCH = register("defendant_bench", SHAPE_BENCH);
		CLERK_BENCH = register("clerk_bench", SHAPE_BENCH);

		UseBlockCallback.EVENT.register(ModBlocks::onUseBlock);
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		BlockState state = level.getBlockState(hit.getBlockPos());
		boolean ours = state.is(JUDGE_BENCH) || state.is(WITNESS_STAND) || state.is(DEFENSE_BENCH)
				|| state.is(PROSECUTION_BENCH) || state.is(DEFENDANT_BENCH) || state.is(CLERK_BENCH);
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
		} else if (state.is(DEFENDANT_BENCH)) {
			CourtService.claimRole(serverPlayer, CourtRole.DEFENDANT);
		} else if (state.is(CLERK_BENCH)) {
			CourtService.claimRole(serverPlayer, CourtRole.CLERK);
		} else {
			CourtService.claimRole(serverPlayer, CourtRole.PROSECUTION);
		}
		return InteractionResult.SUCCESS;
	}

	private static Block register(String name, VoxelShape northShape) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, AceAttorney.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
				new CourtFurnitureBlock(BlockBehaviour.Properties.of()
						.strength(2.0f).sound(SoundType.WOOD).noOcclusion().setId(blockKey), northShape));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, AceAttorney.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}
}
