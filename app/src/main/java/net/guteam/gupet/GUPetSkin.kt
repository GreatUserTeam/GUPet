package net.guteam.gupet

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
    private const val MAX_ENTRIES = 12
    private const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val frameNames = setOf("idle", "blink", "happy", "sleep", "dance", "surprised", "dizzy", "angry", "love", "poop")
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
                // poop.png — не поза питомца: сервис читает файл напрямую, сюда не кладём.
                if (name == "poop") continue
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
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось открыть файл")
        return input.use { importStream(context, it) }
    }

    /** Ядро импорта из потока: читает zip, проверяет, ставит в папку скина. */
    fun importStream(context: Context, stream: java.io.InputStream): GUPetSkinInfo {
        val contents = readZipContents(stream)
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

    private fun readZipContents(stream: java.io.InputStream): LinkedHashMap<String, ByteArray> {
        val contents = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        var entries = 0
        ZipInputStream(stream).use { zip ->
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
        return contents
    }

    /** Папка скина (валидный id, существующая папка) или null. */
    fun skinFolder(context: Context, id: String): File? {
        if (!idPattern.matches(id)) return null
        val folder = File(skinRoot(context), id)
        return if (folder.isDirectory) folder else null
    }

    /** Есть ли у скина PNG-кадры (idle.png валидного размера). */
    fun hasFrames(context: Context, id: String): Boolean {
        val folder = skinFolder(context, id) ?: return false
        val bytes = runCatching { File(folder, "idle.png").readBytes() }.getOrNull() ?: return false
        return decodeImage(bytes) != null
    }

    /** Блок settings из manifest.json скина или null. */
    fun readSettingsJson(context: Context, id: String): JSONObject? {
        val folder = skinFolder(context, id) ?: return null
        return readManifest(File(folder, "manifest.json"))?.settings?.let { JSONObject(it.toString()) }
    }

    /** Перезаписать блок settings в manifest.json скина. */
    fun writeSettingsJson(context: Context, id: String, settings: JSONObject): Boolean {
        val folder = skinFolder(context, id) ?: return false
        return runCatching {
            val file = File(folder, "manifest.json")
            if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return false
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.put("settings", JSONObject(settings.toString()))
            file.writeText(json.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** Перезаписать имя/автора в manifest.json скина. */
    fun writeMeta(context: Context, id: String, name: String, author: String): Boolean {
        val folder = skinFolder(context, id) ?: return false
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank() || cleanName.any { it.isISOControl() }) return false
        val cleanAuthor = author.trim().ifBlank { "Не указан" }.take(40)
        return runCatching {
            val file = File(folder, "manifest.json")
            if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return false
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.put("name", cleanName)
            json.put("author", cleanAuthor)
            file.writeText(json.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** Создать скин из текущих настроек: manifest + settings + прозрачный idle.png. */
    fun createSettingsSkin(context: Context, id: String, name: String, author: String, settings: JSONObject): GUPetSkinInfo {
        if (!idPattern.matches(id)) throw IllegalArgumentException("Некорректный id скина")
        val cleanName = name.trim().take(40)
        require(cleanName.isNotBlank() && !cleanName.any { it.isISOControl() }) {
            "Название должно содержать от 1 до 40 печатных символов"
        }
        val root = skinRoot(context)
        if (!root.exists() && !root.mkdirs()) throw IllegalArgumentException("Не удалось создать папку скинов")
        val folder = File(root, id)
        if (folder.exists()) throw IllegalArgumentException("Скин уже существует")
        if (!folder.mkdirs()) throw IllegalArgumentException("Не удалось создать скин")
        try {
            val manifest = JSONObject()
                .put("format", "GUPet")
                .put("formatVersion", 1)
                .put("version", 2)
                .put("width", WIDTH)
                .put("height", HEIGHT)
                .put("id", id)
                .put("name", cleanName)
                .put("author", author.trim().ifBlank { "Не указан" }.take(40))
                .put("frames", JSONObject().put("idle", "idle.png"))
                .put("settings", JSONObject(settings.toString()))
            File(folder, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
            val blank = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            blank.eraseColor(android.graphics.Color.TRANSPARENT)
            File(folder, "idle.png").outputStream().use { out ->
                if (!blank.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalArgumentException("Не удалось записать idle.png")
                }
            }
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
        return GUPetSkinInfo(id, cleanName, author.trim().ifBlank { "Не указан" }.take(40))
    }

    /** Влить PNG-кадры из zip-архива в существующий скин. */
    fun mergeFramesFromZip(context: Context, skinId: String, stream: java.io.InputStream): Int {
        val folder = skinFolder(context, skinId) ?: throw IllegalArgumentException("Скин не найден")
        val contents = readZipContents(stream)
        var added = 0
        val manifestFile = File(folder, "manifest.json")
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        val frames = manifest.optJSONObject("frames") ?: JSONObject()
        for ((name, bytes) in contents) {
            if (!name.endsWith(".png")) continue
            if (decodeImage(bytes) == null) throw IllegalArgumentException("Файл $name должен быть PNG размером 18 × 13")
            File(folder, name).writeBytes(bytes)
            frames.put(name.removeSuffix(".png"), name)
            added++
        }
        if (added == 0) throw IllegalArgumentException("В архиве нет PNG-кадров")
        manifest.put("frames", frames)
        manifestFile.writeText(manifest.toString(), Charsets.UTF_8)
        return added
    }

    /** Один PNG-кадр скина: файл + запись в manifest.frames. PNG обязан быть 18×13. */
    fun writeFramePng(context: Context, skinId: String, frame: String, bytes: ByteArray): Boolean {
        if (frame !in frameNames) return false
        if (bytes.size > MAX_IMAGE_BYTES) return false
        if (decodeImage(bytes) == null) return false
        val folder = skinFolder(context, skinId) ?: return false
        return runCatching {
            val manifestFile = File(folder, "manifest.json")
            if (!manifestFile.isFile || manifestFile.length() > MAX_MANIFEST_BYTES) return false
            File(folder, "$frame.png").writeBytes(bytes)
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val frames = manifest.optJSONObject("frames") ?: JSONObject()
            frames.put(frame, "$frame.png")
            manifest.put("frames", frames)
            manifestFile.writeText(manifest.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** Удалить PNG-кадр из скина + убрать из manifest.frames. idle удалять нельзя. */
    fun deleteFramePng(context: Context, skinId: String, frame: String): Boolean {
        if (frame == "idle" || frame !in frameNames) return false
        val folder = skinFolder(context, skinId) ?: return false
        return runCatching {
            val manifestFile = File(folder, "manifest.json")
            if (!manifestFile.isFile || manifestFile.length() > MAX_MANIFEST_BYTES) return false
            File(folder, "$frame.png").delete()
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            manifest.optJSONObject("frames")?.remove(frame)
            manifestFile.writeText(manifest.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** Информация о кадре для UI: JSON { exists, fileName }. */
    fun frameInfo(context: Context, skinId: String, frame: String): JSONObject {
        var exists = false
        val folder = skinFolder(context, skinId)
        if (folder != null && frame in frameNames) {
            val file = File(folder, "$frame.png")
            exists = file.isFile && file.length() <= MAX_IMAGE_BYTES &&
                runCatching { decodeImage(file.readBytes()) }.getOrNull() != null
        }
        return JSONObject().put("exists", exists).put("fileName", if (exists) "$frame.png" else "")
    }

    /** Упаковать скин (manifest + PNG) в zip-байты для экспорта. */
    fun exportZip(context: Context, id: String): ByteArray {
        val folder = skinFolder(context, id) ?: throw IllegalArgumentException("Скин не найден")
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            val files = (folder.listFiles() ?: emptyArray())
                .filter { it.isFile && (it.name == "manifest.json" || it.name.endsWith(".png")) }
                .sortedBy { it.name }
            for (file in files) {
                zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private data class Manifest(
        val info: GUPetSkinInfo,
        val frames: Map<String, String>,
        val settings: JSONObject?,
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
        if (frames.isNotEmpty() && !frames.containsKey("idle")) throw IllegalArgumentException("Нужен обязательный кадр idle.png")
        val settings = json.optJSONObject("settings")?.let { JSONObject(it.toString()) }
        return Manifest(GUPetSkinInfo(id, name, author), frames, settings)
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
