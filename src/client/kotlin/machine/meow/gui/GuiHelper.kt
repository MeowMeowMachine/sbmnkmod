package machine.meow.gui

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Shared drawing utilities for all Mnk GUI screens.
 *
 * IMPORTANT (MC 1.21.11): DrawContext.drawTextWithShadow / drawCenteredTextWithShadow
 * do NOT render visible text in custom Screen subclasses. All text must go through
 * DrawContext.getTextConsumer() → DrawnTextConsumer.text(text, x1, x2, y1, y2).
 */
object GuiHelper {

    // ── Colours ──────────────────────────────────────────────────────────────
    val PANEL_BG       = 0xEE090912.toInt()
    val PANEL_HEADER   = 0xFF111128.toInt()
    val PANEL_ACCENT   = 0xFF3344CC.toInt()
    val PANEL_BORDER   = 0xFF252555.toInt()
    val TITLE_COLOR    = 0xCCDDFF        // RGB for withColor()
    val CARD_BG        = 0xFF0C0C1E.toInt()
    val CARD_BORDER    = 0xFF232348.toInt()
    val CARD_ON_ACCENT = 0xFF1E7A35.toInt()
    val CARD_OFF_ACCENT= 0xFF7A1E1E.toInt()
    val TEXT_PRIMARY   = 0xEEEEFF        // RGB for withColor()
    val TEXT_SECONDARY = 0x7788AA        // RGB for withColor()

    // ── Text helpers (DrawnTextConsumer – the only working text API in 1.21.11) ──

    /**
     * Draws text LEFT-ALIGNED starting at (x, y).
     * Width is calculated from the text so centering in a tight range = left-align.
     */
    fun drawTextLeft(context: DrawContext, tr: TextRenderer, text: Text, x: Int, y: Int) {
        val w = tr.getWidth(text)
        val fh = tr.fontHeight
        context.getTextConsumer().text(text, x, x + w, y, y + fh)
    }

    /** Convenience overload with a raw string + RGB colour. */
    fun drawTextLeft(context: DrawContext, tr: TextRenderer, str: String, x: Int, y: Int, color: Int = TEXT_PRIMARY) =
        drawTextLeft(context, tr, Text.literal(str).withColor(color), x, y)

    /**
     * Draws text CENTRED within the horizontal range [x1, x2] at vertical position y.
     */
    fun drawTextCentered(context: DrawContext, text: Text, x1: Int, x2: Int, y: Int) {
        // NOTE: no TextRenderer available here — consumer expects the vertical
        // bounds. 9 was a hardcoded fallback; use a best-effort measurement by
        // querying Minecraft's textRenderer when possible.
        val tr = net.minecraft.client.MinecraftClient.getInstance()?.textRenderer
        val fh = tr?.fontHeight ?: 9
        context.getTextConsumer().text(text, x1, x2, y, y + fh)
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    fun drawPanel(context: DrawContext, px: Int, py: Int, pw: Int, ph: Int) {
        context.fill(px, py, px + pw, py + ph, PANEL_BG)
        context.fill(px, py, px + pw, py + 30, PANEL_HEADER)
        context.fill(px, py, px + pw, py + 2, PANEL_ACCENT)
        context.fill(px,          py, px + 1,  py + ph, PANEL_BORDER)
        context.fill(px + pw - 1, py, px + pw, py + ph, PANEL_BORDER)
        context.fill(px,          py + ph - 1, px + pw, py + ph, PANEL_BORDER)
    }

    /** Draws panel background + a centred title via DrawnTextConsumer. */
    fun drawPanelWithTitle(context: DrawContext, tr: TextRenderer,
                           px: Int, py: Int, pw: Int, ph: Int, title: String) {
        drawPanel(context, px, py, pw, ph)
        // Centred in the header strip [py, py+30]
        drawTextCentered(context, Text.literal(title).withColor(TITLE_COLOR), px, px + pw, py + 11)
    }

    /** Draws a feature card background, border, and accent stripe. */
    fun drawCard(context: DrawContext, cx: Int, cy: Int, cw: Int, ch: Int, enabled: Boolean) {
        context.fill(cx, cy, cx + cw, cy + ch, CARD_BG)
        context.fill(cx,          cy,          cx + cw, cy + 1,  CARD_BORDER)
        context.fill(cx,          cy + ch - 1, cx + cw, cy + ch, CARD_BORDER)
        context.fill(cx,          cy,          cx + 1,  cy + ch, CARD_BORDER)
        context.fill(cx + cw - 1, cy,          cx + cw, cy + ch, CARD_BORDER)
        context.fill(cx, cy, cx + cw, cy + 2, if (enabled) CARD_ON_ACCENT else CARD_OFF_ACCENT)
    }

    /** Black → transparent fade-in overlay. */
    fun drawFade(context: DrawContext, w: Int, h: Int, animProgress: Float) {
        val alpha = ((1f - animProgress) * 220).toInt()
        if (alpha > 0) context.fill(0, 0, w, h, alpha shl 24)
    }
}
