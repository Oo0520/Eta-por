package io.github.mangi.eta.testsupport

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertFalse

/**
 * 「文件不可写」的跨身份断言。
 *
 * 在 POSIX 文件系统上，root 身份对任何文件都能写，`File.canWrite()` 恒为 true，
 * 于是「把它设成只读再断言不可写」这类用例在 root 下必然失败——失败原因是身份，不是行为。
 * 只看权限位就不受身份影响：只读文件的 owner-write 位必须已经被清掉。
 */
internal fun assertNotOwnerWritable(file: File) {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        val permissions = Files.getPosixFilePermissions(Paths.get(file.absolutePath))
        assertFalse(
            "文件仍带 owner-write 权限位：${file.absolutePath}",
            permissions.contains(PosixFilePermission.OWNER_WRITE),
        )
    } else {
        assertFalse("文件仍可写：${file.absolutePath}", file.canWrite())
    }
}
