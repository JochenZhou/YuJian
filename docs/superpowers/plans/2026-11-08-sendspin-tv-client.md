# YuJian Sendspin Lightweight TV Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a zero-dependency, Android 6.0-compatible (`minSdk = 23`) Sendspin synchronized music and full-screen scrolling LRC lyrics client integrated into the YuJian (`com.zqc.tvnotify`) Android TV app.

**Architecture:** Reuse YuJian's resident foreground `NotifyService` and single-socket `MdnsAdvertiser` to advertise `_sendspin._tcp.local.` (Server-Initiated WebSocket on port `8928`) alongside outbound Client-Initiated connections. Authenticate via pure-Kotlin RFC 7748 X25519 + Android API 23 `AES/GCM/NoPadding` (`Noise_KKpsk2_25519_AESGCM_SHA256`, with automatic cleartext fallback for legacy Music Assistant servers), play timestamp-aligned PCM streams directly through native `AudioTrack`, fetch/cache synced LRC lyrics via `SafeNetworkHelper` + `lrclib.net`, and render a 1080p two-column album art & smooth-scrolling lyrics screen (`SendspinActivity`) styled with YuJian's 8 visual themes.

**Tech Stack:** Kotlin (`jvmTarget = "17"`), Android Framework API 23 (`AudioTrack`, `Canvas`, `Socket`/`ServerSocket`, `MulticastSocket`, `javax.crypto`), NanoHTTPD `2.3.1` (existing), ZXing `3.5.3` (existing), JUnit `4.13.2` (`testImplementation` only).

**Spec:** `docs/superpowers/specs/2026-11-07-sendspin-tv-client-design.md`

## Global Constraints

- Minimum Android SDK is strictly `minSdk = 23` (Android 6.0 Marshmallow Xiaomi/Redmi TV); `lint { enable += listOf("NewApi") }` is enforced — do not use `java.util.Base64` (API 26), `java.security.interfaces.XECPublicKey` (API 33), `AudioTrack.Builder` (API 23+ exists, or classic constructor), or `StandardCharsets` methods above API 23; use `android.util.Base64` (or pure-Kotlin Base64URL in `SendspinCrypto`) and `Charsets.UTF_8`.
- Zero new runtime (`implementation`) dependencies in `app/build.gradle.kts`; APK size delta must stay under `35 KB` (`testImplementation` dependencies for unit tests are allowed because they add `0` bytes to the APK).
- All HTTPS lyrics requests (`https://lrclib.net/...`) MUST go through `SafeNetworkHelper.openConnection(...)` so Android 6.0 TVs with expired system root CAs do not fail with `SSLHandshakeException`.
- Note on build environment: if `gradle` is not installed on the local workstation, verify cryptographic/framing vectors locally with `node` where applicable and rely on `.github/workflows/build.yml` (`gradle testDebugUnitTest assembleRelease --no-daemon --stacktrace`) for full JUnit + Android Lint `NewApi` + R8 release APK verification.

## Review Focus

- **Low-order / all-zero X25519 peer public key** (`ByteArray(32)` in `server/init` or `noise/handshake`): `SendspinCrypto` must reject an all-zero DH shared secret by throwing `SecurityException` rather than deriving a predictable AES-GCM key (pinned in Task 1 `SendspinCryptoTest.rejectsLowOrderAllZeroPublicKey`).
- **PSK lookup miss after app reinstall vs. misbound `server_id`** (`psk_category = "lt"` in `noise/handshake` msg1): unknown `psk_id` during initial handshake must fall back to `SENTINEL_PSK` so Music Assistant can connect/re-pair, whereas a matched `lt` record whose stored `serverId` differs from `server/init` must fail the handshake immediately (pinned in Task 1 `SendspinCryptoTest.fallsBackToSentinelOnUnknownPskAndRejectsMisboundServerId`).
- **64-bit clock epoch disparity & progress overflow** (server Unix epoch `~1.76e15` µs vs. TV `System.nanoTime()/1000` `~1e8` µs): offset math and `(nowServerUs - metadata.timestamp) * playbackSpeed / 1_000_000` must not overflow signed `Long` or wrap negative on clock jumps (pinned in Task 2 `SendspinClockFilterTest.handlesLargeEpochDisparityWithoutOverflow`).
- **Real-world LRC edge cases** (mixed 1/2/3-digit fractional seconds `[01:02.5]`/`[01:02.50]`/`[01:02.500]`, stacked chorus timestamps `[00:15.00][01:15.00]`, negative `[offset:-500]` near `00:00.20` clamping to `0L`, and unsynced plain text): `LrcParser` must expand, sort, and clamp cleanly without crashing (pinned in Task 3 `LrcParserTest.parsesMultiTimestampVariablePrecisionAndClampsNegativeOffset`).
- **WebSocket payloads > 125 bytes & multi-frame continuation** (`len == 126` 16-bit extended length for 4–64 KB PCM/JPEG frames, masked client frames, `FIN=0` + `OPCODE_CONTINUATION`, and HTTP upgrade carrying forbidden `Origin` header): `SendspinWsFrameCodec` must reassemble multi-KB/fragmented frames intact and reject server-mode upgrades carrying `Origin` (pinned in Task 4 `SendspinFramingTest.reassemblesExtendedMaskedContinuationFramesAndRejectsOrigin`).

---

### Task 1: Build/Test Harness, Preferences & Pure-Kotlin Sendspin Crypto

**Files:**
- Modify: `app/build.gradle.kts:47-48,124-130`
- Modify: `.github/workflows/build.yml:76-78`
- Modify: `app/src/main/java/com/zqc/tvnotify/NotifyService.kt:39`
- Modify: `app/src/main/java/com/zqc/tvnotify/Prefs.kt:16-109`
- Create: `app/src/main/java/com/zqc/tvnotify/SendspinCrypto.kt`
- Test: `app/src/test/java/com/zqc/tvnotify/SendspinCryptoTest.kt`

