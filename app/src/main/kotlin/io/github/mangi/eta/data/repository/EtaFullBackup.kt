package io.github.mangi.eta.data.repository

/**
 * 全量备份包（.eta-fullbackup.zip）的识别工具。
 * 全量包与自动备份（.eta-backup.json）共用同一目录，这里只负责按文件名识别全量包。
 */
internal object EtaFullBackup {
    private const val SUFFIX = ".eta-fullbackup.zip"

    fun isFullBackup(name: String): Boolean = name.endsWith(SUFFIX, ignoreCase = true)
}
