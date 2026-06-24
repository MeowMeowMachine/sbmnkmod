package machine.meow.config

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

object ModConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path = FabricLoader.getInstance().configDir.resolve("mnk.json")

    var autoReplyEnabled: Boolean = true
    var autoReplyRules: MutableList<AutoReplyRule> = mutableListOf()
    // Space for additional features, e.g.:
    // var someOtherFeatureEnabled: Boolean = false

    private data class Data(
        var autoReplyEnabled: Boolean = true,
        var autoReplyRules: MutableList<AutoReplyRule> = mutableListOf()
    )

    fun load() {
        try {
            if (Files.exists(path)) {
                val json = Files.readString(path)
                val data = gson.fromJson(json, Data::class.java)
                autoReplyEnabled = data.autoReplyEnabled
                autoReplyRules = data.autoReplyRules
            } else {
                save()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun save() {
        try {
            val data = Data(autoReplyEnabled, autoReplyRules)
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(data))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}