**Interfaces:**
- Consumes: `Prefs` (`SharedPreferences` in `Prefs.kt`)
- Produces:
  - `Prefs.isSendspinEnabled(context: Context): Boolean` / `setSendspinEnabled(context: Context, enabled: Boolean)`
  - `Prefs.isSendspinAutoPopup(context: Context): Boolean` / `setSendspinAutoPopup(context: Context, autoPopup: Boolean)`
  - `Prefs.getSendspinServerUrl(context: Context): String` / `setSendspinServerUrl(context: Context, url: String)`
  - `Prefs.getSendspinClientName(context: Context): String` / `setSendspinClientName(context: Context, name: String)`
  - `Prefs.getOrCreateSendspinPrivateKey(context: Context): ByteArray` (32-byte CSPRNG key persisted as Base64URL)
  - `Prefs.getSendspinPairingsJson(context: Context): String?` / `setSendspinPairingsJson(context: Context, raw: String)`
  - `SendspinCrypto`:
    - `const val NOISE_PROTOCOL_NAME = "Noise_KKpsk2_25519_AESGCM_SHA256"`
    - `val SENTINEL_PSK: ByteArray` (`1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3`)
    - `const val SENTINEL_PSK_ID = "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo"`
    - `fun base64UrlEncode(bytes: ByteArray): String` / `fun base64UrlDecode(text: String): ByteArray` (pure Kotlin, no `=` padding, works identically in JVM unit tests and Android API 23)
    - `fun x25519PublicFromPrivate(privateKey32: ByteArray): ByteArray`
    - `fun x25519SharedSecret(privateKey32: ByteArray, peerPublic32: ByteArray): ByteArray` (throws `SecurityException` if output is all zeros)
    - `fun computePskId(psk32: ByteArray): String` (`base64UrlEncode(SHA-256("sendspin-psk-id-v1" || psk32))`)
    - `fun buildPairingToken(clientId43: String, pairingPsk32: ByteArray): String` (`"SP:0$clientId43${base64UrlEncode(pairingPsk32)}"`)
    - `data class PairingRecord(val serverId: String, val psk: ByteArray)`
    - `class TransportCipherPair(val decryptServerToClient: (ByteArray) -> ByteArray, val encryptClientToServer: (ByteArray) -> ByteArray, val handshakeHash: ByteArray, val matchedCategory: String)`
    - `class NoiseResponder(clientPrivateKey32: ByteArray, clientPublicKey32: ByteArray, serverPublicKey32: ByteArray, prologueBytes: ByteArray)`:
      - `fun processHandshakeMsg1AndBuildMsg2(msg1Bytes: ByteArray, longTermRecords: List<PairingRecord>, activePairingPsk: ByteArray?, allowSentinelFallback: Boolean = true): Pair<ByteArray, TransportCipherPair>`

- [ ] **Step 1: Enable JUnit 4 unit tests in `app/build.gradle.kts` and CI, and bump version to `1.5.0`**

In `app/build.gradle.kts`, update `versionCode = 24`, `versionName = "1.5.0"`, and add `testImplementation("junit:junit:4.13.2")` + `testImplementation("org.json:json:20231013")` inside `dependencies { ... }`. In `NotifyService.kt:39`, update `const val VERSION_NAME = "1.5.0"`. In `.github/workflows/build.yml:77`, update the Gradle step to `gradle testDebugUnitTest assembleRelease --no-daemon --stacktrace`.

- [ ] **Step 2: Write the failing unit tests in `app/src/test/java/com/zqc/tvnotify/SendspinCryptoTest.kt`**

```kotlin
package com.zqc.tvnotify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SendspinCryptoTest {
    @Test
    fun rfc7748VectorAndSentinelPskConstantsMatchSpec() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = SendspinCrypto.x25519PublicFromPrivate(alicePriv)
        val bobPub = SendspinCrypto.x25519PublicFromPrivate(bobPriv)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(alicePub))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hex(bobPub))
        val sharedA = SendspinCrypto.x25519SharedSecret(alicePriv, bobPub)
        val sharedB = SendspinCrypto.x25519SharedSecret(bobPriv, alicePub)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(sharedA))
        assertArrayEquals(sharedA, sharedB)
        assertEquals("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3", hex(SendspinCrypto.SENTINEL_PSK))
        assertEquals(SendspinCrypto.SENTINEL_PSK_ID, SendspinCrypto.computePskId(SendspinCrypto.SENTINEL_PSK))
    }

    @Test
    fun rejectsLowOrderAllZeroPublicKey() {
        val priv = ByteArray(32) { (it + 1).toByte() }
        try {
            SendspinCrypto.x25519SharedSecret(priv, ByteArray(32))
            fail("Expected SecurityException on all-zero public key")
        } catch (_: SecurityException) {}
    }

    @Test
    fun noiseKKpsk2HandshakeAndTransportRoundTrip() {
        val clientPriv = ByteArray(32) { (it + 11).toByte() }
        val clientPub = SendspinCrypto.x25519PublicFromPrivate(clientPriv)
        val serverPriv = ByteArray(32) { (it + 41).toByte() }
        val serverPub = SendspinCrypto.x25519PublicFromPrivate(serverPriv)
        val prologue = """{"type":"client/init"}{"type":"server/init"}""".toByteArray(Charsets.UTF_8)

        val initiator = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, prologue)
        val msg1 = initiator.buildMsg1(SendspinCrypto.SENTINEL_PSK_ID, "sn")

        val responder = SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
        val (msg2, clientTransport) = responder.processHandshakeMsg1AndBuildMsg2(msg1, emptyList(), null)
        val serverTransport = initiator.processMsg2(msg2, SendspinCrypto.SENTINEL_PSK)

        val s2cCipher = serverTransport.encryptInitiatorToResponder("hello-tv".toByteArray())
        assertEquals("hello-tv", String(clientTransport.decryptServerToClient(s2cCipher)))

        val c2sCipher = clientTransport.encryptClientToServer("hello-ma".toByteArray())
        assertEquals("hello-ma", String(serverTransport.decryptResponderToInitiator(c2sCipher)))
    }

    @Test
    fun fallsBackToSentinelOnUnknownPskAndRejectsMisboundServerId() {
        val clientPriv = ByteArray(32) { (it + 3).toByte() }
        val clientPub = SendspinCrypto.x25519PublicFromPrivate(clientPriv)
        val serverPriv = ByteArray(32) { (it + 53).toByte() }
        val serverPub = SendspinCrypto.x25519PublicFromPrivate(serverPriv)
        val prologue = "prologue".toByteArray()
        val unknownPsk = ByteArray(32) { 0x7A }

        // 1) Unknown lt psk_id -> falls back to Sentinel PSK on initial handshake
        val init1 = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, prologue)
        val msg1Unknown = init1.buildMsg1(SendspinCrypto.computePskId(unknownPsk), "lt")
        val (_, transport1) = SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
            .processHandshakeMsg1AndBuildMsg2(msg1Unknown, emptyList(), null, allowSentinelFallback = true)
        assertEquals("sn", transport1.matchedCategory)

        // 2) Matched lt psk_id bound to a DIFFERENT server_id -> must throw SecurityException
        val init2 = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, prologue)
        val msg1Misbound = init2.buildMsg1(SendspinCrypto.computePskId(unknownPsk), "lt")
        val wrongServerRecord = SendspinCrypto.PairingRecord("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", unknownPsk)
        try {
            SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
                .processHandshakeMsg1AndBuildMsg2(msg1Misbound, listOf(wrongServerRecord), null, allowSentinelFallback = true)
            fail("Expected SecurityException on misbound server_id")
        } catch (_: SecurityException) {}
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
```

