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

    private var lastReplyAt: Long = 0L
    private const val COOLDOWN_MS = 1500L

    /** Scan-Throttle: verhindert Flooding wenn mehrere User die Mod benutzen. */
    private var lastScanAt: Long = 0L
    private const val SCAN_COOLDOWN_MS = 100L

    private data class SentEntry(val text: String, val sentAt: Long)
    private val recentlySent = ArrayDeque<SentEntry>()
    private const val ANTI_LOOP_WINDOW_MS = 8_000L

    /** Queue für verzögerte sequenzielle Commands/Nachrichten. */
    private data class QueuedAction(val channel: ChatChannel, val text: String, val executeAt: Long)
    private val actionQueue = ArrayDeque<QueuedAction>()

    private val COLOR_IGN  = 0x5599DD
    private val COLOR_BODY = 0x7788AA
    private val COLOR_WORD = 0xEEEEFF

    fun register() {
        // ── Tick-Event: verarbeitet verzögerte Actions aus der Queue ───────
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (actionQueue.isEmpty()) return@register
            // Wenn Auto-Reply mid-sequence deaktiviert wurde → Queue sofort leeren
            if (!ModConfig.autoReplyEnabled) {
                actionQueue.clear()
                return@register
            }
            // Not connected → discard queue, otherwise it would fire after reconnect
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

        LOGGER.info("[mnk] AutoReplyHandler registered (cooldown: ${COOLDOWN_MS}ms)")
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
     * Parst das Sequence-Format: `"cmd1";delay1;"cmd2";delay2;...`
     * - Delays in Ticks (1 Tick = 50 ms, 20 Ticks = 1 s)
     * - Strings können in " " eingeschlossen sein (optional)
     * - Kein Semikolon → einfacher Text, wird sofort gesendet
     *
     * Gibt eine Liste von (commandText, absoluteDelayMs) zurück.
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

        if (now - lastReplyAt < COOLDOWN_MS) return

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

            val regexOpts = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val matches = runCatching {
                chatMessage.contains(Regex(rule.triggerRegex, regexOpts))
            }.getOrDefault(false)

            if (matches) {
                val response = rule.pickResponse()?.takeIf { it.isNotBlank() } ?: continue
                val isSelf   = senderName.equals(selfName, ignoreCase = true)

                LOGGER.info("[MNK] {} triggered auto-reply with '{}' (rule: '{}'){}",
                    senderName, cleanMsg, rule.name,
                    if (isSelf) " > detected self, ignoring" else "")

                if (!isSelf) {
                    val isSequence = response.contains(';')
                    val replyDisplay = if (isSequence || response.startsWith("/"))
                        Text.literal("'$response'").formatted(Formatting.AQUA)
                    else
                        Text.literal("'$response'").withColor(COLOR_WORD)

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

                    sendReply(channel, response)
                    lastReplyAt = now
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

    // ── Öffentliche Kontroll-API (für Panic-Commands) ──────────────────────

    /** Leert die Action-Queue – laufende Sequenz wird sofort gestoppt. */
    fun clearQueue() {
        actionQueue.clear()
    }

    /** Aktuelle Anzahl ausstehender Sequenz-Actions. */
    fun queueSize(): Int = actionQueue.size

    /**
     * Vollständiger Reset: Queue + Verlauf + Cooldowns werden geleert.
     * Auto-Reply bleibt dabei im aktuellen Zustand (an/aus).
     */
    fun fullReset() {
        actionQueue.clear()
        recentlySent.clear()
        lastReplyAt = 0L
        lastScanAt  = 0L
    }

    /**
     * Notfall-Stop: deaktiviert Auto-Reply komplett, leert Queue + State.
     * Speichert die Änderung in der Config.
     */
    fun emergencyStop() {
        fullReset()
        ModConfig.autoReplyEnabled = false
        ModConfig.save()
    }
}