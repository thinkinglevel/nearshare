package com.pcmobilelink.nearshare.updates

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class GitHubReleaseUpdateChecker(
    private val owner: String = ReleaseOwner,
    private val repo: String = ReleaseRepo,
) {
    fun check(currentVersion: String): ReleaseUpdateCheckResult {
        val connection = (URL("https://api.github.com/repos/$owner/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "NearShare-Android")
        }

        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parseRelease(connection.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                HttpURLConnection.HTTP_NOT_FOUND -> ReleaseUpdateCheckResult.Unavailable(
                    "No eligible GitHub release is available yet.",
                )
                else -> ReleaseUpdateCheckResult.Unavailable("GitHub returned HTTP $status while checking for updates.")
            }
        } catch (exception: Exception) {
            ReleaseUpdateCheckResult.Unavailable(exception.message ?: "Could not check for updates.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: String, currentVersion: String): ReleaseUpdateCheckResult {
        val root = JSONObject(json)
        val tagName = root.optString("tag_name").ifBlank {
            return ReleaseUpdateCheckResult.Unavailable("The latest release did not include a version tag.")
        }
        val releaseUrl = root.optString("html_url")
            .ifBlank { "https://github.com/$owner/$repo/releases/latest" }
        val latestVersion = VersionParts.parse(tagName)
            ?: return ReleaseUpdateCheckResult.Unavailable("The latest release tag is not a supported version: $tagName")
        val installedVersion = VersionParts.parse(currentVersion) ?: VersionParts.ZERO
        val asset = findAndroidAsset(root)
        val checksumAsset = findChecksumAsset(root)

        return ReleaseUpdateCheckResult.Checked(
            currentVersion = currentVersion,
            latestVersion = tagName,
            releaseUrl = releaseUrl,
            assetName = asset?.name,
            assetUrl = asset?.downloadUrl,
            checksumAssetName = checksumAsset?.name,
            checksumAssetUrl = checksumAsset?.downloadUrl,
            updateAvailable = latestVersion > installedVersion,
        )
    }

    private fun findAndroidAsset(root: JSONObject): ReleaseAsset? {
        val assets = root.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.lowercase(Locale.US).endsWith(".apk")) {
                return ReleaseAsset(
                    name = name,
                    downloadUrl = asset.optString("browser_download_url").ifBlank { null },
                )
            }
        }
        return null
    }

    private fun findChecksumAsset(root: JSONObject): ReleaseAsset? {
        val assets = root.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val normalizedName = name.lowercase(Locale.US)
            if ("checksum" in normalizedName && (normalizedName.endsWith(".txt") || normalizedName.endsWith(".sha256"))) {
                return ReleaseAsset(
                    name = name,
                    downloadUrl = asset.optString("browser_download_url").ifBlank { null },
                )
            }
        }
        return null
    }

    private data class ReleaseAsset(
        val name: String,
        val downloadUrl: String?,
    )

    private data class VersionParts(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val build: Int,
    ) : Comparable<VersionParts> {
        override fun compareTo(other: VersionParts): Int {
            return compareValuesBy(this, other, VersionParts::major, VersionParts::minor, VersionParts::patch, VersionParts::build)
        }

        companion object {
            val ZERO = VersionParts(0, 0, 0, 0)
            private val VersionPattern = Regex("""\d+(?:\.\d+){0,3}""")

            fun parse(value: String): VersionParts? {
                val match = VersionPattern.find(value.trim())?.value ?: return null
                val parts = match.split('.').map { it.toIntOrNull() ?: return null }
                if (parts.isEmpty()) return null
                return VersionParts(
                    major = parts.getOrElse(0) { 0 },
                    minor = parts.getOrElse(1) { 0 },
                    patch = parts.getOrElse(2) { 0 },
                    build = parts.getOrElse(3) { 0 },
                )
            }
        }
    }

    private companion object {
        private const val ReleaseOwner = "thinkinglevel"
        private const val ReleaseRepo = "nearshare"
    }
}

sealed interface ReleaseUpdateCheckResult {
    data class Checked(
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        val assetName: String?,
        val assetUrl: String?,
        val checksumAssetName: String?,
        val checksumAssetUrl: String?,
        val updateAvailable: Boolean,
    ) : ReleaseUpdateCheckResult

    data class Unavailable(
        val message: String,
    ) : ReleaseUpdateCheckResult
}
