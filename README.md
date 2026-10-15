# Shared Vitals

A Fabric mod for Minecraft 26.2 where every player will share health and hunger.

**Status:** project scaffold only. Shared health and hunger gameplay is not implemented yet.

- Mod name: **Shared Vitals**
- Future GitHub repository name: **`shared-vitals`**
- Mod ID: `shared_vitals`
- Requirements: Java 25, Fabric Loader 0.19.3+, Fabric API for Minecraft 26.2

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

The repository is local only; no GitHub repository or remote has been created.
See [the development guide](docs/DEVELOPMENT.md) for branch and release details.
