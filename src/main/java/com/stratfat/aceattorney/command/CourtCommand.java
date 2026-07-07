package com.stratfat.aceattorney.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.stratfat.aceattorney.court.CaseLog;
import com.stratfat.aceattorney.court.CourtManager;
import com.stratfat.aceattorney.court.CourtRole;
import com.stratfat.aceattorney.court.CourtService;
import com.stratfat.aceattorney.court.CourtSession;
import com.stratfat.aceattorney.court.Evidence;
import com.stratfat.aceattorney.net.DialogueS2CPayload;
import com.stratfat.aceattorney.net.ModNetworking;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Chat command fallback. All real logic lives in {@link CourtService};
 * the primary interface is the courtroom blocks and the Court Record GUI.
 */
public class CourtCommand {

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<CommandSourceStack> court = Commands.literal("court");

			court.then(Commands.literal("start")
					.executes(asPlayer(CourtService::start))
					.then(Commands.argument("name", StringArgumentType.greedyString())
							.executes(ctx -> asPlayerRun(ctx, p -> CourtService.start(p,
									StringArgumentType.getString(ctx, "name"))))));
			court.then(Commands.literal("end").executes(asPlayer(CourtService::end)));
			court.then(Commands.literal("roles").executes(CourtCommand::listRoles));
			court.then(Commands.literal("log").executes(CourtCommand::listLog));

