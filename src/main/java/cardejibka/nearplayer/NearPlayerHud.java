package cardejibka.nearplayer;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.sound.SoundEvents;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Client HUD for BedWars awareness. Only opponents are tracked.
 * Team relationship is determined primarily by the colour of the leading
 * §-code in each player's nickname prefix (matches your own prefix colour
 * = teammate), since many BedWars-type servers don't expose a reliable
 * client-visible scoreboard team. Scoreboard team is still used first when
 * both sides have one.
 */
public class NearPlayerHud implements HudRenderCallback {
    private static final int MAX_RENDERED_PLAYERS = 32;
    private static final int SCAN_INTERVAL_TICKS = 2;
    private static final int ALERT_COOLDOWN_TICKS = 20 * 20;

    private static final int ARROW_SIZE = 64;
    private static final float DISPLAY_ARROW_SIZE = 12.0f;
    private static final float ARROW_RADIUS = 52.0f;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Identifier RED_ARROW = Identifier.of("nearplayer", "textures/red_arrow.png");
    private final Identifier WHITE_ARROW = Identifier.of("nearplayer", "textures/white_arrow.png");
    private final Identifier FLAG_ICON = Identifier.of("nearplayer", "textures/flag_icon.png");
    private final Identifier VIGNETTE_TEXTURE = Identifier.of("nearplayer", "textures/vignette_red.png");
    private static final int VIGNETTE_TEXTURE_SIZE = 512;
    private static final int FLAG_ICON_SIZE = 64;
    private static final float FLAG_ICON_NEAR_DISTANCE = 5.0f;    // largest on-screen size at/below this
    private static final float FLAG_ICON_FAR_DISTANCE = 200.0f;   // smallest on-screen size at/above this
    private static final int FLAG_ICON_MAX_PX = 40;
    private static final int FLAG_ICON_MIN_PX = 14;

    private NearPlayerConfig config;
    private final List<PlayerEntity> nearbyEnemies = new ArrayList<>();
    private final java.util.Map<PlayerEntity, Float> smoothedArrowAngles = new java.util.HashMap<>();

    private PlayerEntity nearestEnemy;
    private double nearestEnemyDistance = Double.MAX_VALUE;
    private long tickCounter;

    private boolean alertActive;
    private double alertEnemyDistance = Double.MAX_VALUE;
    private long lastAlertTick = -ALERT_COOLDOWN_TICKS;
    private float vignetteAlpha;
    private float targetVignetteAlpha;

    private Vec3d flagPosition;
    private String flagWorldId;

    public NearPlayerHud() {
        this.config = NearPlayerConfig.load();
    }

    public void tick() {
        if (mc.player == null || mc.world == null) {
            clearWorldState();
            return;
        }

        String currentWorld = mc.world.getRegistryKey().getValue().toString();
        if (flagWorldId != null && !flagWorldId.equals(currentWorld)) {
            clearFlag();
        }

        tickCounter++;
        if (tickCounter % SCAN_INTERVAL_TICKS == 0) {
            scanEnemies();
            checkFlagAlert();
        }

        // Smoothly approach the desired danger level instead of popping the vignette.
        float smoothing = alertActive ? 0.20f : 0.12f;
        vignetteAlpha = MathHelper.lerp(smoothing, vignetteAlpha, targetVignetteAlpha);
        if (Math.abs(vignetteAlpha - targetVignetteAlpha) < 0.005f) {
            vignetteAlpha = targetVignetteAlpha;
        }
    }

    private void scanEnemies() {
        nearbyEnemies.clear();
        nearestEnemy = null;
        nearestEnemyDistance = Double.MAX_VALUE;

        if (!config.enabled || !config.alertsEnabled && config.arrowOpacity <= 0f && !config.showEquipment) {
            return;
        }

        double radiusSq = (double) config.detectionRadius * config.detectionRadius;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isTrackableEnemy(player)) continue;

            double distanceSq = mc.player.squaredDistanceTo(player);
            if (distanceSq > radiusSq) continue;

