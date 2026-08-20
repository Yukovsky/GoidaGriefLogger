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
| Items | Pickup, drop, throw, craft, smelt, consume, durability break |
| Containers | Access and full transactions — every slot, both directions, including furnaces and brewing stands |
| Carried containers | Backpacks and bags opened from the inventory, which have no position in the world |
| Explosions | TNT, creepers, beds and respawn anchors, cannons and other mod sources; hover shows who set it off and with what |
| Pistons | Block push and pull |
| Entities | Mob kills by player, with a snapshot of anything that made the mob unusual |
| Decorations | Item frames, paintings, signs |
| Deaths | Player inventory snapshot on death, including Curios accessories |
| Sessions | Join and leave |
| Commands | Chat messages and commands |
| Mod blocks | Changes from automation mods, fake players, mob griefing |
| Gravity | Sand, gravel, and other falling blocks |

Machines that run on their own — furnaces, blast furnaces, smokers, brewing stands — report what
they consume and produce themselves, so their work is never attributed to whoever happened to have
the GUI open, and nothing can be hidden in an unusual slot.

### Rollback System

- **`/gl rollback`** — return an area to its state at a chosen moment: every logged action in the
  window is undone, newest first, so the oldest record decides the final state
- **`/gl restore`** — the exact inverse; replays the same actions in chronological order
- **`/gl preview`** — see the result before applying it, with the affected area outlined in particles
- **`/gl preview accept`** — apply what the preview shows, reusing the same filter
- Killed entities come back with their saved data — custom names, equipment, modified attributes
- Rows already rolled back are excluded from a second rollback, so repeating a command cannot
  duplicate or destroy items
- Async batch processing — all queries and writes run off the main thread, no TPS impact

### World Safety

The database is pinned to the world it was collected in. A random marker is stored **inside the
world save**, so resetting the map takes it along; the config would have survived and told you
nothing.

If the marker in the database no longer matches the world, the server log says so at startup and
operators are warned when they join. Nothing is deleted automatically — losing logs to a false
positive is worse than a warning.

- **`gl wipe`** — drops every table and recreates an empty schema, then pins it to the current world
- Console and RCON only. An in-game operator cannot run it, and neither can a command block:
  the history exists to hold operators accountable too
- Two steps: `gl wipe` reports what will be lost, `gl wipe confirm` within 30 seconds does it

### Inspect & Lookup

- **`/gl inspect`** — toggle inspect mode; click any block to see its full history
- **`/gl lookup`** — query the log database with filters and pagination
- Rolled-back entries are shown struck through and dimmed, recognisable at a glance

### Database

- **SQLite** — default, zero configuration, bundled in the jar
- **MySQL / MariaDB** — for high-volume or multi-instance setups, also bundled
- Writes are batched per table and committed together — one prepared statement and one round trip
  per table instead of one per row
- NBT snapshots are content-addressed: identical snapshots are stored once, and a snapshot that
  says nothing beyond the default is not stored at all

### Integrations *(auto-detected, no extra setup)*

