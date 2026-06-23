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

    // ── New feature fields ──────────────────────────────────────────────────
    private lateinit var cooldownField: TextFieldWidget
    /** Nullable – only added to screen when rule.preventLoops == true */
    private var loopSecondsField: TextFieldWidget? = null

    /** Verhindert, dass close() saveFields() nochmals aufruft. */
    private var suppressSaveOnClose = false

    // Label Y positions
    private var labelNameY      = 0
    private var labelTriggerY   = 0
    private var labelChanY      = 0
    private var labelCooldownY  = 0
    private var labelPreventY   = 0
    private var labelOptY       = 0

    /** Summe aller Weight-Felder. */
    private fun currentWeightSum() = optionWeightFields.sumOf { it.text.toIntOrNull() ?: 0 }

    // Spalten-Layout für Options-Zeilen
    private val COL_DEL_W    = 36
    private val COL_CHANCE_W = 48
    private val COL_WEIGHT_W = 64
    private val COL_GAP      = 4
    private val xDel     get() = panelX + 16 + (panelW - 32) - COL_DEL_W
    private val xChance  get() = xDel - COL_GAP - COL_CHANCE_W
    private val xWeight  get() = xChance - COL_GAP - COL_WEIGHT_W
    private val textFW   get() = xWeight - COL_GAP - (panelX + 16)

    // Layout constants for new rows
    private val COOLDOWN_FIELD_W = 72
    private val PREVENT_TOGGLE_W = 176
    private val LOOP_SEC_FIELD_W = 60

    override fun init() {
        val px = panelX; val py = panelY; val pw = panelW; val ph = panelH
        val fieldX = px + 16
        var y = py + 46

        // ── Name field ─────────────────────────────────────────────────────
        labelNameY = y - 9
        nameField = TextFieldWidget(textRenderer, fieldX, y, pw - 32, 20, Text.literal("Name"))
        nameField.setMaxLength(64)
        nameField.text = rule.name
        addDrawableChild(nameField)
        y += 28

        // ── Trigger field ───────────────────────────────────────────────────
        labelTriggerY = y - 9
        triggerField = TextFieldWidget(textRenderer, fieldX, y, pw - 32, 20, Text.literal("Trigger (regex)"))
        triggerField.setMaxLength(128)
        triggerField.text = rule.triggerRegex
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

        // ── Reply Delay ─────────────────────────────────────────────────────
        // Compact row: label on the left, small field on the right
        labelCooldownY = y + 5   // vertically centred to the field
        val cooldownFieldX = px + pw - 16 - COOLDOWN_FIELD_W
        cooldownField = TextFieldWidget(textRenderer, cooldownFieldX, y, COOLDOWN_FIELD_W, 20, Text.literal("Cooldown"))
        cooldownField.setMaxLength(8)
        // Display as seconds with up to 2 decimal places, no trailing zeros
        val displaySecs = "%.2f".format(rule.cooldownMs / 1000.0).trimEnd('0').trimEnd('.')
        cooldownField.text = displaySecs
        cooldownField.setTextPredicate { s ->
            s.isEmpty() || s.matches(Regex("^\\d{0,5}(\\.\\d{0,3})?\$"))
        }
        addDrawableChild(cooldownField)
        y += 28

        // ── Prevent Loops toggle + seconds field ────────────────────────────
        labelPreventY = y
        addDrawableChild(CustomButtonWidget.toggle(fieldX, y, PREVENT_TOGGLE_W, 20, rule.preventLoops, "Prevent Loops") { btn ->
            saveFields()
            rule.preventLoops = !rule.preventLoops
            btn.applyToggle(rule.preventLoops, "Prevent Loops")
            suppressSaveOnClose = true
            client?.setScreen(AutoReplyEditScreen(parent, rule))
        })

        // Seconds field — only added when preventLoops is active
        if (rule.preventLoops) {
            val loopFieldX = fieldX + PREVENT_TOGGLE_W + 6
            val lsf = TextFieldWidget(textRenderer, loopFieldX, y, LOOP_SEC_FIELD_W, 20, Text.literal("Loop sec"))
            lsf.setMaxLength(4)
            lsf.text = rule.preventLoopSeconds.toString()
            lsf.setTextPredicate { s ->
                s.isEmpty() || (s.all { c -> c.isDigit() } && (s.toIntOrNull() ?: 0) in 1..9999)
            }
            addDrawableChild(lsf)
            loopSecondsField = lsf
        } else {
            loopSecondsField = null
        }
        y += 30

        // ── Reply options ───────────────────────────────────────────────────
        labelOptY = y - 9
        optionTextFields.clear()
        optionWeightFields.clear()
        optionRowYs.clear()
        for ((i, option) in rule.options.withIndex()) {
            val rowY = y
            optionRowYs.add(rowY)

            val tf = TextFieldWidget(textRenderer, fieldX, rowY, textFW, 20, Text.literal("Reply text"))
            tf.setMaxLength(256)
            tf.text = option.text
            addDrawableChild(tf)
            optionTextFields.add(tf)

            val wf = TextFieldWidget(textRenderer, xWeight, rowY, COL_WEIGHT_W, 20, Text.literal("Weight"))
            wf.setMaxLength(10)
            wf.text = option.weight.coerceAtLeast(0).toString()
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
                suppressSaveOnClose = true
                client?.setScreen(AutoReplyEditScreen(parent, rule))
            })
            y += 26
        }

        // ── Add reply option ────────────────────────────────────────────────
        addDrawableChild(CustomButtonWidget.of(fieldX, y + 4, pw - 32, 20, "+ Add Reply Option") {
            saveFields()
            rule.options.add(ReplyOption("", 10))
            ModConfig.save()
            suppressSaveOnClose = true
            client?.setScreen(AutoReplyEditScreen(parent, rule))
        })

        addDrawableChild(CustomButtonWidget.exit(fieldX, py + ph - 28, pw - 32, 22, "Save & Back") {
            saveFields(); ModConfig.save(); fadeNav { client?.setScreen(parent) }
        })
    }

    private fun saveFields() {
        rule.name         = nameField.text
        rule.triggerRegex = triggerField.text
        // Cooldown: parse as seconds → convert to ms
        rule.cooldownMs   = ((cooldownField.text.toDoubleOrNull() ?: 1.5) * 1000.0)
            .toLong().coerceAtLeast(0L)
        // Loop seconds
        loopSecondsField?.let { f ->
            rule.preventLoopSeconds = f.text.toIntOrNull()?.coerceIn(1, 9999)
                ?: rule.preventLoopSeconds
        }
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
     * §6 gold       – $ign placeholder
     */
    private fun drawSyntaxHighlight(context: DrawContext, text: String, x: Int, y: Int, maxX: Int) {
        val COL_STR = 0x00AAAA   // §3 dark aqua
        val COL_SEP = 0x5555FF   // §9 blue
        val COL_NUM = 0xFF5555   // §c light red
        val y2 = y + 9

        if (!text.contains(';')) {
            drawSegmentWithIgn(context, text, x, maxX, y, y2, COL_STR)
            return
        }

        var curX = x
        val parts = text.split(";")
        for ((i, part) in parts.withIndex()) {
            if (curX >= maxX) break
            val isNum = part.trim().removeSurrounding("\"").toLongOrNull() != null
            val pw = textRenderer.getWidth(part)
            if (pw > 0) {
                if (isNum) {
                    context.getTextConsumer().text(
                        Text.literal(part).withColor(COL_NUM),
                        curX, minOf(curX + pw, maxX), y, y2
                    )
                } else {
                    drawSegmentWithIgn(context, part, curX, minOf(curX + pw, maxX), y, y2, COL_STR)
                }
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

    /** Draws a single text segment with $ign highlighted in gold (§6 = 0xFFAA00). */
    private fun drawSegmentWithIgn(
        context: DrawContext, text: String,
        startX: Int, maxX: Int, y: Int, y2: Int, baseColor: Int
    ) {
        val IGN = "\$ign"
        val COL_IGN = 0xFFAA00  // §6 gold
        if (!text.contains(IGN, ignoreCase = true)) {
            context.getTextConsumer().text(Text.literal(text).withColor(baseColor), startX, maxX, y, y2)
            return
        }
        var curX = startX
        var remaining = text
        while (remaining.isNotEmpty() && curX < maxX) {
            val idx = remaining.indexOf(IGN, ignoreCase = true)
            if (idx < 0) {
                val w = textRenderer.getWidth(remaining)
                context.getTextConsumer().text(
                    Text.literal(remaining).withColor(baseColor),
                    curX, minOf(curX + w, maxX), y, y2
                )
                break
            }
            if (idx > 0) {
                val before = remaining.substring(0, idx)
                val w = textRenderer.getWidth(before)
                context.getTextConsumer().text(
                    Text.literal(before).withColor(baseColor),
                    curX, minOf(curX + w, maxX), y, y2
                )
                curX += w
            }
            val token = remaining.substring(idx, idx + IGN.length)
            val ignW = textRenderer.getWidth(token)
            context.getTextConsumer().text(
                Text.literal(token).withColor(COL_IGN),
                curX, minOf(curX + ignW, maxX), y, y2
            )
            curX += ignW
            remaining = remaining.substring(idx + IGN.length)
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

        // ── Reply Delay label (inline, left-aligned, vertically centred to field) ──
        GuiHelper.drawTextLeft(context, textRenderer, "Reply Delay (s)", panelX + 16, labelCooldownY, lc)

        // ── Prevent Loops: "s" unit label after the seconds field ───────────
        loopSecondsField?.let { lsf ->
            val sLabelX = lsf.x + lsf.width + 5
            context.getTextConsumer().text(
                Text.literal("s").withColor(lc),
                sLabelX, sLabelX + 14, lsf.y + 6, lsf.y + 15
            )
        }

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
        for (tf in optionTextFields) {
            val t = tf.text
            val isSeq = t.startsWith("/") || t.contains(";")
            tf.setEditableColor(when {
                tf.isFocused && isSeq -> 0xFF55FFFF.toInt()
                tf.isFocused          -> 0xFFE0E0E0.toInt()
                isSeq                 -> 0xFF000000.toInt()
                else                  -> 0xFFE0E0E0.toInt()
            })
        }

        super.render(context, mouseX, mouseY, delta)

        // ── Placeholder texts after super.render() ─────────────────────────
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

        // ── Placeholder for loop seconds field ──────────────────────────────
        loopSecondsField?.let { lsf ->
            if (lsf.text.isEmpty() && !lsf.isFocused) {
                context.getTextConsumer().text(
                    Text.literal("10").withColor(hintColor),
                    lsf.x + 4, lsf.x + lsf.width - 4, lsf.y + 6, lsf.y + 15
                )
            }
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
                        t.isEmpty() && !tf.isFocused -> context.getTextConsumer().text(
                            Text.literal("Reply text").withColor(hintColor),
                            tf.x + 4, tf.x + tf.width - 4, tf.y + 6, tf.y + 15
                        )
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
        if (!suppressSaveOnClose) { saveFields(); ModConfig.save() }
        suppressSaveOnClose = false
        fadeNav { client?.setScreen(parent) }
    }
}

