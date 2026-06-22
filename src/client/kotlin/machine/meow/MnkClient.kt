package machine.meow

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory
import machine.meow.config.ModConfig
import machine.meow.command.ModCommand
import machine.meow.chat.AutoReplyHandler

object MnkClient : ClientModInitializer {
	const val MOD_ID = "mnk"
	val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitializeClient() {
		ModConfig.load()
		ModCommand.register()
		AutoReplyHandler.register()
		LOGGER.info("[$MOD_ID] client init done")
	}
}