package io.github.mangi.eta.agent.prompt

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 预设包的读写。
 *
 * 只认两种输入：zip（内含 `preset.json`）或裸 JSON。单包上限 256KB，
 * 逐字段上限见 [PromptPreset.MAX_FIELD_CHARS]；解析失败返回明确错误，不做"尽力而为"。
 */
internal object PromptPresetCodec {
    private const val ENTRY_NAME = "preset.json"

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failure(val reason: String) : Result<Nothing>
    }

    fun read(context: Context, uri: Uri): Result<PromptPreset> {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > PromptPreset.MAX_BUNDLE_BYTES) return Result.Failure("包太大，超过 256 KB")
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }.getOrNull() ?: return Result.Failure("读不到文件内容")

        val json = if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            readZipEntry(bytes) ?: return Result.Failure("zip 里没有 preset.json")
        } else {
            bytes.toString(Charsets.UTF_8)
        }
        val preset = runCatching { PromptPreset.fromJson(JSONObject(json)) }.getOrNull()
            ?: return Result.Failure("preset.json 格式不对（缺 name 或字段超长）")
        return Result.Ok(preset)
    }

    fun write(context: Context, uri: Uri, preset: PromptPreset): Result<Unit> {
        val payload = encode(preset)
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(payload) }
                ?: return Result.Failure("无法写入目标文件")
            Result.Ok(Unit)
        }.getOrElse { Result.Failure("写入失败：${it.message ?: it.javaClass.simpleName}") }
    }

    fun encode(preset: PromptPreset): ByteArray {
        val json = preset.toJson().toString(2).toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_NAME))
            zip.write(json)
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    /** 内置预设从 assets 读，格式与导入包里的 preset.json 完全一致。 */
    fun readAsset(context: Context, assetPath: String): PromptPreset? = runCatching {
        val text = context.assets.open(assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
        PromptPreset.fromJson(JSONObject(text))?.copy(builtin = true)
    }.getOrNull()

    private fun readZipEntry(bytes: ByteArray): String? = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.name.substringAfterLast('/') == ENTRY_NAME) {
                    // 解压侧也设上限：压缩率高的包解出来可能是几十 MB，不能无界读。
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = zip.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > PromptPreset.MAX_BUNDLE_BYTES) return null
                        buffer.write(chunk, 0, read)
                    }
                    return buffer.toByteArray().toString(Charsets.UTF_8)
                }
            }
            null
        }
    }.getOrNull()
}
