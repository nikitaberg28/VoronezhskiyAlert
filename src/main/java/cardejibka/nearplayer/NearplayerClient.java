package cardejibka.nearplayer;

// ===== PORTING NOTE (Minecraft 26.x) =====================================
// Confirmed from decompiled 26.2 sources (Minecraft.java, Player.java,
// LocalPlayer.java, AbstractButton.java, Font.java, MutableComponent.java).
// ===========================================================================
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
// CONFIRMED (finally!): the missing ".hud" in the package below was the
// actual bug all along. Two separate archive listings (fabric-rendering-v1
// sources jar and the plain jar for 25.3.2+515ac5339e) both showed
// HudElementRegistry.class living in .../rendering/v1/hud/, the exact same
// subpackage as VanillaHudElements right below it - which already had the
// correct ".hud" in its import and was never the problem. This was a plain
// missing-segment typo, not a version/dependency-resolution issue; all the
// version/modImplementation/refresh-dependencies troubleshooting was chasing
// a red herring for this specific error.
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class NearplayerClient implements ClientModInitializer {
    // Key binding categories became a structured record type (KeyMapping.Category)
    // instead of a plain translation-key String starting with 1.21.9, and the
    // factory method is now register(...) instead of create(...) as of 26.1
    // (per the official Key Mappings guide example).
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("nearplayer", "main"));

    private static KeyMapping openSettingsKey;
    private static KeyMapping placeFlagKey;
    private static NearPlayerHud hudInstance;

    @Override
    public void onInitializeClient() {
        hudInstance = new NearPlayerHud();
        // HudRenderCallback was removed; HUD layers are now registered through
        // HudElementRegistry (see Fabric's "Rendering in the HUD" 26.x guide).
        // attachElementBefore/After(anchor, id, element) - CHAT is a reasonable
        // default anchor for a full-screen overlay; TODO-VERIFY this is still
        // the best anchor point for this mod's use case (it draws over the
        // whole screen, not just a HUD corner element).
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("nearplayer", "hud"), hudInstance::render);

        // 26.3: GLFW is gone (LWJGL now ships SDL). InputConstants.Type.KEYSYM was
        // renamed to KEYBOARD and key codes are SDL scancodes, exposed as
        // InputConstants.KEY_*. org.lwjgl.glfw.GLFW is no longer on the classpath.
        openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.nearplayer.opensettings", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY));
        placeFlagKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.nearplayer.place_flag", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            hudInstance.tick();
            // Confirmed from decompiled Minecraft.java: screen access goes through
            // the "gui" field (a Gui instance) - gui.screen() / gui.setScreen(...) -
            // not a direct currentScreen field / setScreen method on Minecraft itself.
            while (openSettingsKey.consumeClick()) {
                if (client.gui.screen() == null) client.gui.setScreen(new NearPlayerSettingsScreen(null, hudInstance));
            }
            while (placeFlagKey.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) hudInstance.placeFlag();
            }
        });
    }

    public static NearPlayerHud getHud() { return hudInstance; }
}
