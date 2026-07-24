package com.aquariuspilot;

import com.aquariuspilot.command.AqpCommand;
import com.aquariuspilot.config.AquariusPilotConfig;
import com.aquariuspilot.module.ElytraPilotModule;
import com.aquariuspilot.module.RegearModule;
import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * Aquarius Pilot — elytra long-haul autopilot (e-bounce + firework cruise) and gear-up ("Regear"), ported
 * from the AquariusProxy fork ({@code aquariusnetwork9/AquariusProxy}, package {@code com.aquarius.*}) into
 * a standalone plugin for stock ZenithProxy. Command prefix: {@code .aqp}.
 *
 * <p>See the repo README for the ViaVersion protocol requirement e-bounce depends on, and ROADMAP.md /
 * "Known limitations" for exactly what was ported vs. deferred from the ~8,000-line source.
 */
@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Elytra long-haul autopilot (e-bounce + cruise) and gear-up, ported from AquariusProxy",
    url = "https://github.com/aquariusnetwork9/aquarius-pilot",
    authors = {"aquariusnetwork9"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class AquariusPilotPlugin implements ZenithProxyPlugin {
    public static AquariusPilotConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("Aquarius Pilot loading...");
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, AquariusPilotConfig.class);
        pluginAPI.registerModule(new RegearModule());
        pluginAPI.registerModule(new ElytraPilotModule());
        pluginAPI.registerCommand(new AqpCommand());
        LOG.info("Aquarius Pilot loaded! Command prefix: .aqp   (see README for the required ViaVersion setup)");
    }
}
