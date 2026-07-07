package com.stratfat.aceattorney;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Courtroom furniture with a custom shape, rotated to face the player on
 * placement. The shape passed to the constructor is for FACING = NORTH
 * (front panel towards -Z); the other three are computed by rotation.
 */
public class CourtFurnitureBlock extends Block {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	private final VoxelShape[] shapes = new VoxelShape[4];

	public CourtFurnitureBlock(Properties properties, VoxelShape northShape) {
		super(properties);
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
		for (Direction dir : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
			shapes[dir.get2DDataValue()] = rotate(northShape, dir);
		}
	}

	private static VoxelShape rotate(VoxelShape shape, Direction dir) {
		if (dir == Direction.NORTH) {
			return shape;
		}
		VoxelShape result = Shapes.empty();
		for (AABB box : shape.toAabbs()) {
			VoxelShape rotated = switch (dir) {
				case SOUTH -> Shapes.create(1 - box.maxX, box.minY, 1 - box.maxZ, 1 - box.minX, box.maxY, 1 - box.minZ);
				case WEST -> Shapes.create(box.minZ, box.minY, 1 - box.maxX, box.maxZ, box.maxY, 1 - box.minX);
				case EAST -> Shapes.create(1 - box.maxZ, box.minY, box.minX, 1 - box.minZ, box.maxY, box.maxX);
				default -> Shapes.create(box);
			};
			result = Shapes.or(result, rotated);
		}
		return result.optimize();
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapes[state.getValue(FACING).get2DDataValue()];
	}
}
