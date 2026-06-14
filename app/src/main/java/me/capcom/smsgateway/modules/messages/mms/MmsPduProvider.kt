package me.capcom.smsgateway.modules.messages.mms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Exposes a composed MMS `M-Send.req` PDU to the system MMS service so that
 * [android.telephony.SmsManager.sendMultimediaMessage] can read it from another process.
 *
 * The PDU is written to the app cache and served read-only via a `content://` Uri. The provider
 * declares `grantUriPermissions="true"` in the manifest so the platform MMS service may open it.
 * This pattern lets the app send MMS WITHOUT being the default SMS app.
 */
class MmsPduProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = requireNotNull(context)
        val name = uri.lastPathSegment ?: throw IllegalArgumentException("Missing PDU id")
        val file = resolveSafely(context, name)
            ?: throw SecurityException("Access denied")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.wap.mms-message"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val ctx = context ?: return 0
        val name = uri.lastPathSegment ?: return 0
        val file = resolveSafely(ctx, name) ?: return 0
        return if (file.delete()) 1 else 0
    }

    companion object {
        private const val AUTHORITY = "me.capcom.smsgateway.mms"
        private const val DIR = "mms"

        private fun cacheDir(context: Context): File =
            File(context.cacheDir, DIR).apply { mkdirs() }

        /**
         * Resolve [name] within the MMS cache dir, rejecting any path traversal attempt.
         * Returns null if the resolved path escapes the directory.
         */
        private fun resolveSafely(context: Context, name: String): File? {
            if (!name.endsWith(".pdu")) return null
            val dir = cacheDir(context).canonicalFile
            val file = File(dir, name).canonicalFile
            return if (file.path.startsWith(dir.path + File.separator)) file else null
        }

        /**
         * Persist [pdu] to cache and return the `content://` Uri the MMS service should read.
         */
        fun writePdu(context: Context, id: String, pdu: ByteArray): Uri {
            val name = "$id.pdu"
            File(cacheDir(context), name).writeBytes(pdu)
            return Uri.parse("content://$AUTHORITY/$name")
        }

        fun cleanup(context: Context, uri: Uri) {
            val name = uri.lastPathSegment ?: return
            resolveSafely(context, name)?.delete()
        }

        /** Delete the cached PDU for [id] (called once the system MMS service is done with it). */
        fun cleanup(context: Context, id: String) {
            resolveSafely(context, "$id.pdu")?.delete()
        }
    }
}
