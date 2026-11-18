# WLED AudioReactive & Artwork Color Sync Implementation Plan

> Scope: `YuJian` (`com.zqc.tvnotify`) v1.10.1 (`versionCode = 57`, `versionName = "1.10.1"`).

## Files to Create / Modify

1. **`app/src/main/java/com/zqc/tvnotify/Prefs.kt`**
   - Constants: `DEFAULT_WLED_SYNC_PORT = 11988`, `DEFAULT_WLED_COLOR_HOST = "192.168.101.206"`.
   - Helpers: `normalizeWledHost(raw: String): String?`, `parseWledSyncPort(raw: String): Int?`.
   - Accessors:
     - `isWledSyncEnabled(context)` / `setWledSyncEnabled(context, enabled)` (default `false`)
     - `getWledSyncPort(context)` / `setWledSyncPort(context, port)` (default `11988`, rejects out-of-range without overwriting)
     - `isWledColorSyncEnabled(context)` / `setWledColorSyncEnabled(context, enabled)` (default `false`)
     - `getWledColorHost(context)` / `setWledColorHost(context, host)` (default `"192.168.101.206"`, rejects invalid without overwriting)

2. **`app/src/main/java/com/zqc/tvnotify/WledAudioSync.kt`** (new)
   - `WledAudioTap` interface + `PcmFrame` holder.
   - `WledAudioSyncPacket` (`PACKET_SIZE = 44`, `MULTICAST_GROUP = "239.0.0.1"`, LE V2 encoder).
   - `WledAudioAnalyzer` (mono/multi-channel 16-bit LE downmix, Hann window, radix-2 512-pt FFT, 16 logarithmic GEQ bands, adaptive onset peak detection, zero crossings, `reset()`).
   - `DatagramSink` + `MulticastLease` abstractions (`AndroidDatagramSink`, `AndroidMulticastLease`).
   - `WledAudioSync` lifecycle coordinator: nonblocking `AtomicReference` latest-frame handoff, `20 ms` rate limiter, stale frame dropping, safe socket error throttling, `MulticastLock` acquire/release symmetry.

3. **`app/src/main/java/com/zqc/tvnotify/WledColorSync.kt`** (new)
   - `WledArtworkColors(primaryRgb, secondaryRgb, selectedRgb)`.
   - `WledArtworkColorExtractor`: quantized RGB cluster extraction, ITU-R BT.709 relative luminance check (`< 0.025` -> dark), dark-primary fallback to secondary, all-dark/empty/opaque-less skip (`null`).
   - `WledColorSync`: JSON payload builder `{"seg":[{"id":0,"col":[[r,g,b]]}]}`, URL builder `http://<host>/json/state`, async `OkHttpClient` poster (`WledHttpPoster` seam for testing), latest-wins generation guard, duplicate suppression, safe shutdown.

4. **`app/src/main/java/com/zqc/tvnotify/SendspinPcmPump.kt` & `AndroidAudioPlayer.kt`**
   - Add default no-op `Output.onPcmWritten(data, offset, length, presentationUs)` to `SendspinPcmPump.Output`.
   - Call `output.onPcmWritten(current.bytes, current.written, count, predictedOutputUs(nowUs, latencyUs, queued))` in `SendspinPcmPump.stepOnce` when `count > 0 && !current.silence`.
   - Accept optional `wledTap: WledAudioTap?` in `AndroidAudioPlayer`; forward written PCM in `TrackOutput.onPcmWritten`; wire playback active gating (`stopLocked`, `releaseTrackLocked`, active play/resume).

5. **`app/src/main/java/com/zqc/tvnotify/SendspinRuntime.kt`**
   - Own `WledAudioSync` and `WledColorSync` instances initialized from `Prefs`.
   - Gate `WledAudioSync.setPlaybackActive(...)` cleanly on actual Sendspin active streaming/playing state and tear down on stop/suspend/disconnect/shutdown.
   - Forward `instance.albumArtwork` updates to `WledColorSync.onArtworkBytes(...)` off the UI thread.
   - Expose `configureWledAudioSync(enabled: Boolean, port: Int)` and `configureWledColorSync(enabled: Boolean, host: String)`.

6. **`app/src/main/java/com/zqc/tvnotify/SendspinActivity.kt`**
   - Update `showSettingsDialog()` with:
     - `WLED 音乐律动：已关闭 / 已开启`
     - `WLED 封面色彩同步：已关闭 / 已开启`
     - `WLED 参数配置` (opens sub-dialog for UDP port & device IP/host input using `ThemedDialogBuilder`, persisting valid values only and displaying inline validation errors).

7. **Tests & Versioning**
   - `app/src/test/java/com/zqc/tvnotify/WledAudioSyncTest.kt`
   - `app/src/test/java/com/zqc/tvnotify/WledColorSyncTest.kt`
   - Extend `SendspinPcmPumpTest.kt` and `SendspinPlayerStyleTest.kt`
   - Bump `app/build.gradle.kts` (`versionCode = 57`, `versionName = "1.10.1"`).

## Verification Steps
1. `git diff --check`
2. `node --test app/src/test/js/sendspin-console.test.cjs`
3. Push `main` and monitor GitHub Actions `build.yml` (`testDebugUnitTest`, `lintDebug`, APK build) until green.
4. Tag `v1.10.1`, push tag, and verify GitHub Release + `YuJian-release.apk`.
