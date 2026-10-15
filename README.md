# Shared Vitals

A server-side Fabric mod for Minecraft 26.2 where survival and adventure players
share health, hunger, and death across every dimension.

**Status:** first gameplay implementation. Multiplayer client playtesting is still needed.

- Mod name: **Shared Vitals**
- GitHub repository: [shared-vitals](https://github.com/evan6509/shared-vitals)
- Mod ID: `shared_vitals`
- Requirements: Java 25, Fabric Loader 0.19.3+, Fabric API for Minecraft 26.2

## Gameplay

- Damage and healing immediately update everyone's health. Hits on different
  players add together, including hits in the same tick.
- Eating feeds everyone. Food level, saturation, and exhaustion are shared;
  everyone's activity contributes exhaustion. Food regeneration and starvation
  run once per server tick, regardless of player count.
- Armor, absorption hearts, and status effects belong to individual players.
  The health damage remaining after the victim's defenses is shared. A totem
  held by the victim can save the group before death is confirmed.
- When one player actually dies, all participating online players die. Other
  players' totems cannot stop that shared death. Ordinary death drops and
  `keepInventory` behavior still apply.
- The first player to respawn starts the next life with full health, 20 food,
  and 5 saturation. Later respawns join the current pool without refilling it.
  Normal Minecraft respawn rules apply, including hardcore restrictions.
- Late joins and reconnects adopt the current pool. Joining while the pool is
  dead causes death; respawning starts the next life. The pool persists when
  everyone disconnects and through normal world saves/restarts.
- Creative and spectator players are excluded. Switching back to survival or
  adventure joins the current pool immediately.

Install the mod JAR and Fabric API on the server; connecting players can use
vanilla clients. For singleplayer, install them in your Fabric client's `mods`
folder. No commands or setup are required.

The shared health cap is the smallest maximum health among living participants,
so health-changing effects cannot leave different players with different values.
Food ticks and peaceful regeneration use the first living participant to join
as their vanilla context (including dimension-specific gamerules). Player status
effects tick individually. Teams and configurable sharing groups are not included.

## Build

```sh
./gradlew build
```

On Windows, use `gradlew.bat build`. The installable JAR is
`build/libs/shared-vitals-0.1.0+mc26.2.jar`; the `-sources.jar` is for development.

## Contributing and releases

Work on `codex/development`. Merge into `main` when you want a release.
Once hosted on GitHub, development pushes and pull requests run build checks.
Every push to `main` builds and publishes a GitHub Release containing the mod JAR,
`CHANGELOG.md`, and `SHA256SUMS`. This includes the first push and direct commits
to `main` as well as merges.

See [the development guide](docs/DEVELOPMENT.md) for branch and release details.
