package machine.meow.gui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Identifier

/** Which type of GUI interaction triggered the sound. */
enum class GuiSoundType {
    /** Boolean toggle (on/off). Tries click_bool → click_general → MC default. */
    BOOL,
    /** Screen close / back navigation. Tries click_exit → click_general → MC default. */
    EXIT,
    /** Navigation to a sub-topic / header button. Tries click_header → click_general → MC default. */
    HEADER,
    /** Generic action button. Tries click_general → MC default. */
    GENERAL
}

/**
 * Plays GUI click sounds with a three-stage fallback:
 *   1. Type-specific OGG  (assets/mnk/sounds/gui/click_<type>.ogg)
 *   2. General OGG        (assets/mnk/sounds/gui/click_general.ogg)
 *   3. Vanilla MC click   – only when neither OGG is found;
 *      each type uses a distinct pitch so buttons still sound different.
 *
 * If the OGG file is not present in any loaded resource pack, the stage is
 * silently skipped and the next fallback is tried.
 */
object SoundHelper {

    private fun oggExists(client: MinecraftClient, fileName: String): Boolean {
        // Primary: classloader check – looks at the actual file in the mod JAR / build output.
        // This is reliable because sounds.json registration does NOT create a physical file,
        // so the ResourceManager can falsely report the path as present.
        if (SoundHelper::class.java.getResource("/assets/mnk/sounds/gui/$fileName.ogg") != null) return true
        // Secondary: resource-pack check – catches externally provided sounds.
        return client.resourceManager.getResource(
            Identifier.of("mnk", "sounds/gui/$fileName.ogg")
        ).isPresent
    }

    private fun playNamed(client: MinecraftClient, eventName: String) {
        val id    = Identifier.of("mnk", "gui.$eventName")
        val event = SoundEvent.of(id)
        client.soundManager.play(PositionedSoundInstance.ui(event, 1.0f))
    }

    /** Last-resort vanilla click with per-type pitch so types stay audibly distinct. */
    private fun playVanilla(client: MinecraftClient, type: GuiSoundType) {
        val pitch = when (type) {
            GuiSoundType.BOOL    -> 1.4f   // high, snappy toggle feel
            GuiSoundType.HEADER  -> 1.0f   // neutral navigation
            GuiSoundType.EXIT    -> 0.7f   // low, closing feel
            GuiSoundType.GENERAL -> 1.0f   // standard
        }
        client.soundManager.play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, pitch))
    }

    /**
     * Play the most specific available sound for [type].
     * Must be called on the render / main thread.
     */
    fun play(client: MinecraftClient, type: GuiSoundType) {
        // Stage 1 – type-specific OGG
        val specific = when (type) {
            GuiSoundType.BOOL    -> "click_bool"
            GuiSoundType.EXIT    -> "click_exit"
            GuiSoundType.HEADER  -> "click_header"
            GuiSoundType.GENERAL -> null   // jump straight to stage 2
        }
        if (specific != null && oggExists(client, specific)) {
            playNamed(client, specific)
            return
        }

        // Stage 2 – general OGG (always tried before MC fallback)
        if (oggExists(client, "click_general")) {
            playNamed(client, "click_general")
            return
        }

        // Stage 3 – vanilla MC sound (only when no custom OGG exists at all)
        playVanilla(client, type)
    }
}