- [Create](https://modrinth.com/mod/create) — contraption block changes, mechanical arm and launcher item tracking
- [Tom's Simple Storage](https://modrinth.com/mod/toms-storage) — terminal transactions attributed to the correct player
- [Sophisticated Backpacks](https://modrinth.com/mod/sophisticated-backpacks) — backpack item tracking
- [Curios](https://modrinth.com/mod/curios) — accessory slots included in the death snapshot
- [Sable](https://modrinth.com/mod/sable) — assembly, movement and disassembly of physics structures;
  the same hook covers Create Aeronautics and Simulated, which route through Sable

Other backpack mods need no integration: carried containers are covered generically.

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 or later |
| Java | 21 |
| Side | Server only |

> **Incompatibility:** GoidaGriefLogger cannot run alongside the original GriefLogger mod. Remove `grieflogger-*.jar` from your server before installing this mod — both claim `/gl` and write to the same database schema.

> **Upgrading from 2.x:** version 3.0.0 added a primary key to the `blocks` and `containers`
> tables, which a 2.x database does not have. Start from a clean database — the migration reports
> the problem at `ERROR` level if it finds the old shape. Upgrades within 3.x need no action.

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
  enableHoppers = false         # many events; goes through the capability hook, logged as [AUTO]
  enableItemPickup = true
  enableContainerAccess = true
  enableContainerTransactions = true
  enableCarriedContainers = true   # backpacks and bags opened from the inventory
  enableBlockActivation = true
  enableModBlockChanges = true
  enableEntityGriefing = true
  enableGravityBlocks = true
  # off by default — enable enablePlayerDeath if you want death snapshots (Curios included):
  # enableSigns, enableItemFrames, enablePlayerDeath,
  # enableFireSpread, enableLavaFlow, enableWaterFlow, enableSculk, enableIceSnow

[performance]
  maxExplosionBlocks = 500
  asyncQueueSize = 10000
  deduplicationWindowMs = 100   # ms window for deduplicating repeated events
  maxNbtSizeKb = 512
  environmentalRateLimitPerBlockSec = 5

[rollback]
  restoreEntities = true        # bring killed entities back; costs almost nothing in storage
  batchSize = 200               # blocks processed per tick
  progressIntervalTicks = 20
  maxRestoreAgeDays = 7
  maxPreviewDurationSec = 60
  previewAutoCancelBlocks = 50  # cancel the preview once the player walks this far

[integrations]
  universalItemTracking = false  # experimental: track all IItemHandler movements
  [integrations.create]
    enabled = true
  [integrations.toms]
    enabled = true
  [integrations.backpacks]
    enabled = true
  [integrations.sable]
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

Every subcommand is listed below. Access differs — one of them is deliberately out of reach
from inside the game.

| Command | What it does | Access |
|---|---|---|
| `/gl lookup <filters>` | Query the log; rolled-back rows appear struck through | OP 2 |
| `/gl page <n>` | Another page of the last lookup | OP 2 |
| `/gl inspect` | Toggle inspect mode, then click a block for its history | OP 2 |
| `/gl rollback <filters>` | Return the area to its state at the chosen moment | OP 2 |
| `/gl restore <filters>` | The inverse of a rollback — replay the same actions | OP 2 |
| `/gl preview <filters>` | Show the result without changing anything, area outlined in particles | OP 2 |
| `/gl preview accept` | Apply what the preview shows, reusing its filter | OP 2 |
| `/gl preview cancel` | Drop the preview | OP 2 |
| `/gl abort` | Stop your running rollback or restore | OP 2 |
| `/gl status` | Queue depth, running jobs, and whether the world matches the database | OP 2 |
| `/gl help` | In-game commands and filters (console-only commands are not listed there) | OP 2 |
| **`gl wipe`** | **Wipe the whole database and pin it to the current world** | **Console / RCON only** |
| **`gl wipe confirm`** | **Confirm the wipe within 30 seconds** | **Console / RCON only** |

Permission node for the in-game commands: `goidagrieflogger.command` (default: OP level 2),
resolvable through FTB Ranks if it is installed.

> ### `gl wipe` is not an in-game command
>
> It runs only from the server console or RCON. An operator inside the game cannot run it, and
> neither can a command block — a redstone-triggered wipe would be an excellent way to cover
> one's tracks. The log exists to hold operators accountable too, so the ability to erase it
> from inside the game would defeat the point.
>
> Note there is no leading slash: you type `gl wipe` at the server console.
> It is needed after a map reset — see [World Safety](#world-safety).

### Parameters

`time` and `radius` are required; everything else is optional. Each has a short alias.

| Parameter | Description |
|---|---|
| `time:` `t:` | **Required.** How far back — `1h`, `30m`, `2d`, `1d12h` |
| `radius:` `r:` | **Required.** Blocks around you, or `r:global` for the whole world |
| `world:` `w:` `dim:` | Dimension — `world:the_nether`, or `world:*` for all of them |
| `user:` `u:` | Player or system user — `u:Steve`, `u:[TNT]`; `u:!Steve` excludes |
| `action:` `a:` | `place`, `break`, `use`, `kill`, `container`, `session`; `a:!break` excludes |
| `source:` `s:` | What caused it — `s:tnt`, `s:creeper`, `s:create:deployer` |
| `include:` `inc:` | Only these blocks or items — `stone`, `minecraft:chest`, mask `inc:*ore*` |
| `exclude:` `exc:` | Skip these |
| `blocks` `b` | Blocks only |
| `items` `i` | Items only |

Pagination is a separate command, `/gl page <n>`, not a filter.

### Usage

```
/gl lookup u:Steve r:10 t:1h
/gl lookup u:Steve t:2h a:break b:minecraft:diamond_ore
/gl page 2

/gl preview u:Griefer t:2h r:50     # show the result, outline the area
/gl preview accept                   # apply it with the same filter
/gl preview cancel                   # drop it

/gl rollback u:Griefer t:2h r:50
/gl restore  u:Griefer t:2h r:50     # the inverse of the rollback above

/gl inspect                          # toggle inspect mode, then click a block
/gl abort                            # stop your running rollback or restore
/gl status
/gl help
```

At the server console (no slash, not available in game):

```
gl wipe                              # reports what will be lost
gl wipe confirm                      # within 30 seconds — wipes and re-pins to this world
```

---

## Building from Source

```bash
git clone https://github.com/Yukovsky/GoidaGriefLogger.git
cd GoidaGriefLogger
./gradlew build
```

Output jar: `build/libs/goidagrieflogger-<version>.jar`. Requires Java 21.

### Self-check

```bash
sh tools/selfcheck.sh
```

Brings up the real schema in a temporary SQLite database and runs the real queries and writes
against it. Covers the parts that fail silently rather than loudly: the `rolled_back` predicate,
parameter binding order, apply order for rollback versus restore, batched writes, queue drain on
shutdown, NBT deduplication, and how rolled-back rows are rendered.

---

## Credits

GoidaGriefLogger is based on [GriefLogger](https://github.com/daqem/GriefLogger) by **daqem** (Apache-2.0).  
The database schema, event model, and core logging architecture originate from that project.  
See [NOTICE](NOTICE) for full attribution.

---

## License

[Apache License 2.0](LICENSE)
