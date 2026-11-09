package io.github.mangi.eta.agent.breeno

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * 接住小布进程内 hook 推来的回答。
 *
 * 只有小布（以及 Eta 自己）能调用；不做任何数据持久化，纯内存转发给 [BreenoTapBus]。
 */
class BreenoTapProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_TAP || !callerAllowed()) return null
        val query = extras?.getString(KEY_QUERY).orEmpty()
        val roomId = extras?.getString(KEY_ROOM_ID).orEmpty()
        val text = extras?.getString(KEY_TEXT).orEmpty()
        if (text.isBlank()) return null
        Log.i(TAG, "收到小布回包 queryChars=${query.length} chars=${text.length} roomId=${roomId.take(12)}")
        BreenoTapBus.publish(
            BreenoTapEvent(
                query = query,
                roomId = roomId,
                text = text,
                at = System.currentTimeMillis(),
            ),
        )
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val manager = context?.packageManager ?: return false
        val packages = manager.getPackagesForUid(uid) ?: return false
        return packages.any { it == CALLER_PACKAGE }
    }

    companion object {
        private const val TAG = "EtaBreeno"
        const val AUTHORITY = "io.github.mangi.eta.breeno-tap"
        const val METHOD_TAP = "tap"

        private const val CALLER_PACKAGE = "com.heytap.speechassist"
        private const val KEY_QUERY = "query"
        private const val KEY_ROOM_ID = "roomId"
        private const val KEY_TEXT = "text"
    }
}
