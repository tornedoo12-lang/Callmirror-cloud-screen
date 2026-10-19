package com.example.callmirror

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local archive of mirrored calls and notifications.
 * Stored only in the app's private storage (no cloud, no backup). Kept until the user deletes it.
 */
class ArchiveDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "archive.db", null, 1) {

    data class Entry(
        val id: Long,
        val type: String,      // TYPE_CALL or TYPE_NOTIFICATION
        val app: String?,
        val title: String?,
        val body: String?,
        val number: String?,
        val ts: Long
    )

    companion object {
        const val TYPE_CALL = "call"
        const val TYPE_NOTIFICATION = "notification"
        private const val MAX_ROWS = 5000      // safety cap so storage never grows forever
        private const val DEDUPE_WINDOW_MS = 20_000L

        @Volatile private var instance: ArchiveDb? = null

        fun get(context: Context): ArchiveDb =
            instance ?: synchronized(this) {
                instance ?: ArchiveDb(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE archive (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT NOT NULL," +
                "app TEXT," +
                "title TEXT," +
                "body TEXT," +
                "number TEXT," +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_archive_ts ON archive(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(type: String, app: String?, title: String?, body: String?, number: String?) {
        val now = System.currentTimeMillis()
        val db = writableDatabase

        // Skip duplicates (same alert arriving via Wi-Fi and Cloud at the same time)
        db.rawQuery(
            "SELECT 1 FROM archive WHERE type=? AND IFNULL(app,'')=? AND IFNULL(title,'')=? " +
                "AND IFNULL(body,'')=? AND IFNULL(number,'')=? AND ts>? LIMIT 1",
            arrayOf(type, app ?: "", title ?: "", body ?: "", number ?: "", (now - DEDUPE_WINDOW_MS).toString())
        ).use { if (it.moveToFirst()) return }

        val values = ContentValues().apply {
            put("type", type)
            put("app", app)
            put("title", title)
            put("body", body)
            put("number", number)
            put("ts", now)
        }
        db.insert("archive", null, values)

        db.execSQL(
            "DELETE FROM archive WHERE id <= (SELECT id FROM archive ORDER BY id DESC LIMIT 1 OFFSET $MAX_ROWS)"
        )
    }

    @Synchronized
    fun list(type: String? = null): List<Entry> {
        val result = mutableListOf<Entry>()
        val cursor = if (type == null) {
            readableDatabase.rawQuery(
                "SELECT id,type,app,title,body,number,ts FROM archive ORDER BY ts DESC, id DESC", null
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT id,type,app,title,body,number,ts FROM archive WHERE type=? ORDER BY ts DESC, id DESC",
                arrayOf(type)
            )
        }
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    Entry(
                        id = it.getLong(0),
                        type = it.getString(1),
                        app = it.getString(2),
                        title = it.getString(3),
                        body = it.getString(4),
                        number = it.getString(5),
                        ts = it.getLong(6)
                    )
                )
            }
        }
        return result
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("archive", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("archive", null, null)
    }
}
