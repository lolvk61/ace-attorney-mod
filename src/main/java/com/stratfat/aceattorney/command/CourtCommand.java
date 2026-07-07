package com.stratfat.aceattorney.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import com.stratfat.aceattorney.ModSounds;
import com.stratfat.aceattorney.ShoutType;
import com.stratfat.aceattorney.court.CourtManager;
import com.stratfat.aceattorney.court.CourtRole;
import com.stratfat.aceattorney.court.CourtSession;
import com.stratfat.aceattorney.court.Evidence;
import com.stratfat.aceattorney.net.ModNetworking;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class CourtCommand {

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<CommandSourceStack> court = Commands.literal("court");

			court.then(Commands.literal("start").executes(CourtCommand::start));
			court.then(Commands.literal("end").executes(CourtCommand::end));
			court.then(Commands.literal("roles").executes(CourtCommand::listRoles));

			LiteralArgumentBuilder<CommandSourceStack> role = Commands.literal("role");
			for (CourtRole r : CourtRole.values()) {
				role.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.literal(r.id()).executes(ctx -> setRole(ctx, r))));
			}
			court.then(role);

			court.then(Commands.literal("evidence")
					.then(Commands.literal("add")
							.then(Commands.argument("name", StringArgumentType.string())
									.then(Commands.argument("description", StringArgumentType.greedyString())
											.executes(CourtCommand::addEvidence))))
					.then(Commands.literal("list").executes(CourtCommand::listEvidence))
					.then(Commands.literal("remove")
							.then(Commands.argument("index", IntegerArgumentType.integer(1))
									.executes(CourtCommand::removeEvidence))));

			court.then(Commands.literal("present")
					.then(Commands.argument("index", IntegerArgumentType.integer(1))
							.executes(CourtCommand::present)));

			court.then(Commands.literal("verdict")
					.then(Commands.literal("guilty").executes(ctx -> verdict(ctx, true)))
					.then(Commands.literal("notguilty").executes(ctx -> verdict(ctx, false))));

			dispatcher.register(court);
		});
	}

	private static int start(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		if (CourtManager.isActive()) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.already_active"));
			return 0;
		}
		CourtManager.start(player);
		CourtManager.broadcastTitle(ctx.getSource().getServer(),
				Component.translatable("court.aceattorney.session_start").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
				Component.translatable("court.aceattorney.session_start.sub", player.getDisplayName()),
				ModSounds.GAVEL, 1.0f);
		CourtManager.broadcast(ctx.getSource().getServer(),
				Component.translatable("court.aceattorney.hint_roles").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		return 1;
	}

	private static int end(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player != null && !session.isJudge(player.getUUID()) && !ctx.getSource().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.judge_only"));
			return 0;
		}
		CourtManager.end();
		CourtManager.broadcast(ctx.getSource().getServer(),
				Component.translatable("court.aceattorney.session_end").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int setRole(CommandContext<CommandSourceStack> ctx, CourtRole role) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		ServerPlayer executor = ctx.getSource().getPlayer();
		if (executor != null && !session.isJudge(executor.getUUID()) && !ctx.getSource().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.judge_only"));
			return 0;
		}
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		session.setRole(target.getUUID(), role);
		CourtManager.broadcast(ctx.getSource().getServer(),
				Component.translatable("court.aceattorney.role_assigned", target.getDisplayName(), role.displayName()));
		return 1;
	}

	private static int listRoles(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("court.aceattorney.roles_header").withStyle(ChatFormatting.GOLD), false);
		session.roles().forEach((uuid, role) -> {
			ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayer(uuid);
			Component name = p != null ? p.getDisplayName() : Component.literal("?");
			ctx.getSource().sendSuccess(() -> Component.literal(" - ").append(name).append(": ").append(role.displayName()), false);
		});
		return 1;
	}

	private static int addEvidence(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		if (!session.isParticipant(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.not_participant"));
			return 0;
		}
		String name = StringArgumentType.getString(ctx, "name");
		String description = StringArgumentType.getString(ctx, "description");
		ItemStack held = player.getMainHandItem().copy();
		session.evidence().add(new Evidence(name, description, held, player.getGameProfile().name()));
		CourtManager.broadcast(ctx.getSource().getServer(),
				Component.translatable("court.aceattorney.evidence_added",
						player.getDisplayName(),
						Component.literal(name).withStyle(ChatFormatting.YELLOW)));
		return 1;
	}

	private static int listEvidence(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		if (session.evidence().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("court.aceattorney.evidence_empty").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("court.aceattorney.evidence_header").withStyle(ChatFormatting.GOLD), false);
		int i = 1;
		for (Evidence e : session.evidence()) {
			final int index = i++;
			ctx.getSource().sendSuccess(() -> Component.literal(" " + index + ". ")
					.append(Component.literal(e.name())
							.withStyle(style -> style
									.withColor(ChatFormatting.YELLOW)
									.withHoverEvent(new HoverEvent.ShowText(Component.literal(e.description())))))
					.append(Component.literal(" (" + e.submitter() + ")").withStyle(ChatFormatting.GRAY)), false);
		}
		return 1;
	}

	private static int removeEvidence(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player != null && !session.isJudge(player.getUUID()) && !ctx.getSource().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.judge_only"));
			return 0;
		}
		int index = IntegerArgumentType.getInteger(ctx, "index");
		if (index > session.evidence().size()) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.no_such_evidence"));
			return 0;
		}
		Evidence removed = session.evidence().remove(index - 1);
		ctx.getSource().sendSuccess(() -> Component.translatable("court.aceattorney.evidence_removed", removed.name()), false);
		return 1;
	}

	private static int present(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		if (!session.isParticipant(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.not_participant"));
			return 0;
		}
		int index = IntegerArgumentType.getInteger(ctx, "index");
		if (index > session.evidence().size()) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.no_such_evidence"));
			return 0;
		}
		Evidence e = session.evidence().get(index - 1);
		ModNetworking.broadcastShout(player, ShoutType.TAKE_THAT);
		CourtManager.broadcast(ctx.getSource().getServer(),
				Component.translatable("court.aceattorney.evidence_presented",
						player.getDisplayName(),
						Component.literal(e.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
		CourtManager.broadcast(ctx.getSource().getServer(),
				Component.literal("  «" + e.description() + "»").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		return 1;
	}

	private static int verdict(CommandContext<CommandSourceStack> ctx, boolean guilty) {
		CourtSession session = requireSession(ctx);
		if (session == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player != null && !session.isJudge(player.getUUID()) && !ctx.getSource().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.judge_only"));
			return 0;
		}
		Component title = guilty
				? Component.translatable("court.aceattorney.verdict.guilty").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
				: Component.translatable("court.aceattorney.verdict.not_guilty").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
		CourtManager.broadcastTitle(ctx.getSource().getServer(), title,
				Component.translatable("court.aceattorney.verdict.sub"),
				ModSounds.GAVEL, guilty ? 0.8f : 1.2f);
		CourtManager.end();
		return 1;
	}

	private static CourtSession requireSession(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = CourtManager.session();
		if (session == null) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.no_session"));
		}
		return session;
	}
}
