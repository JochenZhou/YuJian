# Sendspin Vinyl Record & Synchronized Lyrics UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the music icon prefix from the home screen Sendspin button and upgrade `SendspinActivity` into a two-column TV music player featuring a rotating vinyl record view (`VinylRecordView`) and hardware-accelerated synchronized scrolling lyrics (`LyricsScrollView` + `LyricsFetcher`).

**Architecture:** Restore and harden the zero-dependency LRC parser and `lrclib.net` lyrics fetcher (`LyricsFetcher.kt`) backed by `SafeNetworkHelper` and local disk caching. Replace the static square cover stage in `SendspinPlayerViews.kt` and `SendspinActivity.kt` with a custom-drawn rotating vinyl disc (concentric grooves, specular highlights, circular center cover label, spindle hole) in the left column (alongside track metadata, seekbar, and unified transport/volume/settings controls) and a damped auto-scrolling LRC canvas view in the right column synchronized with `ClockSync` playback progress.

**Tech Stack:** Kotlin (`jvmTarget = "17"`), Android Framework API 23 (`Canvas`, `Paint`, `Path`, `BitmapShader`, `RadialGradient`, `LinearGradient`), `SafeNetworkHelper` (TLS compatibility for Android 6.0), JUnit 4 (`testImplementation`).

**Spec:** `docs/superpowers/specs/2026-11-07-sendspin-tv-client-design.md`

## Global Constraints

- Minimum Android SDK is strictly `minSdk = 23` (Android 6.0 Marshmallow); do not use API 24+ / API 26+ methods (e.g., `Path.op` is API 19+, `Canvas.clipOutPath` is API 26+ so avoid `clipOutPath`).
- Zero new runtime dependencies in `app/build.gradle.kts`.
- All outbound HTTPS calls to `https://lrclib.net/...` MUST use `SafeNetworkHelper.openConnection(...)` to bypass expired root CA stores on Android 6.0 TVs.
- `LyricsScrollView` and `VinylRecordView` must be `isFocusable = false` so Android TV D-Pad focus stays strictly on interactive controls (`back`, `progress`, `volumeControl`, `previous`, `playPause`, `next`, `settings`).

## Review Focus

- **Stale async lyrics callback on rapid track skip**: If a user skips from Track A to Track B before Track A's `lrclib.net` HTTP response returns, Track A's callback must be ignored so Track B never displays Track A's lyrics (pinned in Task 3 via generation counter / track key verification).
- **LRC edge cases (multi-timestamp chorus lines, `.x`/`.xx`/`.xxx` fractional precision, negative `[offset:-500]` clamping at `0L`, and plain unsynced lyrics)**: `LrcParser` must expand, sort, clamp, and binary-search active lines without index out-of-bounds (pinned in Task 1 `LrcParserTest`).
- **Long lyric lines on 1080p TV column width**: Long lyric sentences must wrap or scale cleanly within `LyricsScrollView` rather than clipping off the right edge of the screen (pinned in Task 2 `LyricsScrollView` multi-line layout).
- **Vinyl rotation continuity on pause/resume**: Pausing playback must freeze the vinyl disc at its current rotation angle rather than snapping back to `0°`, and only schedule animation frames when actively playing and visible.
- **Disabled control D-Pad focus chain**: When `previous`, `playPause`, or `next` are disabled (e.g., before a server connects), D-Pad navigation between `back`, `progress`, `volumeControl`, and `settings` must never trap focus on a disabled button.

---

