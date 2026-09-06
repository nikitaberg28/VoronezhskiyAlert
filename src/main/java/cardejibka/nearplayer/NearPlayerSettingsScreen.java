package cardejibka.nearplayer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

// NOTE: This screen does not extend ButtonWidget, so the plain name "Text" here
// always resolves to net.minecraft.text.Text (no ButtonWidget.Text nested-class
// shadowing issue). ModernButton's constructors are typed to accept
// net.minecraft.text.Text explicitly, so passing Text.translatable(...) below
// is unambiguous.

/**
 * Settings menu. Layout matches the original design: narrow input fields with
 * their labels to the left (like vanilla options screens), stacked vertically
 * at the top. Toggle/opacity buttons sit below in a compact two-column grid
 * instead of one long single-column list.
 */
public class NearPlayerSettingsScreen extends Screen {
    private final Screen parent;
    private final NearPlayerHud hud;

    private TextFieldWidget detectionRadiusField;
    private TextFieldWidget alertRadiusField;
    private TextFieldWidget excludedField;

    private static final int WINDOW_WIDTH = 320;
    private static final int WINDOW_HEIGHT = 300;
    private static final int FIELD_WIDTH = 110;
    private static final float[] OPACITY_STEPS = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};

    private int labelX;
    private int fieldsY;
    private int bottomHintY;

    public NearPlayerSettingsScreen(Screen parent, NearPlayerHud hud) {
        super(Text.translatable("title.nearplayer.settings"));
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
        detectionRadiusField = createField(fieldX, startY + 10, FIELD_WIDTH,
                hud.getDetectionRadius(), "placeholder.nearplayer.radius");
        alertRadiusField = createField(fieldX, startY + 38, FIELD_WIDTH,
                hud.getAlertRadius(), "placeholder.nearplayer.alert_radius");

        excludedField = new TextFieldWidget(textRenderer, fieldX, startY + 66,
                FIELD_WIDTH, 20, Text.literal(""));
        excludedField.setMaxLength(200);
        excludedField.setText(hud.getExcludedPlayers());
        excludedField.setPlaceholder(Text.translatable("placeholder.nearplayer.excluded").formatted(Formatting.GRAY));
        addDrawableChild(excludedField);

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
        addDrawableChild(new ModernButton(colLeftX, buttonsY, smallButtonWidth, 22,
                Text.translatable("button.nearplayer.clear_flag"), btn -> hud.clearFlag()));
        addDrawableChild(new ModernButton(colLeftX + smallButtonWidth + 4, buttonsY, smallButtonWidth, 22,
                Text.translatable("button.nearplayer.save"), btn -> {
                    applyFields();
                    hud.saveConfig();
                    client.setScreen(parent);
                }));
        addDrawableChild(new ModernButton(colLeftX + (smallButtonWidth + 4) * 2, buttonsY, smallButtonWidth, 22,
                Text.translatable("button.nearplayer.cancel"), btn -> client.setScreen(parent)));

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
        addDrawableChild(button);
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

    private Text opacityText(String labelKey, float value) {
        return Text.translatable(labelKey, Math.round(value * 100));
    }

    private TextFieldWidget createField(int x, int y, int width, int value, String placeholderKey) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, width, 20, Text.literal(""));
        field.setMaxLength(5);
        field.setTextPredicate(s -> s.matches("\\d*"));
        field.setText(String.valueOf(value));
        field.setPlaceholder(Text.translatable(placeholderKey).formatted(Formatting.GRAY));
        addDrawableChild(field);
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
        addDrawableChild(button);
    }

    private Text toggleText(String keyPrefix, boolean enabled) {
        return Text.translatable(keyPrefix + (enabled ? ".on" : ".off"));
    }

    private void applyFields() {
        try { hud.setDetectionRadius(Integer.parseInt(detectionRadiusField.getText().trim())); }
        catch (NumberFormatException ignored) { }
        try { hud.setAlertRadius(Integer.parseInt(alertRadiusField.getText().trim())); }
        catch (NumberFormatException ignored) { }
        hud.setExcludedPlayers(excludedField.getText().trim());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2,
                (height - WINDOW_HEIGHT) / 2 + 7, 0xFFFFFFFF);

        Text radiusLabel = Text.translatable("label.nearplayer.radius");
        Text alertRadiusLabel = Text.translatable("label.nearplayer.alert_radius");
        Text excludedLabel = Text.translatable("label.nearplayer.excluded");

        context.drawTextWithShadow(textRenderer, radiusLabel, labelX, fieldsY + 16, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, alertRadiusLabel, labelX, fieldsY + 44, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, excludedLabel, labelX, fieldsY + 72, 0xFFFFFFFF);

        Text cooldown = Text.translatable("text.nearplayer.cooldown").formatted(Formatting.GRAY);
        context.drawCenteredTextWithShadow(textRenderer, cooldown, width / 2, bottomHintY, 0xFFAAAAAA);
        Text hint = Text.translatable("text.nearplayer.controls").formatted(Formatting.GRAY);
        context.drawCenteredTextWithShadow(textRenderer, hint, width / 2, bottomHintY + 12, 0xFFAAAAAA);
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void close() { client.setScreen(parent); }
}
