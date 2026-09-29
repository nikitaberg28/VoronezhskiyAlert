package cardejibka.nearplayer;

// ===== PORTING NOTE (Minecraft 26.x) =======================================
// MAJOR REWRITE after decompiling AbstractButton.java, Button.java and
// EditBox.java. Key findings:
//   - Button's own constructor is `protected`, and its OnPress/CreateNarration
//     types live in Button's own package as nested interfaces - awkward to
//     use from a subclass in another package without also implementing
//     CreateNarration. Simpler and equally correct: extend AbstractButton
//     directly (its constructor is public: x, y, width, height, message) and
//     implement the abstract onPress(InputWithModifiers) ourselves, calling
//     a plain PressAction functional interface we define locally (same
//     pattern the old Yarn code used, just re-declared here since Fabric API
//     doesn't expose one under this name any more for buttons in general).
//   - The rendering hook is extractContents(GuiGraphicsExtractor, int, int,
//     float), confirmed on AbstractButton.
//   - Text drawing: confirmed from EditBox.java's extractWidgetRenderState -
//     the real method is graphics.text(Font, FormattedCharSequence/Component,
//     x, y, color, shadow), NOT drawTextWithShadow(...) (that name doesn't
//     exist on GuiGraphicsExtractor - it was a guess and was wrong).
// =============================================================================
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

public class ModernButton extends AbstractButton {
    private boolean toggled;
    private final boolean isToggle;
    private final PressAction onPress;

    public interface PressAction {
        void onPress(ModernButton button);
    }

    public ModernButton(int x, int y, int width, int height, Component message, PressAction onPress) {
        this(x, y, width, height, message, onPress, false);
    }

    public ModernButton(int x, int y, int width, int height, Component message, PressAction onPress, boolean isToggle) {
        super(x, y, width, height, message);
        this.onPress = onPress;
        this.isToggle = isToggle;
        this.toggled = false;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.onPress.onPress(this);
    }

    // AbstractWidget declares this as abstract; AbstractButton doesn't
    // implement it for us (it only provides a default-ish narration text
    // helper). A minimal narration string is enough here.
    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    public void setToggled(boolean toggled) {
        this.toggled = toggled;
    }

    public boolean isToggled() {
        return toggled;
    }

    // Confirmed from AbstractButton.java (26.2): the protected rendering hook
    // is extractContents(GuiGraphicsExtractor, int, int, float).
    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        if (!this.visible) return;

        Minecraft client = Minecraft.getInstance();
        boolean hovered = this.isHovered();

        int topColor = hovered ? 0xFF5A5A5A : 0xFF3A3A3A;
        int bottomColor = hovered ? 0xFF4A4A4A : 0xFF2A2A2A;

        if (isToggle && toggled) {
            topColor = hovered ? 0xFF4A7A4A : 0xFF3A6A3A;
            bottomColor = hovered ? 0xFF3A6A3A : 0xFF2A5A2A;
        }

        context.fillGradient(getX(), getY(), getX() + width, getY() + height, topColor, bottomColor);

        int borderColor = isToggle && toggled ? 0xFF88FF88 : 0xFF666666;
        context.fill(getX(), getY(), getX() + width, getY() + 1, borderColor);
        context.fill(getX(), getY(), getX() + 1, getY() + height, borderColor);
        context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, borderColor);
        context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, borderColor);

        int textColor = isToggle && toggled ? 0xFFCCFFCC : 0xFFFFFFFF;
        if (hovered) textColor = 0xFFFFFFDD;

        // Confirmed from Font.java: width(String) instead of getWidth(...).
        int textX = getX() + (width - client.font.width(getMessage().getString())) / 2;
        int textY = getY() + (height - 8) / 2;
        // Confirmed from EditBox.java's extractWidgetRenderState: the real text
        // drawing method is graphics.text(Font, Component, x, y, color, shadow).
        context.text(client.font, getMessage(), textX, textY, textColor, true);
    }
}
