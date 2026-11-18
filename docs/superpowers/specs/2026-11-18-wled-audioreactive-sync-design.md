# WLED AudioReactive & Artwork Color Sync Design (`YuJian` v1.10.1)

## Goal

Add optional, user-controlled WLED lighting synchronization to `YuJian`'s Sendspin music player:
1. **WLED AudioReactive UDP Sync (`WLED 音乐律动`)**: Broadcast real-time audio analysis (level, smoothed energy, beat peak, zero crossings, FFT peak magnitude/frequency, and 16 logarithmic frequency bands) in WLED's official AudioReactive V2 UDP multicast format (`239.0.0.1:11988`) from post-resample Sendspin PCM output.
2. **WLED Album Artwork Color Sync (`WLED 封面色彩同步`)**: Extract primary and secondary colors from the active track's album artwork and push ONLY segment 0's primary color (`col[0]`) to the configured WLED device's JSON API (`http://<host>/json/state`), using the secondary color whenever the primary color is black / near-black.

Both features are **disabled by default** and controlled independently.

## Constraints & Safety Rules

- **Strict opt-in defaults**: Both `isWledSyncEnabled` and `isWledColorSyncEnabled` default to `false` in `Prefs`. When disabled, zero packets, zero HTTP requests, and zero `MulticastLock` acquisitions occur.
- **No effect/brightness/palette override**:
  - AudioReactive V2 UDP packets convey audio telemetry only; WLED keeps whatever audio-reactive effect (`fx`), palette (`pal`), brightness (`bri`), and power state (`on`) the user configured on the controller.
  - Artwork color HTTP updates send partial JSON `{"seg":[{"id":0,"col":[[r,g,b]]}]}` and omit `on`, `bri`, `fx`, and `pal`.
- **Non-blocking audio path**: `SendspinPcmPump` only copies the PCM slice into an atomic single-slot reference right after `output.write(...)` returns `count > 0 && !current.silence` (using `predictedOutputUs`). All FFT, normalization, smoothing, and `DatagramSocket` / HTTP I/O run asynchronously on dedicated background workers.
- **Multicast lifecycle**: `WifiManager.MulticastLock` is acquired only while AudioReactive sync is actively running (`enabled && playbackActive && !closed`) and is always released on pause, stop, suspend, disconnect, disable, or shutdown.
- **Android compatibility & zero new dependencies**: `minSdk = 23` (`Lint NewApi` clean), pure Kotlin/Android SDK DSP & histogram extraction, reusing existing `OkHttpClient` for HTTP POST.
- **Strict testing/install boundaries**: Never run `adb install` to `192.168.101.14`; verify via unit/Robolectric tests in GitHub Actions CI before tagging `v1.10.1`.

## Protocol Contracts

### 1. WLED AudioReactive V2 UDP Packet (44 bytes, Little-Endian)

Target: UDP multicast `239.0.0.1`, configurable port (default `11988`, range `1..65535`).
Rate limit: max ~50 fps (`>= 20 ms` interval), dropping stale queued frames (`> 120 ms`).

| Offset | Size | Type | Field | Notes |
| --- | --- | --- | --- | --- |
| `0..5` | 6 | ASCII+NUL | Header | `"00002\0"` |
| `6..7` | 2 | `uint8[2]` | `pressure` | `0, 0` |
| `8..11` | 4 | `float32` LE | `sampleRaw` | Instantaneous scaled level `[0..255]` |
| `12..15` | 4 | `float32` LE | `sampleSmth` | Smoothed energy level `[0..255]` |
| `16` | 1 | `uint8` | `samplePeak` | `0` = none, `1` = transient onset detected |
| `17` | 1 | `uint8` | `frameCounter` | Rolling counter `0..255` |
| `18..33` | 16 | `uint8[16]` | `fftResult` | 16 logarithmic frequency bands `[0..255]` |
| `34..35` | 2 | `uint16` LE | `zeroCrossingCount` | Current frame zero crossings `[0..65535]` |
| `36..39` | 4 | `float32` LE | `FFT_Magnitude` | Dominant FFT bin magnitude |
| `40..43` | 4 | `float32` LE | `FFT_MajorPeak` | Dominant FFT frequency in Hz (`0f` on silence) |

### 2. WLED JSON API Partial Color Update

Target: `POST http://<host>/json/state` (default host `192.168.101.206`, optional `:port` preserved, scheme/trailing slash normalized).
Payload (`application/json; charset=utf-8`):
```json
{"seg":[{"id":0,"col":[[r,g,b]]}]}
```
Color selection rule (`WledArtworkColorExtractor`):
- Cluster opaque pixels from downsampled album artwork into quantized RGB clusters to determine `primaryRgb` and distinct `secondaryRgb`.
- Relative luminance threshold: `luminance < 0.025` is classified as black / near-black.
- If `primaryRgb` is non-dark, select `primaryRgb`.
- Else if `secondaryRgb` is non-dark, select `secondaryRgb`.
- Else (no artwork, corrupt bitmap, or all dark), select `null` and skip sending any HTTP update.

## Settings UI (`SendspinActivity`)

In `showSettingsDialog()` (preserving TV D-pad focus and all 9 themes):
- `WLED 音乐律动：已关闭 / 已开启` — toggles `Prefs.setWledSyncEnabled` and calls `SendspinRuntime.configureWledAudioSync(...)`.
- `WLED 封面色彩同步：已关闭 / 已开启` — toggles `Prefs.setWledColorSyncEnabled` and calls `SendspinRuntime.configureWledColorSync(...)`.
- `WLED 参数配置` — opens sub-dialog to configure:
  - `律动 UDP 端口` (default `11988`, validated `1..65535`)
  - `灯带 IP / 地址` (default `192.168.101.206`, validated via `normalizeWledHost`)