- [ ] **Step 3: Extend `Prefs.kt` and implement `SendspinCrypto.kt`**

In `Prefs.kt`, add the Sendspin preference getters/setters listed in **Interfaces** (`sendspin_enabled` default `true`, `sendspin_auto_popup` default `true`, `sendspin_server_url` default `""`, `sendspin_client_name` default `"玉简·${(android.os.Build.MODEL ?: "TV").trim()}"`, `sendspin_priv_key` 32-byte `SecureRandom` persisted with `SendspinCrypto.base64UrlEncode`, and `sendspin_pairings` raw JSON).

In `SendspinCrypto.kt`, implement:
- Pure-Kotlin URL-safe Base64 without padding (`A-Za-z0-9-_`),
- RFC 7748 `X25519` field arithmetic in `GF(2^255 - 19)` (`LongArray(16)`, clamping `k[0] &= 248`, `k[31] = (k[31] & 127) | 64`, `u[31] &= 127`, constant-time `cswap`, base point `[9, 0, ...]`), rejecting all-zero 32-byte shared secrets with `SecurityException`,
- `SymmetricState` & `CipherState` over `Cipher.getInstance("AES/GCM/NoPadding")` (`GCMParameterSpec(128, nonce12)` where `nonce12[0..3] = 0` and `nonce12[4..11] = bigEndianU64(n)`), `MessageDigest.getInstance("SHA-256")`, and HKDF-SHA256 (`HmacSHA256`),
- `NoiseResponder` (and a small `NoiseInitiatorForTest` helper inside `SendspinCrypto` so `KKpsk2` can be round-trip tested without external dependencies).

- [ ] **Step 4: Verify crypto vectors and commit**

Run `gradle testDebugUnitTest --tests com.zqc.tvnotify.SendspinCryptoTest` if Gradle is present locally; otherwise verify the X25519 and AES-GCM vectors against `node` `crypto.diffieHellman` so CI's `testDebugUnitTest` passes on first run. Then commit:
```powershell
git add app/build.gradle.kts .github/workflows/build.yml app/src/main/java/com/zqc/tvnotify/NotifyService.kt app/src/main/java/com/zqc/tvnotify/Prefs.kt app/src/main/java/com/zqc/tvnotify/SendspinCrypto.kt app/src/test/java/com/zqc/tvnotify/SendspinCryptoTest.kt
git commit -m "feat(sendspin): add pure-Kotlin X25519 + Noise KKpsk2 crypto and prefs"
```

---

### Task 2: Sendspin Protocol Models & Clock Synchronization Filter

**Files:**
- Create: `app/src/main/java/com/zqc/tvnotify/SendspinModels.kt`
- Test: `app/src/test/java/com/zqc/tvnotify/SendspinClockFilterTest.kt`

**Interfaces:**
- Consumes: none
- Produces:
  - `data class SendspinPlayerFormat(val codec: String = "pcm", val sampleRate: Int = 48000, val channels: Int = 2, val bitDepth: Int = 16)`
  - `data class SendspinTrackMetadata(val timestampUs: Long = 0L, val title: String = "", val artist: String = "", val albumArtist: String = "", val album: String = "", val artworkUrl: String = "", val year: Int = 0, val track: Int = 0, val hasProgress: Boolean = false, val trackProgressMs: Long = 0L, val trackDurationMs: Long = 0L, val playbackSpeed: Int = 0, val embeddedLyrics: String = "")`
  - `data class SendspinControllerState(val supportedCommands: Set<String> = emptySet(), val volume: Int = 100, val muted: Boolean = false, val repeat: String = "off", val shuffle: Boolean = false, val seekMaxMs: Long = 0L)`
  - `data class SendspinColorPalette(val timestampUs: Long = 0L, val backgroundDarkRgb: Int? = null, val primaryRgb: Int? = null, val accentRgb: Int? = null)`
  - `data class SendspinSnapshot(val enabled: Boolean, val autoPopup: Boolean, val connected: Boolean, val playing: Boolean, val boundPort: Int, val serverName: String, val serverUrl: String, val clientName: String, val clientId: String, val pairingToken: String, val format: SendspinPlayerFormat?, val metadata: SendspinTrackMetadata, val controller: SendspinControllerState, val playerVolume: Int, val playerMuted: Boolean, val rttMs: Long, val synced: Boolean, val colorPalette: SendspinColorPalette?)`
  - `class SendspinClockFilter`:
    - `val isSynced: Boolean`
    - `val minRttUs: Long`
    - `fun reset()`
    - `fun onTimeExchange(clientTxUs: Long, serverRxUs: Long, serverTxUs: Long, clientRxUs: Long)`
    - `fun serverToLocalUs(serverTimeUs: Long): Long`
    - `fun localToServerUs(localTimeUs: Long): Long`
    - `fun currentTrackPositionMs(meta: SendspinTrackMetadata, nowLocalUs: Long): Long`

- [ ] **Step 1: Write the failing unit tests in `app/src/test/java/com/zqc/tvnotify/SendspinClockFilterTest.kt`**

