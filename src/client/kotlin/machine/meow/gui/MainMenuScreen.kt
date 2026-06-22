package machine.meow.gui

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

class MainMenuScreen : Screen(Text.literal("Mnk")) {

    private var openTime  = 0L
    private var closeTime = -1L
    private var isClosing = false
    private var pendingClose: (() -> Unit)? = null

    private val panelW get() = (width  * 0.80).toInt()
    private val panelH get() = (height * 0.80).toInt()
    private val panelX get() = (width  - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    override fun init() {
        openTime  = System.currentTimeMillis()
        isClosing = false
        closeTime = -1L

        val px = panelX; val py = panelY; val pw = panelW; val ph = panelH
        val cx = px + pw / 2
        val titleAreaH = (ph * 0.50).toInt()
        val btnCY = py + titleAreaH + (ph - titleAreaH) / 2

        addDrawableChild(CustomButtonWidget.header(cx - 80, btnCY - 26, 160, 24, "⚙  Features") {
            client?.setScreen(FeatureListScreen(this))
        })
        addDrawableChild(CustomButtonWidget.exit(cx - 80, btnCY + 6, 160, 24, "Close") {
            startClose { super.close() }
        })
    }

    override fun close() = startClose { super.close() }

    private fun startClose(action: () -> Unit) {
        if (!isClosing) {
            isClosing = true
            closeTime = System.currentTimeMillis()
            pendingClose = action
        }
    }

    private fun ease(t: Float) = t * t * (3f - 2f * t)

    private fun openProgress(): Float =
        ease(((System.currentTimeMillis() - openTime) / 260f).coerceIn(0f, 1f))

    private fun closeProgress(): Float =
        ease(((System.currentTimeMillis() - closeTime) / 220f).coerceIn(0f, 1f))

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xC0080810.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)

        val px = panelX; val py = panelY; val pw = panelW; val ph = panelH
        val titleAreaH = (ph * 0.50).toInt()
        val scale = if (isClosing) 1f - 0.3f * closeProgress() else 0.7f + 0.3f * openProgress()

        val mx = context.matrices
        mx.pushMatrix()
        // Scale entire GUI (panel + title text + buttons) from screen centre
        mx.scaleAround(scale, scale, width / 2f, height / 2f)

        // Panel background + borders
        context.fill(px, py, px + pw, py + ph, GuiHelper.PANEL_BG)
        context.fill(px, py, px + pw, py + 2, GuiHelper.PANEL_ACCENT)
        context.fill(px,          py, px + 1,  py + ph, GuiHelper.PANEL_BORDER)
        context.fill(px + pw - 1, py, px + pw, py + ph, GuiHelper.PANEL_BORDER)
        context.fill(px, py + ph - 1, px + pw, py + ph, GuiHelper.PANEL_BORDER)
        context.fill(px + 20, py + titleAreaH, px + pw - 20, py + titleAreaH + 1, GuiHelper.PANEL_BORDER)

        // Title text (inside scaleAround so it moves with the panel)
        drawMnkTitle(context, px, py, pw, ph, titleAreaH)

        // Buttons (inside scaleAround so they animate with the panel)
        super.render(context, mouseX, mouseY, delta)

        mx.popMatrix()

        // Fade overlay always covers the full screen
        drawFadeOverlay(context)
    }

    /**
     * Renders "M(eow) N(yaa) K(itty)" with scaled characters + "By MeowMeowMachine".
     * Uses Matrix3x2fStack pushMatrix/translate/scale per part.
     * Called INSIDE the outer scaleAround, so coordinates are in model space.
     */
    private fun drawMnkTitle(context: DrawContext, px: Int, py: Int, pw: Int, ph: Int, titleAreaH: Int) {
        val tr    = textRenderer
        val LARGE = 2.5f
        val SMALL = 0.85f
        val SUB   = 0.70f
        val mx    = context.matrices

        data class Part(val text: String, val scale: Float, val color: Int)
        val parts = listOf(
            Part("M",       LARGE, 0x4488EE),
            Part("eow ",    SMALL, 0x3377DD),
            Part("N",       LARGE, 0x4488EE),
            Part("yaaa ",   SMALL, 0x3377DD),
            Part("K",       LARGE, 0x4488EE),
            Part("ittyyy",  SMALL, 0x3377DD),
        )

        val totalW = parts.sumOf { (t, s, _) -> (tr.getWidth(t) * s).toDouble() }.toFloat()
        val rowH   = (8f * LARGE).toInt()
        val subH   = (8f * SUB).toInt()
        val blockH = rowH + 10 + subH
        val rowY   = py + (titleAreaH - blockH) / 2

        var x = px + pw / 2f - totalW / 2f
        for ((text, scale, color) in parts) {
            // Baseline alignment: bottom of all chars on the same imaginary line
            val vertOff = 8f * LARGE - 8f * scale
            mx.pushMatrix()
            mx.translate(x, rowY + vertOff)
            mx.scale(scale, scale)
            // Use DrawnTextConsumer – the only reliable text API in MC 1.21.11
            val tw = tr.getWidth(text)
            context.getTextConsumer().text(Text.literal(text).withColor(color), 0, tw, 0, 8)
            mx.popMatrix()
            x += tr.getWidth(text) * scale
        }

        // Subtitle
        val sub  = "By MeowMeowMachine"
        val subW = tr.getWidth(sub) * SUB
        mx.pushMatrix()
        mx.translate(px + pw / 2f - subW / 2f, (rowY + rowH + 10).toFloat())
        mx.scale(SUB, SUB)
        val stw = tr.getWidth(sub)
        context.getTextConsumer().text(Text.literal(sub).withColor(0x6677AA), 0, stw, 0, 8)
        mx.popMatrix()
    }

    private fun drawFadeOverlay(context: DrawContext) {
        if (isClosing) {
            val t = closeProgress()
            val alpha = (t * 220).toInt()
            if (alpha > 0) context.fill(0, 0, width, height, alpha shl 24)
            if (t >= 1f) pendingClose?.invoke()
        } else {
            val t = openProgress()
            val alpha = ((1f - t) * 220).toInt()
            if (alpha > 0) context.fill(0, 0, width, height, alpha shl 24)
        }
    }

    override fun shouldPause() = false
}