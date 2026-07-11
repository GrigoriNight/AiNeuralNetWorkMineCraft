# aibot

A server-side Minecraft Forge 1.7.10 mod that runs an autonomous, neural-network-driven fake player ("Direwolf20") on a live server. No client-side install required — it appears to other players as an ordinary player with a name tag, skin, and tab-list entry.

## What it does

- **Imitation learning**: a small hand-rolled MLP neural network learns to move, jump, sprint, mine, and attack by training on real players' actual in-game actions, recorded live as they play.
- **Autonomous behavior**: gathers wood/stone/ore/wool/food, builds and lives at its own base (crafting table, chest, bed, door, walls), crafts its own tools, avoids hazards (lava, water, protected areas), sleeps at night, and defends itself.
- **Goal queue**: `/brain goal <wood|stone|ore|wool|food> <amount>` gives it a to-do list it works through autonomously, in priority order above ambient behavior.
- **Optional chat AI**: can hold a live conversation and even act on requests ("come here", "go get me some wood") via a local LLM (Ollama), with zero cloud API cost.
- **Web dashboard**: a built-in HTTP server exposes live status, training stats, and an HTTP API.
- **Self-play training**: continuously retrains in the background from its own actions (capped to avoid drowning out real human-derived data) so it keeps improving even when no one's online.

## Commands

All commands are under `/brain` (see `/brain` with no arguments in-game for the full list): spawn/despawn, follow/copy a player, toggle active mining, manage the goal queue, view inventory, check status, and configure the chat AI.

## Building

Standard ForgeGradle 1.7.10 project.

```
gradle build
```

The compiled mod jar will be in `build/libs/`.

## Requirements

- Minecraft Forge 1.7.10
- Java 8
