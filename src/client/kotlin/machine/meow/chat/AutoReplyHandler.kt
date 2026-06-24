package machine.meow.chat

import machine.meow.MnkClient
import machine.meow.command.ModCommand
import machine.meow.config.ModConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting

object AutoReplyHandler {
    private val LOGGER = MnkClient.LOGGER

    /** Scan-Throttle: verhindert Flooding wenn mehrere User die Mod benutzen. */
    private var lastScanAt: Long = 0L
    private const val SCAN_COOLDOWN_MS = 100L

    private data class SentEntry(val text: String, val sentAt: Long)
    private val recentlySent = ArrayDeque<SentEntry>()
    private const val ANTI_LOOP_WINDOW_MS = 8_000L

    /** Per-rule reply cooldown tracking: rule.name → last reply timestamp (ms). */
    private val ruleLastReplyAt = HashMap<String, Long>()

    /** State for Prevent-Loops detection, per rule per sender. */
    private data class LoopState(
        var lastTriggerAt: Long = 0L,
        var suspectMessage: String? = null,
        var suspectWindowEnd: Long = 0L
    )
    /** rule.name → senderName → LoopState */
    private val loopTrackers = HashMap<String, HashMap<String, LoopState>>()

    /** Queue for delayed sequential commands/messages. */
    private data class QueuedAction(val channel: ChatChannel, val text: String, val executeAt: Long)
    private val actionQueue = ArrayDeque<QueuedAction>()

    private val COLOR_IGN  = 0x5599DD
    private val COLOR_BODY = 0x7788AA
    private val COLOR_WORD = 0xEEEEFF

    fun register() {
        // ── Tick event: process delayed actions from the queue ─────────────
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (actionQueue.isEmpty()) return@register
            if (!ModConfig.autoReplyEnabled) {
                actionQueue.clear()
                return@register
            }
            val handler = client.networkHandler
            if (handler == null) {
                actionQueue.clear()
                return@register
            }
            val now = System.currentTimeMillis()
            while (actionQueue.isNotEmpty() && actionQueue.first().executeAt <= now) {
                val action = actionQueue.removeFirst()
                dispatchText(action.channel, action.text, handler)
            }
        }

