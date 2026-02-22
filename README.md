# Voxy World Gen V2 (Multiplayer Edition)

![Logo](src/main/resources/logo.png)

This mod is a rewrite of the original Voxy World Gen. It is designed to generate chunks very fast in the background and auto-inject them into Voxy. This mod is **NOT** a fork of the "passive chunk generator" mod; it is an entirely different implementation.

**This specific fork (v2.2.2-multiplayer)** is optimized for dedicated servers and multiplayer environments, focusing on stability, cross-dimension compatibility, and code clarity.

## 🚀 Fork Changes (v2.2.2-multiplayer)

- **Multi-Dimension Support**: Fixed a critical issue where generation data for Overworld, Nether, and End were overwriting each other. Files are now saved separately (e.g., `voxy_gen_the_nether.bin`).
- **Network Filtering**: Optimized packet distribution. Players now only receive LOD updates for the dimension they are currently in, significantly reducing server-to-client bandwidth and preventing visual glitches.
- **English Localization**: All internal code comments and logs have been translated from French to English for better global accessibility and collaboration.

## Features

- **Background Generation**: Generates chunks at high speed without lagging the main server thread.
- **Voxy Integration**: Automatic ingestion of generated chunks for seamless LOD updates.
- **Tellus Integration**: Native support for [Tellus](https://github.com/Yucareux/Tellus).
- **Advanced Throttling**: Automatically pauses or slows down generation if the server TPS drops below 15.

## 🛠 Dependencies

- **Minecraft**: 1.21.1 - 1.21.11 (Tested on 1.21.11)
- **Fabric Loader**: >= 0.16.0
- **Java**: 21 (Required)
- **Fabric API**
- **Cloth Config**: >= 15.0.127

## 🏗 Building

This project requires **Java 21**.