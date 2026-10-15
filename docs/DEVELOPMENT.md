# Development

## Branches

- `codex/development`: normal development by you and Codex. This is the checked-out branch.
- `main`: changes you have chosen to release. Nothing merges automatically.

Both branches start at the same initial setup commit. To switch back to development:

```sh
git switch codex/development
```

When you choose to release, merge `codex/development` into `main` through a GitHub
pull request, or merge locally and push `main`. The workflow does not merge for you.
Branch protection can be configured after a GitHub repository exists.

## Local checks

Use JDK 25 and Python 3.10 or later:

```sh
./gradlew --no-daemon clean build
python3 -m unittest discover -s tests -v
python3 scripts/verify_jar.py build/libs/shared-vitals-0.1.0+mc26.2.jar 0.1.0+mc26.2
```

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

## Future GitHub setup

No remote is configured. When you decide to host this project, use `shared-vitals`
as the repository name and `main` as its default branch, then upload both branches.
The first push to `main` will publish a release, so implement the gameplay on
`codex/development` and choose what to merge before that push if you want the first
release to contain it. Workflows remain inactive until the project is uploaded.
