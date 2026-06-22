package machine.meow.command

import machine.meow.MnkClient
import machine.meow.chat.AutoReplyHandler
import machine.meow.config.ModConfig
import machine.meow.gui.MainMenuScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting

object ModCommand {
    private val LOGGER = MnkClient.LOGGER

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("mnk")
                    // /mnk  →  Hauptmenü öffnen
                    .executes { _ ->
                        val mc = MinecraftClient.getInstance()
                        mc.execute {
                            LOGGER.info("[mnk] Opening main menu")
                            mc.setScreen(MainMenuScreen())
                        }
                        1
                    }

                    // ── /mnk panic ─────────────────────────────────────────────
                    // Emergency stop: cancel and disable everything immediately
                    .then(ClientCommandManager.literal("panic").executes { ctx ->
                        AutoReplyHandler.emergencyStop()
                        ctx.source.sendFeedback(
                            mnkText("⚠ PANIC – Auto-Reply disabled, queue cleared.", Formatting.RED)
                        )
                        1
                    })

                    // ── /mnk autoreply <subcommand> ────────────────────────────
                    .then(ClientCommandManager.literal("autoreply")

                        // stop / stopallmessages  →  cancel running sequence
                        .then(ClientCommandManager.literal("stop").executes { ctx ->
                            val n = AutoReplyHandler.queueSize()
                            AutoReplyHandler.clearQueue()
                            ctx.source.sendFeedback(mnkText("Queue cleared ($n ${if (n == 1) "message" else "messages"} removed).", Formatting.YELLOW))
                            1
                        })
                        .then(ClientCommandManager.literal("stopallmessages").executes { ctx ->
                            val n = AutoReplyHandler.queueSize()
                            AutoReplyHandler.clearQueue()
                            ctx.source.sendFeedback(mnkText("Queue cleared ($n ${if (n == 1) "message" else "messages"} removed).", Formatting.YELLOW))
                            1
                        })

                        // on  →  enable
                        .then(ClientCommandManager.literal("on").executes { ctx ->
                            ModConfig.autoReplyEnabled = true
                            ModConfig.save()
                            ctx.source.sendFeedback(mnkText("Auto-Reply enabled.", Formatting.GREEN))
                            1
                        })

                        // off  →  disable + clear queue
                        .then(ClientCommandManager.literal("off").executes { ctx ->
                            ModConfig.autoReplyEnabled = false
                            AutoReplyHandler.clearQueue()
                            ModConfig.save()
                            ctx.source.sendFeedback(mnkText("Auto-Reply disabled.", Formatting.RED))
                            1
                        })

                        // toggle  →  switch on/off
                        .then(ClientCommandManager.literal("toggle").executes { ctx ->
                            ModConfig.autoReplyEnabled = !ModConfig.autoReplyEnabled
                            if (!ModConfig.autoReplyEnabled) AutoReplyHandler.clearQueue()
                            ModConfig.save()
                            val (label, color) = if (ModConfig.autoReplyEnabled)
                                "enabled" to Formatting.GREEN
                            else
                                "disabled" to Formatting.RED
                            ctx.source.sendFeedback(mnkText("Auto-Reply $label.", color))
                            1
                        })

                        // reset  →  clear cooldowns + queue + history (stays on/off)
                        .then(ClientCommandManager.literal("reset").executes { ctx ->
                            AutoReplyHandler.fullReset()
                            ctx.source.sendFeedback(mnkText("Reset: Cooldowns, queue and history cleared.", Formatting.AQUA))
                            1
                        })

                        // deactivateall  →  disable all rules (without deleting)
                        .then(ClientCommandManager.literal("deactivateall").executes { ctx ->
                            val count = ModConfig.autoReplyRules.count { it.enabled }
                            ModConfig.autoReplyRules.forEach { it.enabled = false }
                            AutoReplyHandler.clearQueue()
                            ModConfig.save()
                            ctx.source.sendFeedback(mnkText("$count ${if (count == 1) "Rule" else "Rules"} deactivated.", Formatting.YELLOW))
                            1
                        })

                        // activateall  →  enable all rules
                        .then(ClientCommandManager.literal("activateall").executes { ctx ->
                            val count = ModConfig.autoReplyRules.size
                            ModConfig.autoReplyRules.forEach { it.enabled = true }
                            ModConfig.save()
                            ctx.source.sendFeedback(mnkText("$count ${if (count == 1) "Rule" else "Rules"} activated.", Formatting.GREEN))
                            1
                        })

                        // status  →  show current state
                        .then(ClientCommandManager.literal("status").executes { ctx ->
                            val enabled   = ModConfig.autoReplyEnabled
                            val qSize     = AutoReplyHandler.queueSize()
                            val ruleCount = ModConfig.autoReplyRules.size
                            val activeRules = ModConfig.autoReplyRules.count { it.enabled }

                            val stateColor = if (enabled) Formatting.GREEN else Formatting.RED
                            val stateLabel = if (enabled) "ON" else "OFF"
                            val qColor     = if (qSize > 0) Formatting.YELLOW else Formatting.DARK_GRAY

                            ctx.source.sendFeedback(
                                mnkPrefix()
                                    .append(Text.literal("Auto-Reply: ").formatted(Formatting.GRAY))
                                    .append(Text.literal(stateLabel).formatted(stateColor))
                                    .append(Text.literal("  │  Queue: ").formatted(Formatting.DARK_GRAY))
                                    .append(Text.literal("$qSize").formatted(qColor))
                                    .append(Text.literal("  │  Rules: ").formatted(Formatting.DARK_GRAY))
                                    .append(Text.literal("$activeRules/$ruleCount active").formatted(Formatting.GRAY))
                            )
                            1
                        })
                    )
            )
        }
        LOGGER.info("[mnk] /mnk command registered")
    }

    /** Builds the §8> §1[§9MNK§1] prefix as a Text component. */
    fun mnkPrefix(): MutableText =
        Text.literal("> ").formatted(Formatting.DARK_GRAY)
            .append(Text.literal("[").formatted(Formatting.DARK_BLUE))
            .append(Text.literal("MNK").formatted(Formatting.BLUE))
            .append(Text.literal("] ").formatted(Formatting.DARK_BLUE))

    /** Uniform feedback format: §8> §1[§9MNK§1] + message in desired colour. */
    fun mnkText(msg: String, color: Formatting): MutableText =
        mnkPrefix().append(Text.literal(msg).formatted(color))
}