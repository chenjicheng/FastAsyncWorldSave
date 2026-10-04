# Fast Async World Save — Fabric 1.21.11 maintenance

Community maintenance fork of [Someaddon's FastAsyncWorldSave](https://github.com/someaddons/FastAsyncWorldSave), based on its `fabric1.21.8` branch. This branch targets **Minecraft 1.21.11, Fabric Loader 0.19.5 or newer, Fabric API 0.141.6+1.21.11 or newer, and Java 21**.

The mod moves player and world metadata NBT disk writes to one ordered worker. Serialization stays on the server thread. Minecraft already saves chunk and dimension saved-data files asynchronously; this fork leaves those paths with Minecraft.

## Installation

Build the mod, then place `build/libs/fastasyncworldsave-fabric-2.5.1+mc1.21.11.jar` and the compatible Fabric API JAR in your `mods` directory. Cupboard is no longer required. Dedicated servers and integrated single-player servers use the same save path.

This maintenance build prevents a failed player write from publishing partial NBT, waits for pending writes before reloading players or closing worlds, honors explicit save flushes, and coordinates world reads, metadata changes, backups, restoration and deletion. Pending writes are bounded; a full queue applies backpressure without dropping or reordering saves.

## Build and test

With JDK 21 installed:

```powershell
.\gradlew.bat build --console=plain
```

On Linux, use `bash ./gradlew build --console=plain`. `build` runs the Fabric-loader JUnit storage regressions and headless Minecraft server GameTests, then produces remapped and source JARs. CI runs this command on Windows and Linux.

- [Save architecture and failure behavior](docs/saving.md)
- [Verification commands and test coverage](docs/testing.md)

## Attribution and licensing

Original mod by Someaddon. The upstream `ARR` (All Rights Reserved) declaration is retained. This fork does not change the upstream licensing declaration or represent an official Someaddon release.