### Task 1: Home Button Text Fix & LRC Lyrics Parser / Fetcher (`MainActivity.kt`, `LyricsFetcher.kt`)

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/MainActivity.kt:390`
- Create: `app/src/main/java/com/zqc/tvnotify/LyricsFetcher.kt`
- Create: `app/src/test/java/com/zqc/tvnotify/LrcParserTest.kt`

**Interfaces:**
- Consumes: `SafeNetworkHelper.openConnection(urlStr, connectTimeoutMs, readTimeoutMs, userAgent, acceptHeader)`
- Produces:
  - `data class LrcLine(val timeMs: Long, val text: String)`
  - `data class ParsedLyrics(val synced: Boolean, val lines: List<LrcLine>, val source: String)`
  - `object LrcParser`:
    - `fun parse(rawText: String, source: String = "lrc"): ParsedLyrics`
    - `fun findActiveLineIndex(lines: List<LrcLine>, positionMs: Long): Int`
  - `object LyricsFetcher`:
    - `fun fetchAsync(context: Context, title: String, artist: String, album: String, durationMs: Long, embeddedLyrics: String = "", onResult: (ParsedLyrics?) -> Unit)`

- [ ] **Step 1: Remove music icon from Sendspin button on the home screen**

In `app/src/main/java/com/zqc/tvnotify/MainActivity.kt:390`, change:
```kotlin
val musicButton = buildButton("Sendspin 音乐") {
```

- [ ] **Step 2: Write unit tests in `app/src/test/java/com/zqc/tvnotify/LrcParserTest.kt`**

```kotlin
package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesMultiTimestampVariablePrecisionAndClampsNegativeOffset() {
        val raw = """
            [ti:青花瓷]
            [offset:-500]
            [00:00.20]前奏轻吟
            [00:15.5][01:15.50]天青色等烟雨 而我在等你
            [00:22.125]炊烟袅袅升起 隔江千万里
        """.trimIndent()

        val parsed = LrcParser.parse(raw, "lrclib")
        assertTrue(parsed.synced)
        assertEquals(4, parsed.lines.size)
        assertEquals(0L, parsed.lines[0].timeMs)
        assertEquals("前奏轻吟", parsed.lines[0].text)
        assertEquals(15_000L, parsed.lines[1].timeMs)
        assertEquals(21_625L, parsed.lines[2].timeMs)
        assertEquals(75_000L, parsed.lines[3].timeMs)

        assertEquals(-1, LrcParser.findActiveLineIndex(emptyList(), 1_000L))
        assertEquals(0, LrcParser.findActiveLineIndex(parsed.lines, 14_999L))
        assertEquals(1, LrcParser.findActiveLineIndex(parsed.lines, 15_000L))
        assertEquals(2, LrcParser.findActiveLineIndex(parsed.lines, 50_000L))
        assertEquals(3, LrcParser.findActiveLineIndex(parsed.lines, 90_000L))
    }

    @Test
    fun fallsBackToPlainUnsyncedLinesWhenNoTimestampsExist() {
        val parsed = LrcParser.parse("第一行纯文本\n\n第二行纯文本", "plain")
        assertFalse(parsed.synced)
        assertEquals(2, parsed.lines.size)
        assertEquals("第一行纯文本", parsed.lines[0].text)
        assertEquals("第二行纯文本", parsed.lines[1].text)
    }
}
```

- [ ] **Step 3: Implement `LyricsFetcher.kt` (`LrcParser` and `LyricsFetcher`)**

Create `app/src/main/java/com/zqc/tvnotify/LyricsFetcher.kt` implementing:
- `LrcParser.parse` with `[offset:+/-ms]` support, `\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]` multi-tag expansion, sorting by `timeMs`, and plain-text fallback when no timestamp tags exist.
- `LrcParser.findActiveLineIndex` using binary search (`O(log N)`).
- `LyricsFetcher.fetchAsync` with:
  1. Immediate parse if `embeddedLyrics.isNotBlank()`.
  2. Disk cache lookup under `File(context.cacheDir, "sendspin_lyrics")` keyed by `SHA-256("$cleanTitle|$cleanArtist|${durationMs / 1000}")`.
  3. Background thread query to `https://lrclib.net/api/get` followed by fallback `https://lrclib.net/api/search` via `SafeNetworkHelper.openConnection`, caching non-blank results and pruning cache above 100 files.

---

### Task 2: Vinyl Record View & Synchronized Scrolling Lyrics View (`SendspinPlayerViews.kt`)

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/SendspinPlayerViews.kt`

**Interfaces:**
- Consumes: `LrcLine`, `ParsedLyrics`, `LrcParser` from `LyricsFetcher.kt`
- Produces:
  - `internal class VinylRecordView(context: Context, initialAccent: Int) : View(context)`:
    - `fun setArtwork(bitmap: Bitmap?)`
    - `fun setAccentColor(color: Int)`
    - `fun setPlaying(playing: Boolean)`
  - `internal class LyricsScrollView(context: Context, initialAccent: Int) : View(context)`:
    - `fun setLyrics(parsed: ParsedLyrics?)`
    - `fun setLoading(loading: Boolean)`
    - `fun setAccentColor(color: Int)`
    - `fun updatePosition(positionMs: Long)`
  - `internal class SendspinStageLayout(context: Context, private val leftColumn: View, private val rightColumn: View) : ViewGroup(context)`

- [ ] **Step 1: Implement `VinylRecordView` in `SendspinPlayerViews.kt`**

Add `VinylRecordView` with:
- Outer dark vinyl disc (`Color.rgb(18, 20, 24)`), subtle outer rim stroke, and soft radial shadow.
- 6 concentric sound groove rings between `0.50f * radius` and `0.91f * radius` plus two diagonal specular sheen arcs (`drawArc`) that rotate with the disc.
- Center circular album cover (`labelRadius = 0.44f * radius`) rendered via `BitmapShader` (`Shader.TileMode.CLAMP`) with a matrix scaling the bitmap to center-crop the circle; when `artwork == null`, render a stylized label with `accent` tint and `SendspinIconDrawable(SendspinIcon.NOTE)`.
- Center spindle hole (`holeRadius = 0.065f * radius`) with a subtle metallic/accent collar ring (`0.085f * radius`).
- Frame-time-based rotation (`~18f` degrees/second) driven by `postInvalidateOnAnimation()` while `playing` is true, preserving angle across pause/resume.

- [ ] **Step 2: Implement `LyricsScrollView` in `SendspinPlayerViews.kt`**

Add `LyricsScrollView` (`isFocusable = false`) with:
- `setLyrics(parsed: ParsedLyrics?)`, `setLoading(loading: Boolean)`, `setAccentColor(color: Int)`, `updatePosition(positionMs: Long)`.
- Multi-line text wrapping (`StaticLayout`) so long lines wrap cleanly within the lyrics column width.
- Exponentially damped vertical scroll (`currentScrollY += (targetScrollY - currentScrollY) * 0.22f`) keeping the active lyric line vertically centered.
- Active line styled at `22sp` bold in `accent` color with glow shadow (`setShadowLayer`); inactive lines styled at `16sp` in white with distance-based alpha fade (`0.15f..0.75f`).
- Empty / loading state drawing 5 animated equalizer bars and caption (`"正在检索歌词…"` when `loading`, `"纯音乐 · 暂无歌词"` otherwise).

- [ ] **Step 3: Update `SendspinStageLayout` in `SendspinPlayerViews.kt` for two-column Vinyl + Lyrics layout**

Update `SendspinStageLayout` to arrange:
- On wide screens (`contentWidth / density >= 680f`): `leftColumn` (44% width, containing the vinyl disc, track info, seekbar, and controls) and `rightColumn` (56% width, containing full-height `LyricsScrollView`) separated by `gap`.
- On narrow/compact screens: vertical stack with `leftColumn` on top and `rightColumn` below.

---

### Task 3: Integrate Vinyl Record & Lyrics into `SendspinActivity.kt`

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/SendspinActivity.kt`

