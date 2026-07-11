# AiNeuralNetWorkMineCraft

A server-side Minecraft Forge **1.7.10** mod that runs an autonomous, neural-network-driven fake player ("Direwolf20") on a live server. No client-side install required — it appears to other players as an ordinary player with a name tag, skin, and tab-list entry, driven entirely server-side via a fake network handler (no real client connection behind it).

## What is a Minecraft AI bot?

A Minecraft AI bot is an autonomous or semi-autonomous entity controlled by software that perceives the game world and decides what to do in it, rather than following a fixed, hand-written script. The category is broad — it spans everything from a simple macro that repeats one action forever, up through agents that genuinely learn from experience and improve over time. What separates a bot like that from a command block or a scripted NPC is the same thing that separates any AI system from a lookup table: it isn't told the answer for every situation in advance, it works one out from whatever state it's actually in.

Minecraft turns out to be an unusually good place to build one. The world is open-ended, physically consistent, and full of real subgoals — find food, find shelter, find materials, survive the night — without anyone having to define those goals explicitly for the bot. That's exactly why it shows up so often as a research testbed as well as a gameplay feature: it's a sandbox complex enough to be interesting and simple enough to actually instrument.

**This project sits toward the "genuinely learns" end of that spectrum, honestly stated:** its decisions come from a real neural network trained continuously on this specific server, not from a fixed script or a large pretrained model bolted onto the game. It watches actual players and copies what they do (imitation learning), cautiously reinforces its own choices when they're going well (self-play, deliberately capped so it can never drown out real human data), and keeps training even while nobody's around to watch — either through 16 extra bots exploring for more data, or, when even that would get in a real player's way, by replaying what it's already learned in the background like a mind still working while the body isn't doing anything. It doesn't have vision or language understanding beyond an optional local chat model layered on top — its "perception" is direct, structured game state (nearby blocks, hazards, entities, health, position), read straight from the server every tick. That's a narrower, more mundane kind of intelligence than the phrase "AI bot" might suggest, and that's deliberate: it's built to actually work reliably on one real, heavily modded server, not to demonstrate a general capability.

Built and tested on a heavily modded ~150-mod 1.7.10 server, including (non-exhaustive): Applied Energistics 2, Thermal Expansion, Railcraft, BuildCraft, IndustrialCraft 2, Forestry, Tinkers' Construct, EnderIO, Thaumcraft, Botania, Twilight Forest, Mystcraft, Witchery, PneumaticCraft, Big Reactors, RFTools, ComputerCraft, Extra Utilities, Iron Chest, JourneyMap, and dozens of compat/library mods (CodeChickenCore, ForgeMultipart, WAILA, and similar). The mod itself has no hard dependency on any of these — it interacts with the world through vanilla Forge APIs — but was designed and tuned against that environment.

## How it works

- **Perception**: a `StateEncoder` reads the bot's real in-game state each tick (nearby blocks, hazards, entities, health/food, position) directly from server-side game objects — not screen pixels or a client protocol, so it's a fundamentally more direct signal than a real player would have.
- **Imitation learning**: a small hand-rolled MLP neural network (`NeuralNetwork.java`, no external ML library) learns to move, jump, sprint, mine, and attack by training continuously on real players' actual recorded actions (`PlayerActionRecorder`) as they play on the server.
- **Self-play**: when the network's own predictions are used to drive movement/action choices, those get fed back in as additional training samples too — capped at a fraction of the dataset so it can't drown out real human-derived data or reinforce its own bad habits.
- **Priority-tiered decision loop**: each tick, `BotPlayerAI` runs through sleep → flee-if-critical → retaliate-if-attacked → forced-home → copy/follow-a-player → seek-and-kill-hostile-mobs → return-home-if-strayed → deposit-ore → work-an-assigned-goal → explicit-mining-toggle → **the neural network's own decision**. An `IDLE` network decision falls through to a scripted gather/build/loot/farm chain.
- **No real pathfinding** anywhere — movement is "turn toward target, walk straight," with jump/break-obstacle/reroute/teleport as escalating fallbacks when it gets stuck.
- **Builds and lives at its own base**: once it has the materials, it walks to a location picked at random some distance from its spawn anchor and builds a walled base with a crafting table, chest, bed, and door — using the crafting table for real (tools/chest/door/bed all require being near one, matching vanilla's actual 2x2-vs-3x3 crafting-grid rules; only planks/sticks/the table itself skip that, also matching vanilla). Once built, that base becomes its actual home: it returns there when it strays too far, sleeps there at night, and retreats there if it flees a fight.
- **Extra self-play bots**: when no real player is online, up to 16 additional simple bots spawn purely to generate more training data, wandering widely, mining and fighting whatever they encounter to help cover varied terrain (caves, structures, forests). They despawn instantly the moment a real player joins, and share the same underlying network, but have none of the main bot's goal/base/chat complexity.
- **Goal queue**: `/brain goal` gives it a to-do list it works through autonomously, in priority order above ambient behavior, whether the request came from a command or from chat.
- **Optional chat AI**: can hold a live conversation and act on requests ("come here", "go get me some wood") via a local LLM (Ollama) — no cloud API, no per-message cost. Grounded in what the bot is actually doing (won't claim to be doing something it isn't) and in the real modpack, so it doesn't invent vanilla-only or real-world trivia.
- **Web dashboard**: a built-in HTTP server (`WebDashboardServer`) exposes live status, training stats, inventory, and an HTTP API secured with a persisted API key.

## Commands

All commands are under `/brain <subcommand>`:

| Command | What it does |
|---|---|
| `spawn` | Spawns a basic vanilla-zombie AI test mob (no client mod needed) |
| `spawnplayer` | Spawns the fake player bot |
| `despawnplayer` / `hide` | Despawns the bot; stays offline until `spawnplayer` is run again |
| `rename <name>` | Renames the bot (despawns and respawns under the new name) |
| `sleep` | Makes the bot try to sleep in a nearby bed |
| `home` | Bot walks home |
| `follow [player]` | Bot follows you (or a named player) around |
| `unfollow` | Stops following |
| `copy` | Bot mirrors your movement/look/jump input 1:1 |
| `scan` | Reports what's directly around the bot (blocks, hazards, nearby entities) |
| `base` | Reports base-building progress |
| `status` | Reports current behavior, health, food, position, active targets |
| `mine` | Toggles active ore-seeking on/off |
| `goal <wood\|stone\|ore\|wool\|food> <amount>` | Adds a task to the bot's to-do list |
| `goal clear` | Clears the to-do list |
| `goals` | Lists the current to-do list and progress |
| `chat` | Toggles the LLM chat AI on/off |
| `chat model <name>` | Sets the Ollama model to use |
| `chat url <url>` | Sets the Ollama endpoint (local or tunneled) |
| `equip` | Gives the bot whatever armor piece you're holding |
| `gui` | Opens the bot's inventory (vanilla chest-style GUI, drag and drop) |
| `items` | Text summary of the bot's armor/held item/inventory |
| `weburl` | Prints the web dashboard URL |
| `apikey` | Prints the web dashboard's API key/base URL |
| `save` / `load` | Manually save/load the neural network (also happens automatically) |
| `stats` | Training sample counts, training steps, last average loss |
| `test` | Runs the network's prediction against your own current surroundings |

## Building

Standard ForgeGradle 1.7.10 project, Java 8.

```
gradle build
```

The compiled mod jar will be in `build/libs/`.

## Requirements

- Minecraft Forge **1.7.10**
- Java 8
