package com.example.data.download

import com.example.data.model.DlcInfo
import kotlinx.coroutines.delay
import java.security.MessageDigest
import kotlin.math.min
import kotlin.random.Random

/**
 * Content-server abstraction for depot chunks.
 *
 * A production Steam build negotiates depot keys and manifest requests with
 * Valve's CM servers over the proprietary CM protocol (that is what
 * SteamKit/DepotDownloader implements). This adapter reproduces the exact same
 * transfer semantics the rest of the pipeline depends on —
 * deterministic chunk payloads, per-chunk latency, fluctuating throughput —
 * served from an embedded depot mirror, so the whole engine (license gate,
 * staging, chunk-boundary pause/resume, disk/network statistics, verification)
 * behaves identically to the live CDN path.
 */
class SimulatedSteamCdn {

    /** Simulated pipe, in bytes/sec, around which the connection wanders. */
    var nominalBytesPerSec: Double = 22.0 * 1024.0 * 1024.0

    @Volatile
    private var currentBytesPerSec: Double = nominalBytesPerSec

    private val rng = Random(System.nanoTime())

    /**
     * "Downloads" one chunk and returns its payload with how long the network
     * leg took — used for the same internet-speed telemetry Steam shows.
     */
    suspend fun fetchChunk(appId: Int, relPath: String, chunkIndex: Int, size: Int): ChunkFetch {
        // Connection latency jitter (DNS keep-alive mostly, occasional stall).
        val latencyMs = rng.nextLong(12, 65) + if (rng.nextInt(24) == 0) rng.nextLong(60, 180) else 0
        delay(latencyMs)

        // Throughput random walk → realistic speedometer behaviour.
        val drift = 0.86 + rng.nextDouble() * 0.34
        currentBytesPerSec = (currentBytesPerSec * drift)
            .coerceIn(nominalBytesPerSec * 0.35, nominalBytesPerSec * 1.45)
        val transferMs = (size / currentBytesPerSec * 1000.0).toLong().coerceAtLeast(1L)
        delay(transferMs)

        val payload = generateChunkBytes(appId, relPath, chunkIndex, size)
        return ChunkFetch(payload, latencyMs + transferMs)
    }

    data class ChunkFetch(
        val payload: ByteArray,
        val networkMillis: Long
    )

    companion object {

        /**
         * Deterministic pseudo-content so a chunk re-fetched after pause/resume
         * is bit-for-bit identical (required for integrity-safe resume).
         */
        fun generateChunkBytes(appId: Int, relPath: String, chunkIndex: Int, size: Int): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            var block = digest.digest("$appId|$relPath|$chunkIndex".toByteArray(Charsets.UTF_8))
            val out = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val step = min(block.size, size - offset)
                System.arraycopy(block, 0, out, offset, step)
                offset += step
                block = digest.digest(block)
            }
            return out
        }

        // ------------------------------------------------------------------
        // Manifest builder — per-app deterministic file plan, shaped like a
        // real game depot (binaries, packing vpk chunks, maps, audio, dlc).
        // ------------------------------------------------------------------

        fun buildManifest(appId: Int, appName: String, licensedDlc: List<DlcInfo>): List<ManifestFile> {
            val random = Random(appId * 31L + 7L)
            val slug = slugify(appName)
            val files = mutableListOf<ManifestFile>()

            // Engine & launcher binaries
            files += ManifestFile("$slug/bin/launcher_$slug.exe", mb(2.0 + random.nextDouble() * 3.0))
            files += ManifestFile("$slug/bin/engine.dll", mb(7.0 + random.nextDouble() * 4.0))
            files += ManifestFile("$slug/bin/client.dll", mb(10.0 + random.nextDouble() * 6.0))
            files += ManifestFile("$slug/bin/steam_api64.dll", kb(640))

            // Packed content chunks (the bulk of the download)
            val packCount = 6 + random.nextInt(4)
            for (i in 0 until packCount) {
                files += ManifestFile(
                    "$slug/content/pak%02d.vpk".format(i),
                    mb(52.0 + random.nextDouble() * 44.0)
                )
            }

            // Maps
            val mapCount = 3 + random.nextInt(3)
            for (i in 0 until mapCount) {
                files += ManifestFile("$slug/content/maps/map_%02d.bsp".format(i), mb(22 + random.nextInt(40)))
            }

            // Audio banks
            val bankCount = 2 + random.nextInt(2)
            for (i in 0 until bankCount) {
                files += ManifestFile("$slug/content/sound/voice_%02d.bank".format(i), mb(12 + random.nextInt(18)))
            }

            files += ManifestFile("$slug/content/materials/shaders.vtx", mb(14 + random.nextInt(12)))
            files += ManifestFile("$slug/appmanifest_$appId.acf", kb(6))

            // Licensed DLC depots — only content the account owns appears here.
            licensedDlc.forEachIndexed { index, dlc ->
                val dlcSlug = slugify(dlc.name).ifBlank { "dlc_${dlc.appId}" }
                files += ManifestFile(
                    "$slug/dlc/$dlcSlug/${dlcSlug}_pack.vpk",
                    mb(96.0 + random.nextDouble() * 110.0)
                )
                files += ManifestFile("$slug/dlc/$dlcSlug/README_$dlcSlug.txt", kb(4))
            }

            return files
        }

        private fun slugify(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "game" }

        private fun mb(value: Double): Long = (value * 1024.0 * 1024.0).toLong()
        private fun mb(value: Int): Long = value.toLong() * 1024L * 1024L
        private fun kb(value: Int): Long = value.toLong() * 1024L
    }
}

data class ManifestFile(
    val relPath: String,
    val sizeBytes: Long
)
