package cardejibka.nearplayer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class NearplayerClient implements ClientModInitializer {
    // Key binding categories became a structured record type (KeyBinding.Category)
    // instead of a plain translation-key String starting with 1.21.9.
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("nearplayer", "main"));

    private static KeyBinding openSettingsKey;
    private static KeyBinding placeFlagKey;
    private static NearPlayerHud hudInstance;

    @Override
    public void onInitializeClient() {
        hudInstance = new NearPlayerHud();
        HudRenderCallback.EVENT.register(hudInstance);

        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nearplayer.opensettings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));
        placeFlagKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nearplayer.place_flag", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            hudInstance.tick();
            while (openSettingsKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new NearPlayerSettingsScreen(null, hudInstance));
            }
            while (placeFlagKey.wasPressed()) {
                if (client.currentScreen == null && client.player != null) hudInstance.placeFlag();
            }
        });
    }

    public static NearPlayerHud getHud() { return hudInstance; }
}
