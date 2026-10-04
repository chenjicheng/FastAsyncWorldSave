# Verification

Use JDK 21 and the committed Gradle wrapper (9.2.1 with distribution checksum). Loom 1.14.10 uses official Mojang mappings for Minecraft 1.21.11. Loader and Fabric API versions are pinned in `gradle.properties`.

## Commands

```powershell
# Compilation, remapping, JUnit and headless server GameTests
.\gradlew.bat build --console=plain

# Storage/queue regressions alone
.\gradlew.bat test -x runGameTest --console=plain

# Real server integration alone
.\gradlew.bat runGameTest --console=plain

# Patch whitespace
git diff --check
```

Linux equivalents use `bash ./gradlew`. Loom attaches `runGameTest` to `test`; omit `-x runGameTest` to run both suites. Upstream has no configured Java formatter or lint task; `compileJava` and `compileGametestJava` validate the configured Java/Minecraft API. A single local removal-warning suppression is documented beside Fabric's real mock-player GameTest helper.

## Behavioral coverage

JUnit runs with Fabric Loader and real transformed Minecraft storage classes, using temporary world directories. The player object is mocked only for caller-thread serialization; file I/O, NBT parsing, data-fixing, mixins, backups and world locks are real. A separate test-only mod injects a deterministic partial-write `IOException` into `NbtIo`; it is excluded from the production JAR.

Storage and queue tests cover:

- Failed player and level writes preserve current/backup bytes and remove invalid temporary files; subsequent saves recover.
- Failed publication retains fully serialized recovery NBT.
- Immediate player reload waits and reads the new data.
- Nested NBT snapshots are detached and sequential writes rotate the correct previous version.
- Closing waits before lock release, and reopened world access can save again.
- Metadata rename, direct read, backup, restore and deletion coordinate with queued writes.
- Queue saturation blocks producers without running saves out of order.
- Interrupted barriers preserve persistence ordering and the interrupt flag; worker self-wait fails without poisoning the queue.
- Third-party player/world `RETURN` callbacks execute exactly once on the caller thread while NBT writes remain queued, modeling Essential Commands' separate player-data persistence.

The headless GameTest creates a real `ServerPlayer`, checks its persisted XP and world DayTime, verifies ordinary saves return with the writer blocked, and verifies `saveEverything` and direct `saveAllChunks` flushes wait and persist both files. The harness also performs normal server shutdown. Fabric includes its own additional sanity GameTest in the reported total.

The initial storage regressions reproduce corruption, stale reads, lock release, snapshot mutation and backup/rename failures on the upstream behavior after only the 1.21.11 API migration. The server integration test likewise fails at the flush barrier on that baseline. The callback regressions reproduce skipped persistence hooks on the earlier maintenance implementation that cancelled saves at `HEAD`.

## Reports and scope

- JUnit: `build/reports/tests/test/index.html` and `build/test-results/test/`.
- Server tests: `build/reports/gametest/results.xml` and `build/run/gameTest/logs/latest.log`.
- Artifacts: `build/libs/` (the JAR without `-sources` is the installable mod).

The GitHub workflow builds and retains artifacts/reports on Windows and Linux. Automated tests cover isolated storage, a headless test server and normal shutdown; they do not establish interactive client gameplay, behavior on a live production world, or compatibility with third-party mods replacing these save methods. Reopening is covered by real storage access rather than a graphical single-player session. Those checks belong to deployment acceptance for the intended modpack.