```kotlin
package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SendspinClockFilterTest {
    @Test
    fun handlesLargeEpochDisparityWithoutOverflow() {
        val filter = SendspinClockFilter()
        val localBase = 120_000_000L // 120s uptime in us
        val serverBase = 1_762_531_200_000_000L // Unix epoch 2025+ in us
        // True offset = serverBase - localBase, symmetric 2ms one-way delay (4000us RTT)
        filter.onTimeExchange(
            clientTxUs = localBase,
            serverRxUs = serverBase + 2_000L,
            serverTxUs = serverBase + 2_100L,
            clientRxUs = localBase + 4_100L
        )
        assertTrue(filter.isSynced)
        assertEquals(4_000L, filter.minRttUs)
        assertTrue(abs(filter.localToServerUs(localBase + 1_000_000L) - (serverBase + 1_000_000L)) <= 50L)

        // Extrapolate 10.5 seconds at 1.0x (playbackSpeed = 1000) and clamp to duration
        val meta = SendspinTrackMetadata(
            timestampUs = serverBase,
            title = "Test",
            hasProgress = true,
            trackProgressMs = 30_000L,
            trackDurationMs = 180_000L,
            playbackSpeed = 1000
        )
        val pos = filter.currentTrackPositionMs(meta, localBase + 10_500_000L)
        assertEquals(40_500L, pos)

        // Clamps to trackDurationMs even after 10 hours
        val clamped = filter.currentTrackPositionMs(meta, localBase + 36_000_000_000L)
        assertEquals(180_000L, clamped)
    }

    @Test
    fun rejectsHighJitterSpikeUsingMinRttWindow() {
        val filter = SendspinClockFilter()
        // Clean measurement: offset = +500_000us, RTT = 2_000us
        repeat(4) { i ->
            val t = i * 1_000_000L
            filter.onTimeExchange(t, t + 501_000L, t + 501_000L, t + 2_000L)
        }
        // Asymmetric Wi-Fi spike: 200ms delayed downlink -> RTT = 201_000us, skewed offset
        val spikeT = 5_000_000L
        filter.onTimeExchange(spikeT, spikeT + 501_000L, spikeT + 501_000L, spikeT + 201_000L)
        // Mapped time must remain within 2ms of true +500_000us offset
        val mappedLocal = filter.serverToLocalUs(10_500_000L)
        assertTrue(abs(mappedLocal - 10_000_000L) < 2_000L)
    }
}
```

- [ ] **Step 2: Implement `SendspinModels.kt`**

Implement the data classes and `SendspinClockFilter` in `SendspinModels.kt`:
- Compute `rttUs = ((clientRxUs - clientTxUs) - (serverTxUs - serverRxUs)).coerceAtLeast(0L)` and `sampleOffsetUs = ((serverRxUs - clientTxUs) / 2L) + ((serverTxUs - clientRxUs) / 2L) + (((serverRxUs - clientTxUs) % 2L + (serverTxUs - clientRxUs) % 2L) / 2L)` so `serverRxUs + serverTxUs` never overflows `Long`.
- Maintain a sliding window of the last 16 `(rttUs, sampleOffsetUs)` samples; discard outlier samples whose `rttUs > minWindowRttUs * 3 + 5_000L` once synced, and blend accepted samples via EMA (`alpha = 0.25`) toward the minimum-RTT sample's offset.
- In `currentTrackPositionMs`, return `0L` if `!meta.hasProgress`, `meta.trackProgressMs` if `meta.playbackSpeed == 0` or `!isSynced`, otherwise compute `deltaUs = localToServerUs(nowLocalUs) - meta.timestampUs` using `BigInteger` or split quotient/remainder `(deltaUs / 1_000_000L) * meta.playbackSpeed + ((deltaUs % 1_000_000L) * meta.playbackSpeed) / 1_000_000L` so multi-hour deltas never overflow `Long`, and clamp to `0L..meta.trackDurationMs` when `trackDurationMs > 0`.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/zqc/tvnotify/SendspinModels.kt app/src/test/java/com/zqc/tvnotify/SendspinClockFilterTest.kt
git commit -m "feat(sendspin): add protocol models and overflow-safe clock sync filter"
```

---

### Task 3: LRC Lyrics Parser & Android 6.0 Safe LRCLIB Fetcher

**Files:**
- Create: `app/src/main/java/com/zqc/tvnotify/LyricsFetcher.kt`
- Test: `app/src/test/java/com/zqc/tvnotify/LrcParserTest.kt`

**Interfaces:**
- Consumes: `SafeNetworkHelper.openConnection` (`SafeNetworkHelper.kt:43`)
- Produces:
  - `data class LrcLine(val timeMs: Long, val text: String)`
  - `data class ParsedLyrics(val synced: Boolean, val lines: List<LrcLine>, val source: String)`
  - `object LrcParser`:
    - `fun parse(rawText: String, source: String = "lrc"): ParsedLyrics`
    - `fun findActiveLineIndex(lines: List<LrcLine>, positionMs: Long): Int`
  - `object LyricsFetcher`:
    - `fun fetchAsync(context: Context, title: String, artist: String, album: String, durationMs: Long, embeddedLyrics: String = "", onResult: (ParsedLyrics?) -> Unit)`

- [ ] **Step 1: Write the failing unit tests in `app/src/test/java/com/zqc/tvnotify/LrcParserTest.kt`**

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
        // [00:00.20] = 200ms, minus 500ms offset -> clamped to 0ms
        assertEquals(0L, parsed.lines[0].timeMs)
        assertEquals("前奏轻吟", parsed.lines[0].text)
        // [00:15.5] = 15500ms - 500ms = 15000ms
        assertEquals(15_000L, parsed.lines[1].timeMs)
        // [00:22.125] = 22125ms - 500ms = 21625ms
        assertEquals(21_625L, parsed.lines[2].timeMs)
        // [01:15.50] = 75500ms - 500ms = 75000ms
        assertEquals(75_000L, parsed.lines[3].timeMs)

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

- [ ] **Step 2: Implement `LrcParser` and `LyricsFetcher` in `LyricsFetcher.kt`**

Implement `LrcParser`:
- Parse `[offset:+/-int]` metadata tag (positive offset shifts lyric display earlier or per LRC convention subtracts/adds consistently — follow standard LRC where `[offset:-500]` shifts timestamps by `-500ms`, clamped with `.coerceAtLeast(0L)`).
- Match all `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss:xx]`, `[mm:ss.xxx]` tags on a line (`1` digit -> `*100`, `2` digits -> `*10`, `3` digits -> `*1`), ignore non-lyric metadata tags (`[ar:...]`, `[ti:...]`, `[al:...]`, `[by:...]`), sort stably by `timeMs`, and implement binary-search `findActiveLineIndex(lines, positionMs)` returning `-1` when `lines.isEmpty()` or `positionMs < lines[0].timeMs`.
- If a non-blank input has zero timestamped lines, return `ParsedLyrics(synced = false, lines = nonBlankLines.map { LrcLine(0L, it) }, source)`.

Implement `LyricsFetcher`:
- Check `embeddedLyrics` first; if non-blank, parse and deliver immediately.
- Compute cache key `SHA-256("${title.trim().lowercase()}|${artist.trim().lowercase()}")` in `File(context.cacheDir, "sendspin_lyrics")`; prune oldest files when directory exceeds `100` files.
- Query `https://lrclib.net/api/get?track_name=...&artist_name=...&album_name=...&duration=...` (URL-encoded with `URLEncoder.encode(..., "UTF-8")`) via `SafeNetworkHelper.openConnection(url, 4500, 4500, "YuJian-TV/1.5 (https://github.com/JochenZhou/YuJian)", "application/json")`.
- On `404` or missing `syncedLyrics`, fall back to `https://lrclib.net/api/search?track_name=...&artist_name=...`, picking the first item with non-empty `syncedLyrics` whose `duration` is within `3.5s` of `durationMs / 1000.0` (or the first `syncedLyrics` / `plainLyrics` match if `durationMs <= 0`). Save non-empty lyrics to disk cache and invoke `onResult` on a caller-safe callback.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/zqc/tvnotify/LyricsFetcher.kt app/src/test/java/com/zqc/tvnotify/LrcParserTest.kt
git commit -m "feat(sendspin): add LRC parser and Android 6.0 safe LRCLIB fetcher"
```

---

### Task 4: PCM Synchronized Audio Engine, WebSocket Framing & Sendspin Manager

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:20-23`
- Create: `app/src/main/java/com/zqc/tvnotify/SendspinAudioPlayer.kt`
- Create: `app/src/main/java/com/zqc/tvnotify/SendspinWsFrameCodec.kt`
- Create: `app/src/main/java/com/zqc/tvnotify/SendspinManager.kt`
- Test: `app/src/test/java/com/zqc/tvnotify/SendspinFramingTest.kt`

