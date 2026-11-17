# 🌊 Falling Sea

**Falling Sea** is a Minecraft Fabric mod that gradually lowers the world's water level, causing oceans, lakes, rivers, and other bodies of water to slowly dry up.

Watch coastlines expand, ocean floors emerge, and underwater structures become accessible as the world runs out of water.

## ✨ Features

- **Gradual Water Reduction** – Water levels decrease automatically over time.
- **Two Drying Modes:**
  - **Sea Level:** Gradually lowers the global water level.
  - **Drought:** Progressively dries up individual bodies of water.
- **Customizable Intervals** – Choose how often and how much water disappears.
- **Random Drying Events** – Enable unpredictable water level drops every 1–365 Minecraft days.
- **Water Source Removal** – Removes water sources, flowing water, and aquatic plants above the target level.
- **Refill Prevention** – Optionally prevents water from flowing back into dried areas.
- **Chunk-Aware Processing** – Processes loaded chunks gradually to reduce performance impact.
- **Automatic Cleanup** – Rechecks areas to remove remaining water and unwanted water formations.
- **Countdown Effect** – Displays a Minecraft-style countdown before the next drying event.
- **Mod Menu Integration** – Configure the mod directly in-game.
- **Multilingual Support** – English and German translations.
- **Persistent Progress** – Water levels and settings survive world restarts.

## ⚙️ Configuration

Falling Sea can be configured through **Mod Menu**, its configuration file, or in-game commands.

Available settings include:

- Drying mode and starting water level
- Minimum water level
- Blocks removed per drying event
- Fixed or randomized drying intervals
- Minimum and maximum random days
- Water refill prevention
- Chunk processing and performance settings
- Countdown visibility and duration
- Debug messages

Fixed intervals and randomized drying events are mutually exclusive.

## 💬 Commands

| Command | Description |
|---------|-------------|
| `/fallingsea start` | Start or resume drying |
| `/fallingsea stop` | Pause drying |
| `/fallingsea status` | Display the current status |
| `/fallingsea mode sea_level` | Enable global sea level reduction |
| `/fallingsea mode drought` | Enable drought mode |
| `/fallingsea lower <blocks>` | Lower the water level manually |
| `/fallingsea setlevel <y>` | Set the target water level |
| `/fallingsea set randomschedule true` | Enable random drying events |
| `/fallingsea set randomschedule false` | Enable fixed intervals |
| `/fallingsea reload` | Reload configuration |

Additional settings are available through `/fallingsea set`.

## 📦 Requirements

- **Minecraft:** Java Edition 26.3
- **Mod Loader:** Fabric
- **Dependencies:** Fabric API
- **Optional:** Mod Menu

## ⚠️ Important

Falling Sea permanently modifies water blocks in your Minecraft world.

**Always back up your world before installing or using this mod!**

Large worlds may require additional processing time, depending on the configured settings.

## 📜 Credits

**Author:** TueftelTyp

Inspired by the idea of reversing the effects of Rising Sea.

Enjoy watching your Minecraft world slowly dry up!
