# Shared Vitals

A server-side Fabric mod for Minecraft 26.2 where survival and adventure players
share health, hunger, absorption hearts, status effects, and death across every dimension.

**Status:** first gameplay implementation. Multiplayer client playtesting is still needed.

- Mod name: **Shared Vitals**
- GitHub repository: [shared-vitals](https://github.com/evan6509/shared-vitals)
- Mod ID: `shared_vitals`
- Requirements: Java 25, Fabric Loader 0.19.3+, Fabric API for Minecraft 26.2

## Gameplay

- Damage and healing immediately update everyone's health. Hits on different
  players add together, including hits in the same tick.
- A hit that lowers shared health or absorption also gives teammates the vanilla
  hurt flash, hurt sound, and camera flinch, including across dimensions. This feedback does
  not apply another hit, damage teammates' equipment, or give them damage immunity.
  Physical knockback stays with the player who was hit. Healing, joins, and
  maximum-health adjustments do not trigger shared hurt feedback.
- Eating feeds everyone. Food level, saturation, and exhaustion are shared;
  everyone's activity contributes exhaustion. Food regeneration and starvation
  run once per server tick, regardless of player count.
- Absorption (golden hearts) is one shared supply. Golden apples and other
  absorption grants give everyone the same amount; damage to anyone consumes
  that supply once before reducing shared health. The absorption effect's
  strength, remaining duration, expiration, and removal are shared too. Late
  joins and reconnects adopt the remaining hearts without refilling them.
  Removing a timed absorption effect preserves hearts still supported by the
  source player's remaining vanilla absorption capacity, including direct grants.
- Status effects are shared, including their strength, remaining duration,
  particles, and normal HUD/inventory icons. Each effect ticks once for the group:
  regeneration, poison, wither, and hunger do not multiply with player count.
  Passive benefits such as speed and fire resistance apply normally to everyone.
  Milk or an effect-removal command on one participant removes the shared effects.
- Armor belongs to individual players. The damage remaining after the victim's
  defenses reaches the shared pools.
  A totem held by the victim can save the group before death is confirmed.
- When one player actually dies, all participating online players die. Other
  players' totems cannot stop that shared death. Ordinary death drops and
  `keepInventory` behavior still apply.
- The first player to respawn starts the next life with full health, 20 food,
  5 saturation, and no absorption. Later respawns join the current pool without
  refilling it. Normal Minecraft respawn rules apply, including hardcore restrictions.
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
Food ticks, peaceful regeneration, and status-effect ticks use the first living
participant to join as their vanilla context (including dimension-specific
gamerules and location-dependent effect behavior). Shared effects have one clock;
joining does not restart it, and it pauses while no participants are online.
Teams and configurable sharing groups are not included.

## Linked Hardcore integration

On **each Fabric backend**, install Shared Vitals alongside your Linked Hardcore
mod and enable compatibility as an operator (permission level 2) or from that
backend's console:

```mcfunction
/sharedvitals linkedhardcore true
```

Integration defaults to **off**, even when Linked Hardcore is installed. The
command takes effect immediately and saves `config/shared-vitals.json`, outside
the world folders that Linked Hardcore's reset executor deletes. Configure each
backend once, including standby backends, using their consoles if needed. It
does not need to be enabled again for each new world.

Health and hunger still share during play. On the first actual death, Shared
Vitals leaves that death for Linked Hardcore to report and puts surviving
participants into spectator. It does not kill teammates or create extra death
reports. Linked Hardcore handles its wait/countdown, death counters, run timer,
proxy transfer, and world reset as usual. A totem save does not end the run.

Proxy wait/countdown messages also mark the source world as ended, including
when a death occurred on another occupied backend or a proxy restart recovered
the handoff. Respawns, late joins, and ordinary source-server restarts stay in
spectator until the transfer finishes. A fresh destination world starts a new
full pool. This does not copy player inventories or change proxy routing.

```mcfunction
/sharedvitals status
/sharedvitals linkedhardcore false
```

Disable compatibility during an active run to restore ordinary Shared Vitals
death behavior. Mode changes are refused once a source run has ended, so a
pending transfer cannot accidentally reopen that world. Enabling requires the
`linkedhardcore` Fabric mod; startup also rejects an enabled configuration if
that mod was removed. Your existing Linked Hardcore backend/proxy setup is still
required. Its current mod requires Fabric API **0.156.0+26.2 or later**.

Sharing is local to each backend. This mode supports Linked Hardcore's group
handoff between backends; it does not synchronize individual health/hunger
changes between separate, simultaneously active servers.

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