**Interfaces:**
- Consumes: `SendspinCrypto` (Task 1), `Prefs` (Task 1), `SendspinClockFilter` & `SendspinModels` (Task 2), `LyricsFetcher` & `ParsedLyrics` (Task 3)
- Produces:
  - `SendspinWsFrameCodec`:
    - `data class WsMessage(val opcode: Int, val payload: ByteArray)` (`OPCODE_TEXT = 0x1`, `OPCODE_BINARY = 0x2`, `OPCODE_CLOSE = 0x8`, `OPCODE_PING = 0x9`, `OPCODE_PONG = 0xA`)
    - `fun performServerUpgrade(input: InputStream, output: OutputStream, expectedPath: String = "/sendspin"): Boolean` (reads HTTP upgrade headers, **rejects with `403 Forbidden` if `Origin` header is present** per Sendspin spec line 183, verifies `Upgrade: websocket` and `Sec-WebSocket-Key`, writes `101 Switching Protocols` with `Sec-WebSocket-Accept = Base64(SHA-1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`)
    - `fun performClientUpgrade(input: InputStream, output: OutputStream, hostHeader: String, path: String)`
    - `fun readMessage(input: InputStream, maxPayloadBytes: Int = 2 * 1024 * 1024, onPing: (ByteArray) -> Unit): WsMessage?`
    - `fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray, maskClientToServer: Boolean)`
    - `class FragmentReassembler` (`fun pushFragment(packet: ByteArray): Pair<Int, ByteArray>?`)
    - `class ArtworkAssembler` (`fun onBinaryArtwork(packet: ByteArray): ArtworkEvent` — handles `Announce` `flags=0x02`, `Part` `flags=0x00`, `Cancel` `flags=0x01`, and legacy `[8][ts:8][jpeg/png]` single-frame fallback)
  - `SendspinAudioPlayer(context: Context, clockFilter: SendspinClockFilter)`:
    - `fun configureStream(format: SendspinPlayerFormat)`
    - `fun enqueueAudioChunk(serverTimestampUs: Long, pcmBytes: ByteArray)`
    - `fun clearBuffer(fromServerTimestampUs: Long? = null)`
    - `fun stopStream()`
    - `fun setVolumeAndMute(volumePercent: Int, muted: Boolean)`
    - `fun release()`
  - `object SendspinManager`:
    - `fun init(context: Context, onAutoPopupRequested: () -> Unit)`
    - `fun reloadConfig()`
    - `fun shutdown()`
    - `fun shouldAdvertiseMdns(): Boolean`
    - `val boundPort: Int`
    - `fun snapshot(): SendspinSnapshot`
    - `fun currentArtwork(): Bitmap?`
    - `fun currentLyrics(): ParsedLyrics?`
    - `fun currentPositionMs(): Long`
    - `fun sendControllerCommand(command: String, volume: Int? = null, mute: Boolean? = null)`
    - `fun adjustLocalOrGroupVolume(deltaPercent: Int)`
    - `fun regeneratePairingToken(): String`
    - `fun addListener(listener: () -> Unit)` / `fun removeListener(listener: () -> Unit)`

- [ ] **Step 1: Add `WAKE_LOCK` permission to `app/src/main/AndroidManifest.xml`**

Add `<uses-permission android:name="android.permission.WAKE_LOCK" />` right below `FOREGROUND_SERVICE_SPECIAL_USE` in `app/src/main/AndroidManifest.xml`.

- [ ] **Step 2: Write the failing unit tests in `app/src/test/java/com/zqc/tvnotify/SendspinFramingTest.kt`**

