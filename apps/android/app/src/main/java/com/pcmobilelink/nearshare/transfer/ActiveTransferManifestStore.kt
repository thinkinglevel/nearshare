package com.pcmobilelink.nearshare.transfer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ActiveTransferManifestStore(
    private val manifestFile: File,
) {
    fun loadAll(): List<ActiveTransferManifest> {
        if (!manifestFile.exists()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(manifestFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getJSONObject(index).toManifest())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveBatch(manifests: List<ActiveTransferManifest>) {
        val retained = loadAll().filterNot { existing -> manifests.any { it.batchId == existing.batchId } }
        saveAll(retained + manifests)
    }

    fun deleteBatch(batchId: String) {
        saveAll(loadAll().filterNot { it.batchId == batchId })
    }

    fun deleteCompletedOrCancelled() {
        saveAll(loadAll().filter { it.status == ActiveTransferStatus.Active })
    }

    private fun saveAll(manifests: List<ActiveTransferManifest>) {
        manifestFile.parentFile?.mkdirs()
        val array = JSONArray()
        manifests.forEach { array.put(it.toJson()) }
        manifestFile.writeText(array.toString())
    }

    private fun ActiveTransferManifest.toJson(): JSONObject {
        return JSONObject()
            .put("batchId", batchId)
            .put("pcDeviceId", pcDeviceId)
            .put("clientSessionId", clientSessionId)
            .put("displayName", displayName)
            .put("mimeType", mimeType)
            .put("cacheFilePath", cacheFilePath)
            .put("sizeBytes", sizeBytes)
            .put("sha256Base64Url", sha256Base64Url)
            .put("status", status.name)
    }

    private fun JSONObject.toManifest(): ActiveTransferManifest {
        return ActiveTransferManifest(
            batchId = getString("batchId"),
            pcDeviceId = getString("pcDeviceId"),
            clientSessionId = getString("clientSessionId"),
            displayName = getString("displayName"),
            mimeType = getString("mimeType"),
            cacheFilePath = getString("cacheFilePath"),
            sizeBytes = getLong("sizeBytes"),
            sha256Base64Url = getString("sha256Base64Url"),
            status = ActiveTransferStatus.valueOf(getString("status")),
        )
    }
}

data class ActiveTransferManifest(
    val batchId: String,
    val pcDeviceId: String,
    val clientSessionId: String,
    val displayName: String,
    val mimeType: String,
    val cacheFilePath: String,
    val sizeBytes: Long,
    val sha256Base64Url: String,
    val status: ActiveTransferStatus,
)

enum class ActiveTransferStatus {
    Active,
    Completed,
    Cancelled,
}
