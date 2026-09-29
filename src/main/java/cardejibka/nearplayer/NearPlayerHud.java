package cardejibka.nearplayer;

// ===== PORTING NOTE (Minecraft 26.x) =======================================
// Confirmed from decompiled 26.2 sources (Minecraft.java, Player.java,
// LocalPlayer.java, AbstractButton.java, Font.java, MutableComponent.java).
// Remaining unconfirmed items (SoundEvents package, exact Team/scoreboard
// access path, Camera package, EquipmentSlot access methods, GuiGraphicsExtractor's
// text-drawing method) are marked TODO-VERIFY below and need an IDE check.
// =============================================================================
import net.minecraft.client.gui.GuiGraphicsExtractor; // confirmed from AbstractButton.java decompile: package is net.minecraft.client.gui, NOT net.fabricmc.*
import net.minecraft.client.DeltaTracker; // confirmed (Minecraft.java field type + hud guide)
import net.minecraft.client.Minecraft; // confirmed (Minecraft.java)
import net.minecraft.client.Camera; // TODO-VERIFY: package unconfirmed - mc.gameRenderer.mainCamera() returns this type per Minecraft.java, but Camera.java itself wasn't decompiled
import net.minecraft.client.renderer.RenderPipelines; // confirmed (AbstractButton.java: net.minecraft.client.renderer.RenderPipelines)
import net.minecraft.world.entity.EquipmentSlot; // confirmed (Player.java imports net.minecraft.world.entity.EquipmentSlot)
import net.minecraft.world.entity.player.Player; // confirmed (Player.java itself: package net.minecraft.world.entity.player)
import net.minecraft.world.item.ItemStack; // confirmed (Player.java imports net.minecraft.world.item.ItemStack)
import net.minecraft.world.scores.PlayerTeam; // confirmed (Player.java imports net.minecraft.world.scores.PlayerTeam)
import net.minecraft.world.scores.Team; // confirmed (Player.java imports net.minecraft.world.scores.Team; getTeam() returns this, PlayerTeam extends it)
import net.minecraft.network.chat.Component; // confirmed (used throughout LocalPlayer.java / Player.java)
import net.minecraft.network.chat.MutableComponent; // confirmed (MutableComponent.java: package net.minecraft.network.chat)
import net.minecraft.network.chat.TextColor; // TODO-VERIFY: package assumed net.minecraft.network.chat (matches Component/MutableComponent), not directly decompiled
import net.minecraft.ChatFormatting; // confirmed (MutableComponent.java: import net.minecraft.ChatFormatting)
import net.minecraft.resources.Identifier; // confirmed (Minecraft.java: import net.minecraft.resources.Identifier - NOT net.minecraft.util.Identifier)
import net.minecraft.util.Mth; // confirmed (LocalPlayer.java: import net.minecraft.util.Mth)
import net.minecraft.world.phys.Vec3; // confirmed (used throughout LocalPlayer.java / Player.java)
import net.minecraft.sounds.SoundEvents; // confirmed (LocalPlayer.java: import net.minecraft.sounds.SoundEvents)
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
public class NearPlayerHud {
    private static final int MAX_RENDERED_PLAYERS = 32;
    private static final int SCAN_INTERVAL_TICKS = 2;
    private static final int ALERT_COOLDOWN_TICKS = 20 * 20;

    private static final int ARROW_SIZE = 64;
    private static final float DISPLAY_ARROW_SIZE = 12.0f;
    private static final float ARROW_RADIUS = 52.0f;

    private final Minecraft mc = Minecraft.getInstance();
    private final Identifier RED_ARROW = Identifier.fromNamespaceAndPath("nearplayer", "textures/red_arrow.png");
    private final Identifier WHITE_ARROW = Identifier.fromNamespaceAndPath("nearplayer", "textures/white_arrow.png");
    private final Identifier FLAG_ICON = Identifier.fromNamespaceAndPath("nearplayer", "textures/flag_icon.png");
    private final Identifier VIGNETTE_TEXTURE = Identifier.fromNamespaceAndPath("nearplayer", "textures/vignette_red.png");
    private static final int VIGNETTE_TEXTURE_SIZE = 512;
    private static final int FLAG_ICON_SIZE = 64;
    private static final float FLAG_ICON_NEAR_DISTANCE = 5.0f;    // largest on-screen size at/below this
    private static final float FLAG_ICON_FAR_DISTANCE = 200.0f;   // smallest on-screen size at/above this
    private static final int FLAG_ICON_MAX_PX = 40;
    private static final int FLAG_ICON_MIN_PX = 14;