			LiteralArgumentBuilder<CommandSourceStack> role = Commands.literal("role");
			for (CourtRole r : CourtRole.values()) {
				role.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.literal(r.id()).executes(ctx -> {
							ServerPlayer executor = ctx.getSource().getPlayer();
							ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
							return executor != null && CourtService.setRole(executor, target, r) ? 1 : 0;
						})));
			}
			court.then(role);

			court.then(Commands.literal("evidence")
					.then(Commands.literal("add")
							.then(Commands.argument("name", StringArgumentType.string())
									.then(Commands.argument("description", StringArgumentType.greedyString())
											.executes(ctx -> asPlayerRun(ctx, p -> CourtService.addEvidence(p,
													StringArgumentType.getString(ctx, "name"),
													StringArgumentType.getString(ctx, "description")))))))
					.then(Commands.literal("list").executes(CourtCommand::listEvidence))
					.then(Commands.literal("remove")
							.then(Commands.argument("index", IntegerArgumentType.integer(1))
									.executes(ctx -> asPlayerRun(ctx, p -> CourtService.removeEvidence(p,
											IntegerArgumentType.getInteger(ctx, "index")))))));

			court.then(Commands.literal("present")
					.then(Commands.argument("index", IntegerArgumentType.integer(1))
							.executes(ctx -> asPlayerRun(ctx, p -> CourtService.present(p,
									IntegerArgumentType.getInteger(ctx, "index"))))));

			court.then(Commands.literal("verdict")
					.then(Commands.literal("guilty").executes(ctx -> asPlayerRun(ctx, p -> CourtService.verdict(p, true))))
					.then(Commands.literal("notguilty").executes(ctx -> asPlayerRun(ctx, p -> CourtService.verdict(p, false)))));

			court.then(Commands.literal("testimony")
					.then(Commands.literal("add")
							.then(Commands.argument("text", StringArgumentType.greedyString())
									.executes(ctx -> asPlayerRun(ctx, p -> CourtService.addStatement(p,
											StringArgumentType.getString(ctx, "text"))))))
					.then(Commands.literal("edit")
							.then(Commands.argument("statement", IntegerArgumentType.integer(1))
									.then(Commands.argument("text", StringArgumentType.greedyString())
											.executes(ctx -> asPlayerRun(ctx, p -> CourtService.editStatement(p,
													IntegerArgumentType.getInteger(ctx, "statement"),
													StringArgumentType.getString(ctx, "text")))))))
					.then(Commands.literal("list").executes(CourtCommand::listTestimony))
					.then(Commands.literal("play").executes(asPlayer(CourtService::playTestimony)))
					.then(Commands.literal("clear").executes(asPlayer(CourtService::clearTestimony))));

			court.then(Commands.literal("press")
					.then(Commands.argument("statement", IntegerArgumentType.integer(1))
							.executes(ctx -> asPlayerRun(ctx, p -> CourtService.press(p,
									IntegerArgumentType.getInteger(ctx, "statement"))))));

			court.then(Commands.literal("object")
					.then(Commands.argument("statement", IntegerArgumentType.integer(1))
							.executes(ctx -> asPlayerRun(ctx, p -> CourtService.object(p,
									IntegerArgumentType.getInteger(ctx, "statement"), 0)))
							.then(Commands.argument("evidence", IntegerArgumentType.integer(1))
									.executes(ctx -> asPlayerRun(ctx, p -> CourtService.object(p,
											IntegerArgumentType.getInteger(ctx, "statement"),
											IntegerArgumentType.getInteger(ctx, "evidence")))))));

			dispatcher.register(court);

			// /aa say <текст> — диалоговое окно в стиле AA для игроков поблизости
			dispatcher.register(Commands.literal("aa")
					.then(Commands.literal("say")
							.then(Commands.argument("text", StringArgumentType.greedyString())
									.executes(CourtCommand::aaSay))));
		});
	}

	private interface PlayerAction {
		boolean run(ServerPlayer player);
	}

	private static com.mojang.brigadier.Command<CommandSourceStack> asPlayer(PlayerAction action) {
		return ctx -> {
			ServerPlayer player = ctx.getSource().getPlayer();
			return player != null && action.run(player) ? 1 : 0;
		};
	}

	private static int asPlayerRun(CommandContext<CommandSourceStack> ctx, PlayerAction action) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayer();
		return player != null && action.run(player) ? 1 : 0;
	}

	private static int aaSay(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		String text = StringArgumentType.getString(ctx, "text");
		ModNetworking.broadcastDialogue(player,
				new DialogueS2CPayload(player.getGameProfile().name(), text, 0), 32);
		return 1;
	}

	private static int listLog(CommandContext<CommandSourceStack> ctx) {
		var records = CaseLog.records();
		if (records.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("gui.aceattorney.log_empty").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("gui.aceattorney.log_title").withStyle(ChatFormatting.GOLD), false);
		for (int i = Math.max(0, records.size() - 10); i < records.size(); i++) {
			CaseLog.CaseRecord r = records.get(i);
			Component verdict = switch (r.verdict()) {
				case "guilty" -> Component.translatable("court.aceattorney.verdict.guilty").withStyle(ChatFormatting.RED);
				case "not_guilty" -> Component.translatable("court.aceattorney.verdict.not_guilty").withStyle(ChatFormatting.GREEN);
				default -> Component.translatable("court.aceattorney.verdict.dismissed").withStyle(ChatFormatting.GRAY);
			};
			Component line = Component.literal(" №" + r.number()
							+ (r.name().isBlank() ? "" : " «" + r.name() + "»") + " — ")
					.append(verdict)
					.append(Component.literal(" • " + r.judge() + " • " + r.date()).withStyle(ChatFormatting.GRAY));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return 1;
	}

	private static int listRoles(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = CourtManager.session();
		if (session == null) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.no_session"));
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

	private static int listEvidence(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = CourtManager.session();
		if (session == null) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.no_session"));
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

	private static int listTestimony(CommandContext<CommandSourceStack> ctx) {
		CourtSession session = CourtManager.session();
		if (session == null) {
			ctx.getSource().sendFailure(Component.translatable("court.aceattorney.no_session"));
			return 0;
		}
		if (session.testimony().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("court.aceattorney.testimony_empty").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("court.aceattorney.testimony_header").withStyle(ChatFormatting.GREEN), false);
		int i = 1;
		for (CourtSession.Statement s : session.testimony()) {
			final int index = i++;
			ctx.getSource().sendSuccess(() -> Component.literal(" " + index + ". " + s.text())
					.append(Component.literal(" (" + s.speaker() + ")").withStyle(ChatFormatting.GRAY)), false);
		}
		return 1;
	}
}
