package cardejibka.nearplayer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

public class ModernButton extends ButtonWidget {
    private boolean toggled;
    private final boolean isToggle;

    public ModernButton(int x, int y, int width, int height, net.minecraft.text.Text message, PressAction onPress) {
        this(x, y, width, height, message, onPress, false);
    }

    public ModernButton(int x, int y, int width, int height, net.minecraft.text.Text message, PressAction onPress, boolean isToggle) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        this.isToggle = isToggle;
        this.toggled = false;
    }

    public void setToggled(boolean toggled) {
        this.toggled = toggled;
    }

    public boolean isToggled() {
        return toggled;
    }

    // In 1.21.9+ ButtonWidget#renderWidget is final; the customizable hook is now
    // the protected drawIcon(...) method (Yarn name), called by the superclass
    // after it draws the background/border. We draw everything ourselves here.
    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        if (!this.visible) return;

        MinecraftClient client = MinecraftClient.getInstance();
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

        int textX = getX() + (width - client.textRenderer.getWidth(getMessage())) / 2;
        int textY = getY() + (height - 8) / 2;
        context.drawTextWithShadow(client.textRenderer, getMessage(), textX, textY, textColor);
    }
}