            if (nearbyEnemies.size() < MAX_RENDERED_PLAYERS) {
                nearbyEnemies.add(player);
            }
            if (distanceSq < nearestEnemyDistance * nearestEnemyDistance) {
                nearestEnemy = player;
                nearestEnemyDistance = Math.sqrt(distanceSq);
            }
        }
    }

    private boolean isTrackableEnemy(PlayerEntity player) {
        if (player == mc.player || player.isSpectator()) return false;
        if (isExcludedByName(player)) return false;
        return isEnemy(player);
    }

    /**
     * Supports a comma-separated list of ignored nicknames (e.g. "Alice, Bob"),
     * matched case-insensitively against the player's raw (uncoloured) name.
     */
    private boolean isExcludedByName(PlayerEntity player) {
        if (config.excludedPlayers == null || config.excludedPlayers.isBlank()) return false;
        String playerName = player.getName().getString();
        for (String excluded : config.excludedPlayers.split(",")) {
            String trimmed = excluded.trim();
            if (!trimmed.isEmpty() && trimmed.equalsIgnoreCase(playerName)) return true;
        }
        return false;
    }

    /**
     * Team relationship is judged primarily by displayed name colour — the tab-list
     * colour (or scoreboard team colour, when present) — rather than by scoreboard
     * Team object identity. Some servers put every player into a single scoreboard
     * team just to drive tab-list formatting, with no per-side team split at all;
     * treating "same Team object" as "same side" on those servers makes literally
     * everyone read as a teammate. Colour is the actual signal players see and the
     * one that reliably represents side across different server setups.
     */
    private boolean isEnemy(PlayerEntity player) {
        if (player == mc.player) return false;

        Formatting ownColor = getTabListColor(mc.player);
        Formatting playerColor = getTabListColor(player);
        if (ownColor != null && playerColor != null) {
            return ownColor != playerColor;
        }

        // No usable colour on one or both sides: fall back to the scoreboard
        // relationship the client actually has, if any.
        Team playerTeam = resolveTeam(player);
        Team ownTeam = resolveTeam(mc.player);
        if (playerTeam != null && ownTeam != null) {
            return playerTeam != ownTeam;
        }

        if (player.isTeammate(mc.player)) return false;

        return true;
    }

    private Team resolveTeam(PlayerEntity player) {
        Team team = player.getScoreboardTeam();
        if (team != null) return team;
        if (mc.getNetworkHandler() == null) return null;
        var entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return entry != null ? entry.getScoreboardTeam() : null;
    }

    /**
     * Reads the colour of the first coloured segment of the player's tab-list name.
     * Falls back to the in-world display name if no tab-list entry exists yet
     * (e.g. right after joining, before the player-info packet arrives).
     */
    private Formatting getTabListColor(PlayerEntity player) {
        Text tabName = null;
        if (mc.getNetworkHandler() != null) {
            var entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null) tabName = entry.getDisplayName();
        }
        if (tabName == null) tabName = player.getDisplayName();
        return firstStyledColor(tabName);
    }

    private Formatting firstStyledColor(Text text) {
        Formatting[] result = new Formatting[1];
        text.visit((style, asString) -> {
            if (!asString.isEmpty() && style.getColor() != null) {
                Formatting formatting = closestFormatting(style.getColor());
                if (formatting != null) {
                    result[0] = formatting;
                    return Text.TERMINATE_VISIT;
                }
            }
            return java.util.Optional.empty();
        }, text.getStyle());
        return result[0];
    }

    /**
     * Maps a resolved TextColor to the nearest vanilla Formatting colour. Servers
     * commonly send exact vanilla colours (even via hex) for team-coded prefixes,
     * so an exact RGB match covers the overwhelming majority of cases; nearest-match
     * by distance covers the rest without ever returning null for a real colour.
     */
    private Formatting closestFormatting(TextColor color) {
        int rgb = color.getRgb();
        Formatting best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Formatting f : Formatting.values()) {
            if (!f.isColor() || f.getColorValue() == null) continue;
            int c = f.getColorValue();
            int dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            int dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            int db = (c & 0xFF) - (rgb & 0xFF);
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = f;
            }
        }
        return best;
    }

    private void checkFlagAlert() {
        if (!config.alertsEnabled || flagPosition == null || mc.player == null) {
            alertActive = false;
            alertEnemyDistance = Double.MAX_VALUE;
            targetVignetteAlpha = 0.0f;
            return;
        }

        PlayerEntity closest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        double alertRadiusSq = (double) config.alertRadius * config.alertRadius;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isTrackableEnemy(player)) continue;

            double distanceSq = player.squaredDistanceTo(flagPosition);
            if (distanceSq <= alertRadiusSq && distanceSq < closestDistanceSq) {
                closest = player;
                closestDistanceSq = distanceSq;
            }
        }

        boolean inside = closest != null;
        alertEnemyDistance = closestDistanceSq;

        if (inside) {
            double distance = Math.sqrt(closestDistanceSq);
            float danger = 1.0f - MathHelper.clamp(
                    (float) (distance / Math.max(1, config.alertRadius)), 0.0f, 1.0f);
            // Keep the effect deliberately subtle and edge-only.
            targetVignetteAlpha = 0.10f + 0.26f * danger;

            if (tickCounter - lastAlertTick >= ALERT_COOLDOWN_TICKS) {
                sendAlert(closest, distance);
                lastAlertTick = tickCounter;
            }
        } else {
            targetVignetteAlpha = 0.0f;
        }

        alertActive = inside;
    }

    private void sendAlert(PlayerEntity enemy, double distance) {
        int blocks = Math.max(0, (int) Math.round(distance));
        Text message = Text.translatable(
                "message.nearplayer.enemy_approaching",
                enemy.getName().copy().formatted(Formatting.RED),
                Text.literal(String.valueOf(blocks)).formatted(Formatting.RED)
        );
        mc.player.sendMessage(message, false);

        if (config.alertSoundEnabled) {
            // playSoundToPlayer no longer exists; ClientPlayerEntity#playSound(SoundEvent, float, float)
            // plays a sound audible only to this client, which is what we want here.
            mc.player.playSound(SoundEvents.BLOCK_BELL_USE, 1.0F, 0.9F);
            mc.player.playSound(SoundEvents.BLOCK_BELL_USE, 0.70F, 1.12F);
        }
    }

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        if (!config.enabled || mc.player == null || mc.world == null) return;

        renderFlagVignette(context);
        renderEnemyArrows(context, tickCounter);
        renderNearestEnemy(context);
        renderFlagIndicator(context);
    }

    private void renderEnemyArrows(DrawContext context, RenderTickCounter tickCounter) {
        if (config.arrowOpacity <= 0f) return;

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;
        float yaw = mc.player.getYaw(tickCounter.getTickProgress(false));

        // Arrow angles are smoothed frame-to-frame (independent of the tick-rate
        // enemy scan) so fast PvP movement doesn't make them visibly snap/jitter.
        // Angle smoothing has to go the "short way" around the circle, otherwise
        // an angle crossing the -180/180 boundary spins the arrow the long way.
        smoothedArrowAngles.keySet().retainAll(nearbyEnemies);

        int alpha = MathHelper.clamp(Math.round(255 * config.arrowOpacity), 0, 255);
        if (alpha <= 0) return;
        int tint = (alpha << 24) | 0x00FFFFFF;

        for (PlayerEntity player : nearbyEnemies) {
            Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            Vec3d clientPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            double deltaX = playerPos.x - clientPos.x;
            double deltaZ = playerPos.z - clientPos.z;

            float targetAngle = MathHelper.wrapDegrees(
                    (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - yaw + 180.0f);

            Float previous = smoothedArrowAngles.get(player);
            float angle;
            if (previous == null) {
                angle = targetAngle;
            } else {
                float delta = MathHelper.wrapDegrees(targetAngle - previous);
                angle = previous + delta * 0.35f;
            }
            smoothedArrowAngles.put(player, angle);

            float angleRad = (float) Math.toRadians(angle);

            float arrowX = centerX + ARROW_RADIUS * (float) Math.cos(angleRad);
            float arrowY = centerY + ARROW_RADIUS * (float) Math.sin(angleRad);

            Matrix3x2fStack matrices = context.getMatrices();
            matrices.pushMatrix();
            matrices.translate(arrowX, arrowY);
            matrices.rotate(angleRad);
            float scale = DISPLAY_ARROW_SIZE / ARROW_SIZE;
            matrices.scale(scale, scale);

            // Nearest enemy gets the red arrow (matches the red name/label treatment
            // elsewhere); every other tracked opponent gets the white arrow.
            Identifier arrowTexture = (player == nearestEnemy) ? RED_ARROW : WHITE_ARROW;

            context.drawTexture(
                    RenderPipelines.GUI_TEXTURED,
                    arrowTexture,
                    -ARROW_SIZE / 2, -ARROW_SIZE / 2,
                    0.0f, 0.0f,
                    ARROW_SIZE, ARROW_SIZE,
                    ARROW_SIZE, ARROW_SIZE,
                    ARROW_SIZE, ARROW_SIZE,
                    tint
            );
            matrices.popMatrix();
        }
    }

    private void renderNearestEnemy(DrawContext context) {
        if (nearestEnemy == null) return;

        int screenWidth = mc.getWindow().getScaledWidth();
        int y = 5;
        // Show the enemy's name exactly as it appears in the tab list (own colour
        // preserved), not force-recoloured — the colour itself is useful information
        // (which team/base they're from), and forcing it to red destroyed that.
        Text nameText = getTabListName(nearestEnemy);
        Text displayText = Text.literal("")
                .append(nameText)
                .append(Text.literal(": ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf((int) nearestEnemyDistance)).formatted(Formatting.WHITE))
                .append(Text.literal(" ").formatted(Formatting.GRAY))
                .append(Text.translatable("text.nearplayer.distance").formatted(Formatting.GRAY));

        int textWidth = mc.textRenderer.getWidth(displayText);
        int x = screenWidth / 2 - textWidth / 2;
        context.fill(x - 3, y - 2, x + textWidth + 3,
                y + mc.textRenderer.fontHeight + 2, 0x90000000);
        context.drawTextWithShadow(mc.textRenderer, displayText, x, y, 0xFFFFFFFF);

        if (config.showEquipment) {
            renderEquipment(context, nearestEnemy, y + mc.textRenderer.fontHeight + 4);
        }
    }

    /**
     * The tab-list display name (with its real server-assigned colour/prefix),
     * falling back to the plain in-world name if no tab-list entry is available yet.
     */
    private Text getTabListName(PlayerEntity player) {
        if (mc.getNetworkHandler() != null) {
            var entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null && entry.getDisplayName() != null) return entry.getDisplayName();
        }
        return player.getDisplayName();
    }

    private void renderEquipment(DrawContext context, PlayerEntity player, int equipmentY) {
        // getArmorItems()/getAllArmorItems() are gone from the public LivingEntity/PlayerEntity
        // API surface in 1.21.11; equipment is now read per-slot via getEquippedStack(EquipmentSlot).
        List<ItemStack> equipment = new ArrayList<>();
        equipment.add(player.getEquippedStack(EquipmentSlot.HEAD));
        equipment.add(player.getEquippedStack(EquipmentSlot.CHEST));
        equipment.add(player.getEquippedStack(EquipmentSlot.LEGS));
        equipment.add(player.getEquippedStack(EquipmentSlot.FEET));
        equipment.add(player.getMainHandStack());
        equipment.add(player.getOffHandStack());

        int slotWidth = 16;
        int slotSpacing = 2;
        int equipmentWidth = 6 * slotWidth + 5 * slotSpacing;
        int equipmentX = mc.getWindow().getScaledWidth() / 2 - equipmentWidth / 2;

        for (int i = 0; i < Math.min(6, equipment.size()); i++) {
            int slotX = equipmentX + i * (slotWidth + slotSpacing);
            context.fill(slotX - 1, equipmentY - 1,
                    slotX + slotWidth + 1, equipmentY + slotWidth + 1, 0x90000000);
            ItemStack stack = equipment.get(i);
            if (!stack.isEmpty()) context.drawItem(stack, slotX, equipmentY);
        }
    }

    private void renderFlagIndicator(DrawContext context) {
        if (flagPosition == null || mc.player == null || !config.showFlagIndicator) return;

        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double dx = flagPosition.x - playerPos.x;
        double dz = flagPosition.z - playerPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Icon shown with its natural colours (no red tint) — the flag texture
        // itself is the visual, text stays plain white for readability.
        int iconSize = mc.textRenderer.fontHeight + 2;
        int textX = mc.getWindow().getScaledWidth() - 10;

        Text flagText = Text.translatable(
                "text.nearplayer.flag_distance", Math.max(0, (int) Math.round(distance)));
        int textWidth = mc.textRenderer.getWidth(flagText);
        int x = textX - textWidth - iconSize - 4;
        int y = 8;

        context.fill(x - 5, y - 3, textX + 5,
                y + mc.textRenderer.fontHeight + 3, 0x90000000);
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                FLAG_ICON,
                x, y - 1,
                0.0f, 0.0f,
                iconSize, iconSize,
                FLAG_ICON_SIZE, FLAG_ICON_SIZE,
                FLAG_ICON_SIZE, FLAG_ICON_SIZE
        );
        context.drawTextWithShadow(mc.textRenderer, flagText, x + iconSize + 4, y, 0xFFFFFFFF);

        renderFlagWorldMarker(context, distance);
    }

    /**
     * Projects the flag's actual 3D world position onto the screen and draws the
     * flag icon there, the way waypoint/base-marker mods do it: the icon stays
     * pinned to the block it was placed on as you move and look around, instead of
     * sitting at a fixed spot on the HUD. Falls back to hiding the icon entirely
     * when the point is behind the camera or off-screen.
     */
    private void renderFlagWorldMarker(DrawContext context, double distance) {
        Camera camera = mc.gameRenderer.getCamera();
        if (!camera.isReady()) return;

        // Marker sits one block above the placement point so it doesn't get buried
        // in the ground/floor block it was placed on.
        Vec3d markerPos = flagPosition.add(0, 1.2, 0);

        org.joml.Vector3f screen = worldToScreen(camera, markerPos);
        if (screen == null) return; // behind the camera

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();
        float sx = screen.x();
        float sy = screen.y();

        // Scale by distance so it reads like a real object: bigger up close,
        // smaller far away, clamped to a sane pixel range.
        float t = MathHelper.clamp((float) distance, FLAG_ICON_NEAR_DISTANCE, FLAG_ICON_FAR_DISTANCE);
        float normalized = (t - FLAG_ICON_NEAR_DISTANCE) / (FLAG_ICON_FAR_DISTANCE - FLAG_ICON_NEAR_DISTANCE);
        int size = Math.round(MathHelper.lerp(normalized, FLAG_ICON_MAX_PX, FLAG_ICON_MIN_PX));
        if (size <= 0) return;

        // Reject only when the icon's centre is far enough off-screen that none of
        // it would be visible at all; a margin of a full icon size (instead of a
        // fixed 32px) avoids clipping the icon right as it enters/leaves the screen
        // edge, which is what produced the "6 pixels visible" clipping before.
        if (sx < -size || sx > screenWidth + size || sy < -size || sy > screenHeight + size) return;

        int px = Math.round(sx) - size / 2;
        int py = Math.round(sy) - size; // anchor at the bottom of the icon (pole base)

        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                FLAG_ICON,
                px, py,
                0.0f, 0.0f,
                size, size,
                FLAG_ICON_SIZE, FLAG_ICON_SIZE,
                FLAG_ICON_SIZE, FLAG_ICON_SIZE
        );
    }

    /**
     * Manually projects a world-space point to screen pixel coordinates, using the
     * same view/projection setup the game itself uses for the current frame. There
     * is no public one-call API for this, so the view matrix is built from the
     * camera's own position/yaw/pitch and combined with GameRenderer's projection
     * matrix. Returns null if the point is behind the camera (would show a mirrored
     * marker on-screen, which is worse than not drawing it at all).
     */
    private org.joml.Vector3f worldToScreen(Camera camera, Vec3d worldPos) {
        Vec3d camPos = camera.getCameraPos();
        float dx = (float) (worldPos.x - camPos.x);
        float dy = (float) (worldPos.y - camPos.y);
        float dz = (float) (worldPos.z - camPos.z);

        org.joml.Matrix4f view = new org.joml.Matrix4f()
                .rotateX((float) Math.toRadians(camera.getPitch()))
                .rotateY((float) Math.toRadians(camera.getYaw() + 180.0f));
        org.joml.Vector4f viewSpace = new org.joml.Vector4f(dx, dy, dz, 1.0f);
        view.transform(viewSpace);
        // View space in this engine looks down -Z; discard points behind the camera.
        if (viewSpace.z() >= 0) return null;

        int fov = mc.options.getFov().getValue();
        org.joml.Matrix4f projection = mc.gameRenderer.getBasicProjectionMatrix(fov);
        org.joml.Vector4f clip = new org.joml.Vector4f(viewSpace);
        projection.transform(clip);
        if (clip.w() == 0) return null;

        float ndcX = clip.x() / clip.w();
        float ndcY = clip.y() / clip.w();

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();
        float sx = (ndcX * 0.5f + 0.5f) * screenWidth;
        float sy = (1.0f - (ndcY * 0.5f + 0.5f)) * screenHeight;
        return new org.joml.Vector3f(sx, sy, 0);
    }

    private void renderFlagVignette(DrawContext context) {
        if (config.vignetteOpacity <= 0f || vignetteAlpha <= 0.001f || flagPosition == null) return;

        int width = mc.getWindow().getScaledWidth();
        int height = mc.getWindow().getScaledHeight();
        int alpha = MathHelper.clamp((int) (255.0f * vignetteAlpha * config.vignetteOpacity), 0, 255);
        if (alpha <= 0) return;

        // A single pre-baked, smoothly-feathered radial gradient texture stretched
        // over the screen. This replaces the old 4-rectangle-gradient approach
        // (hard seams in every corner) and an earlier cell-grid approximation
        // (visible blocky steps); a real image with a soft alpha falloff is both
        // cheaper (one draw call) and strictly smoother than either.
        int tint = (alpha << 24) | 0x00FFFFFF;
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                VIGNETTE_TEXTURE,
                0, 0,
                0.0f, 0.0f,
                width, height,
                VIGNETTE_TEXTURE_SIZE, VIGNETTE_TEXTURE_SIZE,
                VIGNETTE_TEXTURE_SIZE, VIGNETTE_TEXTURE_SIZE,
                tint
        );
    }

    public void placeFlag() {
        if (mc.player == null || mc.world == null) return;

        flagPosition = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        flagWorldId = mc.world.getRegistryKey().getValue().toString();
        alertActive = false;
        alertEnemyDistance = Double.MAX_VALUE;
        targetVignetteAlpha = 0.0f;
        lastAlertTick = tickCounter - ALERT_COOLDOWN_TICKS;

        mc.player.sendMessage(Text.translatable("message.nearplayer.flag_set"), false);
        mc.player.playSound(SoundEvents.BLOCK_BELL_USE, 0.65F, 1.45F);
    }

    public void clearFlag() {
        flagPosition = null;
        flagWorldId = null;
        alertActive = false;
        alertEnemyDistance = Double.MAX_VALUE;
        targetVignetteAlpha = 0.0f;
        vignetteAlpha = 0.0f;
        lastAlertTick = -ALERT_COOLDOWN_TICKS;
    }

    private void clearWorldState() {
        nearbyEnemies.clear();
        nearestEnemy = null;
        nearestEnemyDistance = Double.MAX_VALUE;
        clearFlag();
    }

    public NearPlayerConfig getConfig() { return config; }
    public void saveConfig() { config.save(); }
    public boolean isEnabled() { return config.enabled; }
    public void setEnabled(boolean value) { config.enabled = value; }
    public int getDetectionRadius() { return config.detectionRadius; }
    public void setDetectionRadius(int radius) { config.detectionRadius = Math.max(10, Math.min(500, radius)); }
    public int getAlertRadius() { return config.alertRadius; }
    public void setAlertRadius(int radius) { config.alertRadius = Math.max(5, Math.min(100, radius)); }
    public boolean isShowEquipment() { return config.showEquipment; }
    public void setShowEquipment(boolean value) { config.showEquipment = value; }
    public boolean isAlertSoundEnabled() { return config.alertSoundEnabled; }
    public void setAlertSoundEnabled(boolean value) { config.alertSoundEnabled = value; }
    public boolean isShowFlagIndicator() { return config.showFlagIndicator; }
    public void setShowFlagIndicator(boolean value) { config.showFlagIndicator = value; }
    public boolean isAlertsEnabled() { return config.alertsEnabled; }
    public void setAlertsEnabled(boolean value) { config.alertsEnabled = value; }
    public float getVignetteOpacity() { return config.vignetteOpacity; }
    public void setVignetteOpacity(float value) { config.vignetteOpacity = Math.max(0.0f, Math.min(1.0f, value)); }
    public float getArrowOpacity() { return config.arrowOpacity; }
    public void setArrowOpacity(float value) { config.arrowOpacity = Math.max(0.0f, Math.min(1.0f, value)); }
    public String getExcludedPlayers() { return config.excludedPlayers; }
    public void setExcludedPlayers(String value) { config.excludedPlayers = value == null ? "" : value.trim(); }
}