```kotlin
package com.zqc.tvnotify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SendspinFramingTest {
    @Test
    fun reassemblesExtendedMaskedContinuationFramesAndRejectsOrigin() {
        // 1) Server upgrade MUST reject requests containing an Origin header
        val reqWithOrigin = (
            "GET /sendspin HTTP/1.1\r\n" +
            "Host: 192.168.1.50:8928\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Origin: http://evil.example\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
        ).toByteArray()
        val respOut1 = ByteArrayOutputStream()
        assertFalse(SendspinWsFrameCodec.performServerUpgrade(ByteArrayInputStream(reqWithOrigin), respOut1))
        assertTrue(respOut1.toString("UTF-8").contains("403"))

        // 2) Valid server upgrade without Origin succeeds with RFC 6455 accept hash
        val validReq = (
            "GET /sendspin HTTP/1.1\r\n" +
            "Host: 192.168.1.50:8928\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
        ).toByteArray()
        val respOut2 = ByteArrayOutputStream()
        assertTrue(SendspinWsFrameCodec.performServerUpgrade(ByteArrayInputStream(validReq), respOut2))
        assertTrue(respOut2.toString("UTF-8").contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))

        // 3) Masked >125-byte (len=126, 8192 bytes) split across two continuation frames
        val original = ByteArray(8192) { (it * 31 and 0xFF).toByte() }
        val wire = ByteArrayOutputStream()
        SendspinWsFrameCodec.writeRawFrameForTest(wire, fin = false, opcode = 0x2, payload = original.copyOfRange(0, 5000), masked = true)
        SendspinWsFrameCodec.writeRawFrameForTest(wire, fin = true, opcode = 0x0, payload = original.copyOfRange(5000, 8192), masked = true)

        val decoded = SendspinWsFrameCodec.readMessage(ByteArrayInputStream(wire.toByteArray())) {}
        assertNotNull(decoded)
        assertEquals(0x2, decoded!!.opcode)
        assertArrayEquals(original, decoded.payload)
    }

    @Test
    fun reassemblesSendspinFragmentsAndArtworkAnnouncePartCancel() {
        val frag = SendspinWsFrameCodec.FragmentReassembler()
        assertNull(frag.pushFragment(byteArrayOf(1, 0x00, 0x00) + "{\"type\":".toByteArray()))
        val done = frag.pushFragment(byteArrayOf(1, 0x01, 0x00) + "\"ok\"}".toByteArray())
        assertNotNull(done)
        assertEquals(0, done!!.first)
        assertEquals("{\"type\":\"ok\"}", String(done.second))

        val art = SendspinWsFrameCodec.ArtworkAssembler()
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        // Announce: [8][0x02][timestamp=1000us (8B BE)][total_size=6 (4B BE)]
        val announce = byteArrayOf(8, 2, 0, 0, 0, 0, 0, 0, 0x03, 0xE8.toByte(), 0, 0, 0, 6)
        assertTrue(art.onBinaryArtwork(announce) is SendspinWsFrameCodec.ArtworkEvent.InFlight)
        assertTrue(art.onBinaryArtwork(byteArrayOf(8, 0) + imgBytes.copyOfRange(0, 3)) is SendspinWsFrameCodec.ArtworkEvent.InFlight)
        val completed = art.onBinaryArtwork(byteArrayOf(8, 0) + imgBytes.copyOfRange(3, 6))
        assertTrue(completed is SendspinWsFrameCodec.ArtworkEvent.Completed)
        assertArrayEquals(imgBytes, (completed as SendspinWsFrameCodec.ArtworkEvent.Completed).imageBytes)
    }
}
```

- [ ] **Step 3: Implement `SendspinWsFrameCodec.kt`, `SendspinAudioPlayer.kt`, and `SendspinManager.kt`**

1. **`SendspinWsFrameCodec.kt`**: Implement RFC 6455 HTTP server/client handshake (rejecting `Origin` on server upgrade), 7/16/64-bit frame reading/writing with masking when `maskClientToServer = true`, `FIN=0` continuation buffer, automatic `Pong` reply callback on `Ping`, `FragmentReassembler` (`[1][flags][inner_id][data]`), and `ArtworkAssembler` (`Announce`/`Part`/`Cancel` + `total_size == 0` clear + legacy `[8][ts:8][jpeg/png]` fallback).
2. **`SendspinAudioPlayer.kt`**: Dedicated high-priority playback thread (`Process.THREAD_PRIORITY_AUDIO`) consuming a timestamp-ordered queue bounded to `1_048_576` bytes. Convert mono 16-bit LE PCM to stereo when `channels == 1`. Align playback against `clockFilter.serverToLocalUs(chunk.timestampUs)`: insert up to `250ms` of silence when early (`> 20ms`), drop expired frames when late (`> 45ms`), write continuously within tolerance, apply `AudioTrack.setVolume(if (muted) 0f else (volume / 100f) * (volume / 100f))`, and hold a `PowerManager.PARTIAL_WAKE_LOCK` (`"tvnotify:sendspin-audio"`, `setReferenceCounted(false)`) only while stream is active.
3. **`SendspinManager.kt`**:
   - Server-Initiated mode (when `Prefs.getSendspinServerUrl` is blank): bind `ServerSocket` on first free port in `8928..8932` (`reuseAddress = true`), accept connections, perform `performServerUpgrade`, and run session loop.
   - Client-Initiated mode (when `Prefs.getSendspinServerUrl` is non-blank, e.g. `ws://192.168.1.10:8927/sendspin`): connect `Socket` with exponential backoff (`2s..30s`), perform `performClientUpgrade` (`maskClientToServer = true`), and run the same session loop.
   - Handshake auto-detection: send `client/init` (`version = 1`, `client_id`, `suite = "25519_AESGCM_SHA256"`). If peer replies with `server/init` + `noise/handshake`, complete `NoiseResponder` handshake (saving new long-term PSKs on `server/pair-finalize` and supporting transport re-handshakes), switch to encrypted binary frames, handle `server/hello` -> send `client/hello` -> handle `server/activate` -> send `client/state` + periodic `client/time` (every `250ms` for first 8 samples, then every `5s`). If peer replies directly with legacy cleartext `server/hello`, switch seamlessly to legacy cleartext JSON + raw binary Type `4`/`8` mode.
   - Decode artwork with `BitmapFactory` two-pass `inJustDecodeBounds` downsampling to `<= 600x600`, trigger `LyricsFetcher.fetchAsync` whenever `title`/`artist` changes, apply scheduled `metadata` and `color` updates when their `timestampUs` arrives, and invoke `onAutoPopupRequested()` when audio/playback transitions from idle to playing and `Prefs.isSendspinAutoPopup` is `true`.

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/zqc/tvnotify/SendspinWsFrameCodec.kt app/src/main/java/com/zqc/tvnotify/SendspinAudioPlayer.kt app/src/main/java/com/zqc/tvnotify/SendspinManager.kt app/src/test/java/com/zqc/tvnotify/SendspinFramingTest.kt
git commit -m "feat(sendspin): add WebSocket codec, PCM sync player, and session manager"
```

---

### Task 5: Dual-Service mDNS Discovery & `NotifyService` Lifecycle Integration

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/MdnsAdvertiser.kt:255-398`
- Modify: `app/src/main/java/com/zqc/tvnotify/NotifyService.kt:85-112,203-266`

