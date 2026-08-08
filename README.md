# Fortune Pillars

**Fortune Pillars** is an **open-source Minecraft minigame** built and maintained **by SwapFlip**, where players **spawn on bedrock pillars**, get **random items** every few seconds, and fight to be the last one standing.

**Highly configurable** modes and **lightweight performance** make it perfect for small and medium servers.

## Special about this Version

The special part about this plugin is the high *customizability* and *a bunch of different modes*!
Here are the different modes that are available, with each of them being fully customizable:

| Name           | Cooldown | Time | Pillars  | Description                                         |
|----------------|----------|------|----------|-----------------------------------------------------|
| Blocky         | 10s      | 4m   | Circular | Starts with a weapon; only blocks are given.        |
| Chaos          | 3s       | 8m   | Random   | No filters, randomized pillars, fast item cooldown. |
| Classic        | 5s       | 5m   | Circular | Classic rules, inspired by CubeCraft.               |
| Item-Only      | 10s      | 10m  | Circular | Only items (no blocks): long & tough.               |
| Item-Shuffle   | 10s      | 5m   | Circular | Items replaced every 10s (9 new items).             |
| Original       | 5s       | 5m   | Circular | Original gameplay as seen in early videos.          |
| Player-Shuffle | 10s      | 5m   | Circular | All players randomly swapped every 10s.             |

## Inspiration

This plugin is inspired by pillar-style minigames featured by creators like [CheapPickle](https://youtube.com/@CheapPickle) and large servers such as [CubeCraft](https://www.cubecraft.net/), and is a maintained continuation of the open-source [Pillar Peril](https://github.com/MarcPG1905/PillarPeril) plugin. It is independent, open-source, and not affiliated with those projects.

## Commands

### Game Management

- **Start** using `/game start <mode> <center> <world> <players>`
- **Stop** using `/game stop <id>`
- Get **info** using `/game info <id>`
- **List** games using `/game list`

> Use `/game list raw` to get a **raw list** which uses `2yiKLf2h1X6CenH1;h1enf2H12yiX6CKL;I8jos1lsvkh57wjs` format or, just `empty`.

### Queue

- **Join/leave** using `/queue join/leave`
- **Do admin stuff** using `/queue admin <operation>`

### Configuration

- **Get** a value using `/pp-config modify <path> get`
- **Set** a value using `/pp-config modify <path> set <value>`
- **Modify** a list using `/pp-config modify <path> add/remove <value>`
- **Reload** the config using `/pp-config reload`

## Configuration

See **Commands** section above for the `/pp-config` command to modify the configuration in-game.

The configuration is designed to be simple and ships with comments, which explain themselves.

By default, the queue is disabled, so it needs to manually be enabled.
There are two queue modes: `command` (players use /queue) and `auto` (players are automatically queued).

## Translations

Translations are auto-downloaded from our translation server; if the server is not reachable, the plugin falls back to English. (No sensitive data is sent.)

---

<details>
<summary>Info about Metrics collected</summary>

Fortune Pillars by default collects some data about how people use the plugin, so game modes can better be adjusted and the game can further be balanced.

This is a complete list of all data collected, besides [FastStats](https://faststats.dev/) defaults:

- Amount of games running/started
- Game modifiers most frequently used
- Average amount of players per game

If you do not want to send any of these metrics, you can set `disable-faststats` in the configuration to true, or follow the instructions given on first startup with FastStats.
</details>

## Releases & Contact

You can find the official releases of **Fortune Pillars** on:

**Recommended:** [GitHub Releases](https://github.com/SwapFlip/FortunePillars/releases)

For questions, bug reports or feature requests, open an issue on [GitHub](https://github.com/SwapFlip/FortunePillars/issues) or reach out to **SwapFlip**.