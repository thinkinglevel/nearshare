package com.pcmobilelink.nearshare.receiver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.pcmobilelink.nearshare.settings.ReceiveFolder
import com.pcmobilelink.nearshare.settings.ReceiveSettingsStore
import java.io.File

class AndroidReceiveStorageResolver(
    private val context: Context,
    private val settingsStore: ReceiveSettingsStore = ReceiveSettingsStore(context),
) : AndroidReceiveStorage {
    override fun saveCompletedFile(
        originalFileName: String,
        contentType: String,
        tempFile: File,
    ): AndroidSavedFile {
        return when (val folder = settingsStore.load().receiveFolder) {
            ReceiveFolder.DefaultDownloads -> saveToDownloads(originalFileName, contentType, tempFile)
            is ReceiveFolder.CustomTree -> saveToCustomTree(folder, originalFileName, contentType, tempFile)
        }
    }

    private fun saveToDownloads(originalFileName: String, contentType: String, tempFile: File): AndroidSavedFile {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStoreDownloads(originalFileName, contentType, tempFile)
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            val target = collisionSafeFile(downloads, originalFileName)
            tempFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AndroidSavedFile(displayName = target.name, sizeBytes = target.length())
        }
    }

    private fun saveToMediaStoreDownloads(originalFileName: String, contentType: String, tempFile: File): AndroidSavedFile {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, originalFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, contentType.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.SIZE, tempFile.length())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create a Downloads entry for $originalFileName.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open the Downloads entry for $originalFileName.")
            val publishedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publishedValues, null, null)
            return AndroidSavedFile(displayName = originalFileName, sizeBytes = tempFile.length(), uri = uri.toString())
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }

    private fun saveToCustomTree(
        folder: ReceiveFolder.CustomTree,
        originalFileName: String,
        contentType: String,
        tempFile: File,
    ): AndroidSavedFile {
        val treeUri = Uri.parse(folder.uri)
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Selected receive folder is no longer available.")
        require(tree.canWrite()) { "Selected receive folder is not writable." }
        val displayName = collisionSafeDocumentName(tree, originalFileName)
        val document = tree.createFile(contentType.ifBlank { "application/octet-stream" }, displayName)
            ?: throw IllegalStateException("Could not create $displayName in the selected folder.")
        try {
            context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not write $displayName in the selected folder.")
            return AndroidSavedFile(displayName = document.name ?: displayName, sizeBytes = tempFile.length(), uri = document.uri.toString())
        } catch (exception: Exception) {
            document.delete()
            throw exception
        }
    }

    private fun collisionSafeFile(directory: File, fileName: String): File {
        val safeName = safeStorageFileName(fileName)
        val dotIndex = safeName.lastIndexOf('.').takeIf { it > 0 }
        val base = dotIndex?.let { safeName.substring(0, it) } ?: safeName
        val extension = dotIndex?.let { safeName.substring(it) }.orEmpty()
        var candidate = File(directory, safeName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$extension")
            index += 1
        }
        return candidate
    }

    private fun collisionSafeDocumentName(tree: DocumentFile, fileName: String): String {
        val safeName = safeStorageFileName(fileName)
        val dotIndex = safeName.lastIndexOf('.').takeIf { it > 0 }
        val base = dotIndex?.let { safeName.substring(0, it) } ?: safeName
        val extension = dotIndex?.let { safeName.substring(it) }.orEmpty()
        var candidate = safeName
        var index = 1
        while (tree.findFile(candidate) != null) {
            candidate = "$base ($index)$extension"
            index += 1
        }
        return candidate
    }

    private fun safeStorageFileName(fileName: String): String {
        val leaf = fileName.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = leaf
            .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
            .trim(' ', '.')
        return cleaned.ifBlank { "received-file" }
    }
}
