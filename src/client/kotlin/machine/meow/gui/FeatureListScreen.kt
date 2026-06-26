package machine.meow.gui

import machine.meow.config.ModConfig
import machine.meow.gui.autoreply.AutoReplyListScreen
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.Click
import net.minecraft.text.Text

/**
 * Shows all available features as compact cards arranged in a responsive grid.
 * Cards that overflow are accessible via mouse-wheel scrolling;
 * content is always clipped to the panel bounds.
 */
class FeatureListScreen(private val parent: Screen) : Screen(Text.literal("Features")) {

    private var openTime  = 0L
    private var closeTime = -1L
    private var pendingNav: (() -> Unit)? = null

    private fun anim() = ((System.currentTimeMillis() - openTime) / 280f).coerceIn(0f, 1f)
    private fun fadeNav(action: () -> Unit) {
        if (closeTime < 0) { closeTime = System.currentTimeMillis(); pendingNav = action }
    }

    private val panelW get() = (width  * 0.80).toInt()
    private val panelH get() = (height * 0.80).toInt()
    private val panelX get() = (width  - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    // ── Feature descriptor ───────────────────────────────────────────────────
    private inner class FeatureEntry(
        val name: String,
        val description: List<String>,
        val isEnabled: () -> Boolean,
        val onToggle: () -> Unit,
        val onSettings: (() -> Unit)? = null
    )

    private val features: List<FeatureEntry> by lazy {
        listOf(
            FeatureEntry(
                name        = "Auto-Reply",
                description = listOf("Automatically replies to chat messages", "using configurable regex rules & channels."),
                isEnabled   = { ModConfig.autoReplyEnabled },
                onToggle    = { ModConfig.autoReplyEnabled = !ModConfig.autoReplyEnabled; ModConfig.save() },
                onSettings  = { client?.setScreen(AutoReplyListScreen(this@FeatureListScreen)) }
            )
            // ← Add more features here
        )
    }

    // ── Layout constants ─────────────────────────────────────────────────────
    private val CARD_W = 240
    private val CARD_H = 90
    private val GAP    = 14

    // ── Per-frame layout info (recalculated in init + mouseScrolled) ─────────
    private data class CardLayout(val x: Int, val y: Int, val w: Int, val h: Int, val idx: Int)
    private val cards = mutableListOf<CardLayout>()

    // Scrolling state
    private var scrollOffset = 0
    private var maxScroll    = 0

    // Content area bounds (set in init)
    private var contentX = 0; private var contentY = 0
    private var contentW = 0; private var contentH = 0

    // Hit-test areas (absolute, unscrolled)
    private data class HitZone(val x: Int, val y: Int, val w: Int, val h: Int,
                                val sound: GuiSoundType, val action: () -> Unit)
    private val hitZones = mutableListOf<HitZone>()

    override fun init() {
        openTime  = System.currentTimeMillis()
        closeTime = -1L
        scrollOffset = 0

        val px = panelX; val py = panelY; val pw = panelW; val ph = panelH

        contentX = px + 20
        contentY = py + 44
        contentW = pw - 40
        contentH = ph - 80   // reserve space for Back button

        buildLayout()

        // Only the Back button is a widget (fixed position, outside scroll area)
        addDrawableChild(CustomButtonWidget.exit(px + pw / 2 - 55, py + ph - 28, 110, 22, "← Back") {
            fadeNav { client?.setScreen(parent) }
        })
    }

    private fun buildLayout() {
        cards.clear()
        hitZones.clear()

        val maxCols = maxOf(1, (contentW + GAP) / (CARD_W + GAP))
        val cols    = minOf(features.size, minOf(maxCols, 3))
        val rows    = (features.size + cols - 1) / cols
        val gridW   = cols * CARD_W + (cols - 1) * GAP
        val gridH   = rows * CARD_H + (rows - 1) * GAP

        val startX  = contentX + (contentW - gridW) / 2
        val startY  = contentY + GAP   // small top padding

        maxScroll = maxOf(0, startY + gridH - (contentY + contentH))

        features.forEachIndexed { i, feature ->
            val col = i % cols
            val row = i / cols
            val cx  = startX + col * (CARD_W + GAP)
            val cy  = startY + row * (CARD_H + GAP)
            cards.add(CardLayout(cx, cy, CARD_W, CARD_H, i))

            // Toggle hit-zone (top-right of card)
            hitZones.add(HitZone(cx + CARD_W - 56, cy + 8, 52, 18, GuiSoundType.BOOL) {
                feature.onToggle()
            })
            // Settings hit-zone (bottom-right of card)
            feature.onSettings?.let { action ->
                hitZones.add(HitZone(cx + CARD_W - 72, cy + CARD_H - 26, 68, 19, GuiSoundType.HEADER) {
                    action()
                })
            }
        }
    }

    // ── Input ────────────────────────────────────────────────────────────────
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // Only intercept scroll when the pointer is inside the scrollable content area
        // and there is actually something to scroll.
        val inContent = mouseX >= contentX && mouseX <= contentX + contentW &&
                        mouseY >= contentY && mouseY <= contentY + contentH
        if (!inContent || maxScroll == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        scrollOffset = (scrollOffset - (verticalAmount * 16).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (super.mouseClicked(click, doubled)) return true
        val mx = click.x().toInt(); val my = click.y().toInt()
        // Only process clicks inside the content area
        if (mx < contentX || mx > contentX + contentW || my < contentY || my > contentY + contentH) return false
        for (hz in hitZones) {
            val drawY = hz.y - scrollOffset
            if (mx >= hz.x && mx < hz.x + hz.w && my >= drawY && my < drawY + hz.h) {
                client?.let { SoundHelper.play(it, hz.sound) }
                hz.action()
                return true
            }
        }
        return false
    }

    // ── Rendering ────────────────────────────────────────────────────────────
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xC0080810.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val a = anim()
        renderBackground(context, mouseX, mouseY, delta)
        GuiHelper.drawPanelWithTitle(context, textRenderer, panelX, panelY, panelW, panelH, "Features")

        val mX = mouseX; val mY = mouseY
        for (cl in cards) {
            val drawY = cl.y - scrollOffset
            if (drawY + cl.h <= contentY || drawY >= contentY + contentH) continue

            val f = features[cl.idx]

            // Scissor = intersection of card + content area.
            // MC 1.21.11 enableScissor REPLACES (not stacks), so we compute the
            // intersection manually instead of nesting two enableScissor calls.
            val sx1 = maxOf(cl.x, contentX)
            val sy1 = maxOf(drawY, contentY)
            val sx2 = minOf(cl.x + cl.w, contentX + contentW)
            val sy2 = minOf(drawY + cl.h, contentY + contentH)
            context.enableScissor(sx1, sy1, sx2, sy2)

            GuiHelper.drawCard(context, cl.x, drawY, cl.w, cl.h, f.isEnabled())

            // Feature name
            GuiHelper.drawTextLeft(context, textRenderer,
                Text.literal(f.name).withColor(GuiHelper.TEXT_PRIMARY), cl.x + 10, drawY + 12)
            // Description – auto-clipped to card width by scissor
            f.description.forEachIndexed { di, line ->
                GuiHelper.drawTextLeft(context, textRenderer,
                    Text.literal(line).withColor(GuiHelper.TEXT_SECONDARY), cl.x + 10, drawY + 28 + di * 11)
            }

            // Toggle button
            val tBtnX = cl.x + cl.w - 56; val tBtnY = drawY + 8; val tBtnW = 52; val tBtnH = 18
            val tHov = mX in tBtnX until tBtnX + tBtnW && mY in tBtnY until tBtnY + tBtnH
            drawManualToggle(context, tBtnX, tBtnY, tBtnW, tBtnH, f.isEnabled(), tHov)

            // Settings button
            if (f.onSettings != null) {
                val sBtnX = cl.x + cl.w - 72; val sBtnY = drawY + cl.h - 26; val sBtnW = 68; val sBtnH = 19
                val sHov = mX in sBtnX until sBtnX + sBtnW && mY in sBtnY until sBtnY + sBtnH
                drawManualBtn(context, sBtnX, sBtnY, sBtnW, sBtnH, "⚙ Settings", sHov)
            }

            context.disableScissor()
        }

        // Scrollbar rendered with a fresh content-area scissor
        if (maxScroll > 0) {
            context.enableScissor(contentX, contentY, contentX + contentW + 12, contentY + contentH)
            drawScrollBar(context)
            context.disableScissor()
        }

        super.render(context, mouseX, mouseY, delta)

        if (closeTime >= 0) {
            val t = ((System.currentTimeMillis() - closeTime) / 200f).coerceIn(0f, 1f)
            val alpha = (t * 220).toInt()
            if (alpha > 0) context.fill(0, 0, width, height, alpha shl 24)
            if (t >= 1f) pendingNav?.invoke()
        } else {
            GuiHelper.drawFade(context, width, height, a)
        }
    }

    /** Green/red toggle button drawn manually (for scrollable card area). */
    private fun drawManualToggle(context: DrawContext, x: Int, y: Int, w: Int, h: Int, isOn: Boolean, hovered: Boolean) {
        val bg = if (isOn) 0xFF0A1A0C.toInt() else 0xFF1A0A0A.toInt()
        val bc = if (isOn) { if (hovered) CustomButtonWidget.GREEN_HOVER else CustomButtonWidget.GREEN_BORDER }
                 else      { if (hovered) CustomButtonWidget.RED_HOVER   else CustomButtonWidget.RED_BORDER   }
        context.fill(x, y, x + w, y + h, bg)
        context.fill(x, y, x + w, y + 1, bc);  context.fill(x, y + h - 1, x + w, y + h, bc)
        context.fill(x, y, x + 1, y + h, bc);  context.fill(x + w - 1, y, x + w, y + h, bc)
        val label = if (isOn) "§a✔ ON" else "§c✗ OFF"
        context.getTextConsumer().text(Text.literal(label), x, x + w, y, y + h)
    }

    /** Plain dark button drawn manually. */
    private fun drawManualBtn(context: DrawContext, x: Int, y: Int, w: Int, h: Int, label: String, hovered: Boolean) {
        val bg = if (hovered) 0xFF1C1C3A.toInt() else 0xFF0E0E20.toInt()
        val bc = if (hovered) CustomButtonWidget.BLUE_HOVER else CustomButtonWidget.BLUE_BORDER
        context.fill(x, y, x + w, y + h, bg)
        context.fill(x, y, x + w, y + 1, bc);  context.fill(x, y + h - 1, x + w, y + h, bc)
        context.fill(x, y, x + 1, y + h, bc);  context.fill(x + w - 1, y, x + w, y + h, bc)
        context.getTextConsumer().text(Text.literal(label).withColor(0xCCDDFF), x, x + w, y, y + h)
    }

    /** Thin scroll bar on the right edge of the content area. */
    private fun drawScrollBar(context: DrawContext) {
        val barX = contentX + contentW + 4
        val barH = contentH
        val barY = contentY
        context.fill(barX, barY, barX + 3, barY + barH, 0x33FFFFFF.toInt())
        // Ensure thumb has a sensible minimum and never exceeds the bar height.
        val rawThumb = (contentH.toFloat() / maxOf(1, contentH + maxScroll) * barH).toInt()
        val thumbH = rawThumb.coerceIn(16, maxOf(16, barH - 4))
        val thumbY = if (maxScroll > 0) {
            val t = (scrollOffset.toFloat() / maxScroll) * (barH - thumbH)
            (barY + t.toInt()).coerceIn(barY, barY + barH - thumbH)
        } else {
            barY
        }
        context.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xAA5566EE.toInt())
    }

    override fun shouldPause() = false
}

