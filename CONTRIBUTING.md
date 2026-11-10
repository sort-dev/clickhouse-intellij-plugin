# Contributing

## Build

The build and the test IDEs run on JDK 25, which IDE line 2026.2 requires. Without
a JDK 25 on `PATH`, prefix Gradle commands with `mise exec java@25 --`.

```sh
./gradlew buildPlugin
```

Development builds carry a commit-derived `-dev.<timestamp>+<hash>` suffix and are
refused by the release guard. A release build needs the explicit flag:

```sh
./gradlew buildPlugin -Prelease=true --no-daemon --console=plain
python3 build-logic/tools/verify-release-zip.py build/distributions dev.sort.clickhouse-intellij-plugin <version>
```

The verifier checks the single ZIP in `build/distributions`, so clear older ZIPs
first. Keep scratch files under `build/`.

## Test

Tests run against real IDE platforms (builds 262, 263):

```sh
./gradlew test                          # default lane
./gradlew test -Ptest.kotlin=absent     # without the Kotlin plugin
```

Assert real platform/parser/console behavior — ranges and inserted text, not
presentation. Niche fixtures (live ClickHouse server, local SQL corpus scan) are
documented in [dev-docs/testing.md](dev-docs/testing.md).

## Release

1. Set the release version in `build.gradle.kts` and update the change-notes in
   `src/main/resources/META-INF/plugin.xml`. Commit to `main`.
2. Create `release/<version>` from that commit and push it. The release workflow
   rejects commits not on `main`, builds on JDK 25, verifies the plugin against
   IntelliJ 262/263, validates the ZIP and creates the GitHub release.
3. JetBrains Marketplace upload is a separate, owner-only step:
   `./gradlew publishPlugin -Prelease=true`.
