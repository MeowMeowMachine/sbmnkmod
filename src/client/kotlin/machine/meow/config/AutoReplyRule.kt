package machine.meow.config

import machine.meow.chat.ChatChannel
import kotlin.random.Random

data class AutoReplyRule(
    var name: String = "New Rule",
    var triggerRegex: String = "hello",
    var enabled: Boolean = true,
    var onGuild: Boolean = true,
    var onParty: Boolean = true,
    var onCoop: Boolean = true,
    var onAll: Boolean = true,
    var caseSensitive: Boolean = false,
    var options: MutableList<ReplyOption> = mutableListOf()
) {
    fun appliesTo(channel: ChatChannel): Boolean = when (channel) {
        ChatChannel.GUILD -> onGuild
        ChatChannel.PARTY -> onParty
        ChatChannel.COOP -> onCoop
        ChatChannel.ALL -> onAll
    }

    /** Wählt eine Antwort gewichtet nach den Weight-Werten ("%"-Roll) aus. */
    fun pickResponse(): String? {
        if (options.isEmpty()) return null
        val total = options.sumOf { it.weight.coerceAtLeast(0) }
        if (total <= 0) return options.random().text

        var roll = Random.nextInt(total)
        for (option in options) {
            val w = option.weight.coerceAtLeast(0)
            if (roll < w) return option.text
            roll -= w
        }
        return options.last().text
    }
}