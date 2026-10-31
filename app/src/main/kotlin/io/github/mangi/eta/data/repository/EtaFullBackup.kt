package io.github.mangi.eta.data.repository

/**
 * 全量备份包（`.eta-fullbackup.zip`）的识别工具。
 * 真正的打包 / 解包实现在 [EtaFullBackupArchive]。
 */
internal object EtaFullBackup {
    private const val SUFFIX = EtaFullBackupArchive.FULL_SUFFIX

    fun isFullBackup(name: String): Boolean = name.endsWith(SUFFIX, ignoreCase = true)
}