    private NearPlayerConfig config;
    private final List<Player> nearbyEnemies = new ArrayList<>();
    private final java.util.Map<Player, Float> smoothedArrowAngles = new java.util.HashMap<>();

    private Player nearestEnemy;
    private double nearestEnemyDistance = Double.MAX_VALUE;
    private long tickCounter;

    private boolean alertActive;
    private double alertEnemyDistance = Double.MAX_VALUE;
    private long lastAlertTick = -ALERT_COOLDOWN_TICKS;
    private float vignetteAlpha;
    private float targetVignetteAlpha;

    private Vec3 flagPosition;
    private String flagWorldId;

    public NearPlayerHud() {
        this.config = NearPlayerConfig.load();
    }

    public void tick() {
        if (mc.player == null || mc.level == null) {
            clearWorldState();
            return;
        }

        // Confirmed pattern from Minecraft.java (getSituationalMusic uses
        // playerLevel.dimension()): ClientLevel.dimension() returns
        // ResourceKey<Level>. TODO-VERIFY: .location() to get the Identifier from
        // a ResourceKey follows Mojang's standard ResourceKey convention but
        // wasn't directly decompiled here.
        String currentWorld = mc.level.dimension().identifier().toString();
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
        vignetteAlpha = Mth.lerp(smoothing, vignetteAlpha, targetVignetteAlpha);
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
        for (Player player : mc.level.players()) { // confirmed: ClientLevel.players(), not getPlayers()
            if (!isTrackableEnemy(player)) continue;

            double distanceSq = mc.player.distanceToSqr(player); // TODO-VERIFY: renamed from squaredDistanceTo per Mojang convention (distanceToSqr), Entity-accepting overload not directly confirmed
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

    private boolean isTrackableEnemy(Player player) {
        if (player == mc.player || player.isSpectator()) return false;
        if (isExcludedByName(player)) return false;
        return isEnemy(player);
    }

    /**
     * Supports a comma-separated list of ignored nicknames (e.g. "Alice, Bob"),
     * matched case-insensitively against the player's raw (uncoloured) name.
     */
    private boolean isExcludedByName(Player player) {
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
    private boolean isEnemy(Player player) {
        if (player == mc.player) return false;

        // Team identity FIRST. On MineBlaze (confirmed via debug logging), the
        // tab-list display name carries NO color code at all - both allies and
        // enemies showed the exact same "§7" default, which meant the old
        // color-first logic below immediately (and wrongly) concluded "same
        // color => same team => ally" for literally everyone, without ever
        // reaching the team check. Scoreboard team identity is a much more
        // reliable signal: if the client has a real PlayerTeam object for
        // both players (most bed-wars-style servers register one team per
        // side), same/different Team instance directly tells us the answer,
        // completely independent of how the server chooses to render names.
        PlayerTeam playerTeam = resolveTeam(player);
        PlayerTeam ownTeam = resolveTeam(mc.player);

        // TEMPORARY DEBUG - remove once enemy detection is confirmed working.
        System.out.println("[NearPlayer DEBUG2] player=" + player.getName().getString()
                + " ownTeam=" + (ownTeam != null ? ownTeam.getName() : "null")
                + " ownTeamColor=" + (ownTeam != null ? ownTeam.getColor() : "n/a")
                + " playerTeam=" + (playerTeam != null ? playerTeam.getName() : "null")
                + " playerTeamColor=" + (playerTeam != null ? playerTeam.getColor() : "n/a"));

        if (playerTeam != null && ownTeam != null) {
            return playerTeam != ownTeam;
        }

        // No scoreboard team on one or both sides (some servers don't use
        // teams at all, or teams haven't synced yet). Fall back to comparing
        // each team's own configured color (PlayerTeam.getColor(), the real
        // color the server assigned that team - not text formatting pulled
        // out of a display name, which may carry none at all).
        // TODO-VERIFY: PlayerTeam.getColor() itself wasn't 100% directly
        // confirmed in decompiled/mapping sources checked so far (a private
        // "color" field on PlayerTeam and a public getColor() on the related
        // network packet's Parameters class were both confirmed, strongly
        // suggesting PlayerTeam has the same accessor, but double-check in
        // your IDE if this line fails to compile).
        if (playerTeam != null || ownTeam != null) {
            ChatFormatting playerTeamColor = playerTeam != null ? playerTeam.getColor() : null;
            ChatFormatting ownTeamColor = ownTeam != null ? ownTeam.getColor() : null;
            if (playerTeamColor != null && ownTeamColor != null) {
                return playerTeamColor != ownTeamColor;
            }
        }

        // Last resort: tab-list/display-name color, but only treat it as
        // meaningful if it's an actual team-like color, not the "no color
        // set" default (GRAY/WHITE/RESET all commonly show up as the
        // fallback style on plain, unformatted text and don't indicate a
        // real side).
        ChatFormatting ownColor = meaningfulColor(getTabListColor(mc.player));
        ChatFormatting playerColor = meaningfulColor(getTabListColor(player));
        if (ownColor != null && playerColor != null) {
            return ownColor != playerColor;
        }

        // TODO-VERIFY: isTeammate(Player) was not found on Player/LocalPlayer in the
        // decompiled sources available; Player.canHarmPlayer(Player) or a Team-based
        // check (team.isAlliedTo(otherTeam)) may be the 26.x replacement. Left as a
        // stub (falls through to "is enemy") until confirmed - please check in your IDE.
        // if (player.isTeammate(mc.player)) return false;

        return true;
    }

    /**
     * Filters out colors that are really just "no color was set" defaults
     * rather than a genuine team-assigned color, so they don't get treated
     * as if every such player were on the same real side.
     */
    private ChatFormatting meaningfulColor(ChatFormatting color) {
        if (color == null) return null;
        if (color == ChatFormatting.GRAY || color == ChatFormatting.WHITE || color == ChatFormatting.RESET) {
            return null;
        }
        return color;
    }

    private PlayerTeam resolveTeam(Player player) {
        // Confirmed from Player.java: getScoreboardTeam() doesn't exist; the
        // accessor is getTeam(), returning Team (PlayerTeam is its subclass -
        // see canHarmPlayer(), which calls this.getTeam()/target.getTeam()).
        Team team = player.getTeam();
        if (team instanceof PlayerTeam playerTeam) return playerTeam;
        // TODO-VERIFY: mc.getNetworkHandler() doesn't exist on Minecraft; network
        // access goes through mc.player.connection (confirmed field on LocalPlayer,
        // type ClientPacketListener), but the exact method to look up a tab-list
        // entry/team by UUID on ClientPacketListener was not directly confirmed.
        if (mc.player == null || mc.player.connection == null) return null;
        var entry = mc.player.connection.getPlayerInfo(player.getUUID());
        return entry != null && entry.getTeam() instanceof PlayerTeam pt ? pt : null;
    }

    /**
     * Reads the colour of the first coloured segment of the player's tab-list name.
     * Falls back to the in-world display name if no tab-list entry exists yet
     * (e.g. right after joining, before the player-info packet arrives).
     */
    private ChatFormatting getTabListColor(Player player) {
        Component tabName = null;
        // TODO-VERIFY: see resolveTeam() note above - getPlayerInfo(UUID) on
        // mc.player.connection is an educated guess, not directly confirmed.
        if (mc.player != null && mc.player.connection != null) {
            var entry = mc.player.connection.getPlayerInfo(player.getUUID());
            if (entry != null) tabName = entry.getTabListDisplayName();
        }
        if (tabName == null) tabName = player.getDisplayName();
        return firstStyledColor(tabName);
    }

    // Confirmed from ChatFormatting.java (26.2 decompile): ChatFormatting is now
    // a pure §-code text-formatting enum (colors AND styles like BOLD/ITALIC)
    // with NO color-value accessors at all (no isColor()/getColor()/getColorValue()).
    // Actual RGB colors live entirely on the TextColor side. Since the 16
    // standard chat colors are a fixed, publicly documented part of Minecraft's
    // text format (unchanged across versions/mappings), they're hardcoded here
    // instead of read reflectively off the enum - this is the correct fix, not
    // a workaround, given ChatFormatting no longer carries color data at all.
    private static final java.util.Map<ChatFormatting, Integer> VANILLA_COLORS = new java.util.EnumMap<>(ChatFormatting.class);
    static {
        VANILLA_COLORS.put(ChatFormatting.BLACK, 0x000000);
        VANILLA_COLORS.put(ChatFormatting.DARK_BLUE, 0x0000AA);
        VANILLA_COLORS.put(ChatFormatting.DARK_GREEN, 0x00AA00);
        VANILLA_COLORS.put(ChatFormatting.DARK_AQUA, 0x00AAAA);
        VANILLA_COLORS.put(ChatFormatting.DARK_RED, 0xAA0000);
        VANILLA_COLORS.put(ChatFormatting.DARK_PURPLE, 0xAA00AA);
        VANILLA_COLORS.put(ChatFormatting.GOLD, 0xFFAA00);
        VANILLA_COLORS.put(ChatFormatting.GRAY, 0xAAAAAA);
        VANILLA_COLORS.put(ChatFormatting.DARK_GRAY, 0x555555);
        VANILLA_COLORS.put(ChatFormatting.BLUE, 0x5555FF);
        VANILLA_COLORS.put(ChatFormatting.GREEN, 0x55FF55);
        VANILLA_COLORS.put(ChatFormatting.AQUA, 0x55FFFF);
        VANILLA_COLORS.put(ChatFormatting.RED, 0xFF5555);
        VANILLA_COLORS.put(ChatFormatting.LIGHT_PURPLE, 0xFF55FF);
        VANILLA_COLORS.put(ChatFormatting.YELLOW, 0xFFFF55);
        VANILLA_COLORS.put(ChatFormatting.WHITE, 0xFFFFFF);
    }

    private ChatFormatting firstStyledColor(Component text) {
        // TODO-VERIFY: Component.visit(...)'s exact signature/behavior and the
        // early-termination sentinel (was Text.TERMINATE_VISIT under Yarn) were
        // not confirmed in the decompiled sources available. Rewritten below to
        // avoid needing that sentinel at all: walk siblings/style manually via
        // the confirmed Style/Component API (style.getColor(), text.getStyle())
        // instead of the visitor pattern, which sidesteps the uncertainty.
        ChatFormatting direct = styleColorOf(text.getStyle());
        if (direct != null) return direct;
        if (text instanceof net.minecraft.network.chat.MutableComponent mutable) {
            for (Component sibling : mutable.getSiblings()) {
                ChatFormatting found = firstStyledColor(sibling);
                if (found != null) return found;
            }
        }
        return null;
    }

    private ChatFormatting styleColorOf(net.minecraft.network.chat.Style style) {
        TextColor color = style.getColor();
        return color != null ? closestFormatting(color) : null;
    }

    /**
     * Maps a resolved TextColor to the nearest vanilla ChatFormatting colour. Servers
     * commonly send exact vanilla colours (even via hex) for team-coded prefixes,
     * so an exact RGB match covers the overwhelming majority of cases; nearest-match
     * by distance covers the rest without ever returning null for a real colour.
     */
    private ChatFormatting closestFormatting(TextColor color) {
        int rgb = color.getValue(); // TODO-VERIFY: TextColor.getValue() not directly confirmed (TextColor.java itself wasn't decompiled) - check in IDE, common alternatives are getRGB()/getRgb()/value()
        ChatFormatting best = null;
        int bestDist = Integer.MAX_VALUE;
        for (java.util.Map.Entry<ChatFormatting, Integer> entry : VANILLA_COLORS.entrySet()) {
            int c = entry.getValue();
            int dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            int dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            int db = (c & 0xFF) - (rgb & 0xFF);
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = entry.getKey();
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

        Player closest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        double alertRadiusSq = (double) config.alertRadius * config.alertRadius;

        for (Player player : mc.level.players()) { // confirmed: ClientLevel.players(), not getPlayers()
            if (!isTrackableEnemy(player)) continue;

            double distanceSq = player.distanceToSqr(flagPosition); // confirmed: distanceToSqr(Vec3) pattern seen in Player.java/AABB usage
            if (distanceSq <= alertRadiusSq && distanceSq < closestDistanceSq) {
                closest = player;
                closestDistanceSq = distanceSq;
            }
        }

        boolean inside = closest != null;
        alertEnemyDistance = closestDistanceSq;

        if (inside) {
            double distance = Math.sqrt(closestDistanceSq);
            float danger = 1.0f - Mth.clamp(
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

    private void sendAlert(Player enemy, double distance) {
        int blocks = Math.max(0, (int) Math.round(distance));
        // Confirmed from MutableComponent.java: formatted(ChatFormatting) doesn't
        // exist; the method is withStyle(ChatFormatting).
        Component message = Component.translatable(
                "message.nearplayer.enemy_approaching",
                ((MutableComponent) enemy.getName().copy()).withStyle(ChatFormatting.RED),
                Component.literal(String.valueOf(blocks)).withStyle(ChatFormatting.RED)
        );
        // Confirmed from LocalPlayer.java: "public void sendSystemMessage(final Component message)".
        mc.player.sendSystemMessage(message);

        if (config.alertSoundEnabled) {
            // playSoundToPlayer no longer exists; ClientPlayerEntity#playSound(SoundEvent, float, float)
            // plays a sound audible only to this client, which is what we want here.
            // TODO-VERIFY: SoundEvents.java itself wasn't decompiled, so the exact
            // field name BLOCK_BELL_USE is unconfirmed under official mappings, and
            // whether SoundEvents fields are plain SoundEvent or Holder<SoundEvent>
            // (ClientLevel.java uses Holder<SoundEvent> in some overloads) is also
            // unconfirmed - check in IDE; .value() may be needed if it's a Holder.
            mc.player.playSound(SoundEvents.BELL_BLOCK, 1.0F, 0.9F);
            mc.player.playSound(SoundEvents.BELL_BLOCK, 0.70F, 1.12F);
        }
    }

    // Registered as a method reference against HudElementRegistry in
    // NearplayerClient (HudRenderCallback no longer exists in 26.x). The
    // GuiGraphicsExtractor/DeltaTracker parameter types are confirmed from
    // Fabric's official "Rendering in the HUD" guide for 26.1.2/26.2.
    public void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        if (!config.enabled || mc.player == null || mc.level == null) return;

        renderFlagVignette(context);
        renderEnemyArrows(context, tickCounter);
        renderNearestEnemy(context);
        renderFlagIndicator(context);
    }

    private void renderEnemyArrows(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        if (config.arrowOpacity <= 0f) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;
        // getTickProgress(boolean) -> getGameTimeDeltaPartialTick(boolean): confirmed
        // rename per Fabric's official "Rendering in the HUD" 26.x guide.
        // Confirmed from LocalPlayer.java: "public float getViewYRot(final float a)"
        // exists and gives the interpolated view yaw for a given partial tick -
        // this is the correct replacement for the old getYaw(tickDelta) call
        // (better than the non-interpolated getYRot() used here previously).
        float yaw = mc.player.getViewYRot(tickCounter.getGameTimeDeltaPartialTick(false));

        // Arrow angles are smoothed frame-to-frame (independent of the tick-rate
        // enemy scan) so fast PvP movement doesn't make them visibly snap/jitter.
        // Angle smoothing has to go the "short way" around the circle, otherwise
        // an angle crossing the -180/180 boundary spins the arrow the long way.
        smoothedArrowAngles.keySet().retainAll(nearbyEnemies);

        int alpha = Mth.clamp(Math.round(255 * config.arrowOpacity), 0, 255);
        if (alpha <= 0) return;
        int tint = (alpha << 24) | 0x00FFFFFF;

        for (Player player : nearbyEnemies) {
            Vec3 playerPos = new Vec3(player.getX(), player.getY(), player.getZ());
            Vec3 clientPos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            double deltaX = playerPos.x - clientPos.x;
            double deltaZ = playerPos.z - clientPos.z;

            float targetAngle = Mth.wrapDegrees(
                    (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - yaw + 180.0f);

            Float previous = smoothedArrowAngles.get(player);
            float angle;
            if (previous == null) {
                angle = targetAngle;
            } else {
                float delta = Mth.wrapDegrees(targetAngle - previous);
                angle = previous + delta * 0.35f;
            }
            smoothedArrowAngles.put(player, angle);

            float angleRad = (float) Math.toRadians(angle);

            float arrowX = centerX + ARROW_RADIUS * (float) Math.cos(angleRad);
            float arrowY = centerY + ARROW_RADIUS * (float) Math.sin(angleRad);

            Matrix3x2fStack matrices = context.pose(); // confirmed from GuiGraphicsExtractor.java: pose() returns the Matrix3x2fStack
            matrices.pushMatrix();
            matrices.translate(arrowX, arrowY);
            matrices.rotate(angleRad);
            float scale = DISPLAY_ARROW_SIZE / ARROW_SIZE;
            matrices.scale(scale, scale);

            // Nearest enemy gets the red arrow (matches the red name/label treatment
            // elsewhere); every other tracked opponent gets the white arrow.
            Identifier arrowTexture = (player == nearestEnemy) ? RED_ARROW : WHITE_ARROW;

            context.blit(
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

    private void renderNearestEnemy(GuiGraphicsExtractor context) {
        if (nearestEnemy == null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int y = 5;
        // Show the enemy's name exactly as it appears in the tab list (own colour
        // preserved), not force-recoloured — the colour itself is useful information
        // (which team/base they're from), and forcing it to red destroyed that.
        Component nameText = getTabListName(nearestEnemy);
        Component displayText = Component.literal("")
                .append(nameText)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf((int) nearestEnemyDistance)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("text.nearplayer.distance").withStyle(ChatFormatting.GRAY));

        int textWidth = mc.font.width(displayText); // confirmed: Font.width(FormattedText), not getWidth(...)
        int x = screenWidth / 2 - textWidth / 2;
        context.fill(x - 3, y - 2, x + textWidth + 3,
                y + mc.font.lineHeight + 2, 0x90000000);
        context.text(mc.font, displayText, x, y, 0xFFFFFFFF, true); // confirmed from EditBox.java: graphics.text(Font, Component, x, y, color, shadow) replaces drawTextWithShadow

        if (config.showEquipment) {
            renderEquipment(context, nearestEnemy, y + mc.font.lineHeight + 4);
        }
    }

    /**
     * The tab-list display name (with its real server-assigned colour/prefix),
     * falling back to the plain in-world name if no tab-list entry is available yet.
     */
    private Component getTabListName(Player player) {
        // TODO-VERIFY: see resolveTeam() note above.
        if (mc.player != null && mc.player.connection != null) {
            var entry = mc.player.connection.getPlayerInfo(player.getUUID());
            if (entry != null && entry.getTabListDisplayName() != null) return entry.getTabListDisplayName();
        }
        return player.getDisplayName();
    }

    private void renderEquipment(GuiGraphicsExtractor context, Player player, int equipmentY) {
        // Confirmed from Player.java: getEquippedStack/getMainHandStack/getOffHandStack
        // don't exist. getItemBySlot(EquipmentSlot) is confirmed (used internally by
        // Player.hurtArmor/hurtHelmet). getItemInHand(InteractionHand) is confirmed
        // (used throughout LocalPlayer.java/Player.java) for main/off hand.
        List<ItemStack> equipment = new ArrayList<>();
        equipment.add(player.getItemBySlot(EquipmentSlot.HEAD));
        equipment.add(player.getItemBySlot(EquipmentSlot.CHEST));
        equipment.add(player.getItemBySlot(EquipmentSlot.LEGS));
        equipment.add(player.getItemBySlot(EquipmentSlot.FEET));
        equipment.add(player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND));
        equipment.add(player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND));

        int slotWidth = 16;
        int slotSpacing = 2;
        int equipmentWidth = 6 * slotWidth + 5 * slotSpacing;
        int equipmentX = mc.getWindow().getGuiScaledWidth() / 2 - equipmentWidth / 2;

        for (int i = 0; i < Math.min(6, equipment.size()); i++) {
            int slotX = equipmentX + i * (slotWidth + slotSpacing);
            context.fill(slotX - 1, equipmentY - 1,
                    slotX + slotWidth + 1, equipmentY + slotWidth + 1, 0x90000000);
            ItemStack stack = equipment.get(i);
            if (!stack.isEmpty()) context.item(stack, slotX, equipmentY); // confirmed from GuiGraphicsExtractor.java: item(ItemStack, x, y)
        }
    }

    private void renderFlagIndicator(GuiGraphicsExtractor context) {
        if (flagPosition == null || mc.player == null || !config.showFlagIndicator) return;

        Vec3 playerPos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double dx = flagPosition.x - playerPos.x;
        double dz = flagPosition.z - playerPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Icon shown with its natural colours (no red tint) — the flag texture
        // itself is the visual, text stays plain white for readability.
        int iconSize = mc.font.lineHeight + 2;
        int textX = mc.getWindow().getGuiScaledWidth() - 10;

        Component flagText = Component.translatable(
                "text.nearplayer.flag_distance", Math.max(0, (int) Math.round(distance)));
        int textWidth = mc.font.width(flagText); // confirmed: Font.width(FormattedText), not getWidth(...)
        int x = textX - textWidth - iconSize - 4;
        int y = 8;

        context.fill(x - 5, y - 3, textX + 5,
                y + mc.font.lineHeight + 3, 0x90000000);
        context.blit(
                RenderPipelines.GUI_TEXTURED,
                FLAG_ICON,
                x, y - 1,
                0.0f, 0.0f,
                iconSize, iconSize,
                FLAG_ICON_SIZE, FLAG_ICON_SIZE,
                FLAG_ICON_SIZE, FLAG_ICON_SIZE
        );
        context.text(mc.font, flagText, x + iconSize + 4, y, 0xFFFFFFFF, true); // confirmed from EditBox.java: graphics.text(Font, Component, x, y, color, shadow) replaces drawTextWithShadow

        renderFlagWorldMarker(context, distance);
    }

    /**
     * Projects the flag's actual 3D world position onto the screen and draws the
     * flag icon there, the way waypoint/base-marker mods do it: the icon stays
     * pinned to the block it was placed on as you move and look around, instead of
     * sitting at a fixed spot on the HUD. Falls back to hiding the icon entirely
     * when the point is behind the camera or off-screen.
     */
    private void renderFlagWorldMarker(GuiGraphicsExtractor context, double distance) {
        // Confirmed from ClientLevel.java (multiple call sites e.g.
        // this.minecraft.gameRenderer.mainCamera()): the method is mainCamera(),
        // not getCamera().
        Camera camera = mc.gameRenderer.mainCamera();
        // Confirmed from Camera.java: isReady() doesn't exist; isInitialized()
        // is the real readiness check.
        if (!camera.isInitialized()) return;

        // Marker sits one block above the placement point so it doesn't get buried
        // in the ground/floor block it was placed on.
        Vec3 markerPos = flagPosition.add(0, 1.2, 0);

        org.joml.Vector3f screen = worldToScreen(camera, markerPos);
        if (screen == null) return; // behind the camera

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float sx = screen.x();
        float sy = screen.y();

        // Scale by distance so it reads like a real object: bigger up close,
        // smaller far away, clamped to a sane pixel range.
        float t = Mth.clamp((float) distance, FLAG_ICON_NEAR_DISTANCE, FLAG_ICON_FAR_DISTANCE);
        float normalized = (t - FLAG_ICON_NEAR_DISTANCE) / (FLAG_ICON_FAR_DISTANCE - FLAG_ICON_NEAR_DISTANCE);
        int size = Math.round(Mth.lerp(normalized, FLAG_ICON_MAX_PX, FLAG_ICON_MIN_PX));
        if (size <= 0) return;

        // Reject only when the icon's centre is far enough off-screen that none of
        // it would be visible at all; a margin of a full icon size (instead of a
        // fixed 32px) avoids clipping the icon right as it enters/leaves the screen
        // edge, which is what produced the "6 pixels visible" clipping before.
        if (sx < -size || sx > screenWidth + size || sy < -size || sy > screenHeight + size) return;

        int px = Math.round(sx) - size / 2;
        int py = Math.round(sy) - size; // anchor at the bottom of the icon (pole base)

        context.blit(
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
     * Projects a world-space point to screen pixel coordinates.
     *
     * REWRITTEN after projectPointToScreen(Vec3) turned out to NOT behave like a
     * simple NDC projection in practice (the flag icon rendered in essentially
     * random screen positions instead of staying pinned to its world position -
     * projectPointToScreen is apparently meant for a different use case, such as
     * the panorama screenshot code in GameRenderer.java that calls it, and its
     * output convention doesn't match what a standard "world point -> screen
     * pixel" helper needs here).
     *
     * This version instead does the standard, well-defined clip-space transform
     * by hand, using Camera.getViewRotationProjectionMatrix(Matrix4f) (confirmed
     * from Camera.java: it returns the combined view*projection matrix already
     * folding in the camera's own Projection, so no separate FOV/aspect-ratio
     * lookup is needed):
     *   1. Build a point relative to the camera position (view-space offset).
     *   2. Transform by the view-projection matrix to get clip-space coordinates
     *      (x, y, z, w).
     *   3. Perspective-divide by w to get normalized device coordinates (NDC),
     *      each roughly in [-1, 1] when the point is visible, by definition of
     *      how any standard OpenGL/Vulkan-style projection matrix works - this
     *      part is standard graphics math, not an unverified game-specific API.
     *   4. Map NDC to pixel coordinates using the GUI-scaled window size.
     * A point is behind the camera when w <= 0 after the transform.
     */
    private org.joml.Vector3f worldToScreen(Camera camera, Vec3 worldPos) {
        Vec3 camPos = camera.position();
        float dx = (float) (worldPos.x - camPos.x);
        float dy = (float) (worldPos.y - camPos.y);
        float dz = (float) (worldPos.z - camPos.z);

        org.joml.Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new org.joml.Matrix4f());
        org.joml.Vector4f clip = new org.joml.Vector4f(dx, dy, dz, 1.0f);
        viewProjection.transform(clip);

        if (clip.w() <= 0) return null; // behind the camera

        float ndcX = clip.x() / clip.w();
        float ndcY = clip.y() / clip.w();

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float sx = (ndcX * 0.5f + 0.5f) * screenWidth;
        float sy = (1.0f - (ndcY * 0.5f + 0.5f)) * screenHeight;
        return new org.joml.Vector3f(sx, sy, 0);
    }

    private void renderFlagVignette(GuiGraphicsExtractor context) {
        if (config.vignetteOpacity <= 0f || vignetteAlpha <= 0.001f || flagPosition == null) return;

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        int alpha = Mth.clamp((int) (255.0f * vignetteAlpha * config.vignetteOpacity), 0, 255);
        if (alpha <= 0) return;

        // A single pre-baked, smoothly-feathered radial gradient texture stretched
        // over the screen. This replaces the old 4-rectangle-gradient approach
        // (hard seams in every corner) and an earlier cell-grid approximation
        // (visible blocky steps); a real image with a soft alpha falloff is both
        // cheaper (one draw call) and strictly smoother than either.
        int tint = (alpha << 24) | 0x00FFFFFF;
        context.blit(
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
        if (mc.player == null || mc.level == null) return;

        flagPosition = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        flagWorldId = mc.level.dimension().identifier().toString(); // TODO-VERIFY: see tick() note above
        alertActive = false;
        alertEnemyDistance = Double.MAX_VALUE;
        targetVignetteAlpha = 0.0f;
        lastAlertTick = tickCounter - ALERT_COOLDOWN_TICKS;

        mc.player.sendSystemMessage(Component.translatable("message.nearplayer.flag_set")); // confirmed: LocalPlayer.sendSystemMessage(Component)
        mc.player.playSound(SoundEvents.BELL_BLOCK, 0.65F, 1.45F);
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
