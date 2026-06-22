package machine.meow.gui.autoreply

import machine.meow.config.AutoReplyRule
import machine.meow.config.ModConfig
import machine.meow.config.ReplyOption
import machine.meow.gui.CustomButtonWidget
import machine.meow.gui.GuiHelper
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

class AutoReplyEditScreen(
    private val parent: Screen,
    private val rule: AutoReplyRule
) : Screen(Text.literal("Edit Rule")) {

    private val openTime = System.currentTimeMillis()
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

    private lateinit var nameField: TextFieldWidget
    private lateinit var triggerField: TextFieldWidget
    private val optionTextFields   = mutableListOf<TextFieldWidget>()
    private val optionWeightFields = mutableListOf<TextFieldWidget>()
    /** Y-Positionen jeder Options-Zeile, für die Chance-Anzeige im render(). */
    private val optionRowYs = mutableListOf<Int>()

    private lateinit var activeToggleBtn: CustomButtonWidget

    // Label Y positions
    private var labelNameY    = 0
    private var labelTriggerY = 0
    private var labelChanY    = 0
    private var labelOptY     = 0

    /** Summe aller Weight-Felder. */
    private fun currentWeightSum() = optionWeightFields.sumOf { it.text.toIntOrNull() ?: 0 }

    // Spalten-Layout für Options-Zeilen
    // [  Reply text field (fieldW-150)  ] [Weight 64px] [chance 48px] [Del 36px]
    private val COL_DEL_W    = 36
    private val COL_CHANCE_W = 48
    private val COL_WEIGHT_W = 64
    private val COL_GAP      = 4
    // X-Offsets vom rechten Ende des fieldX+fieldW:
    private val xDel     get() = panelX + 16 + (panelW - 32) - COL_DEL_W
    private val xChance  get() = xDel - COL_GAP - COL_CHANCE_W
    private val xWeight  get() = xChance - COL_GAP - COL_WEIGHT_W
    private val textFW   get() = xWeight - COL_GAP - (panelX + 16)

    override fun init() {
        val px = panelX; val py = panelY; val pw = panelW; val ph = panelH
        val fieldX = px + 16
        var y = py + 46

        // ── Name field ─────────────────────────────────────────────────────
        labelNameY = y - 9
        nameField = TextFieldWidget(textRenderer, fieldX, y, pw - 32, 20, Text.literal("Name"))
        nameField.text = rule.name
        nameField.setMaxLength(64)
        // Kein setSuggestion – Placeholder wird manuell gerendert (kein Overflow)
        addDrawableChild(nameField)
        y += 28

        // ── Trigger field ───────────────────────────────────────────────────
        labelTriggerY = y - 9
        triggerField = TextFieldWidget(textRenderer, fieldX, y, pw - 32, 20, Text.literal("Trigger (regex)"))
        triggerField.text = rule.triggerRegex
        triggerField.setMaxLength(128)
        addDrawableChild(triggerField)
        y += 32

        // ── Channel toggles ─────────────────────────────────────────────────
        labelChanY = y - 9
        data class Chan(val label: String, var state: Boolean, val setter: (Boolean) -> Unit)
        val channels = listOf(
            Chan("Guild", rule.onGuild) { v -> rule.onGuild = v },
            Chan("Party", rule.onParty) { v -> rule.onParty = v },
            Chan("Co-op", rule.onCoop)  { v -> rule.onCoop  = v },
            Chan("All",   rule.onAll)   { v -> rule.onAll   = v }
        )
        val toggleW = 72; val toggleGap = 6
        val totalChanW = channels.size * toggleW + (channels.size - 1) * toggleGap
        var tx = fieldX + ((pw - 32) - totalChanW) / 2
        for (ch in channels) {
            addDrawableChild(CustomButtonWidget.toggle(tx, y, toggleW, 20, ch.state, ch.label) { btn ->
                ch.state = !ch.state; ch.setter(ch.state); btn.applyToggle(ch.state, ch.label)
            })
            tx += toggleW + toggleGap
        }
        y += 28

        // ── Active toggle ───────────────────────────────────────────────────
        activeToggleBtn = CustomButtonWidget.toggle(fieldX, y, pw - 32, 20, rule.enabled, "Active") { btn ->
            rule.enabled = !rule.enabled
            btn.applyToggle(rule.enabled, "Active")
        }
        addDrawableChild(activeToggleBtn)
        y += 28

        // ── Case sensitivity toggle ─────────────────────────────────────────
        val caseLabel = if (rule.caseSensitive) "Case Sensitive" else "Ignore Case"
        addDrawableChild(CustomButtonWidget.toggle(fieldX, y, pw - 32, 20, rule.caseSensitive, caseLabel) { btn ->
            rule.caseSensitive = !rule.caseSensitive
            btn.applyToggle(rule.caseSensitive, if (rule.caseSensitive) "Case Sensitive" else "Ignore Case")
        })
        y += 30

        // ── Reply options ───────────────────────────────────────────────────
        labelOptY = y - 9
        optionTextFields.clear()
        optionWeightFields.clear()
        optionRowYs.clear()
        for ((i, option) in rule.options.withIndex()) {
            val rowY = y
            optionRowYs.add(rowY)

            // Text field – color managed per-frame in render() based on focus state
            val tf = TextFieldWidget(textRenderer, fieldX, rowY, textFW, 20, Text.literal("Reply text"))
            tf.text = option.text
            tf.setMaxLength(256)
            addDrawableChild(tf)
            optionTextFields.add(tf)

            // Weight field – digits only, range [0, Int.MAX_VALUE]
            val wf = TextFieldWidget(textRenderer, xWeight, rowY, COL_WEIGHT_W, 20, Text.literal("Weight"))
            wf.text = option.weight.coerceAtLeast(0).toString()
            wf.setMaxLength(10)   // Int.MAX_VALUE = 2147483647 → 10 digits
            // Only allow digit characters; reject minus / letters / symbols
            wf.setTextPredicate { s ->
                s.isEmpty() || (s.all { c -> c.isDigit() } &&
                    (s.toLongOrNull() ?: Long.MAX_VALUE) <= Int.MAX_VALUE)
            }
            wf.setChangedListener { t -> wf.setSuggestion(if (t.isEmpty()) "0" else "") }
            addDrawableChild(wf)
            optionWeightFields.add(wf)

            val idx = i
            addDrawableChild(CustomButtonWidget.delete(xDel, rowY, COL_DEL_W, 20, "✕") {
                saveFields(); rule.options.removeAt(idx); ModConfig.save()
                client?.setScreen(AutoReplyEditScreen(parent, rule))
            })
            y += 26
        }

        // ── Add reply option ────────────────────────────────────────────────
        addDrawableChild(CustomButtonWidget.of(fieldX, y + 4, pw - 32, 20, "+ Add Reply Option") {
            saveFields()
            rule.options.add(ReplyOption("", 10))
            ModConfig.save()
            client?.setScreen(AutoReplyEditScreen(parent, rule))
        })

        addDrawableChild(CustomButtonWidget.exit(fieldX, py + ph - 28, pw - 32, 22, "Save & Back") {
            saveFields(); ModConfig.save(); fadeNav { client?.setScreen(parent) }
        })
    }

    private fun saveFields() {
        rule.name         = nameField.text
        rule.triggerRegex = triggerField.text
        for (i in rule.options.indices) {
            if (i < optionTextFields.size)   rule.options[i].text   = optionTextFields[i].text
            if (i < optionWeightFields.size) rule.options[i].weight =
                optionWeightFields[i].text.toLongOrNull()
                    ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                    ?.toInt()
                    ?: rule.options[i].weight.coerceAtLeast(0)
        }
    }

    /**
     * Draws §3/§9/§c syntax highlighting for a sequence/command string.
     * §3 dark aqua  – text/command segments
     * §9 blue       – semicolons
     * §c light red  – delay numbers
     * Colours are passed as 24-bit RGB to getTextConsumer which ignores alpha.
     */
    private fun drawSyntaxHighlight(context: DrawContext, text: String, x: Int, y: Int, maxX: Int) {
        val COL_STR = 0x00AAAA   // §3 dark aqua
        val COL_SEP = 0x5555FF   // §9 blue
        val COL_NUM = 0xFF5555   // §c light red
        val y2 = y + 9

        if (!text.contains(';')) {
            context.getTextConsumer().text(Text.literal(text).withColor(COL_STR), x, maxX, y, y2)
            return
        }

        var curX = x
        val parts = text.split(";")
        for ((i, part) in parts.withIndex()) {
            if (curX >= maxX) break
            val isNum = part.trim().removeSurrounding("\"").toLongOrNull() != null
            val pw = textRenderer.getWidth(part)
            if (pw > 0) {
                context.getTextConsumer().text(
                    Text.literal(part).withColor(if (isNum) COL_NUM else COL_STR),
                    curX, minOf(curX + pw, maxX), y, y2
                )
                curX += pw
            }
            if (i < parts.size - 1 && curX < maxX) {
                val sw = textRenderer.getWidth(";")
                context.getTextConsumer().text(
                    Text.literal(";").withColor(COL_SEP),
                    curX, minOf(curX + sw, maxX), y, y2
                )
                curX += sw
            }
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xC0080810.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val a = anim()
        renderBackground(context, mouseX, mouseY, delta)
        GuiHelper.drawPanelWithTitle(context, textRenderer, panelX, panelY, panelW, panelH, "Edit Rule")

        // ── Section labels ──────────────────────────────────────────────────
        val lc = GuiHelper.TEXT_SECONDARY
        GuiHelper.drawTextLeft(context, textRenderer, "Name",          panelX + 16, labelNameY,    lc)
        GuiHelper.drawTextLeft(context, textRenderer, "Trigger Regex", panelX + 16, labelTriggerY, lc)
        GuiHelper.drawTextLeft(context, textRenderer, "Channels",      panelX + 16, labelChanY,    lc)

        // ── Reply Options Label + Chance-Header ─────────────────────────────
        val total = currentWeightSum()
        if (rule.options.isNotEmpty()) {
            val optLabel = "Reply Options  ·  Weight  [total: $total]"
            GuiHelper.drawTextLeft(context, textRenderer, optLabel, panelX + 16, labelOptY, lc)
            GuiHelper.drawTextLeft(context, textRenderer, "Chance", xChance, labelOptY, lc)
        }

        // ── [?] Info-Icon oben rechts ────────────────────────────────────────
        val infoStr  = "[?]"
        val infoStrW = textRenderer.getWidth(infoStr)
        val infoX    = panelX + panelW - infoStrW - 8
        val infoY    = panelY + 11
        val hovInfo  = mouseX in infoX..(infoX + infoStrW) && mouseY in infoY..(infoY + 9)
        context.getTextConsumer().text(
            Text.literal(infoStr).withColor(if (hovInfo) 0xFFFF88 else 0x7799BB),
            infoX, infoX + infoStrW, infoY, infoY + 9
        )

        // ── Per-frame field colour management ──────────────────────────────
        // setEditableColor needs 0xFF alpha (ARGB) to be visible in MC 1.21.11.
        // When focused  → plain cyan / white (user can see cursor & selection).
        // When unfocused with sequence/cmd → near-black (we draw coloured overlay).
        // When unfocused plain text         → normal white.
        for (tf in optionTextFields) {
            val t = tf.text
            val isSeq = t.startsWith("/") || t.contains(";")
            tf.setEditableColor(when {
                tf.isFocused && isSeq -> 0xFF55FFFF.toInt()   // focused, aqua
                tf.isFocused          -> 0xFFE0E0E0.toInt()   // focused, white
                isSeq                 -> 0xFF000000.toInt()   // unfocused: hide (overlay drawn below)
                else                  -> 0xFFE0E0E0.toInt()   // unfocused plain text
            })
        }

        super.render(context, mouseX, mouseY, delta)

        // ── Placeholder texts after super.render() ─────────────────────────
        // getTextConsumer().text() clips to [x1..x2], no scissor needed
        val hintColor = 0x666680
        if (nameField.text.isEmpty() && !nameField.isFocused) {
            context.getTextConsumer().text(
                Text.literal("Rule name").withColor(hintColor),
                nameField.x + 4, nameField.x + nameField.width - 4,
                nameField.y + 6, nameField.y + 15
            )
        }
        if (triggerField.text.isEmpty() && !triggerField.isFocused) {
            context.getTextConsumer().text(
                Text.literal("Trigger regex pattern").withColor(hintColor),
                triggerField.x + 4, triggerField.x + triggerField.width - 4,
                triggerField.y + 6, triggerField.y + 15
            )
        }

        // ── Per row: chance value, placeholder, syntax highlight ───────────
        if (rule.options.isNotEmpty()) {
            for (i in optionRowYs.indices) {
                val rowY = optionRowYs[i]
                val tf   = optionTextFields.getOrNull(i)
                val wf   = optionWeightFields.getOrNull(i)

                if (tf != null) {
                    val t = tf.text
                    when {
                        // Empty field → placeholder hint
                        t.isEmpty() && !tf.isFocused -> context.getTextConsumer().text(
                            Text.literal("Reply text").withColor(hintColor),
                            tf.x + 4, tf.x + tf.width - 4, tf.y + 6, tf.y + 15
                        )
                        // Non-focused sequence/command → §3/§9/§c syntax highlight overlay
                        !tf.isFocused && (t.startsWith("/") || t.contains(";")) ->
                            drawSyntaxHighlight(context, t, tf.x + 4, tf.y + 6, tf.x + tf.width - 4)
                    }
                }

                if (wf != null) {
                    val w   = wf.text.toIntOrNull() ?: 0
                    val txt = if (total > 0) "$w/$total" else "–"
                    val tw  = textRenderer.getWidth(txt)
                    val cx  = xChance + (COL_CHANCE_W - tw) / 2
                    context.getTextConsumer().text(
                        Text.literal(txt).withColor(0xAABBCC),
                        cx, cx + tw + 1, rowY + 6, rowY + 15
                    )
                }
            }
        }

        // ── Tooltip für [?] Icon ─────────────────────────────────────────────
        if (hovInfo) {
            val tip = listOf(
                Text.literal("§eSequence Format§r  §8(Reply Text field)"),
                Text.literal(""),
                Text.literal("§7Simple:  §fresponse text"),
                Text.literal("§7Sequence:  §3\"cmd\"§9;§cdelay§9;§3\"cmd\"§9;§cdelay§9;§3..."),
                Text.literal(""),
                Text.literal("§7• §cdelay §7= ticks  §8(20 = 1 s,  10 = 0.5 s)"),
                Text.literal("§7• Text with spaces: §3\"hello world\"  §8(wrap in quotes)"),
                Text.literal("§7• §f/ §7at start → executed as Minecraft command"),
                Text.literal("§7• §f/say <msg> §7→ sends <msg> to the §ftriggering channel"),
                Text.literal("§8  Guild trigger → /gc msg  ·  Party → /pc msg  ·  All → msg"),
                Text.literal("§7• §f\$ign §7→ replaced with the sender's IGN"),
                Text.literal(""),
                Text.literal("§eExamples§r:"),
                Text.literal("§3\"say hi\"§9;§c20§9;§3\"say whats up\""),
                Text.literal("§8→ 0 s: §3say hi  §8· 1 s: §3say whats up  §8(plain chat)"),
                Text.literal(""),
                Text.literal("§3\"/say hi\"§9;§c20§9;§3\"/say whats up\""),
                Text.literal("§8→ same but routed to the triggering channel §8(Guild/Party/…)")
            )
            context.drawTooltip(textRenderer, tip, mouseX, mouseY)
        }

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

    override fun close() {
        saveFields(); ModConfig.save(); fadeNav { client?.setScreen(parent) }
    }
}