**Interfaces:**
- Consumes: `VinylRecordView`, `LyricsScrollView`, `SendspinStageLayout`, `LyricsFetcher`, `ParsedLyrics`
- Produces: Updated `SendspinActivity` with two-column vinyl + lyrics UI, artwork palette tinting, lyrics lifecycle management, and D-Pad navigation.

- [ ] **Step 1: Rebuild `SendspinActivity.buildUi()` with left vinyl/control column and right lyrics column**

In `SendspinActivity.kt`:
- Replace `artwork` / `artworkFrame` with `vinylRecord = VinylRecordView(this, accent)`.
- Build `leftColumn` (`LinearLayout.VERTICAL`, centered) containing:
  1. `vinylRecord` (sized responsively via `applyLayoutMetrics`, e.g., `220dp` on standard TV height, smaller on compact screens).
  2. `title` (maxLines = 1, marquee/ellipsize end), `artist`, and `album` centered beneath the vinyl disc.
  3. `timeline` (`progress` seekbar + `position` / `duration` time row).
  4. Unified bottom control bar combining `volumeControl`, `previous`, `playPause`, `next`, and `settings` so vertical height fits comfortably below the vinyl disc.
- Add `lyricsView = LyricsScrollView(this, accent)` as the right column of `SendspinStageLayout(this, leftColumn, lyricsView)`.

- [ ] **Step 2: Wire track changes to `LyricsFetcher` and progress ticks to `lyricsView` & `vinylRecord`**

In `SendspinActivity.kt`:
- Maintain `lyricsGeneration: Long` and `lastLyricsTrackKey: String`.
- When `trackChanged` or lyrics have not been fetched for a non-blank `state.title`:
  - Increment `lyricsGeneration`, clear previous lyrics, set `lyricsView.setLoading(true)`, and call `LyricsFetcher.fetchAsync(this, state.title, state.artist, state.album, state.durationMs)`.
  - On callback, post to `main` handler, verify `generation == lyricsGeneration && !destroyed`, set `lyricsView.setLoading(false)`, and call `lyricsView.setLyrics(parsed)`.
- In `render(state)`:
  - Call `vinylRecord.setPlaying(connected && state.playing)`.
- In `updateProgress()`:
  - Pass `elapsed` to `lyricsView.updatePosition(elapsed)`.
- When artwork decodes into a `bitmap`:
  - Compute `artColor = sendspinArtworkColor(bitmap, accent)` and update `ambient.setAmbientColor(artColor)`, `vinylRecord.setArtwork(bitmap)`, `vinylRecord.setAccentColor(artColor)`, and `lyricsView.setAccentColor(artColor)`.
- In `onKeyDown`:
  - Support `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` for quick volume adjustment (`adjustVolume(+5)` / `adjustVolume(-5)`) when focus is on the bottom transport row, while preserving normal focus traversal to `progress` and `back`.

- [ ] **Step 3: Review git diff and commit changes**

Verify all modified and created files compile cleanly against API 23 constraints and commit.
