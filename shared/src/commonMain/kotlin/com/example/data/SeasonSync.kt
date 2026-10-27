package com.example.data

import com.example.json.JSONObject
import com.example.platform.Device
import com.example.platform.Http
import com.example.platform.HttpException
import com.example.platform.newHttp
import com.example.text.currentTimeMs
import com.example.text.hex2
import com.example.text.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

sealed interface SyncResult {
    data class Updated(val generatedAt: String?) : SyncResult
    data object NoChange : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * Downloads the latest parsed season data (published by CI next to the APK)
 * and folds it into the database via [Seeder.merge] — the same gap-filling
 * merge used for the bundled seed, so user-entered data is never overwritten.
 */
object SeasonSync {

    private const val DATA_URL =
        "https://github.com/inspectorgad/ku-wbb/releases/latest/download/season-data.json"
    private const val PREFS = "season_sync"
    private const val KEY_HASH = "last_hash"
    private const val KEY_SUCCESS_MS = "last_success_ms"
    private const val KEY_GENERATED_AT = "last_generated_at"
    private const val AUTO_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val defaultHttp by lazy { newHttp(connectSeconds = 10, readSeconds = 20) }

    fun lastGeneratedAt(device: Device): String? = prefs(device).string(KEY_GENERATED_AT)

    fun shouldAutoSync(device: Device): Boolean =
        currentTimeMs() - prefs(device).long(KEY_SUCCESS_MS) > AUTO_SYNC_INTERVAL_MS

    suspend fun sync(device: Device, dao: JayhawksDao, http: Http = defaultHttp): SyncResult =
        withContext(Dispatchers.IO) {
            val body = try {
                val resp = http.get(DATA_URL)
                if (!resp.isSuccessful) {
                    return@withContext SyncResult.Failed("server returned ${resp.code}")
                }
                resp.body
            } catch (e: CancellationException) {
                // Giving up on a refresh is not a network error, and the catch
                // below would read it as one. withContext would re-raise it
                // either way, so this changes no behaviour today - it keeps
                // the distinction true for whatever reads the result later.
                throw e
            } catch (e: HttpException) {
                return@withContext SyncResult.Failed(e.reason ?: e.message ?: "network error")
            } catch (e: Exception) {
                return@withContext SyncResult.Failed(e.message ?: "network error")
            }

            val root = SeasonDataValidator.parse(body)
                ?: return@withContext SyncResult.Failed("downloaded data failed validation")

            val hash = sha256(body).joinToString("") { hex2(it) }
            val p = prefs(device)
            if (hash == p.string(KEY_HASH)) {
                p.edit { putLong(KEY_SUCCESS_MS, currentTimeMs()) }
                return@withContext SyncResult.NoChange
            }

            try {
                Seeder.merge(root, dao)
            } catch (e: Exception) {
                return@withContext SyncResult.Failed("merge failed: ${e.message}")
            }

            val generatedAt = root.optString("generatedAt").takeIf { it.isNotBlank() }
            p.edit {
                putString(KEY_HASH, hash)
                putLong(KEY_SUCCESS_MS, currentTimeMs())
                putString(KEY_GENERATED_AT, generatedAt)
            }
            SyncResult.Updated(generatedAt)
        }

    private fun prefs(device: Device) = device.settings(PREFS)
}

/**
 * Sanity gate for downloaded season data: a truncated, garbled, or
 * newer-format payload is rejected before it can reach the database.
 */
object SeasonDataValidator {
    const val SUPPORTED_FORMAT = 1
    const val MIN_PLAYERS = 8
    const val MIN_GAMES = 20

    fun parse(bytes: ByteArray): JSONObject? = try {
        val root = JSONObject(bytes.decodeToString())
        val valid = root.optInt("formatVersion", 1) <= SUPPORTED_FORMAT &&
            (root.optJSONArray("players")?.length() ?: 0) >= MIN_PLAYERS &&
            (root.optJSONArray("games")?.length() ?: 0) >= MIN_GAMES
        if (valid) root else null
    } catch (e: Exception) {
        null
    }
}
