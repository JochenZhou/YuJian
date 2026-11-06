package com.zqc.tvnotify

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 纯代码 PCM/WAV 数字声学合成器（零外部音频资源体积、零第三方库依赖）。
 *
 * 为 6 款扩展主题实时合成 44.1kHz 16-bit 单声道标准 WAV 音效并缓存在 cacheDir：
 * - [Theme.PALACE]    ：大唐鎏金 · 宫廷青铜编钟（浑厚基频 + 非整倍金属泛音列 + 殿堂混响）
 * - [Theme.CYBERPUNK] ：赛博霓虹 · 科幻 HUD 电子脉冲扫频音（双频上扫 Chirp + 霓虹和弦）
 * - [Theme.WUKONG]    ：黑神话·暗金 · 古刹金钵低鸣（低沉禅钟 + 2.5Hz 自然拍频余韵）
 * - [Theme.MATRIX]    ：极客终端 · 复古 CRT 终端双脉冲蜂鸣（带限方波双连 Beep-Beep）
 * - [Theme.INK_LIGHT] ：江南宣纸 · 空谷清竹水滴音（清脆竹木叩击 + 水滴上滑泛音）
 * - [Theme.GAMER]     ：主机奖杯 · 解锁奖杯四连升调琶音（D 大调璀璨白金琶音）
 */
object ThemeSoundSynth {

    private const val TAG = "TVNotifySoundSynth"
    private const val SAMPLE_RATE = 44100
    private const val CACHE_VERSION = "v1"