**Interfaces:**
- Consumes: `SendspinManager.init`, `SendspinManager.shutdown`, `SendspinManager.shouldAdvertiseMdns`, `SendspinManager.boundPort`, `Prefs.getSendspinClientName`
- Produces:
  - `MdnsAdvertiser` simultaneously answering and periodically announcing both `_tvnotify._tcp.local` (port `8791`) and `_sendspin._tcp.local` (port `SendspinManager.boundPort`, TXT `path=/sendspin` & `name=${Prefs.getSendspinClientName(appContext)}`) using the ASCII-safe `instanceName()` (`YuJian-xxxx._sendspin._tcp.local`).
  - `NotifyService` managing `SendspinManager` lifecycle and auto-launching `SendspinActivity` on main thread when playback begins.

- [ ] **Step 1: Extend `MdnsAdvertiser.kt` to advertise `_sendspin._tcp.local` alongside `_tvnotify._tcp.local`**

In `MdnsAdvertiser.kt`:
- Add `private const val SENDSPIN_SERVICE_TYPE = "_sendspin._tcp"`.
- In `handleQuery(packet)`, track `askedTvNotify = HashSet<Int>()` and `askedSendspin = HashSet<Int>()` separately by matching `name` against `tvNotifyServiceTypeFqdn()` / `tvNotifyInstanceFqdn()` vs. `sendspinServiceTypeFqdn()` / `sendspinInstanceFqdn()` (only populating `askedSendspin` when `SendspinManager.shouldAdvertiseMdns()` is `true` and `SendspinManager.boundPort > 0`), plus shared `hostFqdn()` for `TYPE_A`.
- In `respond(askedTvNotify, askedSendspin)` and `announce()`, emit `sendspinPtrRecord()`, `sendspinSrvRecord(SendspinManager.boundPort)`, and `sendspinTxtRecord()` (`"path=/sendspin"`, `"name=${Prefs.getSendspinClientName(appContext)}"`) whenever `askedSendspin` is non-empty, sharing `buildARecord()` without duplicating `TYPE_A` in the same packet.

- [ ] **Step 2: Wire `SendspinManager` into `NotifyService.kt`**

In `NotifyService.kt`:
- In `onCreate()` / `onStartCommand()`, call `SendspinManager.init(applicationContext) { launchSendspinActivityFromBackground() }` where `launchSendspinActivityFromBackground()` posts on `mainHandler` and starts `Intent(this, SendspinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)` inside `try { ... } catch (e: Exception) { Log.w(TAG, "后台拉起歌词页受限", e) }`.
- Call `SendspinManager.shutdown()` in `onDestroy()`.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/zqc/tvnotify/MdnsAdvertiser.kt app/src/main/java/com/zqc/tvnotify/NotifyService.kt
git commit -m "feat(sendspin): advertise _sendspin._tcp in MdnsAdvertiser and wire NotifyService"
```

---

### Task 6: Full-Screen 1080p Album Art & Scrolling Lyrics Activity (`SendspinActivity.kt`)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:69-71`
- Modify: `app/proguard-rules.pro:24-26`
- Create: `app/src/main/java/com/zqc/tvnotify/SendspinActivity.kt`

**Interfaces:**
- Consumes: `SendspinManager` (Task 4), `LrcParser` & `ParsedLyrics` (Task 3), `Theme` (`Theme.kt`), `Prefs` (Task 1), `QRCodeWriter` (`com.google.zxing.qrcode.QRCodeWriter`)
- Produces:
  - `class SendspinActivity : Activity()` with companion helper `SendspinActivity.closeIfOpen()` (used by HTTP `close_ui` action in Task 7).

- [ ] **Step 1: Register `SendspinActivity` in `AndroidManifest.xml` and `proguard-rules.pro`**

In `app/src/main/AndroidManifest.xml`, register:
```xml
<activity
    android:name=".SendspinActivity"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:exported="true"
    android:launchMode="singleTask"
    android:screenOrientation="landscape" />
```
In `app/proguard-rules.pro`, append `-keep class com.zqc.tvnotify.SendspinActivity { *; }`.

- [ ] **Step 2: Implement `SendspinActivity.kt` (1080p two-column TV player + custom `Canvas` `LyricsScrollView`)**

Build the pure-Kotlin Android Framework UI in `SendspinActivity.kt`:
- Set `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` and track active instance in `@Volatile var activeInstance: WeakReference<SendspinActivity>?` so `closeIfOpen()` can call `finish()` on the UI thread.
- **Background & Theme**: Style root layout with `Theme.current(this)` (`windowBg`, `cardBg`, `cardStroke`, `accentColor`, `bannerTitleTypeface`) plus a subtle radial ambient tint derived from `snapshot.colorPalette?.accentRgb ?: theme.accentColor`.
- **Left Column (42% width)**:
  - Top status pill: connection badge (`● 已连接 <serverName> · PCM 48kHz` or `◌ 等待 Music Assistant 发现 · :8928`), volume indicator (`🔊 100%`).
  - Square rounded album artwork container (`VinylArtworkView`): draws rounded `Bitmap` cover with subtle border glow when `SendspinManager.currentArtwork() != null`, or draws a rotating theme-styled vinyl/jade disc emblem on `Canvas` when no cover is available.
  - Track info: single-line bold marquee title (`22sp`), artist & album subtitle (`14sp`), thin progress bar (`Canvas` or `ProgressBar`) + `mm:ss / mm:ss` time row updated every `100ms` via `mainHandler` using `SendspinManager.currentPositionMs()`.
  - Bottom 7-button focusable D-Pad bar (`isFocusable = true` ONLY on these 7 buttons, initial focus on `playPauseBtn`): `⏮` (`previous`), `⏯` (`play`/`pause` toggle based on `playbackSpeed > 0`), `⏭` (`next`), `🔉` (`-5%` volume), `🔊` (`+5%` volume), `🎨` (theme selector dialog), `🔗` (Sendspin pairing QR code `SP:0...` + manual `ws://` server URL & auto-popup toggle dialog).