        // ── GAME event: Hypixel + system messages ──────────────────────────
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            if (!ModConfig.autoReplyEnabled) return@register
            val rawLine = cleanRaw(message.string)
            val detection = ChatChannel.detect(rawLine)
                ?: ChatChannel.detectByPrefix(rawLine)
            if (detection != null) processIncoming(detection)
            else LOGGER.debug("[mnk] GAME – no detection for: {}", rawLine)
        }

        // ── CHAT event: signed player chat (vanilla / non-Hypixel servers) ─
        ClientReceiveMessageEvents.CHAT.register { message, _, sender, _, _ ->
            if (!ModConfig.autoReplyEnabled) return@register
            val rawLine = cleanRaw(message.string)
            val detection = ChatChannel.detect(rawLine)
                ?: ChatChannel.detectByPrefix(rawLine)
                ?: sender?.name?.let { name -> ChatDetection(ChatChannel.ALL, rawLine, name) }
            if (detection != null) processIncoming(detection)
            else LOGGER.debug("[mnk] CHAT – no detection for: {}", rawLine)
        }

        LOGGER.info("[mnk] AutoReplyHandler registered (scan throttle: ${SCAN_COOLDOWN_MS}ms, per-rule cooldowns)")
    }

    /**
     * Strips Minecraft colour/format codes (§x), Unicode invisible/format chars,
     * non-breaking spaces, and trims surrounding whitespace.
     * Must be called before ChatChannel.detect() so regexes match plain text.
     */
    private fun cleanRaw(raw: String): String = raw
        .replace(Regex("§[0-9A-FK-ORa-fk-or]"), "")  // §-colour codes
        .replace('\u00A0', ' ')                         // non-breaking space → space
        .replace(Regex("\\p{Cf}"), "")                  // Unicode format chars (BOM, ZWJ, …)
        .trim()

    /**
     * Advanced template matcher.
     * Template supports quoted segments and $macros inside quoted segments.
     * Behavior:
     *  - Unquoted text must appear in order in the message.
     *  - Quoted segment starting with "$name" and closed: captures a single word.
     *  - Quoted segment "$name stopword" (closed) captures up until the stopword.
     *  - Quoted segment without a closing quote starting with "$name" captures the rest of the message.
     * Returns Pair(matchBoolean, capturesMap).
     */
    private fun matchAdvanced(template: String, message: String, caseSensitive: Boolean, exact: Boolean): Pair<Boolean, Map<String, String>> {
        val origMsg = message
        val msg = if (caseSensitive) message else message.lowercase()
        val tpl = if (caseSensitive) template else template.lowercase()

        data class Token(val quoted: Boolean, val content: String, val closed: Boolean)
        val tokens = mutableListOf<Token>()

        var i = 0
        while (i < tpl.length) {
            if (tpl[i] == '"') {
                val j = tpl.indexOf('"', i + 1)
                if (j >= 0) {
                    tokens.add(Token(true, tpl.substring(i + 1, j), true))
                    i = j + 1
                } else {
                    tokens.add(Token(true, tpl.substring(i + 1), false))
                    break
                }
            } else {
                val j = tpl.indexOf('"', i)
                if (j >= 0) {
                    tokens.add(Token(false, tpl.substring(i, j), true))
                    i = j
                } else {
                    tokens.add(Token(false, tpl.substring(i), true))
                    break
                }
            }
        }

        var pos = 0
        val caps = HashMap<String, String>()

        for (token in tokens) {
            val content = token.content
            if (!token.quoted) {
                val lit = content
                if (lit.isBlank()) continue
                // Support simple OR/alternation groups of the form (a,b,c) inside the
                // literal content. Expand variants and try to match any of them.
                fun expandOrVariants(s: String): List<String> {
                    val start = s.indexOf('(')
                    if (start < 0) return listOf(s)
                    val end = s.indexOf(')', start + 1)
                    if (end < 0) return listOf(s)
                    val inside = s.substring(start + 1, end)
                    val parts = inside.split(',').map { it.trim() }
                    val results = mutableListOf<String>()
                    for (p in parts) {
                        val replaced = s.substring(0, start) + p + s.substring(end + 1)
                        results.addAll(expandOrVariants(replaced))
                    }
                    return results
                }

                val variants = expandOrVariants(lit)
                if (exact) {
                    // In exact mode, the literal must match at the current position
                    var matched = false
                    var matchedLen = 0
                    for (v in variants) {
                        if (msg.startsWith(v, pos)) {
                            matched = true
                            matchedLen = v.length
                            break
                        }
                    }
                    if (!matched) return Pair(false, emptyMap())
                    pos += matchedLen
                } else {
                    var bestIdx = Int.MAX_VALUE
                    var bestLen = 0
                    for (v in variants) {
                        val idx = msg.indexOf(v, pos)
                        if (idx >= 0 && idx < bestIdx) {
                            bestIdx = idx
                            bestLen = v.length
                        }
                    }
                    if (bestIdx == Int.MAX_VALUE) return Pair(false, emptyMap())
                    pos = bestIdx + bestLen
                }
            } else {
                val seg = content.trim()
                if (seg.isEmpty()) continue
                if (!seg.startsWith("$")) {
                    // quoted literal: must match
                    val idx = msg.indexOf(seg, pos)
                    if (idx < 0) return Pair(false, emptyMap())
                    pos = idx + seg.length
                } else {
                    // macro handling
                    val parts = seg.split(Regex("\\s+"), 2)
                    val macro = parts[0].removePrefix("$")
                    if (!token.closed) {
                        // capture rest of message
                        val start = skipSpacesIndex(msg, pos)
                        val captured = origMsg.substring(start)
                        caps[macro] = captured.trim()
                        pos = msg.length
                    } else if (parts.size == 1) {
                        // capture single word
                        var s = skipSpacesIndex(msg, pos)
                        if (s >= msg.length) return Pair(false, emptyMap())
                        var e = s
                        while (e < msg.length && !msg[e].isWhitespace()) e++
                        val captured = origMsg.substring(s, e)
                        caps[macro] = captured
                        pos = e
                    } else {
                        // capture until stopword
                        val stop = parts[1]
                        val idx = msg.indexOf(stop, pos)
                        if (idx < 0) return Pair(false, emptyMap())
                        val captured = origMsg.substring(pos, idx).trim()
                        caps[macro] = captured
                        pos = idx + stop.length
                    }
                }
            }
        }

        return Pair(true, caps)
    }

    private fun skipSpacesIndex(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /**
     * Parses the sequence format: `"cmd1";delay1;"cmd2";delay2;...`
     * - Delays are in ticks (1 tick = 50 ms, 20 ticks = 1 s)
     * - Strings can be optionally wrapped in double quotes
     * - No semicolon means simple text that will be sent immediately
     *
     * Returns a list of (commandText, absoluteDelayMs).
     */
    internal fun parseSequence(raw: String): List<Pair<String, Long>> {
        if (!raw.contains(';')) {
            val cmd = raw.trim().removeSurrounding("\"")
            return if (cmd.isNotEmpty()) listOf(cmd to 0L) else emptyList()
        }

        val result = mutableListOf<Pair<String, Long>>()
        var absoluteMs = 0L   // kumulierter Delay bis zum aktuellen Command
        var pendingMs  = 0L   // Delay-Akkumulator zwischen zwei Commands

        for (part in raw.split(";")) {
            val trimmed = part.trim().removeSurrounding("\"")
            val ticks   = trimmed.toLongOrNull()
            when {
                ticks != null       -> pendingMs  += ticks * 50L
                trimmed.isNotEmpty() -> {
                    absoluteMs += pendingMs
                    pendingMs   = 0L
                    result.add(trimmed to absoluteMs)
                }
            }
        }
        return result.ifEmpty {
            val fallback = raw.trim().removeSurrounding("\"")
            if (fallback.isNotEmpty()) listOf(fallback to 0L) else emptyList()
        }
    }

    /** Shared processing for both GAME and CHAT events. */
    private fun processIncoming(detection: ChatDetection) {
        val (channel, chatMessage, senderName) = detection

        val now = System.currentTimeMillis()

        // ── Scan-Throttle (100 ms) ──────────────────────────────────────────
        if (now - lastScanAt < SCAN_COOLDOWN_MS) return
        lastScanAt = now

        // Purge stale anti-loop entries
        val cutoff = now - ANTI_LOOP_WINDOW_MS
        while (recentlySent.isNotEmpty() && recentlySent.first().sentAt < cutoff) {
            recentlySent.removeFirst()
        }

        val cleanMsg = chatMessage.trim()
        if (recentlySent.any { it.text.equals(cleanMsg, ignoreCase = true) }) return

        val mc = MinecraftClient.getInstance()
        val selfName = mc.player?.gameProfile?.name ?: ""

        for (rule in ModConfig.autoReplyRules) {
            if (!rule.enabled) continue
            if (!rule.appliesTo(channel)) continue

            // Matching: simple regex or advanced template mode
            val captures = HashMap<String, String>()
            val matches = if (!rule.triggerAdvanced) {
                val regexOpts = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val regex = Regex(rule.triggerRegex, regexOpts)
                runCatching {
                    if (rule.triggerExact) regex.matchEntire(chatMessage) != null
                    else chatMessage.contains(regex)
                }.getOrDefault(false)
            } else {
                val (ok, caps) = matchAdvanced(rule.triggerRegex, chatMessage, rule.caseSensitive, rule.triggerExact)
                if (ok) captures.putAll(caps)
                ok
            }

            if (matches) {
                val ignCaptured = captures["ign"]?.trim()
                val isSelf = if (ignCaptured != null) {
                    ignCaptured.equals(selfName, ignoreCase = true)
                } else {
                    senderName.equals(selfName, ignoreCase = true)
                }

                LOGGER.info("[MNK] {} triggered auto-reply with '{}' (rule: '{}'){}",
                    senderName, cleanMsg, rule.name,
                    if (isSelf) " > detected self, ignoring" else "")

                if (!isSelf) {
                    // ── Per-rule cooldown ──────────────────────────────────────
                    val ruleLastReply = ruleLastReplyAt[rule.name] ?: 0L
                    if (now - ruleLastReply < rule.cooldownMs) break

                    // ── Prevent-Loops detection ────────────────────────────────
                    if (rule.preventLoops) {
                        val windowMs  = rule.preventLoopSeconds * 1000L
                        val stateMap  = loopTrackers.getOrPut(rule.name) { HashMap() }
                        val state     = stateMap.getOrPut(senderName) { LoopState() }

                        // Suspect window active and same message seen again → loop!
                        if (state.suspectMessage != null && now < state.suspectWindowEnd &&
                            cleanMsg.equals(state.suspectMessage, ignoreCase = true)) {

                            val notif = ModCommand.mnkPrefix()
                                .append(Text.literal("Detected Loop Replies between you and ").withColor(COLOR_BODY))
                                .append(Text.literal(senderName).withColor(COLOR_IGN))
                                .append(Text.literal(". Reply has been cancelled").withColor(COLOR_BODY))
                            mc.inGameHud?.chatHud?.addMessage(notif)
                            state.suspectMessage  = null
                            state.suspectWindowEnd = 0L
                            break  // don't reply
                        }

                        // Same person triggered again within window → enter suspect mode
                        if (now - state.lastTriggerAt < windowMs) {
                            state.suspectMessage  = cleanMsg
                            state.suspectWindowEnd = now + windowMs
                        }
                        state.lastTriggerAt = now
                    }

                    val response = rule.pickResponse()?.takeIf { it.isNotBlank() } ?: break
                    // Resolve macros inside the response. Captured macros from the advanced
                    // trigger take precedence; fallback to senderName for $ign.
                    var resolvedResponse = response
                    for ((k, v) in captures) {
                        resolvedResponse = resolvedResponse.replace("\$$k", v)
                    }
                    if (!captures.containsKey("ign")) {
                        resolvedResponse = resolvedResponse.replace("\$ign", senderName)
                    }

                    // Build a coloured preview where each macro ($name) is assigned a cycling colour
                    val originalResponse = response
                    val MACRO_RE = Regex("\\$[A-Za-z0-9_]+")
                    val palette = listOf(0xFFAA00, 0x00AAAA, 0x55AAFF, 0xFF66AA)

                    // Determine macro order / colours from the trigger template when in Advanced mode
                    val macroOrder = mutableListOf<String>()
                    if (rule.triggerAdvanced) {
                        for (m in MACRO_RE.findAll(rule.triggerRegex)) {
                            val tok = m.value
                            if (!macroOrder.contains(tok)) macroOrder.add(tok)
                        }
                    } else {
                        for (m in MACRO_RE.findAll(originalResponse)) {
                            val tok = m.value
                            if (!macroOrder.contains(tok)) macroOrder.add(tok)
                        }
                    }
                    val macroColor = HashMap<String, Int>()
                    for ((i, tok) in macroOrder.withIndex()) {
                        val col = palette[i % palette.size]
                        macroColor[tok] = col
                        macroColor[tok.removePrefix("$")] = col
                    }

                    val parts = MACRO_RE.findAll(originalResponse).toList()
                    val builder = Text.literal("")
                    if (parts.isEmpty()) {
                        val isSeq = originalResponse.contains(';') || originalResponse.startsWith("/")
                        val simple = if (isSeq) Text.literal("'${originalResponse}'").formatted(Formatting.AQUA)
                                     else Text.literal("'${originalResponse}'").withColor(COLOR_WORD)
                        builder.append(simple)
                    } else {
                        builder.append(Text.literal("'").withColor(COLOR_WORD))
                        var last = 0
                        for (m in parts) {
                            val s = m.range.first
                            val e = m.range.last + 1
                            if (s > last) {
                                val lit = originalResponse.substring(last, s)
                                builder.append(Text.literal(lit).withColor(COLOR_WORD))
                            }
                            val tok = originalResponse.substring(s, e)
                            val macroName = tok.removePrefix("$")
                            val valStr = if (captures.containsKey(macroName)) captures[macroName]!!
                                         else if (macroName == "ign") senderName
                                         else tok
                            val col = macroColor[tok] ?: palette[0]
                            builder.append(Text.literal(valStr).withColor(col))
                            last = e
                        }
                        if (last < originalResponse.length) {
                            val tail = originalResponse.substring(last)
                            builder.append(Text.literal(tail).withColor(COLOR_WORD))
                        }
                        builder.append(Text.literal("'").withColor(COLOR_WORD))
                    }
                    val replyDisplay = builder

                    val notif = ModCommand.mnkPrefix()
                        .append(Text.literal(senderName).withColor(COLOR_IGN))
                        .append(Text.literal(" triggered auto-reply with ").withColor(COLOR_BODY))
                        .append(Text.literal("'$cleanMsg'").withColor(COLOR_WORD))
                        .append(Text.literal(" → ").withColor(COLOR_BODY))
                        .append(replyDisplay)
                    mc.inGameHud?.chatHud?.addMessage(notif)

                    mc.soundManager.play(
                        PositionedSoundInstance.ui(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f)
                    )

                    sendReply(channel, resolvedResponse)
                    ruleLastReplyAt[rule.name] = now
                }
                break
            }
        }
    }

    private fun sendReply(channel: ChatChannel, text: String) {
        val client  = MinecraftClient.getInstance()
        val handler = client.networkHandler ?: return
        val now     = System.currentTimeMillis()

        val sequence = parseSequence(text)
        for ((cmd, delayMs) in sequence) {
            // Jeden geplanten Command sofort ins Anti-Loop-Register eintragen
            recentlySent.addLast(SentEntry(cmd.trim(), now))
            if (recentlySent.size > 20) recentlySent.removeFirst()

            if (delayMs == 0L) {
                dispatchText(channel, cmd, handler)
            } else {
                actionQueue.addLast(QueuedAction(channel, cmd, now + delayMs))
            }
        }
    }

    /**
     * Sends a single text or command to the server.
     *
     * Special case: "/say <msg>" is NOT executed as a Minecraft command –
     * instead, <msg> is sent to the channel the trigger was detected in.
     *   • Guild trigger  → /gc <msg>
     *   • Party trigger  → /pc <msg>
     *   • All/other      → <msg>  (plain chat)
     * This lets you write channel-agnostic replies using /say syntax.
     */
    private fun dispatchText(channel: ChatChannel, text: String, handler: ClientPlayNetworkHandler) {
        when {
            text.startsWith("/say ") -> {
                // Route to the triggering channel instead of running /say
                val msg = text.removePrefix("/say ")
                handler.sendChatMessage(channel.replyPrefix + msg)
            }
            text.startsWith("/") -> handler.sendChatCommand(text.removePrefix("/"))
            else                 -> handler.sendChatMessage(channel.replyPrefix + text)
        }
    }

    // ── Public control API (used by panic commands) ───────────────────────

    /** Clears the action queue – running sequences are stopped immediately. */
    fun clearQueue() {
        actionQueue.clear()
    }

    /** Returns current number of pending sequence actions. */
    fun queueSize(): Int = actionQueue.size

    /**
     * Full reset: clears queue, history and cooldowns.
     * Auto-reply enabled/disabled state is preserved.
     */
    fun fullReset() {
        actionQueue.clear()
        recentlySent.clear()
        ruleLastReplyAt.clear()
        loopTrackers.clear()
        lastScanAt = 0L
    }

    /**
     * Emergency stop: disables auto-reply completely and clears queue/state.
     * Saves the change to the config.
     */
    fun emergencyStop() {
        fullReset()
        ModConfig.autoReplyEnabled = false
        ModConfig.save()
    }
}