    /**
     * 获取或生成指定主题的 WAV 缓存文件。
     * 若生成失败则返回 null（调用方自动回退至内置 R.raw 音效）。
     */
    fun getOrGenerateWav(context: Context, theme: Theme): File? {
        val outFile = File(context.cacheDir, "theme_sound_${CACHE_VERSION}_${theme.storageId}.wav")
        if (outFile.exists() && outFile.length() > 100L) {
            return outFile
        }
        return try {
            val samples = when (theme) {
                Theme.PALACE -> synthPalaceBell()
                Theme.CYBERPUNK -> synthCyberpunkHud()
                Theme.WUKONG -> synthWukongBowl()
                Theme.MATRIX -> synthMatrixTerminal()
                Theme.INK_LIGHT -> synthInkBambooDrop()
                Theme.GAMER -> synthGamerTrophy()
                else -> return null
            }
            val wavBytes = encodePcm16MonoWav(samples, SAMPLE_RATE)
            val tmpFile = File(context.cacheDir, "theme_sound_${CACHE_VERSION}_${theme.storageId}.tmp")
            FileOutputStream(tmpFile).use { it.write(wavBytes) }
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                FileOutputStream(outFile).use { it.write(wavBytes) }
                tmpFile.delete()
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "合成主题音效失败: ${theme.storageId}", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // 1. 大唐鎏金：宫廷编钟（青铜钟基频 + 金属泛音 + 殿堂双延迟混响）
    // ------------------------------------------------------------------
    private fun synthPalaceBell(): FloatArray {
        val durationSec = 1.25f
        val total = (SAMPLE_RATE * durationSec).toInt()
        val buf = FloatArray(total)

        val f0 = 528.0 // C5 古音中吕
        val f1 = 792.0 // G5 五度相生

        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0

            // 钟槌木击瞬态
            if (t < 0.015) {
                v += sin(2.0 * PI * 190.0 * t) * exp(-t * 180.0) * 0.35
            }

            // 第一声：浑厚主编钟（基音 + 低八度共鸣 + 青铜非整倍泛音）
            val attack0 = (t / 0.005).coerceAtMost(1.0)
            v += sin(2.0 * PI * (f0 * 0.5) * t) * exp(-t * 2.4) * 0.32 * attack0
            v += sin(2.0 * PI * f0 * t) * exp(-t * 2.8) * 0.52 * attack0
            v += sin(2.0 * PI * (f0 * 2.02) * t) * exp(-t * 4.5) * 0.26 * attack0
            v += sin(2.0 * PI * (f0 * 2.76) * t) * exp(-t * 6.8) * 0.16 * attack0
            v += sin(2.0 * PI * (f0 * 3.92) * t) * exp(-t * 9.5) * 0.10 * attack0

            // 第二声（0.17s 后）：鎏金五度高音应和
            if (t >= 0.17) {
                val dt = t - 0.17
                val attack1 = (dt / 0.006).coerceAtMost(1.0)
                v += sin(2.0 * PI * f1 * dt) * exp(-dt * 3.1) * 0.44 * attack1
                v += sin(2.0 * PI * (f1 * 2.01) * dt) * exp(-dt * 5.4) * 0.20 * attack1
                v += sin(2.0 * PI * (f1 * 3.05) * dt) * exp(-dt * 8.2) * 0.10 * attack1
            }

            buf[i] = (v * 0.68).toFloat()
        }

        applyReverb(buf, delayMs1 = 68, gain1 = 0.26f, delayMs2 = 124, gain2 = 0.16f)
        applyFadeOut(buf, fadeMs = 90)
        return normalize(buf, peak = 0.88f)
    }

    // ------------------------------------------------------------------
    // 2. 赛博霓虹：科幻 HUD 电子脉冲扫频音
    // ------------------------------------------------------------------
    private fun synthCyberpunkHud(): FloatArray {
        val durationSec = 0.68f
        val total = (SAMPLE_RATE * durationSec).toInt()
        val buf = FloatArray(total)

        var phase1 = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0

            // 阶段 A (0.00s ~ 0.10s)：快速上扫电子脉冲 (660Hz -> 1080Hz)
            if (t < 0.105) {
                val p = (t / 0.105).coerceIn(0.0, 1.0)
                val freq = 660.0 + (1080.0 - 660.0) * p * p
                phase1 += 2.0 * PI * freq / SAMPLE_RATE
                val env = sin(PI * p)
                v += (sin(phase1) * 0.65 + sin(phase1 * 2.0) * 0.25 + sin(phase1 * 3.0) * 0.12) * env
            }

            // 阶段 B (0.11s ~ 0.21s)：高频锁定脉冲 (1480Hz FM 调制)
            if (t >= 0.11 && t < 0.215) {
                val dt = t - 0.11
                val p = (dt / 0.105).coerceIn(0.0, 1.0)
                val env = (dt / 0.005).coerceAtMost(1.0) * exp(-dt * 9.0)
                val mod = sin(2.0 * PI * 74.0 * dt) * 0.7
                v += sin(2.0 * PI * 1480.0 * dt + mod) * env * 0.78
                v += sin(2.0 * PI * 740.0 * dt) * env * 0.30
            }

            // 阶段 C (0.22s ~ 0.68s)：霓虹合成器和弦余韵 (740Hz + 1110Hz + 1850Hz)
            if (t >= 0.22) {
                val dt = t - 0.22
                val env = (dt / 0.006).coerceAtMost(1.0) * exp(-dt * 6.8)
                v += (sin(2.0 * PI * 740.0 * dt) * 0.48 +
                        sin(2.0 * PI * 1110.0 * dt) * 0.42 +
                        sin(2.0 * PI * 1850.0 * dt) * 0.24) * env
            }

            buf[i] = (v * 0.75).toFloat()
        }

        applyReverb(buf, delayMs1 = 46, gain1 = 0.24f, delayMs2 = 92, gain2 = 0.12f)
        applyFadeOut(buf, fadeMs = 55)
        return normalize(buf, peak = 0.86f)
    }

    // ------------------------------------------------------------------
    // 3. 黑神话·暗金：古刹金钵低鸣（深沉禅音 + 自然拍频余韵）
    // ------------------------------------------------------------------
    private fun synthWukongBowl(): FloatArray {
        val durationSec = 1.42f
        val total = (SAMPLE_RATE * durationSec).toInt()
        val buf = FloatArray(total)

        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (t / 0.008).coerceAtMost(1.0)

            // 钵体轻击瞬态
            val strike = if (t < 0.025) sin(2.0 * PI * 145.0 * t) * exp(-t * 110.0) * 0.42 else 0.0

            // 220Hz 深沉基音 + 440Hz/442.4Hz 微差拍频（产生古刹禅钵空灵共鸣）+ 五度/高八度泛音
            val low = sin(2.0 * PI * 220.0 * t) * exp(-t * 2.0) * 0.46
            val mid1 = sin(2.0 * PI * 440.0 * t) * exp(-t * 2.3) * 0.38
            val mid2 = sin(2.0 * PI * 442.4 * t) * exp(-t * 2.4) * 0.34
            val fifth = sin(2.0 * PI * 659.25 * t) * exp(-t * 3.8) * 0.22
            val shimmer = sin(2.0 * PI * 1174.66 * t) * exp(-t * 6.5) * 0.12

            buf[i] = ((strike + (low + mid1 + mid2 + fifth + shimmer) * attack) * 0.65).toFloat()
        }

