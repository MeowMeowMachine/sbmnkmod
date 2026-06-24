package machine.meow.config

data class ReplyOption(
    var text: String = "",
    var weight: Int = 10  // relative weight (sum-based: chance = weight / sum of all weights)
)