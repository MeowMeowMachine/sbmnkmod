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

    /**
     * Draws a simple syntax highlight for regex/complex trigger patterns.
     * - Parentheses: orange
     * - Character classes: green
     * - Quantifiers (* + ? { } ): red
     * - Alternation '|' : blue
     * - Escapes (\\): gray
     * - Macros like $name: gold
     */
    private fun drawRegexHighlight(context: DrawContext, text: String, x: Int, y: Int, maxX: Int) {
        val COL_PAREN = 0xFFAA66
        val COL_CLASS = 0x55AA55
        val COL_QUANT = 0xFF5555
        val COL_ALT   = 0x5555FF
        val COL_ESC   = 0x999999
        val COL_MACRO = 0xFFAA00
        val COL_TEXT  = 0xEEEEFF

        // build macro colour map for this template so macros are coloured consistently
        val macroMap = buildMacroColorMapFromTemplate(text)

        var i = 0
        var curX = x
        while (i < text.length && curX < maxX) {
            val ch = text[i]
            when {
                ch == '\\' -> {
                    val next = if (i + 1 < text.length) text.substring(i, i + 2) else text.substring(i, i + 1)
                    val w = textRenderer.getWidth(next)
                    context.getTextConsumer().text(Text.literal(next).withColor(COL_ESC), curX, minOf(curX + w, maxX), y, y + 9)
                    curX += w; i += next.length
                }
                ch == '[' -> {
                    val j = text.indexOf(']', i + 1).let { if (it < 0) text.length else it + 1 }
                    val seg = text.substring(i, j)
                    val w = textRenderer.getWidth(seg)
                    context.getTextConsumer().text(Text.literal(seg).withColor(COL_CLASS), curX, minOf(curX + w, maxX), y, y + 9)
                    curX += w; i = j
                }
                ch == '(' || ch == ')' -> {
                    val s = ch.toString()
                    val w = textRenderer.getWidth(s)
                    context.getTextConsumer().text(Text.literal(s).withColor(COL_PAREN), curX, minOf(curX + w, maxX), y, y + 9)
                    curX += w; i++
                }
                ch == '*' || ch == '+' || ch == '?' || ch == '{' || ch == '}' -> {
                    val s = ch.toString()
                    val w = textRenderer.getWidth(s)
                    context.getTextConsumer().text(Text.literal(s).withColor(COL_QUANT), curX, minOf(curX + w, maxX), y, y + 9)
                    curX += w; i++
                }
                ch == '|' -> {
                    val s = ch.toString()
                    val w = textRenderer.getWidth(s)
                    context.getTextConsumer().text(Text.literal(s).withColor(COL_ALT), curX, minOf(curX + w, maxX), y, y + 9)
                    curX += w; i++
                }
                ch == '$' -> {
                    val m = Regex("\\$[A-Za-z0-9_]+")
                    val mr = m.find(text.substring(i))
                    if (mr != null && mr.range.first == 0) {
                        val seg = mr.value
                        val w = textRenderer.getWidth(seg)
                        val col = macroMap[seg] ?: COL_MACRO
                        context.getTextConsumer().text(Text.literal(seg).withColor(col), curX, minOf(curX + w, maxX), y, y + 9)
                        curX += w; i += seg.length
                    } else {
                        val s = ch.toString(); val w = textRenderer.getWidth(s)
                        context.getTextConsumer().text(Text.literal(s).withColor(COL_MACRO), curX, minOf(curX + w, maxX), y, y + 9)
                        curX += w; i++
                    }
                }
                else -> {
                    var j = i
                    while (j < text.length && text[j] !in listOf('\\', '[', '(', ')', '*', '+', '?', '{', '}', '|', '$')) j++
                    val seg = text.substring(i, j)
                    val w = textRenderer.getWidth(seg)
                    context.getTextConsumer().text(Text.literal(seg).withColor(COL_TEXT), curX, minOf(curX + w, maxX), y, y + 9)
                    curX += w; i = j
                }
            }
        }
    }

    private fun buildMacroColorMapFromTemplate(template: String): Map<String, Int> {
        val MACRO_RE = Regex("\\$[A-Za-z0-9_]+")
        val palette = listOf(0xFFAA00, 0x00AAAA, 0x55AAFF, 0xFF66AA)
        val order = mutableListOf<String>()
        for (m in MACRO_RE.findAll(template)) {
            val tok = m.value
            if (!order.contains(tok)) order.add(tok)
        }
        val map = HashMap<String, Int>()
        for ((i, tok) in order.withIndex()) {
            val col = palette[i % palette.size]
            map[tok] = col
            // also map without leading '$' so lookups are flexible
            map[tok.removePrefix("$")] = col
        }
        return map
    }

    private val panelW get() = (width  * 0.80).toInt()
    // panelH is dynamic: starts at 80% of height and grows with number of reply options
    private var panelHVar = (height * 0.80).toInt()
    private val panelH get() = panelHVar
    private val panelX get() = (width  - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private lateinit var nameField: TextFieldWidget
    private lateinit var triggerField: TextFieldWidget
    private lateinit var triggerModeToggle: CustomButtonWidget
    private var triggerModeInfoX = 0
    private var triggerModeInfoY = 0
    private var preventInfoX = 0
    private var preventInfoY = 0
    // Save button rectangle (used to draw a top overlay to avoid underlying fields showing through)
    private var saveBtnX = 0
    private var saveBtnY = 0
    private var saveBtnW = 0
    private var saveBtnH = 0
    private val optionTextFields   = mutableListOf<TextFieldWidget>()
    private val optionWeightFields = mutableListOf<TextFieldWidget>()
    /** Y positions of each option row, used for drawing chance values in render(). */
    private val optionRowYs = mutableListOf<Int>()

    private lateinit var activeToggleBtn: CustomButtonWidget

    // ── New feature fields ──────────────────────────────────────────────────
    private lateinit var cooldownField: TextFieldWidget
    /** Nullable – only added to screen when rule.preventLoops == true */
    private var loopSecondsField: TextFieldWidget? = null

    /** Prevent close() from calling saveFields() again. */
    private var suppressSaveOnClose = false

    // Label Y positions
    private var labelNameY      = 0
    private var labelTriggerY   = 0
    private var labelChanY      = 0
    private var labelCooldownY  = 0
    private var labelPreventY   = 0
    private var labelOptY       = 0

    /** Sum of all weight fields. */
    private fun currentWeightSum() = optionWeightFields.sumOf { it.text.toIntOrNull() ?: 0 }

    // Column layout for option rows
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
        // adjust panel height based on number of reply options so Save button moves
        val base = (height * 0.80).toInt()
        val extra = (rule.options.size.coerceAtLeast(0)) * 26
        panelHVar = (base + extra).coerceAtMost(height - 40)
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

        // ── Trigger Mode toggle (Simple / Advanced) ───────────────────────
        val modeLabel = if (rule.triggerAdvanced) "Advanced" else "Simple"
        triggerModeToggle = CustomButtonWidget.toggle(fieldX, y, 140, 20, rule.triggerAdvanced, modeLabel) { btn ->
            rule.triggerAdvanced = !rule.triggerAdvanced
            btn.applyToggle(rule.triggerAdvanced, if (rule.triggerAdvanced) "Advanced" else "Simple")
        }
        addDrawableChild(triggerModeToggle)

        // Info [?] for advanced mode usage (hover-only)
        triggerModeInfoX = fieldX + 140 + 8
        triggerModeInfoY = y + 3
        y += 28

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
        // Use gold / aqua theme for this toggle (independent semantic meaning).
        val caseLabel = if (rule.caseSensitive) "Case Sensitive" else "Ignore Case"
        val caseMsg = Text.literal(if (rule.caseSensitive) "§6✔  $caseLabel" else "§b✗  $caseLabel")
        val caseBtn = CustomButtonWidget(fieldX, y, pw - 32, 20, caseMsg, { btn ->
            rule.caseSensitive = !rule.caseSensitive
            val lbl = if (rule.caseSensitive) "Case Sensitive" else "Ignore Case"
            btn.message = Text.literal(if (rule.caseSensitive) "§6✔  $lbl" else "§b✗  $lbl")
            btn.normalBorder = if (rule.caseSensitive) CustomButtonWidget.GOLD_BORDER else CustomButtonWidget.AQUA_BORDER
            btn.hoverBorder  = if (rule.caseSensitive) CustomButtonWidget.GOLD_HOVER  else CustomButtonWidget.AQUA_HOVER
        },
            if (rule.caseSensitive) CustomButtonWidget.GOLD_BORDER else CustomButtonWidget.AQUA_BORDER,
            if (rule.caseSensitive) CustomButtonWidget.GOLD_HOVER  else CustomButtonWidget.AQUA_HOVER,
            machine.meow.gui.GuiSoundType.BOOL)
        addDrawableChild(caseBtn)
        y += 30

        // ── Exact match toggle (checks whole message instead of substring) ──
        // Exact/Contains is a different semantic category — use the same gold/aqua theme
        val exactLabel = if (rule.triggerExact) "Exact Match" else "Contains"
        val exactMsg = Text.literal(if (rule.triggerExact) "§6✔  $exactLabel" else "§b✗  $exactLabel")
        val exactBtn = CustomButtonWidget(fieldX, y, pw - 32, 20, exactMsg, { btn ->
            rule.triggerExact = !rule.triggerExact
            val lbl = if (rule.triggerExact) "Exact Match" else "Contains"
            btn.message = Text.literal(if (rule.triggerExact) "§6✔  $lbl" else "§b✗  $lbl")
            btn.normalBorder = if (rule.triggerExact) CustomButtonWidget.GOLD_BORDER else CustomButtonWidget.AQUA_BORDER
            btn.hoverBorder  = if (rule.triggerExact) CustomButtonWidget.GOLD_HOVER  else CustomButtonWidget.AQUA_HOVER
        },
            if (rule.triggerExact) CustomButtonWidget.GOLD_BORDER else CustomButtonWidget.AQUA_BORDER,
            if (rule.triggerExact) CustomButtonWidget.GOLD_HOVER  else CustomButtonWidget.AQUA_HOVER,
            machine.meow.gui.GuiSoundType.BOOL)
        addDrawableChild(exactBtn)
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

        // Prevent Loops info icon coordinates (only shown when enabled)
        preventInfoX = fieldX + PREVENT_TOGGLE_W + 8
        preventInfoY = y + 3

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

        saveBtnX = fieldX; saveBtnY = py + ph - 28; saveBtnW = pw - 32; saveBtnH = 22
        addDrawableChild(CustomButtonWidget.exit(saveBtnX, saveBtnY, saveBtnW, saveBtnH, "Save & Back") {
            val ok = saveFields()
            if (ok) {
                ModConfig.save()
                fadeNav { client?.setScreen(parent) }
            }
        })
    }

    private fun saveFields(): Boolean {
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
        // Validation: when Exact mode is enabled, replies using "/say " are unsafe
        // because ChatChannel detection is not applied to raw lines in Exact mode.
        // Prevent saving if any reply option starts with /say when rule.triggerExact == true.
        if (rule.triggerExact) {
            for (tf in optionTextFields) {
                val s = tf.text.trim().removeSurrounding("\"")
                if (s.startsWith("/say ", ignoreCase = true)) {
                    // show user a red message in chat HUD and abort save
                    client?.inGameHud?.chatHud?.addMessage(
                        Text.literal("Cannot use /say replies while Exact match is enabled. Disable Exact or remove /say from replies.").withColor(0xFF5555)
                    )
                    return false
                }
            }
        }

        // Validation: when in Advanced trigger mode, every macro used in replies (except $ign)
        // must also be present in the trigger/template. Abort save and show error if not.
        if (rule.triggerAdvanced) {
            val MACRO_RE = Regex("\\$([A-Za-z0-9_]+)")
            val triggerMacros = mutableSetOf<String>()
            for (m in MACRO_RE.findAll(rule.triggerRegex)) {
                val name = m.groupValues[1]
                if (name.lowercase() != "ign") triggerMacros.add(name)
            }
            for (tf in optionTextFields) {
                for (m in MACRO_RE.findAll(tf.text)) {
                    val name = m.groupValues[1]
                    if (name.lowercase() == "ign") continue
                    if (!triggerMacros.contains(name)) {
                        client?.inGameHud?.chatHud?.addMessage(
                            Text.literal("Reply uses macro \$$name which is not present in the trigger template. Add it to the template or remove it from the reply.").withColor(0xFF5555)
                        )
                        return false
                    }
                }
            }
        }

        for (i in rule.options.indices) {
            if (i < optionTextFields.size)   rule.options[i].text   = optionTextFields[i].text
            if (i < optionWeightFields.size) rule.options[i].weight =
                optionWeightFields[i].text.toLongOrNull()
                    ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                    ?.toInt()
                    ?: rule.options[i].weight.coerceAtLeast(0)
        }
        return true
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

        // Determine macro colours from the trigger template (so reply macros copy regex colours)
        val macroMap = if (rule.triggerAdvanced) buildMacroColorMapFromTemplate(rule.triggerRegex) else null

        if (!text.contains(';')) {
            drawSegmentWithMacros(context, text, x, maxX, y, y2, COL_STR, macroMap)
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
                    drawSegmentWithMacros(context, part, curX, minOf(curX + pw, maxX), y, y2, COL_STR, macroMap)
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

    /** Draws a single text segment with $macros highlighted in cycling colors. */
    private fun drawSegmentWithMacros(
        context: DrawContext, text: String,
        startX: Int, maxX: Int, y: Int, y2: Int, baseColor: Int,
        macroColorMap: Map<String, Int>? = null
    ) {
        val MACRO_RE = Regex("\\$[A-Za-z0-9_]+")
        // Palette: gold, aqua, light blue, pink — cycles when exhausted
        val palette = listOf(0xFFAA00, 0x00AAAA, 0x55AAFF, 0xFF66AA)

        // Determine macro->color mapping: use provided map (from trigger template) if present,
        // otherwise derive from the order of appearance in this text.
        val macroColor = HashMap<String, Int>()
        if (macroColorMap != null) {
            macroColor.putAll(macroColorMap)
        } else {
            val macroOrder = mutableListOf<String>()
            for (m in MACRO_RE.findAll(text)) {
                val tok = m.value
                if (!macroOrder.contains(tok)) macroOrder.add(tok)
            }
            for ((i, tok) in macroOrder.withIndex()) macroColor[tok] = palette[i % palette.size]
        }

        var curX = startX
        var idx = 0
        val matches = MACRO_RE.findAll(text).toList()
        if (matches.isEmpty()) {
            context.getTextConsumer().text(Text.literal(text).withColor(baseColor), startX, maxX, y, y2)
            return
        }

        for (m in matches) {
            val s = m.range.first
            val e = m.range.last + 1
            if (s > idx) {
                val before = text.substring(idx, s)
                val w = textRenderer.getWidth(before)
                context.getTextConsumer().text(Text.literal(before).withColor(baseColor), curX, minOf(curX + w, maxX), y, y2)
                curX += w
            }
            val token = text.substring(s, e)
            val col = macroColor[token] ?: macroColor[token.removePrefix("$")] ?: palette[0]
            val w = textRenderer.getWidth(token)
            context.getTextConsumer().text(Text.literal(token).withColor(col), curX, minOf(curX + w, maxX), y, y2)
            curX += w
            idx = e
            if (curX >= maxX) break
        }
        if (idx < text.length && curX < maxX) {
            val rest = text.substring(idx)
            val w = textRenderer.getWidth(rest)
            context.getTextConsumer().text(Text.literal(rest).withColor(baseColor), curX, minOf(curX + w, maxX), y, y2)
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
        // If advanced mode and any reply option uses macros not defined in the trigger,
        // show a top-level warning similar to the $ign note.
        if (rule.triggerAdvanced) {
            val MACRO_RE = Regex("\\$([A-Za-z0-9_]+)")
            val triggerMacros = mutableSetOf<String>()
            for (m in MACRO_RE.findAll(rule.triggerRegex)) triggerMacros.add(m.groupValues[1])
            val undefinedAll = mutableSetOf<String>()
            for (tf in optionTextFields) {
                for (m in MACRO_RE.findAll(tf.text)) {
                    val name = m.groupValues[1]
                    if (name.lowercase() == "ign") continue
                    if (!triggerMacros.contains(name)) undefinedAll.add(name)
                }
            }
            if (undefinedAll.isNotEmpty()) {
                val warn = "Undefined macros in replies: " + undefinedAll.joinToString(",") { "\$$it" }
                context.getTextConsumer().text(Text.literal(warn).withColor(0xFF5555), panelX + 16, panelX + panelW - 32, labelOptY + 14, labelOptY + 23)
            }
        }

        // ── [?] Info-Icon top-right ────────────────────────────────────────
        val infoStr  = "[?]"
        val infoStrW = textRenderer.getWidth(infoStr)
        val infoX    = panelX + panelW - infoStrW - 8
        val infoY    = panelY + 11
        val hovInfo  = mouseX in infoX..(infoX + infoStrW) && mouseY in infoY..(infoY + 9)
        context.getTextConsumer().text(
            Text.literal(infoStr).withColor(if (hovInfo) 0xFFFF88 else 0x7799BB),
            infoX, infoX + infoStrW, infoY, infoY + 9
        )

        // ── Trigger mode [?] icon (next to the Simple/Advanced toggle)
        val infoStr2 = "[?]"
        val infoStr2W = textRenderer.getWidth(infoStr2)
        val hovInfo2 = mouseX in triggerModeInfoX..(triggerModeInfoX + infoStr2W) && mouseY in triggerModeInfoY..(triggerModeInfoY + 9)
        // Draw the trigger-mode help icon only when Advanced mode is enabled.
        if (rule.triggerAdvanced) {
            context.getTextConsumer().text(
                Text.literal(infoStr2).withColor(if (hovInfo2) 0xFFFF88 else 0x7799BB),
                triggerModeInfoX, triggerModeInfoX + infoStr2W, triggerModeInfoY, triggerModeInfoY + 9
            )
        }

        // ── Prevent Loops [?] icon (only shown when Prevent Loops is enabled)
        val infoStr3 = "[?]"
        val infoStr3W = textRenderer.getWidth(infoStr3)
        val hovPrevent = mouseX in preventInfoX..(preventInfoX + infoStr3W) && mouseY in preventInfoY..(preventInfoY + 9)
        if (rule.preventLoops) {
            context.getTextConsumer().text(
                Text.literal(infoStr3).withColor(if (hovPrevent) 0xFFFF88 else 0x7799BB),
                preventInfoX, preventInfoX + infoStr3W, preventInfoY, preventInfoY + 9
            )
        }

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

        // We want to draw a colored preview for the trigger field when it's not focused.
        // The TextFieldWidget would normally draw the raw text as well which causes
        // a doubled/offset look. To avoid that we temporarily hide the field text
        // during the default rendering pass and restore it afterwards.
        val triggerPreviewNeeded = !triggerField.isFocused && triggerField.text.isNotEmpty() &&
            triggerField.text.any { ch -> ch in listOf('(', ')', '|', '[', ']', '*', '+', '?', '\\', '.', '$') }

        // Draw a solid overlay behind the Save button area to avoid many option rows
        // visually appearing behind it when the list grows long. Draw this first so
        // the actual Save button (rendered by super.render) appears on top.
        if (saveBtnW > 0 && saveBtnH > 0) {
            // panel background dark with border
            context.fill(saveBtnX - 2, saveBtnY - 2, saveBtnX + saveBtnW + 2, saveBtnY + saveBtnH + 2, 0xFF0E0E20.toInt())
            // subtle border
            context.fill(saveBtnX - 2, saveBtnY - 2, saveBtnX + saveBtnW + 2, saveBtnY - 1, 0xFF5566EE.toInt())
            context.fill(saveBtnX - 2, saveBtnY + saveBtnH + 1, saveBtnX + saveBtnW + 2, saveBtnY + saveBtnH + 2, 0xFF5566EE.toInt())
        }

        if (triggerPreviewNeeded) {
            val saved = triggerField.text
            try {
                // hide text so super.render doesn't draw it
                triggerField.text = ""
                super.render(context, mouseX, mouseY, delta)
            } finally {
                // restore immediately
                triggerField.text = saved
            }
        } else {
            super.render(context, mouseX, mouseY, delta)
        }


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
        } else if (!triggerField.isFocused && triggerField.text.isNotEmpty()) {
            // If the trigger field contains regex-like content and is not focused, draw
            // a highlighted preview similar to how reply sequences are shown.
            val t = triggerField.text
            if (t.any { ch -> ch in listOf('(', ')', '|', '[', ']', '*', '+', '?', '\\', '.', '$') }) {
                drawRegexHighlight(context, t, triggerField.x + 4, triggerField.y + 6, triggerField.x + triggerField.width - 4)
            }
        }

        // If the advanced trigger template contains a $ign macro, show an explicit
        // note so the user knows captured $ign will override the automatic senderName.
        val triggerCapturesIgn = rule.triggerAdvanced && triggerField.text.contains("\$ign")
        if (triggerCapturesIgn) {
            val infoX = triggerField.x + 4
            val infoY = triggerField.y + triggerField.height + 6
            context.getTextConsumer().text(
                Text.literal("Trigger captures \$ign — captured value overrides sender name").withColor(0xFFAA00),
                infoX, infoX + triggerField.width - 8, infoY, infoY + 9
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
                // compute undefined macros for this option (only when advanced)
                val undefinedMacros = mutableListOf<String>()
                if (rule.triggerAdvanced && tf != null) {
                    val MACRO_RE = Regex("\\$([A-Za-z0-9_]+)")
                    val triggerMacros = mutableSetOf<String>()
                    for (m in MACRO_RE.findAll(rule.triggerRegex)) triggerMacros.add(m.groupValues[1])
                    for (m in MACRO_RE.findAll(tf.text)) {
                        val name = m.groupValues[1]
                        if (name.lowercase() == "ign") continue
                        if (!triggerMacros.contains(name)) undefinedMacros.add(name)
                    }
                }

                if (tf != null) {
                    val t = tf.text
                    when {
                        t.isEmpty() && !tf.isFocused -> context.getTextConsumer().text(
                            Text.literal("Reply text").withColor(hintColor),
                            tf.x + 4, tf.x + tf.width - 4, tf.y + 6, tf.y + 15
                        )
                        !tf.isFocused && (t.startsWith("/") || t.contains(";") || t.contains("$")) ->
                            drawSyntaxHighlight(context, t, tf.x + 4, tf.y + 6, tf.x + tf.width - 4)
                    }
                    // Draw inline warning under option if undefined macros exist
                    if (undefinedMacros.isNotEmpty()) {
                        val warn = "Undefined macros: " + undefinedMacros.joinToString(",") { "\$$it" }
                        context.getTextConsumer().text(Text.literal(warn).withColor(0xFF5555), tf.x + 4, tf.x + tf.width - 4, tf.y + 22, tf.y + 31)
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

        // ── Tooltip for [?] Icon ─────────────────────────────────────────----
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

        // ── Tooltip for trigger mode [?] (only when Advanced mode selected) ──
        if (hovInfo2 && rule.triggerAdvanced) {
            val tip2 = listOf(
                Text.literal("§eAdvanced Trigger Mode§r  §8(Templates & macros)"),
                Text.literal(""),
                Text.literal("§7Quick: Put named macros like §f\$player §7into quoted parts to capture values."),
                Text.literal("§7Example trigger: §f\"\$player\" joined \"\$type\"§7 → captures player and type."),
                Text.literal(""),
                Text.literal("§7How to write replies:"),
                Text.literal("§8• Use captured macros in replies: §f\$player §f\$type"),
                Text.literal("§8• The editor colors each macro so you can match them visually."),
                Text.literal(""),
                Text.literal("§eOR / alternation§r:"),
                Text.literal("§7Use parentheses with commas to match alternatives: §f(Guild >,Party >)"),
                Text.literal("§7Example: §f(Guild >,Party >) \$player joined §7matches either channel."),
                Text.literal(""),
                Text.literal("§eExact mode§r:"),
                Text.literal("§7• Regex: Exact = entire message must match."),
                Text.literal("§7• Template: Exact = literals must be at the exact positions (no searching)."),
                Text.literal(""),
                Text.literal("§7Notes:"),
                Text.literal("§8• \$ign is special: if captured in the template it overrides the sender's name."),
                Text.literal("§8• Replies may only use macros present in the template (except \$ign).")
            )
            context.drawTooltip(textRenderer, tip2, mouseX, mouseY)
        }

        // ── Tooltip for Prevent Loops [?] (only when enabled and hovered) ──
        if (hovPrevent && rule.preventLoops) {
            val tip3 = listOf(
                Text.literal("§eSmart Loop Prevention§r  §8(Prevent Loops)"),
                Text.literal(""),
                Text.literal("§7When enabled, the mod detects rapid repeated triggers between you and another player."),
                Text.literal("§7If the same message is triggered repeatedly within the configured window, replies are cancelled to avoid ping-pong loops."),
                Text.literal(""),
                Text.literal("§7How it works:"),
                Text.literal("§8• Module tracks recent outgoing replies and per-sender trigger times."),
                Text.literal("§8• If a suspect pattern is detected, further replies to the same message are blocked for the window."),
                Text.literal(""),
                Text.literal("§7You can adjust the window in seconds next to the toggle. Disable if you want all replies regardless of loops.")
            )
            context.drawTooltip(textRenderer, tip3, mouseX, mouseY)
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

