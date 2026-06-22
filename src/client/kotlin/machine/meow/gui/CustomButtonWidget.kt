package machine.meow.gui

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.sound.SoundManager
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Custom-styled button for MC 1.21.11.
 * Background + borders via context.fill(); text via DrawnTextConsumer.
 * Border colours are mutable so toggle-state changes can be reflected immediately.
 */
class CustomButtonWidget(
    x: Int, y: Int, w: Int, h: Int,
    message: Text,
    private val clickHandler: (CustomButtonWidget) -> Unit,
    var normalBorder: Int = BLUE_BORDER,
    var hoverBorder:  Int = BLUE_HOVER,
    var soundType: GuiSoundType = GuiSoundType.GENERAL
) : ClickableWidget(x, y, w, h, message) {

    override fun onClick(click: Click, doubled: Boolean) {
        net.minecraft.client.MinecraftClient.getInstance()?.let { SoundHelper.play(it, soundType) }
        clickHandler(this)
    }

    /** Suppress the vanilla click sound – SoundHelper handles it entirely. */
    override fun playDownSound(soundManager: SoundManager) = Unit

    /** Update message and border colours to reflect a boolean toggle state. */
    fun applyToggle(isOn: Boolean, label: String) {
        message    = Text.literal(if (isOn) "§a✔  $label" else "§c✗  $label")
        normalBorder = if (isOn) GREEN_BORDER else RED_BORDER
        hoverBorder  = if (isOn) GREEN_HOVER  else RED_HOVER
    }

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val hov = isHovered
        val bg  = if (hov) 0xFF1C1C3A.toInt() else 0xFF0E0E20.toInt()
        val bc  = if (hov) hoverBorder else normalBorder
        val bx = x; val by = y; val bw = width; val bh = height

        context.fill(bx, by, bx + bw, by + bh, bg)
        context.fill(bx,          by,          bx + bw, by + 1,  bc)
        context.fill(bx,          by + bh - 1, bx + bw, by + bh, bc)
        context.fill(bx,          by,          bx + 1,  by + bh, bc)
        context.fill(bx + bw - 1, by,          bx + bw, by + bh, bc)

        // MC 1.21.11: text must go through DrawnTextConsumer
        val styledMsg = if (active) message else message.copy().formatted(Formatting.DARK_GRAY)
        val consumer  = context.getTextConsumer()
        drawTextWithMargin(consumer, styledMsg, 4)
    }

    override fun appendClickableNarrations(builder: NarrationMessageBuilder) =
        appendDefaultNarrations(builder)

    companion object {
        val BLUE_BORDER  = 0xFF2D3FAA.toInt()
        val BLUE_HOVER   = 0xFF5566EE.toInt()
        val GREEN_BORDER = 0xFF2A7A3A.toInt()
        val GREEN_HOVER  = 0xFF44BB66.toInt()
        val RED_BORDER   = 0xFF7A2A2A.toInt()
        val RED_HOVER    = 0xFFBB4444.toInt()

        fun of(x: Int, y: Int, w: Int, h: Int, label: String,
               soundType: GuiSoundType = GuiSoundType.GENERAL,
               action: (CustomButtonWidget) -> Unit) =
            CustomButtonWidget(x, y, w, h, Text.literal(label), action,
                BLUE_BORDER, BLUE_HOVER, soundType)

        /** Back / Close button → EXIT sound. */
        fun exit(x: Int, y: Int, w: Int, h: Int, label: String, action: (CustomButtonWidget) -> Unit) =
            of(x, y, w, h, label, GuiSoundType.EXIT, action)

        /** Top-level navigation / expand button → HEADER sound. */
        fun header(x: Int, y: Int, w: Int, h: Int, label: String, action: (CustomButtonWidget) -> Unit) =
            of(x, y, w, h, label, GuiSoundType.HEADER, action)

        fun green(x: Int, y: Int, w: Int, h: Int, label: String, action: (CustomButtonWidget) -> Unit) =
            CustomButtonWidget(x, y, w, h, Text.literal(label), action,
                GREEN_BORDER, GREEN_HOVER, GuiSoundType.GENERAL)

        fun red(x: Int, y: Int, w: Int, h: Int, label: String, action: (CustomButtonWidget) -> Unit) =
            CustomButtonWidget(x, y, w, h, Text.literal(label), action,
                RED_BORDER, RED_HOVER, GuiSoundType.GENERAL)

        /** Red delete/remove button → EXIT sound (same category as Back). */
        fun delete(x: Int, y: Int, w: Int, h: Int, label: String, action: (CustomButtonWidget) -> Unit) =
            CustomButtonWidget(x, y, w, h, Text.literal(label), action,
                RED_BORDER, RED_HOVER, GuiSoundType.EXIT)

        /**
         * Bool-toggle button: green ✔ when [isOn]=true, red ✗ when false → BOOL sound.
         */
        fun toggle(x: Int, y: Int, w: Int, h: Int, isOn: Boolean, label: String,
                   action: (CustomButtonWidget) -> Unit): CustomButtonWidget {
            val msg = if (isOn) "§a✔  $label" else "§c✗  $label"
            return CustomButtonWidget(x, y, w, h, Text.literal(msg), action,
                if (isOn) GREEN_BORDER else RED_BORDER,
                if (isOn) GREEN_HOVER  else RED_HOVER,
                GuiSoundType.BOOL)
        }
    }
}
