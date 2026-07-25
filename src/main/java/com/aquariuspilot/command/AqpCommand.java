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
 *
 * <p>Command output goes to the terminal, in-game chat <i>and</i> Discord, so nothing here ever echoes a
 * world coordinate back — targets are acknowledged by distance, not by position.
 *
 * <p>{@link ElytraPilotModule} is a one-shot: its {@code enabledSetting()} is always {@code false} so a
 * proxy restart can never resume an old flight, which means the toggles below drive it with
 * {@code setEnabled} directly rather than through {@code syncEnabledFromConfig}.
 */
public class AqpCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("aqp")
            .category(CommandCategory.MODULE)
            .description("""
                Aquarius Pilot - elytra long-haul autopilot (e-bounce + cruise) and gear-up.
                Requires an ACTIVE ViaVersion downgrade to 1.20.3-1.20.4 (protocol 765) for e-bounce - see README.
                """)
            .usageLines(
                "fly on/off",
                "fly to <x> <z>",
                "fly bounce on/off",
                "fly nether on/off",
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
                    var pilot = MODULE.get(ElytraPilotModule.class);
                    pilot.setEnabled(getToggle(c, "toggle"));
                    // report the module's REAL state: enabling can refuse itself (no target, AutoArmor on,
                    // ViaVersion downgrade not active, ...) and the operator needs to see that
                    c.getSource().getEmbed().title("ElytraPilot " + toggleStrCaps(pilot.isEnabled()));
                }))
                .then(literal("to").then(argument("x", doubleArg()).then(argument("z", doubleArg()).executes(c -> {
                    var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot;
                    cfg.hasTarget = true;
                    cfg.targetX = getDouble(c, "x");
                    cfg.targetZ = getDouble(c, "z");
                    var pilot = MODULE.get(ElytraPilotModule.class);
                    // restart cleanly so a new target re-runs the pre-flight check instead of being ignored
                    // by an already-enabled module (Module#enable() is a no-op when already enabled)
                    pilot.setEnabled(false);
                    pilot.setEnabled(true);
                    double dist = pilot.distanceToTarget();
                    c.getSource().getEmbed()
                        .title("ElytraPilot " + toggleStrCaps(pilot.isEnabled()))
                        .addField("Target", dist < 0
                            ? "set (" + (cfg.targetIsNether ? "nether" : "overworld") + ")"
                            : Math.round(dist) + " blocks out, "
                                + (cfg.targetIsNether ? "nether" : "overworld"));
                }))))
                .then(literal("bounce").then(argument("toggle", toggle()).executes(c -> {
                    AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.bounceEnabled = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("E-bounce " + toggleStrCaps(AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.bounceEnabled));
                })))
                .then(literal("nether").then(argument("toggle", toggle()).executes(c -> {
                    // which dimension the target coordinates are in - checked at pre-flight, not ignored
                    AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.targetIsNether = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Flight target dimension: "
                        + (AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.targetIsNether ? "nether" : "overworld"));
                }))))
            .then(literal("regear").then(argument("toggle", toggle()).executes(c -> {
                AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled = getToggle(c, "toggle");
                MODULE.get(RegearModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed().title("Regear " + toggleStrCaps(AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled));
            })))
            .then(literal("status").executes(c -> {
                var pilot = MODULE.get(ElytraPilotModule.class);
                var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot;
                double dist = pilot.distanceToTarget();
                c.getSource().getEmbed()
                    .primaryColor()
                    .title("Aquarius Pilot status")
                    .addField("ElytraPilot", toggleStr(pilot.isEnabled()) + " (phase " + pilot.getPhase() + ")")
                    .addField("Target", !cfg.hasTarget
                        ? "none"
                        : Math.round(dist) + " blocks out, " + (cfg.targetIsNether ? "nether" : "overworld"))
                    .addField("Regear", toggleStr(MODULE.get(RegearModule.class).isEnabled())
                        + " (" + MODULE.get(RegearModule.class).statusLine() + ")")
                    .addField("ViaVersion 765 downgrade", ElytraPilotModule.viaGateOk()
                        ? "active (OK for e-bounce)"
                        : "NOT active - e-bounce needs it"
                            + " [enabled=" + CONFIG.client.viaversion.enabled
                            + ", disableOn2b2t=" + CONFIG.client.viaversion.disableOn2b2t
                            + ", auto=" + CONFIG.client.viaversion.autoProtocolVersion
                            + ", version=" + CONFIG.client.viaversion.protocolVersion + "]")
                    .addField("Pre-flight", "```\n" + FlightGear.report() + "\n```");
            }));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .primaryColor()
            .addField("ElytraPilot", toggleStr(MODULE.get(ElytraPilotModule.class).isEnabled()))
            .addField("Regear", toggleStr(MODULE.get(RegearModule.class).isEnabled()));
    }
}
