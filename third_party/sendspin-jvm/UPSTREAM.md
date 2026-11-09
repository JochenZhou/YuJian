# Vendored Sendspin JVM protocol module

- Project: https://github.com/Sendspin/sendspin-jvm
- Upstream revision: `288f22e362d9defbe08bf0545decb00c64909aab`
- Module: `sendspin-protocol`
- License: see `LICENSE` (included verbatim from that revision)
- Compatibility target: Music Assistant 2.10.9, using `aiosendspin` 10.2.1 at `692831b07bc453a38d5a39a16ee566141547bfa1`

## Local changes

`MessageParser.kt`, `Messages.kt`, and `MessageParserTest.kt` are modified from upstream to parse Music Assistant 2.10.9's 13-byte audio frame header: message type, timestamp, and unsigned big-endian `send_ahead` value. Artwork and visualizer frames retain the upstream 9-byte header. Regression tests cover unsigned values and truncated audio headers.

`Messages.kt` maps controller mute commands to MA's `mute` wire field. `DiscoveryService.kt` and `NsdBrowser.kt` carry the discovered DNS-SD path. `SendSpinClient.kt` adds local player-volume controls and an explicit close hook, and uses a 40 ms output-delay default tuned provisionally for the Android output adapter. These compatibility changes have not yet been compiled or exercised against a running server.

The module build file is adapted for inclusion in the Android application's Gradle build. Upstream protocol, clock, buffering, and WebSocket code remain the implementation; Android playback and Android NSD are supplied by the application.
