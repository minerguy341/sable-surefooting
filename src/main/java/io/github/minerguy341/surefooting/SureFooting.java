package io.github.minerguy341.surefooting;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SureFooting.MOD_ID)
public final class SureFooting {

    public static final String MOD_ID = "surefooting";
    public static final Logger LOGGER = LoggerFactory.getLogger("Sable: Sure Footing");

    public SureFooting(final ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SureFootingConfig.SPEC);

        // The fix is entirely client-side: players are client-authoritative for movement, and the
        // server adopts the client's tracking state from movement packets. On a dedicated server
        // this mod is a no-op so it can safely sit in a shared modlist.
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.register(new JumpCarryHandler());

            // Enables the "Config" button on the Mods screen, using NeoForge's built-in
            // auto-generated configuration UI for our ModConfigSpec.
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }
    }
}
