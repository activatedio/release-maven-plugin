# release-maven-plugin

A tiny Maven plugin that cuts a release the git-native way: it reads the latest semver tag
reachable from `HEAD`, bumps it, and creates + pushes a new annotated tag. **No POM edits, no
release-plugin commits** — just the tag, which your CI can react to. Pairs naturally with
git-driven versioning (e.g. jgitver).

Coordinates: `io.activated:release-maven-plugin`

## Usage

By default `bump` creates the tag **locally only** — it does not push, so it can't trigger a
release by accident. Push the tag (or pass `-Drelease.push=true`) when you're ready to release.

```bash
mvn release:bump                         # minor (default): latest v1.4.2 -> creates tag v1.5.0
mvn release:bump -Drelease.level=patch   #                  v1.4.2 -> v1.4.3
mvn release:bump -Drelease.level=major   #                  v1.4.2 -> v2.0.0

git push origin v1.5.0                    # ...then push to trigger CI
# or in one step:
mvn release:bump -Drelease.push=true      # bump + push
```

For the short `release:bump` form, add the plugin group to `~/.m2/settings.xml`:

```xml
<pluginGroups>
  <pluginGroup>io.activated</pluginGroup>
</pluginGroups>
```

Otherwise invoke it fully qualified: `mvn io.activated:release-maven-plugin:1.0.0:bump`.

### Options

| Property | Default | Meaning |
|---|---|---|
| `release.level` | `minor` | `patch`, `minor`, or `major` |
| `release.dryRun` | `false` | print what would happen; make no changes |
| `release.push` | `false` | push the tag to the remote (this is what triggers a CI release) |
| `release.allowDirty` | `false` | allow tagging with uncommitted changes in the working tree |
| `release.remote` | `origin` | remote to push to |
| `release.tagPrefix` | `v` | tag prefix (e.g. `v1.5.0`) |

If no matching tag exists yet, the base is treated as `0.0.0` (so a minor bump gives `v0.1.0`).

## Releasing this plugin

Publishing to Maven Central is automated: push a `vX.Y.Z` tag and the `publish` GitHub Actions
workflow signs and uploads via the Sonatype Central Portal. The tag drives the released version.

Required GitHub repo secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` (Central Portal token),
`GPG_PRIVATE_KEY` (ASCII-armored), `MAVEN_GPG_PASSPHRASE`.

## License

[Apache License 2.0](LICENSE).
