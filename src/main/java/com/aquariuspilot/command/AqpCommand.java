package com.aquariuspilot.command;

import com.aquariuspilot.AquariusPilotPlugin;
import com.aquariuspilot.module.ElytraPilotModule;
import com.aquariuspilot.module.FlightGear;
import com.aquariuspilot.module.RegearModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

/**
 * The plugin's single command entry point, prefix {@code .aqp} (e.g. {@code .aqp fly to 100 -200},
 * {@code .aqp regear on}). Kept as one Command class registering one Brigadier tree, matching how the
 * {@code ZenithProxyExamplePlugin}/{@code aquariusproxy-plugin-template} examples structure a single verb —
 * here the verb is {@code aqp} with {@code fly}/{@code regear}/{@code status} sub-trees.
 */
public class AqpCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("aqp")
            .category(CommandCategory.MODULE)
            .description("""
                Aquarius Pilot - elytra long-haul autopilot (e-bounce + cruise) and gear-up.
                Requires client.viaversion protocol 765 (1.20.3-1.20.4) for e-bounce - see README.
                """)
            .usageLines(
                "fly on/off",
                "fly to <x> <z>",
                "fly bounce on/off",
                "regear on/off",
                "status"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("aqp")
            .then(literal("fly")
                .then(argument("toggle", toggle()).executes(c -> {
                    AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.enabled = getToggle(c, "toggle");
                    MODULE.get(ElytraPilotModule.class).syncEnabledFromConfig();
                    c.getSource().getEmbed().title("ElytraPilot " + toggleStrCaps(AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.enabled));
                }))
                .then(literal("to").then(argument("x", doubleArg()).then(argument("z", doubleArg()).executes(c -> {
                    var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot;
                    cfg.hasTarget = true;
                    cfg.targetX = getDouble(c, "x");
                    cfg.targetZ = getDouble(c, "z");
                    cfg.enabled = true;
                    MODULE.get(ElytraPilotModule.class).syncEnabledFromConfig();
                    c.getSource().getEmbed().title("Flying to " + cfg.targetX + ", " + cfg.targetZ);
                }))))
                .then(literal("bounce").then(argument("toggle", toggle()).executes(c -> {
                    AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.bounceEnabled = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("E-bounce " + toggleStrCaps(AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.bounceEnabled));
                }))))
            .then(literal("regear").then(argument("toggle", toggle()).executes(c -> {
                AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled = getToggle(c, "toggle");
                MODULE.get(RegearModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed().title("Regear " + toggleStrCaps(AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled));
            })))
            .then(literal("status").executes(c -> {
                c.getSource().getEmbed()
                    .primaryColor()
                    .title("Aquarius Pilot status")
                    .addField("ElytraPilot", toggleStr(MODULE.get(ElytraPilotModule.class).isEnabled())
                        + " (phase " + MODULE.get(ElytraPilotModule.class).getPhase() + ")")
                    .addField("Regear", toggleStr(MODULE.get(RegearModule.class).isEnabled())
                        + " (" + MODULE.get(RegearModule.class).statusLine() + ")")
                    .addField("ViaVersion protocol", String.valueOf(CONFIG.client.viaversion.protocolVersion)
                        + (CONFIG.client.viaversion.protocolVersion == 765 ? " (OK for e-bounce)" : " (e-bounce needs 765!)"))
                    .addField("Pre-flight", "```\n" + FlightGear.report() + "\n```");
            }));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .primaryColor()
            .addField("ElytraPilot", toggleStr(AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.enabled))
            .addField("Regear", toggleStr(AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled));
    }
}
