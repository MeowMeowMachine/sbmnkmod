# MNK — Auto-Reply Mod

A **Fabric client-side mod** for Minecraft `1.21.11` that adds a fully configurable **regex-based Auto-Reply chat system** with an in-game GUI.  
Built for Hypixel (Guild / Party / Co-op / All chat) and vanilla servers.

---

## Features

### 🤖 Auto-Reply
- Trigger replies via **regex patterns** (case-sensitive or insensitive)
- Multiple **weighted reply options** per rule — responses are chosen randomly by probability
- Per-rule **channel filtering**: Guild, Party, Co-op, All
- **Anti-loop protection**: tracks recently sent messages (8-second window) to prevent infinite reply chains
- **1.5 s reply cooldown** + **100 ms scan throttle** to prevent spam

### 🔢 Sequence Format
Chain multiple messages and commands with tick-based delays:

```
"cmd1";20;"cmd2";30;"cmd3"
```

| Token | Meaning |
|---|---|
| `"text"` or `text` | Chat message (sent to the triggering channel) |
| `/say <msg>` | Routed to the triggering channel (Guild → `/gc`, Party → `/pc`, else plain chat) |
| `/command` | Executed as a Minecraft command |
| `20` | Delay in ticks before the next token (20 ticks = 1 s) |
| `$ign` | Replaced with the sender's in-game name |

**Examples:**
```
# Simple reply
hello back!

# Sequence with delay
"say hi";20;"say how are you"

# Channel-aware command sequence with sender name
"/say hi $ign";40;"/say welcome!"
```

### 🖥️ In-Game GUI
Open with `/mnk` — all settings are managed visually without editing any files.

- **Main Menu** — animated entry point
- **Features Screen** — toggle and configure features
- **Auto-Reply List** — create, edit, delete rules
- **Rule Editor** — full editor with:
  - Name & trigger regex field
  - Per-channel toggles (Guild / Party / Co-op / All)
  - Active / Case Sensitive toggles
  - Reply options with **weight input** and live **chance display** (`weight / total`)
  - Syntax highlighting in reply fields (aqua = text, red = delay, gold = `$ign`)
  - `[?]` tooltip with sequence format reference

---

## Commands

| Command | Description |
|---|---|
| `/mnk` | Open the main menu |
| `/mnk panic` | **Emergency stop** — disables Auto-Reply and clears the queue immediately |
| `/mnk autoreply on` | Enable Auto-Reply |
| `/mnk autoreply off` | Disable Auto-Reply and clear queue |
| `/mnk autoreply toggle` | Toggle on/off |
| `/mnk autoreply stop` | Clear the message queue (stop mid-sequence) |
| `/mnk autoreply status` | Show current state, queue size and active rules |
| `/mnk autoreply reset` | Clear cooldowns, queue and send history |
| `/mnk autoreply activateall` | Enable all rules |
| `/mnk autoreply deactivateall` | Disable all rules (without deleting them) |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) `0.19.3+` for Minecraft `1.21.11`
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) `0.141.4+1.21.11`
3. Install [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) `1.13.12+kotlin.2.4.0`
4. Drop `mnk-1.0.0.jar` into your `mods/` folder

---

## Configuration

Config is stored at `.minecraft/config/mnk.json` and is fully managed through the in-game GUI.

<details>
<summary>Example <code>mnk.json</code></summary>

```json
{
  "autoReplyEnabled": true,
  "autoReplyRules": [
    {
      "name": "Meow reply",
      "triggerRegex": "meow",
      "enabled": true,
      "onGuild": true,
      "onParty": true,
      "onCoop": true,
      "onAll": true,
      "caseSensitive": false,
      "options": [
        { "text": "/say meow", "weight": 10 }
      ]
    }
  ]
}
```

</details>

---

## Building from Source

**Requirements:** JDK 21, Gradle (wrapper included)

```bash
git clone <repo>
cd mnkmod
./gradlew build
# Output: build/libs/mnk-1.0.0.jar
```

---

## Dependencies

| Dependency | Version |
|---|---|
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.141.4+1.21.11` |
| Fabric Language Kotlin | `1.13.12+kotlin.2.4.0` |
| Kotlin | `2.4.0` |
| Java | `21` |

---

## License

See [LICENSE](LICENSE).
