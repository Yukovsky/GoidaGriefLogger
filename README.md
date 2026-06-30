<div align="center">

# GoidaGriefLogger

[![Latest Release](https://img.shields.io/github/v/release/Yukovsky/GoidaGriefLogger?style=flat-square&label=latest&color=brightgreen)](https://github.com/Yukovsky/GoidaGriefLogger/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.228+-orange?style=flat-square)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-lightgrey?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/Yukovsky/GoidaGriefLogger/build.yml?style=flat-square)](https://github.com/Yukovsky/GoidaGriefLogger/actions)

**Server-side world event logger with rollback, restore, and inspect for NeoForge 1.21.1.**

Log everything that happens in your world — block changes, container activity, explosions, pistons, mob griefing, and more. Roll back any damage without touching server performance.

A fork and full absorption of [GriefLogger](https://github.com/daqem/GriefLogger) by daqem — unified into a single mod, single database connection, single write queue.

| Links | |
|---|---|
| Discord | [discord.gg/prJwFwy5ns](https://discord.gg/prJwFwy5ns) |
| Issues | [github.com/Yukovsky/GoidaGriefLogger/issues](https://github.com/Yukovsky/GoidaGriefLogger/issues) |
| Releases | [github.com/Yukovsky/GoidaGriefLogger/releases](https://github.com/Yukovsky/GoidaGriefLogger/releases) |

</div>

---

## Features

### Event Logging

| Category | What is logged |
|---|---|
| Blocks | Player placement, breaking, and interaction |
| Items | Pickup, drop, throw |
| Containers | Chest opens, hopper transfers (configurable) |
| Explosions | TNT, Creeper, Wither, End Crystal, and mod sources |
| Pistons | Block push and pull |
| Entities | Mob kills by player |
| Decorations | Item frames, paintings, signs |
| Deaths | Player inventory snapshot on death |
| Sessions | Join and leave |
| Commands | Chat messages and commands |
| Mod blocks | Changes from automation mods, fake players, mob griefing |
| Gravity | Sand, gravel, and other falling blocks |

### Rollback System

- **`/gl rollback`** — roll back changes by time, player, radius, action, or block type
- **`/gl restore`** — restore previously rolled-back changes
- **`/gl preview`** — preview what a rollback will affect before running it
- Async batch processing — all operations run off the main thread, no TPS impact

### Inspect & Lookup

- **`/gl inspect`** — toggle inspect mode; click any block to see its full history
- **`/gl lookup`** — query the log database with filters and pagination

### Database

- **SQLite** — default, zero configuration, bundled in the jar
- **MySQL / MariaDB** — for high-volume or multi-instance setups, also bundled

### Integrations *(auto-detected, no extra setup)*

- [Create](https://modrinth.com/mod/create) — contraption block changes, mechanical arm and launcher item tracking
- [Tom's Simple Storage](https://modrinth.com/mod/toms-storage) — terminal transactions attributed to the correct player
- [Sophisticated Backpacks](https://modrinth.com/mod/sophisticated-backpacks) — backpack item tracking

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 or later |
| Java | 21 |
| Side | Server only |

> **Incompatibility:** GoidaGriefLogger cannot run alongside the original GriefLogger mod. Remove `grieflogger-*.jar` from your server before installing this mod — both claim `/gl` and write to the same database schema.

---

## Installation

1. Download the latest jar from [Releases](https://github.com/Yukovsky/GoidaGriefLogger/releases).
2. Place it in the `mods/` folder of your NeoForge server.
3. Start the server — the config file is created at `config/goidagrieflogger-common.toml`.

The SQLite and MySQL-connector drivers are embedded in the jar. Nothing extra to install.

---

## Configuration

`config/goidagrieflogger-common.toml` — created on first launch with defaults.

```toml
[database]
  useMysql = false              # true = MySQL/MariaDB, false = SQLite (default)
  sqliteFile = "database.db"    # path relative to server root

  mysqlHost = "localhost"
  mysqlPort = 3306
  mysqlDatabase = "database"
  mysqlUsername = "username"
  mysqlPassword = "password"
  mysqlTimeout = 5000

[logging]
  enableExplosions = true
  enablePistons = true
  enableHoppers = false         # generates a lot of events; off by default
  enableModBlockChanges = true
  enableEntityGriefing = true
  enableGravityBlocks = true
  enableItemPickup = true
  enableContainerAccess = true
  enableContainerTransactions = true
  enableBlockActivation = true
  # Phase 2/3 options (off by default): signs, itemFrames, playerDeath,
  # fireSpread, lavaFlow, waterFlow, sculk, iceSnow

[performance]
  asyncQueueSize = 10000
  maxExplosionBlocks = 500
  deduplicationWindowMs = 100   # ms window for deduplicating repeated events
  maxNbtSizeKb = 512

[rollback]
  batchSize = 200               # blocks processed per tick
  maxRestoreAgeDays = 7
  maxPreviewDurationSec = 60

[integrations]
  universalItemTracking = false  # experimental: track all IItemHandler movements
  [integrations.create]
    enabled = true
  [integrations.toms]
    enabled = true
  [integrations.backpacks]
    enabled = true

[blacklists]
  worldBlacklist = []           # dimension IDs to skip entirely
  blockBlacklist = []
  modBlacklist = []
  sourceTypeBlacklist = []      # e.g. ["water", "lava", "hopper"]
  entityTypeBlacklist = []
```

---

## Commands

Permission node: `goidagrieflogger.command` (default: OP level 2).

### Parameters

| Parameter | Description |
|---|---|
| `u:<player>` | Player name |
| `t:<time>` | Time limit — `1h`, `30m`, `2d`, etc. |
| `r:<radius>` | Block radius around your position |
| `a:<action>` | Action type — `break`, `place`, `kill`, `explode`, `pick_up`, … |
| `b:<block>` | Block or item ID — e.g. `minecraft:chest` |
| `p:<page>` | Page number for lookup results |

### Usage

```
/gl lookup u:Steve r:10 t:1h
/gl lookup u:Steve t:2h a:break b:minecraft:diamond_ore

/gl rollback u:Griefer t:2h r:50
/gl preview  u:Griefer t:2h r:50
/gl restore  u:Griefer t:2h r:50

/gl inspect
```

---

## Building from Source

```bash
git clone https://github.com/Yukovsky/GoidaGriefLogger.git
cd GoidaGriefLogger
./gradlew build
```

Output jar: `build/libs/goidagrieflogger-<version>.jar`. Requires Java 21.

---

## Credits

GoidaGriefLogger is based on [GriefLogger](https://github.com/daqem/GriefLogger) by **daqem** (Apache-2.0).  
The database schema, event model, and core logging architecture originate from that project.  
See [NOTICE](NOTICE) for full attribution.

---

## License

[Apache License 2.0](LICENSE)