- **Right Column (58% width) — `LyricsScrollView(context)` (`isFocusable = false`)**:
  - Custom `View` rendering `ParsedLyrics` on `Canvas`:
    - Wraps long lyric lines cleanly (`StaticLayout` or line-break measurement within view width).
    - Smoothly interpolates `currentScrollY` (`currentScrollY += (targetScrollY - currentScrollY) * 0.22f`, calling `postInvalidateOnAnimation()` while `abs(diff) > 0.5f`) so the active line (`LrcParser.findActiveLineIndex`) glides into vertical center.
    - Active line: `26sp` bold, `theme.accentColor` (or bright `bannerTitle` on `INK_LIGHT`), subtle `setShadowLayer` glow.
    - Neighbor lines: `18sp`, alpha fading smoothly from `0xCC` near center down to `0x28` at top/bottom edges.
    - Empty / instrumental state: draws gentle animated 5-bar theme-colored equalizer wave + track status prompt in the center.
- **Remote Key Handling (`onKeyDown`)**:
  - `KEYCODE_DPAD_UP` -> `SendspinManager.adjustLocalOrGroupVolume(+5)` and consume (`return true`).
  - `KEYCODE_DPAD_DOWN` -> `SendspinManager.adjustLocalOrGroupVolume(-5)` and consume (`return true`).
  - `KEYCODE_MEDIA_PLAY_PAUSE` -> toggle `play`/`pause`; `KEYCODE_MEDIA_NEXT` -> `next`; `KEYCODE_MEDIA_PREVIOUS` -> `previous`.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/AndroidManifest.xml app/proguard-rules.pro app/src/main/java/com/zqc/tvnotify/SendspinActivity.kt
git commit -m "feat(sendspin): add 1080p full-screen album art and scrolling lyrics activity"
```

---

### Task 7: `MainActivity` Entry Point, HTTP Control Route & Mobile Web Console

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/MainActivity.kt:209-273`
- Modify: `app/src/main/java/com/zqc/tvnotify/NotifyHttpServer.kt:40-92,135-170,400-412`
- Modify: `app/src/main/java/com/zqc/tvnotify/WebConsoleHtml.kt:10-20,299-340,361-436`

**Interfaces:**
- Consumes: `SendspinManager` (Task 4), `SendspinActivity` (Task 6), `Prefs` (Task 1)
- Produces:
  - Focusable `🎵 灵音歌词台` button in `MainActivity` header bar (click -> starts `SendspinActivity`, long-click -> opens Sendspin quick settings).
  - `POST /{token}/sendspin` route in `NotifyHttpServer` supporting `action` in `["open_ui", "close_ui", "command", "config"]`, plus `"sendspin": { ... }` status object in `GET /health` and `POST /{token}/config`.
  - Mobile Web Console `🎵 Sendspin 灵音歌词台` control card in `WebConsoleHtml.kt`.

- [ ] **Step 1: Add `🎵 灵音歌词台` entry button to `MainActivity.kt`**

In `MainActivity.kt` inside `headerBar` (right before `headerBadgeView` at lines `265-272`), add `sendspinEntryButton = buildButton(label = if (theme.isXianxia) "🎵 灵音歌词台" else "🎵 Sendspin 歌词", isPrimary = true, onClick = { startActivity(Intent(this, SendspinActivity::class.java)) })` with `rightMargin = dp(10)` so TV remote `DPAD_UP` from `statusCard` reaches it cleanly without adding height to the 3×4 button grid. Also update its label on `refreshUi()` to show `"🎵 播放中 · ${snap.metadata.title.take(8)}"` when `SendspinManager.snapshot().playing` is `true`.

- [ ] **Step 2: Add `POST /{token}/sendspin` and `/health` Sendspin telemetry in `NotifyHttpServer.kt`**

In `NotifyHttpServer.kt`:
- Add `private const val ROUTE_SENDSPIN = "sendspin"` and dispatch `ROUTE_SENDSPIN -> handleSendspin(session)` in `serve(...)`.
- Implement `buildSendspinStatusJson(): JSONObject` returning keys `enabled`, `auto_popup`, `connected`, `playing`, `port`, `server_name`, `server_url`, `client_name`, `title`, `artist`, `album`, `volume`, `muted` from `SendspinManager.snapshot()`.
- Embed `"sendspin": ${buildSendspinStatusJson()}` in `buildHealthBody()` and `handleConfig(...)`.
- Implement `handleSendspin(session)` (`POST` only):
  - `"open_ui"` -> start `SendspinActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP`;
  - `"close_ui"` -> call `SendspinActivity.closeIfOpen()`;
  - `"command"` -> call `SendspinManager.sendControllerCommand(cmd, volume, mute)` (and update local player volume when `cmd == "volume"` or `"mute"`);
  - `"config"` -> update `Prefs` (`enabled`, `auto_popup`, `server_url`, `client_name`) and call `SendspinManager.reloadConfig()`;
  - Return `{"ok":true,"sendspin":${buildSendspinStatusJson()}}`.

- [ ] **Step 3: Add the Sendspin remote-control card to `WebConsoleHtml.kt`**

Pass `sendspinSnapshot: SendspinSnapshot = SendspinManager.snapshot()` into `WebConsoleHtml.render(...)` and insert a `🎵 Sendspin 灵音歌词台` `.card` between `电视阵法调控` and `传音简录` with:
- Live status line (connected server / listening port `:8928` + now-playing `title - artist`),
- Transport button row (`⏮ 上一曲`, `⏯ 播放/暂停`, `⏭ 下一曲`, `📺 弹开歌词屏`, `✖ 收起歌词屏`),
- Auto-popup checkbox + optional `ws://ip:8927/sendspin` manual server URL input + Save button posting to `/{token}/sendspin`.

- [ ] **Step 4: Commit and verify clean working tree**

```powershell
git add app/src/main/java/com/zqc/tvnotify/MainActivity.kt app/src/main/java/com/zqc/tvnotify/NotifyHttpServer.kt app/src/main/java/com/zqc/tvnotify/WebConsoleHtml.kt
git commit -m "feat(sendspin): add MainActivity entry, HTTP control API, and Web Console card"
git status
```
