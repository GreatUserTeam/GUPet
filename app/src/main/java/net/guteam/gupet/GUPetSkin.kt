package com.frizovdanya.pixpet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

internal data class GUPetSkinInfo(
    val id: String,
    val name: String,
    val author: String,
)

internal class GUPetSkin(
    val info: GUPetSkinInfo,
    private val frames: Map<String, Bitmap>,
) {
    fun frameFor(frame: PetFrame): Bitmap {
        val name = when {
            frame.pose == PetPose.SLEEP || frame.pose == PetPose.YAWN -> "sleep"
            frame.pose == PetPose.DANCE || frame.pose == PetPose.SPIN -> "dance"
            frame.expression == PetExpression.BLINK -> "blink"
            frame.expression == PetExpression.HAPPY -> "happy"
            frame.expression == PetExpression.SURPRISED -> "surprised"
            frame.expression == PetExpression.DIZZY -> "dizzy"
            frame.expression == PetExpression.ANGRY -> "angry"
            frame.expression == PetExpression.LOVE -> "love"
            else -> "idle"
        }
        return frames[name] ?: frames.getValue("idle")
    }
}

internal object GUPetSkinStore {
    const val WIDTH = 18
    const val HEIGHT = 13
    private const val MAX_ENTRIES = 10
    private const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 16 * 1024
    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val frameNames = setOf("idle", "blink", "happy", "sleep", "dance", "surprised", "dizzy", "angry", "love")
    private val allowedFiles = frameNames.map { "$it.png" }.toSet() + "manifest.json"

    fun list(context: Context): List<GUPetSkinInfo> = skinRoot(context)
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { readInfo(it) }
        .sortedBy { it.name.lowercase() }

    fun load(context: Context, id: String?): GUPetSkin? {
        if (id == null || !idPattern.matches(id)) return null
        return runCatching {
            val folder = File(skinRoot(context), id)
            val manifest = readManifest(File(folder, "manifest.json")) ?: return@runCatching null
            val frames = linkedMapOf<String, Bitmap>()
            for ((name, fileName) in manifest.frames) {
                val image = File(folder, fileName)
                if (!image.isFile || image.length() > MAX_IMAGE_BYTES) return@runCatching null
                val bitmap = decodeImage(image.readBytes()) ?: return@runCatching null
                frames[name] = bitmap
            }
            if (!frames.containsKey("idle")) return@runCatching null
            GUPetSkin(manifest.info, frames)
        }.getOrNull()
    }

    fun importSkin(context: Context, uri: Uri): GUPetSkinInfo {
        val contents = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        var entries = 0
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось открыть файл")
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw IllegalArgumentException("В архиве слишком много файлов")
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = entry.name
                if (name !in allowedFiles) throw IllegalArgumentException("Недопустимый файл в архиве: $name")
                if (contents.containsKey(name)) throw IllegalArgumentException("Файл $name указан дважды")
                val limit = if (name == "manifest.json") MAX_MANIFEST_BYTES else MAX_IMAGE_BYTES
                val bytes = readEntry(zip, limit)
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) throw IllegalArgumentException("Архив превышает 2 МБ")
                contents[name] = bytes
                zip.closeEntry()
            }
        }
        val manifestBytes = contents["manifest.json"] ?: throw IllegalArgumentException("В архиве нет manifest.json")
        val manifest = parseManifest(manifestBytes.toString(Charsets.UTF_8))
        for ((_, fileName) in manifest.frames) {
            val bytes = contents[fileName] ?: throw IllegalArgumentException("Не найден файл $fileName")
            if (decodeImage(bytes) == null) throw IllegalArgumentException("Файл $fileName должен быть PNG размером 18 × 13")
        }
        val root = skinRoot(context)
        if (!root.exists() && !root.mkdirs()) throw IllegalArgumentException("Не удалось создать папку скинов")
        val folder = File(root, manifest.info.id)
        val suffix = System.nanoTime()
        val staging = File(root, ".${manifest.info.id}-$suffix.tmp")
        val backup = File(root, ".${manifest.info.id}-$suffix.bak")
        var backedUp = false
        try {
            if (!staging.mkdirs()) throw IllegalArgumentException("Не удалось подготовить скин")
            for ((name, bytes) in contents) File(staging, name).writeBytes(bytes)
            if (folder.exists()) {
                if (!folder.renameTo(backup)) throw IllegalArgumentException("Не удалось заменить установленный скин")
                backedUp = true
            }
            if (!staging.renameTo(folder)) throw IllegalArgumentException("Не удалось завершить импорт скина")
            if (backedUp) backup.deleteRecursively()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            if (backedUp && backup.exists() && !folder.exists()) backup.renameTo(folder)
        }
        return manifest.info
    }

    fun delete(context: Context, id: String): Boolean {
        if (!idPattern.matches(id)) return false
        return File(skinRoot(context), id).deleteRecursively()
    }

    private data class Manifest(
        val info: GUPetSkinInfo,
        val frames: Map<String, String>,
    )

    private fun skinRoot(context: Context): File = File(context.filesDir, "gupet/skins")

    private fun readInfo(folder: File): GUPetSkinInfo? =
        readManifest(File(folder, "manifest.json"))?.info

    private fun readManifest(file: File): Manifest? = runCatching {
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return null
        parseManifest(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun parseManifest(text: String): Manifest {
        val json = JSONObject(text)
        if (json.optString("format") != "GUPet" || json.optInt("formatVersion") != 1) {
            throw IllegalArgumentException("Неподдерживаемый формат GUPet")
        }
        if (json.optInt("width") != WIDTH || json.optInt("height") != HEIGHT) {
            throw IllegalArgumentException("Размер каждого PNG должен быть 18 × 13 пикселей")
        }
        val id = json.optString("id").trim()
        if (!idPattern.matches(id)) throw IllegalArgumentException("В id разрешены строчные латинские буквы, цифры, _ и -")
        val name = json.optString("name").trim()
        if (name.isBlank() || name.length > 40 || name.any { it.isISOControl() }) {
            throw IllegalArgumentException("Название должно содержать от 1 до 40 печатных символов")
        }
        val author = json.optString("author", "Не указан").trim().ifBlank { "Не указан" }.take(40)
            .filterNot { it.isISOControl() }.ifBlank { "Не указан" }
        val frameObject = json.optJSONObject("frames") ?: throw IllegalArgumentException("В manifest.json нет списка frames")
        val frames = linkedMapOf<String, String>()
        val keys = frameObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val fileName = frameObject.optString(key).trim()
            if (key !in frameNames) throw IllegalArgumentException("Неизвестный кадр: $key")
            if (fileName != "$key.png") throw IllegalArgumentException("Кадр $key должен называться $key.png")
            frames[key] = fileName
        }
        if (!frames.containsKey("idle")) throw IllegalArgumentException("Нужен обязательный кадр idle.png")
        return Manifest(GUPetSkinInfo(id, name, author), frames)
    }

    private fun decodeImage(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth != WIDTH || options.outHeight != HEIGHT) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun readEntry(zip: ZipInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IllegalArgumentException("Файл в архиве превышает допустимый размер")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
