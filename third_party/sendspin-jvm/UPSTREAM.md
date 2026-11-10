# Vendored Sendspin JVM protocol module

- Project: https://github.com/Sendspin/sendspin-jvm
- Upstream revision: `288f22e362d9defbe08bf0545decb00c64909aab`
- Module: `sendspin-protocol`
- License: see `LICENSE` (included verbatim from that revision)
- Compatibility target: Music Assistant 2.10.9, using `aiosendspin` 10.2.1 at `692831b07bc453a38d5a39a16ee566141547bfa1`

## Local changes

`MessageParser.kt`, `Messages.kt`, and `MessageParserTest.kt` are modified from upstream to parse Music Assistant 2.10.9's 13-byte audio frame header: message type, timestamp, and unsigned big-endian `send_ahead` value. Artwork and visualizer frames retain the upstream 9-byte header. Regression tests cover unsigned values and truncated audio headers.

`Messages.kt` maps controller mute commands to MA's `mute` wire field and treats metadata `progress` as absent/present so partial updates can be merged. `DiscoveryService.kt` and `NsdBrowser.kt` carry the discovered DNS-SD path. `SendSpinClient.kt` adds local player-volume controls and an explicit close hook. Player controls, including `set_output_delay`, are sent in `client/state`; hello advertises only the legacy-compatible `volume` and `mute` subset because MA 2.10.9 rejects `set_output_delay` in `client/hello`. These compatibility changes have not yet been compiled or exercised against a running server.

`ClockSync.kt` exposes session readiness and offset uncertainty, rejects impossible or out-of-order probe measurements, and retains the upstream Kalman filter. `AudioBuffer.kt` stores a bounded timestamp-priority queue before clock readiness and adds unconditional `peek()` / `take()` access for host-controlled playback timing; the legacy `poll()` still applies its playback window after readiness. `AudioPlayer.outputSync` reports the host output controller's state. Its `transition(format)` contract is configure + start: a stream handoff must leave the playback pump running. The client clears old encoded chunks synchronously at a player `stream/start` message boundary; host configure/transition operations preserve frames received afterward.

`SendSpinClient.kt` completes initial synchronization only after an accepted eight-probe burst, resets readiness on socket close/failure, and reports clock/output-controller diagnostics every 500 ms. Output synchronization defaults to automatic (`output_sync_automatic = 1`), with zero fixed compensation in `client/state`; the saved `output_delay_ms` remains the manual calibration. Explicit delay changes, including zero and server commands, select manual mode. The host observes `(AudioBuffer.automaticSync, AudioBuffer.outputDelayMicros)` and re-primes local output on changes without flushing the network queue.

The module build file is adapted for inclusion in the Android application's Gradle build. Android playback and Android NSD are supplied by the application.