        applyReverb(buf, delayMs1 = 82, gain1 = 0.28f, delayMs2 = 155, gain2 = 0.16f)
        applyFadeOut(buf, fadeMs = 110)
        return normalize(buf, peak = 0.88f)
    }

    // ------------------------------------------------------------------
    // 4. 极客终端：复古 CRT 终端双脉冲蜂鸣 (8-bit Beep-Beep)
    // ------------------------------------------------------------------
    private fun synthMatrixTerminal(): FloatArray {
        val durationSec = 0.46f
        val total = (SAMPLE_RATE * durationSec).toInt()
        val buf = FloatArray(total)

        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0

            // 第一声短促方波 (0.00s ~ 0.082s, 1180Hz)
            if (t < 0.082) {
                val env = ((t / 0.003).coerceAtMost(1.0)) * (((0.082 - t) / 0.006).coerceIn(0.0, 1.0))
                val f = 1180.0
                v += (sin(2.0 * PI * f * t) +
                        sin(2.0 * PI * f * 3.0 * t) / 3.0 +
                        sin(2.0 * PI * f * 5.0 * t) / 5.0) * env * 0.65
            }

            // 第二声确认高音方波 (0.12s ~ 0.28s, 1580Hz)
            if (t >= 0.12 && t < 0.29) {
                val dt = t - 0.12
                val env = (dt / 0.003).coerceAtMost(1.0) * exp(-dt * 11.5)
                val f = 1580.0
                v += (sin(2.0 * PI * f * dt) +
                        sin(2.0 * PI * f * 3.0 * dt) / 3.0 +
                        sin(2.0 * PI * f * 5.0 * dt) / 5.0) * env * 0.68
            }

            buf[i] = v.toFloat()
        }

        applyReverb(buf, delayMs1 = 38, gain1 = 0.18f, delayMs2 = 76, gain2 = 0.08f)
        applyFadeOut(buf, fadeMs = 40)
        return normalize(buf, peak = 0.80f)
    }

    // ------------------------------------------------------------------
    // 5. 江南宣纸：空谷清竹 + 水滴泛音
    // ------------------------------------------------------------------
    private fun synthInkBambooDrop(): FloatArray {
        val durationSec = 0.86f
        val total = (SAMPLE_RATE * durationSec).toInt()
        val buf = FloatArray(total)

        var dropPhase = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0

            // 第一声 (0.00s)：清脆竹木叩击 (760Hz + 1210Hz 短促木腔共鸣)
            val bambooEnv = (t / 0.002).coerceAtMost(1.0) * exp(-t * 26.0)
            v += (sin(2.0 * PI * 760.0 * t) * 0.58 + sin(2.0 * PI * 1210.0 * t) * 0.32) * bambooEnv

            // 第二声 (0.13s)：空谷清泉水滴（860Hz -> 1568Hz 快速上滑 + 清越余音）
            if (t >= 0.13) {
                val dt = t - 0.13
                val sweepProgress = (dt / 0.075).coerceIn(0.0, 1.0)
                val freq = 860.0 + (1568.0 - 860.0) * sweepProgress * sweepProgress
                dropPhase += 2.0 * PI * freq / SAMPLE_RATE
                val dropEnv = (dt / 0.003).coerceAtMost(1.0) * exp(-dt * 5.8)
                v += sin(dropPhase) * dropEnv * 0.62
                v += sin(2.0 * PI * 1046.5 * dt) * exp(-dt * 4.5) * 0.24
            }

            buf[i] = v.toFloat()
        }

        applyReverb(buf, delayMs1 = 58, gain1 = 0.25f, delayMs2 = 112, gain2 = 0.14f)
        applyFadeOut(buf, fadeMs = 65)
        return normalize(buf, peak = 0.86f)
    }

    // ------------------------------------------------------------------
    // 6. 主机奖杯：解锁奖杯四连升调琶音 (D5 -> F#5 -> A5 -> D6)
    // ------------------------------------------------------------------
    private fun synthGamerTrophy(): FloatArray {
        val durationSec = 1.06f
        val total = (SAMPLE_RATE * durationSec).toInt()
        val buf = FloatArray(total)

        val notes = doubleArrayOf(587.33, 739.99, 880.00, 1174.66) // D5, F#5, A5, D6
        val starts = doubleArrayOf(0.00, 0.085, 0.170, 0.255)
        val decays = doubleArrayOf(6.2, 6.0, 5.5, 3.4)
        val weights = doubleArrayOf(0.42, 0.44, 0.46, 0.56)

        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0

            for (n in notes.indices) {
                if (t >= starts[n]) {
                    val dt = t - starts[n]
                    val f = notes[n]
                    val attack = (dt / 0.004).coerceAtMost(1.0)
                    val env = attack * exp(-dt * decays[n])
                    v += (sin(2.0 * PI * f * dt) * 0.68 +
                            sin(2.0 * PI * (f * 2.0) * dt) * 0.22 +
                            sin(2.0 * PI * (f * 3.0) * dt) * 0.10) * env * weights[n]
                }
            }

            // 第四音叠加白金星芒泛音 (A6 + D7)
            if (t >= 0.255) {
                val dt = t - 0.255
                val env = (dt / 0.005).coerceAtMost(1.0) * exp(-dt * 4.2)
                v += (sin(2.0 * PI * 1760.0 * dt) * 0.18 + sin(2.0 * PI * 2349.32 * dt) * 0.14) * env
            }

            buf[i] = (v * 0.66).toFloat()
        }

        applyReverb(buf, delayMs1 = 54, gain1 = 0.24f, delayMs2 = 108, gain2 = 0.14f)
        applyFadeOut(buf, fadeMs = 75)
        return normalize(buf, peak = 0.88f)
    }

    // ------------------------------------------------------------------
    // 通用 DSP 工具与 WAV 编码器
    // ------------------------------------------------------------------

    private fun applyReverb(
        buf: FloatArray,
        delayMs1: Int,
        gain1: Float,
        delayMs2: Int,
        gain2: Float
    ) {
        val d1 = (SAMPLE_RATE * delayMs1 / 1000).coerceAtLeast(1)
        val d2 = (SAMPLE_RATE * delayMs2 / 1000).coerceAtLeast(1)
        for (i in buf.indices) {
            var echo = 0f
            if (i >= d1) echo += buf[i - d1] * gain1
            if (i >= d2) echo += buf[i - d2] * gain2
            buf[i] += echo
        }
    }

    private fun applyFadeOut(buf: FloatArray, fadeMs: Int) {
        val fadeSamples = (SAMPLE_RATE * fadeMs / 1000).coerceIn(1, buf.size)
        val start = buf.size - fadeSamples
        for (i in start until buf.size) {
            val factor = (buf.size - 1 - i).toFloat() / fadeSamples.toFloat()
            buf[i] *= factor
        }
    }

    private fun normalize(buf: FloatArray, peak: Float): FloatArray {
        var maxAbs = 0.0001f
        for (s in buf) {
            val a = if (s < 0f) -s else s
            if (a > maxAbs) maxAbs = a
        }
        val scale = peak / maxAbs
        for (i in buf.indices) {
            buf[i] = (buf[i] * scale).coerceIn(-1f, 1f)
        }
        return buf
    }

    private fun encodePcm16MonoWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val totalBytes = 44 + dataBytes
        val out = ByteArray(totalBytes)

        // RIFF chunk descriptor
        out[0] = 'R'.code.toByte()
        out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte()
        out[3] = 'F'.code.toByte()
        writeIntLE(out, 4, 36 + dataBytes)
        out[8] = 'W'.code.toByte()
        out[9] = 'A'.code.toByte()
        out[10] = 'V'.code.toByte()
        out[11] = 'E'.code.toByte()

        // fmt sub-chunk
        out[12] = 'f'.code.toByte()
        out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte()
        out[15] = ' '.code.toByte()
        writeIntLE(out, 16, 16) // PCM chunk size
        writeShortLE(out, 20, 1) // AudioFormat = 1 (PCM)
        writeShortLE(out, 22, 1) // NumChannels = 1 (Mono)
        writeIntLE(out, 24, sampleRate)
        writeIntLE(out, 28, sampleRate * 2) // ByteRate
        writeShortLE(out, 32, 2) // BlockAlign
        writeShortLE(out, 34, 16) // BitsPerSample

        // data sub-chunk
        out[36] = 'd'.code.toByte()
        out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte()
        out[39] = 'a'.code.toByte()
        writeIntLE(out, 40, dataBytes)

        var offset = 44
        for (s in samples) {
            val pcm = (s.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            out[offset] = (pcm and 0xFF).toByte()
            out[offset + 1] = ((pcm shr 8) and 0xFF).toByte()
            offset += 2
        }
        return out
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
