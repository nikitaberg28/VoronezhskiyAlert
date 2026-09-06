package cardejibka.nearplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NearPlayerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nearplayer.json");

    public boolean enabled = true;
    public int detectionRadius = 100;
    public int alertRadius = 25;
    public boolean showEquipment = true;
    public boolean alertsEnabled = true;
    public boolean alertSoundEnabled = true;
    public float vignetteOpacity = 1.0f;
    public float arrowOpacity = 1.0f;
    public boolean showFlagIndicator = true;
    public String excludedPlayers = "";

    public static NearPlayerConfig load() {
        if (!Files.exists(FILE)) return new NearPlayerConfig();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            NearPlayerConfig config = GSON.fromJson(reader, NearPlayerConfig.class);
            return config != null ? config.sanitize() : new NearPlayerConfig();
        } catch (Exception ignored) {
            return new NearPlayerConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(this.sanitize(), writer);
            }
        } catch (Exception ignored) {
        }
    }

    private NearPlayerConfig sanitize() {
        detectionRadius = Math.max(10, Math.min(500, detectionRadius));
        alertRadius = Math.max(5, Math.min(100, alertRadius));
        arrowOpacity = Math.max(0.0f, Math.min(1.0f, arrowOpacity));
        vignetteOpacity = Math.max(0.0f, Math.min(1.0f, vignetteOpacity));
        excludedPlayers = excludedPlayers == null ? "" : excludedPlayers.trim();
        return this;
    }
}
