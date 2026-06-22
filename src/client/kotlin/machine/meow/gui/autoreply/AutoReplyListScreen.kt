package machine.meow.gui.autoreply

import machine.meow.config.AutoReplyRule
import machine.meow.config.ModConfig
import machine.meow.gui.CustomButtonWidget
import machine.meow.gui.GuiHelper
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

class AutoReplyListScreen(private val parent: Screen) : Screen(Text.literal("Auto-Reply Rules")) {

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

    override fun init() {
        openTime  = System.currentTimeMillis()
        closeTime = -1L
        val px = panelX; val py = panelY; val pw = panelW; val ph = panelH
        val cx = px + pw / 2

        // Global enable/disable toggle
        val isOn = ModConfig.autoReplyEnabled
        addDrawableChild(CustomButtonWidget.toggle(cx - 100, py + 38, 200, 22, isOn, "Auto-Reply") { btn ->
            ModConfig.autoReplyEnabled = !ModConfig.autoReplyEnabled
            ModConfig.save()
            btn.applyToggle(ModConfig.autoReplyEnabled, "Auto-Reply")
        })

        // Rule rows
        var y = py + 70
        ModConfig.autoReplyRules.forEachIndexed { index, rule ->
            val rowY = y
            val prefix = if (rule.enabled) "§a✔  " else "§c✗  "
            addDrawableChild(CustomButtonWidget.of(px + 16, rowY, pw - 116, 22, "$prefix${rule.name}") {
                client?.setScreen(AutoReplyEditScreen(this, rule))
            })
            addDrawableChild(CustomButtonWidget.red(px + pw - 96, rowY, 80, 22, "✕ Delete") {
                ModConfig.autoReplyRules.removeAt(index)
                ModConfig.save()
                client?.setScreen(AutoReplyListScreen(parent))
            })
            y += 26
        }

        addDrawableChild(CustomButtonWidget.of(cx - 75, py + ph - 56, 150, 22, "+ Add Rule") {
            val newRule = machine.meow.config.AutoReplyRule()
            ModConfig.autoReplyRules.add(newRule)
            ModConfig.save()
            client?.setScreen(AutoReplyEditScreen(this, newRule))
        })
        addDrawableChild(CustomButtonWidget.of(cx - 55, py + ph - 28, 110, 22, "← Back") {
            fadeNav { client?.setScreen(parent) }
        })
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xC0080810.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val a = anim()
        renderBackground(context, mouseX, mouseY, delta)
        GuiHelper.drawPanelWithTitle(context, textRenderer, panelX, panelY, panelW, panelH, "Auto-Reply Rules")

        // "Rules" sub-header
        GuiHelper.drawTextLeft(context, textRenderer, "Rules", panelX + 16, panelY + 61, GuiHelper.TEXT_SECONDARY)

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

    override fun shouldPause() = false
}