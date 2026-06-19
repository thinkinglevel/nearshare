package com.pcmobilelink.nearshare.storage

import android.content.Context
import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import org.json.JSONArray
import org.json.JSONObject

class PairedPcStore(context: Context) {
    private val preferences = context.getSharedPreferences("nearshare_paired_pcs", Context.MODE_PRIVATE)

    fun loadAll(): List<PairedPcRecord> {
        val raw = preferences.getString(KEY_PAIRED_PCS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            PairedPcRecords.deduplicate(
                buildList {
                    for (index in 0 until array.length()) {
                        add(array.getJSONObject(index).toRecord())
                    }
                },
            )
        }.getOrDefault(emptyList())
    }

    fun addOrUpdate(record: PairedPcRecord) {
        saveAll(PairedPcRecords.addOrUpdate(loadAll(), record))
    }

    fun remove(pcDeviceId: String): Boolean {
        val existingRecords = loadAll()
        if (!PairedPcRecords.containsDeviceId(existingRecords, pcDeviceId)) {
            return false
        }

        saveAll(PairedPcRecords.removeByDeviceId(existingRecords, pcDeviceId))
        if (loadLastSelectedSendPcDeviceId()?.equals(pcDeviceId, ignoreCase = true) == true) {
            clearLastSelectedSendPcDeviceId()
        }
        return true
    }

    fun loadLastSelectedSendPcDeviceId(): String? {
        return preferences.getString(KEY_LAST_SELECTED_SEND_PC_DEVICE_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun saveLastSelectedSendPcDeviceId(pcDeviceId: String) {
        val trimmed = pcDeviceId.trim()
        if (trimmed.isEmpty()) {
            clearLastSelectedSendPcDeviceId()
            return
        }

        preferences.edit().putString(KEY_LAST_SELECTED_SEND_PC_DEVICE_ID, trimmed).apply()
    }

    fun clearLastSelectedSendPcDeviceId() {
        preferences.edit().remove(KEY_LAST_SELECTED_SEND_PC_DEVICE_ID).apply()
    }

    fun findByTlsCertificateSha256(tlsCertificateSha256: String): PairedPcRecord? {
        return loadAll().firstOrNull { record ->
            record.tlsCertificateSha256.equals(tlsCertificateSha256, ignoreCase = true)
        }
    }

    private fun saveAll(records: List<PairedPcRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_PAIRED_PCS, array.toString()).apply()
    }

    private fun PairedPcRecord.toJson(): JSONObject {
        return JSONObject()
            .put("pcDeviceId", pcDeviceId)
            .put("pcName", pcName)
            .put("tlsCertificateSha256", tlsCertificateSha256)
            .put("sharedSecret", sharedSecret)
            .put("pairedAtUnixTimeSeconds", pairedAtUnixTimeSeconds)
            .put(
                "endpoints",
                JSONArray().also { array ->
                    endpoints.forEach { endpoint ->
                        array.put(JSONObject().put("host", endpoint.host).put("port", endpoint.port))
                    }
                },
            )
    }

    private fun JSONObject.toRecord(): PairedPcRecord {
        val endpointsJson = getJSONArray("endpoints")
        val endpoints = buildList {
            for (index in 0 until endpointsJson.length()) {
                val endpoint = endpointsJson.getJSONObject(index)
                add(PairingEndpointCandidate(endpoint.getString("host"), endpoint.getInt("port")))
            }
        }

        return PairedPcRecord(
            pcDeviceId = getString("pcDeviceId"),
            pcName = getString("pcName"),
            endpoints = endpoints,
            tlsCertificateSha256 = getString("tlsCertificateSha256"),
            sharedSecret = getString("sharedSecret"),
            pairedAtUnixTimeSeconds = getLong("pairedAtUnixTimeSeconds"),
        )
    }

    private companion object {
        private const val KEY_PAIRED_PCS = "pairedPcs"
        private const val KEY_LAST_SELECTED_SEND_PC_DEVICE_ID = "lastSelectedSendPcDeviceId"
    }
}
