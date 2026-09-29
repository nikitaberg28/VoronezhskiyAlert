package cardejibka.nearplayer;

// ===== PORTING NOTE (Minecraft 26.x) =======================================
// MAJOR REWRITE after decompiling Screen.java and EditBox.java (26.2).
// Key confirmed findings:
//   - Screen has public fields: `minecraft` (Minecraft instance), `font`
//     (Font), `width`/`height` (int). My earlier guesses for those two field
//     names were correct.
//   - addDrawableChild(...) does not exist. The real method is
//     addRenderableWidget(T) (registers a widget that is both interactive
//     and rendered).
//   - render(GuiGraphicsExtractor, int, int, float) does not exist as an
//     override point; Screen's own entry point is
//     extractRenderState(GuiGraphicsExtractor, int, int, float). There is no
//     more separate background-drawing pass to call super for in the same
//     way - extractRenderStateWithTooltipAndSubtitles is the actual root
//     entry point Fabric/vanilla calls, and it already calls
//     extractBackground(...) + extractRenderState(...) for us. So this class
//     now overrides extractRenderState(...) and simply calls
//     super.extractRenderState(...) first (which draws all registered
//     renderables/widgets), then draws our own extra text on top - this
//     mirrors what the old render() override used to do.
//   - shouldPause() does not exist; the equivalent is isPauseScreen()
//     (confirmed method on Screen, default returns true).
//   - close() does not exist on Screen; the built-in cancel/back hook is
//     onClose() (confirmed - default implementation is
//     `this.minecraft.gui.setScreen(null)`), so that's what's overridden
//     below instead, calling setScreen(parent) the same way the old code did.
//   - There is no drawCenteredTextWithShadow/drawTextWithShadow on
//     GuiGraphicsExtractor. Confirmed from EditBox.java: real text drawing is
//     graphics.text(Font, Component/FormattedCharSequence, x, y, color,
//     shadow). Centering has to be computed manually via font.width(...),
//     same as EditBox itself does internally.
//   - EditBox no longer has setText/getText/setPlaceholder/setTextPredicate.
//     Confirmed real API: setValue(String)/getValue(), setHint(Component)
//     for placeholder text, and addFormatter(TextFormatter) for custom
//     per-character formatting/filtering (TextFormatter interface is defined
//     in EditBox's own file, not decompiled here - for the numeric-only
//     filter we instead filter input in the responder, which is simpler and
//     doesn't need TextFormatter's exact shape).
// =============================================================================
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Settings menu. Layout matches the original design: narrow input fields with
 * their labels to the left (like vanilla options screens), stacked vertically
 * at the top. Toggle/opacity buttons sit below in a compact two-column grid
 * instead of one long single-column list.
 */
public class NearPlayerSettingsScreen extends Screen {
    private final Screen parent;
    private final NearPlayerHud hud;

    private EditBox detectionRadiusField;
    private EditBox alertRadiusField;
    private EditBox excludedField;

