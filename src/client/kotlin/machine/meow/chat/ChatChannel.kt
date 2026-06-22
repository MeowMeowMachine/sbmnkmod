package machine.meow.chat

import java.util.regex.Pattern

/** Result of a successful channel detection. */
data class ChatDetection(
    val channel: ChatChannel,
    val message: String,
    val senderName: String
)

/**
 * Hypixel chat format (after MC strips § color codes):
 *   Guild  → "Guild > [RANK] Player [GRANK]: msg"
 *            "Guild > ✿ [RANK] Player [GRANK]: msg"
 *            "Guild > Player: msg"
 *   Party  → same structure with "Party > "
 *   Co-op  → same structure with "Co-op > "
 *   ALL    → "[level] ✎ [RANK] Player: msg" / "Player: msg"
 *   Vanilla→ "<Player> msg"
 *
 * Key insight from legacy code: the player name is always the last
 * alphanumeric word directly before an optional [GRANK] and then ": ".
 * Pattern (?:[^:]*\s)? greedily consumes any prefix (unicode, ranks, level
 * badges) ending in whitespace, leaving the player name exposed.
 */
enum class ChatChannel(val displayName: String, val pattern: Pattern, val replyPrefix: String) {
    GUILD(
        "Guild",
        // "Guild > [optional unicode/rank/etc] Player [optional grank]: message"
        Pattern.compile("^Guild > (?:[^:]*\\s)?(?<player>[A-Za-z0-9_]+)(?:\\s+\\[[^\\]]+])?\\s*:\\s+(?<message>.+)$"),
        "/gc "
    ),
    PARTY(
        "Party",
        Pattern.compile("^Party > (?:[^:]*\\s)?(?<player>[A-Za-z0-9_]+)(?:\\s+\\[[^\\]]+])?\\s*:\\s+(?<message>.+)$"),
        "/pc "
    ),
    COOP(
        "Co-op",
        Pattern.compile("^Co-op > (?:[^:]*\\s)?(?<player>[A-Za-z0-9_]+)(?:\\s+\\[[^\\]]+])?\\s*:\\s+(?<message>.+)$"),
        // NOTE: Hypixel removed /cc. Verify the current co-op chat command.
        // Common alternatives: "/cc " (old), "/co " (untested). Update here when confirmed.
        "/coopchat "
    ),
    ALL(
        "All",
        // "[optional level/rank/unicode] Player: message"  (Hypixel public chat)
        Pattern.compile("^(?:[^:]*\\s)?(?<player>[A-Za-z0-9_]+)(?:\\s+\\[[^\\]]+])?\\s*:\\s+(?<message>.+)$"),
        ""
    );

    companion object {
        /** Vanilla singleplayer / vanilla servers: "&lt;PlayerName&gt; message" */
        private val VANILLA_ALL = Pattern.compile("^<(?<player>\\w+)> (?<message>.+)$")

        fun detect(rawLine: String): ChatDetection? {
            // 1. Specific channels first (Guild / Party / Co-op)
            for (channel in listOf(GUILD, PARTY, COOP)) {
                val m = channel.pattern.matcher(rawLine)
                if (m.matches()) {
                    val msg    = runCatching { m.group("message") }.getOrNull() ?: continue
                    val player = runCatching { m.group("player")  }.getOrElse { "Unknown" }
                    return ChatDetection(channel, msg, player ?: "Unknown")
                }
            }
            // 2. Vanilla <Player> message (singleplayer / vanilla servers)
            val vm = VANILLA_ALL.matcher(rawLine)
            if (vm.matches()) {
                val msg    = runCatching { vm.group("message") }.getOrNull() ?: return null
                val player = runCatching { vm.group("player")  }.getOrElse { "Unknown" }
                return ChatDetection(ALL, msg, player ?: "Unknown")
            }
            // 3. Hypixel public chat (ALL)
            val am = ALL.pattern.matcher(rawLine)
            if (am.matches()) {
                val msg    = runCatching { am.group("message") }.getOrNull() ?: return null
                val player = runCatching { am.group("player")  }.getOrElse { "Unknown" }
                return ChatDetection(ALL, msg, player ?: "Unknown")
            }
            return null
        }

        /**
         * Fallback detection used when the full regex match fails.
         * Determines channel from line prefix and extracts sender + message
         * by finding the last "word:" pattern before the colon separator.
         * Handles Hypixel rank/prefix variants robustly.
         *
         * Format assumed: "<ChannelPrefix> > [...] PlayerName [...]: message"
         */
        fun detectByPrefix(rawLine: String): ChatDetection? {
            val channel = when {
                rawLine.startsWith("Guild > ") -> GUILD
                rawLine.startsWith("Party > ") -> PARTY
                rawLine.startsWith("Co-op > ") -> COOP
                else -> return null  // unknown prefix – don't guess channel
            }
            // After "Guild > " / "Party > " / "Co-op > " find "Name: message"
            val afterPrefix = rawLine.substringAfter("> ").trim()
            // Find last <word>: before the message (the player name is the last word before ": ")
            val colonIdx = afterPrefix.indexOf(": ")
            if (colonIdx < 0) return null
            val beforeColon = afterPrefix.substring(0, colonIdx)
            val msg         = afterPrefix.substring(colonIdx + 2)
            // Player name = last alphanumeric token in the prefix section
            val player = beforeColon.split(" ", "\t")
                .lastOrNull { it.matches(Regex("[A-Za-z0-9_]+")) }
                ?: return null
            return ChatDetection(channel, msg, player)
        }
    }
}

