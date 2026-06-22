package machine.meow.config

data class ReplyOption(
    var text: String = "",
    var weight: Int = 10  // relatives Gewicht (sum-based: Chance = weight / Summe aller Weights)
)