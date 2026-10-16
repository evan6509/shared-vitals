# Development

## Branches

- `codex/development`: normal development by you and Codex. This is the checked-out branch.
- `main`: changes you have chosen to release. Nothing merges automatically.

To switch back to development:

```sh
git switch codex/development
```

When you choose to release, merge `codex/development` into `main` through a GitHub
pull request, or merge locally and push `main`. The workflow does not merge for you.
The GitHub repository is `evan6509/shared-vitals`.

## Local checks

Use JDK 25 and Python 3.10 or later:

```sh
./gradlew --no-daemon clean build
./gradlew --no-daemon runGametest
python3 -m unittest discover -s tests -v
python3 scripts/verify_jar.py build/libs/shared-vitals-0.1.0+mc26.2.jar 0.1.0+mc26.2
```

`runGametest` starts a headless Minecraft server with the actual mod and mixins.
It covers shared damage/healing, same-tick hits, armor/absorption, food and
exhaustion, regeneration/starvation timing, joins, mode changes, totems, shared
death, real player respawns, dimensions, maximum health, and saved-data disk
reloads. Results are written to `build/gametest-results.xml`. Test code is not
included in the installable mod JAR.

Hurt feedback tests inspect outbound vanilla damage-event and camera-animation
packets, including cross-dimension delivery, excluded game modes, absorption,
rejected hits, and ordinary health synchronization. They also check that feedback
does not apply extra damage, damage teammate armor, or grant damage immunity.
Absorption is consumed once by the actual hit. In two-client playtesting, check
the red flash, hurt sound, and camera flinch on both clients and when observing
a teammate. Physical knockback applies
only to the actual victim.

Absorption and all status effects are persisted shared state. Test golden apples, absorption-only hits,
spillover into health, same-tick hits, stronger/hidden absorption effects, milk,
expiration, cross-dimension joins, mode changes, death/respawn, and saved-data
reloads. Joining or restoring an absorption effect must not refill consumed hearts.

All participants reference one instance of each status effect. Only the first
living participant ticks it, once per server tick; others receive its vanilla
attributes, particles, icon, amplifier, and remaining duration. Regression tests
check native icon/removal packets, three-player regeneration/poison/wither/hunger
rates, normal speed/fire-resistance behavior, hidden effect downgrades, and
join/leader/creative-mode transitions. Tick-dependent effect logic uses the same
participant's vanilla context as shared food. For direct repeated player-tick
tests, call the state's server-start tick between simulated ticks.

Linked Hardcore coverage checks the opt-in command and permissions, configuration
persistence/failure handling, original-death-only handling, totems, late arrivals,
proxy respawns, ended-run persistence, fresh-world initialization, and migration
of existing saved data. The mode is stored per backend in
`config/shared-vitals.json`; the ended-run flag is stored with the world's pool.

To also load the real Linked Hardcore mod and exercise its wait/countdown hooks
and outbound `PLAYER_DIED` frame, supply its built JAR:

```sh
./gradlew --no-daemon runGametest \
  -Pfabric_version=0.156.0+26.2 \
  -PlinkedHardcoreJar=/absolute/path/to/linked-hardcore/fabric-mod/build/libs/fabric-mod-0.1.0.jar
```

The companion JAR is a test runtime dependency and is never bundled or required
for standalone Shared Vitals. Without it, the actual-companion test case returns
without running its compatibility assertions; the other linked-state tests still
run. The optional transfer mixin targets Linked Hardcore's
`TransferCountdown.waitForServer(MinecraftServer)` and
`TransferCountdown.start(MinecraftServer)`. No protocol opcodes or proxy state
files are modified.

For full deployment validation, install both mods on every backend, enable the
mode through each backend console, and connect two real clients through Velocity.
Check one death count, spectator/countdown behavior, fresh-world health/food,
reconnects during waiting, proxy/backend restart recovery, and a second death
after rotating back to a reset backend. The companion GameTests exercise its
backend classes; they do not run a real proxy or Docker reset loop.

Before releasing gameplay changes, also test with two real clients: take damage
and eat on either client, sprint together, change dimensions, use a totem, die,
respawn at different times, reconnect, and restart the server. Check both HUDs,
death screens, inventory drops/`keepInventory`, and hardcore behavior. Headless
tests do not establish client behavior or compatibility with other mods.

## Automated releases

`.github/workflows/ci.yml` checks development pushes and pull requests targeting
`main` or `codex/development`. It cannot publish releases.

`.github/workflows/release.yml` runs on pushes to `main` only. Once the build and
release checks pass, it creates a draft, uploads the assets, downloads them to
verify their checksums, and publishes the release. A failed upload or verification
leaves a draft that can be retried by rerunning the same workflow.

Release versions are `0.1.<release workflow run number>`, beginning with `0.1.1`.
Reruns keep the same version. The JAR metadata also includes `+mc26.2`.
The local version defaults to `0.1.0`; CI overrides it with `SHARED_VITALS_VERSION`.
The workflow never commits version bumps or changelogs back to either branch.

Each release includes:

- `shared-vitals-<version>+mc26.2.jar`
- `CHANGELOG.md`, also shown in the release description
- `SHA256SUMS` for the JAR and changelog

The changelog lists non-merge commit subjects since the newest published release
that is an ancestor of the released commit. The first release includes all commits.
Use clear commit subjects and pull request squash titles so the bullets explain
what changed. Merge commits are omitted because their constituent commits are listed.

Publication uses the repository's automatic `GITHUB_TOKEN` with `contents: write`.
No personal access token is needed. The release always targets the triggering
commit, even if `main` moves while the build runs.

The `origin` remote points to `https://github.com/evan6509/shared-vitals.git`.
Development work stays on `codex/development` until you choose to release it.