    private static final int WINDOW_WIDTH = 320;
    private static final int WINDOW_HEIGHT = 300;
    private static final int FIELD_WIDTH = 110;
    private static final float[] OPACITY_STEPS = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};

    private int labelX;
    private int fieldsY;
    private int bottomHintY;

    public NearPlayerSettingsScreen(Screen parent, NearPlayerHud hud) {
        super(Component.translatable("title.nearplayer.settings"));
        this.parent = parent;
        this.hud = hud;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = (width - WINDOW_WIDTH) / 2;
        int startY = (height - WINDOW_HEIGHT) / 2 + 26;
        int fieldX = centerX + WINDOW_WIDTH - FIELD_WIDTH - 40;

        // --- Narrow input fields, label to the left (original layout) ----------
        detectionRadiusField = createNumericField(fieldX, startY + 10, FIELD_WIDTH,
                hud.getDetectionRadius(), "placeholder.nearplayer.radius");
        alertRadiusField = createNumericField(fieldX, startY + 38, FIELD_WIDTH,
                hud.getAlertRadius(), "placeholder.nearplayer.alert_radius");

        excludedField = new EditBox(font, fieldX, startY + 66, FIELD_WIDTH, 20, Component.literal(""));
        excludedField.setMaxLength(200);
        excludedField.setValue(hud.getExcludedPlayers());
        excludedField.setHint(Component.translatable("placeholder.nearplayer.excluded").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(excludedField);

        // --- Toggle/opacity buttons, compact two-column grid --------------------
        int gridY = startY + 100;
        int colWidth = 148;
        int rowHeight = 24;
        int colLeftX = centerX + 6;
        int colRightX = colLeftX + colWidth + 8;

        addToggle(colLeftX, gridY, colWidth, "button.nearplayer.mod", hud.isEnabled(),
                () -> { hud.setEnabled(!hud.isEnabled()); return hud.isEnabled(); });
        addToggle(colRightX, gridY, colWidth, "button.nearplayer.equipment", hud.isShowEquipment(),
                () -> { hud.setShowEquipment(!hud.isShowEquipment()); return hud.isShowEquipment(); });

        addToggle(colLeftX, gridY + rowHeight, colWidth, "button.nearplayer.alerts", hud.isAlertsEnabled(),
                () -> { hud.setAlertsEnabled(!hud.isAlertsEnabled()); return hud.isAlertsEnabled(); });
        addToggle(colRightX, gridY + rowHeight, colWidth, "button.nearplayer.show_flag", hud.isShowFlagIndicator(),
                () -> { hud.setShowFlagIndicator(!hud.isShowFlagIndicator()); return hud.isShowFlagIndicator(); });

        addOpacityCycle(colLeftX, gridY + rowHeight * 2, colWidth, "button.nearplayer.arrow_opacity",
                hud.getArrowOpacity(), hud::setArrowOpacity);
        addOpacityCycle(colRightX, gridY + rowHeight * 2, colWidth, "button.nearplayer.vignette_opacity",
                hud.getVignetteOpacity(), hud::setVignetteOpacity);

        // --- Bottom buttons ------------------------------------------------------
        int buttonsY = gridY + rowHeight * 3 + 14;
        int totalWidth = colWidth * 2 + 8;
        int smallButtonWidth = (totalWidth - 8) / 3;
        addRenderableWidget(new ModernButton(colLeftX, buttonsY, smallButtonWidth, 22,
                Component.translatable("button.nearplayer.clear_flag"), btn -> hud.clearFlag()));
        addRenderableWidget(new ModernButton(colLeftX + smallButtonWidth + 4, buttonsY, smallButtonWidth, 22,
                Component.translatable("button.nearplayer.save"), btn -> {
                    applyFields();
                    hud.saveConfig();
                    minecraft.gui.setScreen(parent);
                }));
        addRenderableWidget(new ModernButton(colLeftX + (smallButtonWidth + 4) * 2, buttonsY, smallButtonWidth, 22,
                Component.translatable("button.nearplayer.cancel"), btn -> minecraft.gui.setScreen(parent)));

        this.labelX = centerX + 20;
        this.fieldsY = startY;
        this.bottomHintY = buttonsY + 30;
    }

    private void addOpacityCycle(int x, int y, int width, String labelKey,
                                  float initialValue, java.util.function.Consumer<Float> setter) {
        int[] index = {closestOpacityIndex(initialValue)};
        ModernButton button = new ModernButton(x, y, width, 20,
                opacityText(labelKey, OPACITY_STEPS[index[0]]), btn -> {
                    index[0] = (index[0] + 1) % OPACITY_STEPS.length;
                    float value = OPACITY_STEPS[index[0]];
                    setter.accept(value);
                    btn.setMessage(opacityText(labelKey, value));
                });
        addRenderableWidget(button);
    }

    private int closestOpacityIndex(float value) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < OPACITY_STEPS.length; i++) {
            float dist = Math.abs(OPACITY_STEPS[i] - value);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private Component opacityText(String labelKey, float value) {
        return Component.translatable(labelKey, Math.round(value * 100));
    }

    // Confirmed from EditBox.java: no setTextPredicate(...) exists. Filtering
    // non-digit input is instead done in the responder by rejecting/reverting
    // the change - simpler than reimplementing TextFormatter for this case.
    private EditBox createNumericField(int x, int y, int width, int value, String placeholderKey) {
        EditBox field = new EditBox(font, x, y, width, 20, Component.literal(""));
        field.setMaxLength(5);
        field.setValue(String.valueOf(value));
        field.setHint(Component.translatable(placeholderKey).withStyle(ChatFormatting.GRAY));
        field.setResponder(text -> {
            if (!text.matches("\\d*")) {
                field.setValue(text.replaceAll("\\D", ""));
            }
        });
        addRenderableWidget(field);
        return field;
    }

    private void addToggle(int x, int y, int width, String keyPrefix, boolean initial,
                           java.util.function.Supplier<Boolean> toggler) {
        ModernButton button = new ModernButton(x, y, width, 20,
                toggleText(keyPrefix, initial), btn -> {
                    boolean value = toggler.get();
                    ModernButton self = (ModernButton) btn;
                    self.setToggled(value);
                    self.setMessage(toggleText(keyPrefix, value));
                }, true);
        button.setToggled(initial);
        addRenderableWidget(button);
    }

    private Component toggleText(String keyPrefix, boolean enabled) {
        return Component.translatable(keyPrefix + (enabled ? ".on" : ".off"));
    }

    private void applyFields() {
        try { hud.setDetectionRadius(Integer.parseInt(detectionRadiusField.getValue().trim())); }
        catch (NumberFormatException ignored) { }
        try { hud.setAlertRadius(Integer.parseInt(alertRadiusField.getValue().trim())); }
        catch (NumberFormatException ignored) { }
        hud.setExcludedPlayers(excludedField.getValue().trim());
    }

    // Draws a component centred horizontally, mirroring what
    // drawCenteredTextWithShadow used to do (that convenience method is gone
    // from GuiGraphicsExtractor; only graphics.text(...) is confirmed).
    private void drawCentered(GuiGraphicsExtractor graphics, Component text, int centerX, int y, int color) {
        int textWidth = font.width(text.getString());
        graphics.text(font, text, centerX - textWidth / 2, y, color, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        drawCentered(context, title, width / 2, (height - WINDOW_HEIGHT) / 2 + 7, 0xFFFFFFFF);

        Component radiusLabel = Component.translatable("label.nearplayer.radius");
        Component alertRadiusLabel = Component.translatable("label.nearplayer.alert_radius");
        Component excludedLabel = Component.translatable("label.nearplayer.excluded");

        context.text(font, radiusLabel, labelX, fieldsY + 16, 0xFFFFFFFF, true);
        context.text(font, alertRadiusLabel, labelX, fieldsY + 44, 0xFFFFFFFF, true);
        context.text(font, excludedLabel, labelX, fieldsY + 72, 0xFFFFFFFF, true);

        Component cooldown = Component.translatable("text.nearplayer.cooldown").withStyle(ChatFormatting.GRAY);
        drawCentered(context, cooldown, width / 2, bottomHintY, 0xFFAAAAAA);
        Component hint = Component.translatable("text.nearplayer.controls").withStyle(ChatFormatting.GRAY);
        drawCentered(context, hint, width / 2, bottomHintY + 12, 0xFFAAAAAA);
    }

    // Confirmed from Screen.java: shouldPause() doesn't exist; isPauseScreen()
    // is the real method (default true - we want false so the game doesn't pause).
    @Override public boolean isPauseScreen() { return false; }

    // Confirmed from Screen.java: close() doesn't exist; onClose() is the real
    // hook (default implementation: this.minecraft.gui.setScreen(null)).
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
