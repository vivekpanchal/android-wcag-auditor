package com.a11yauditor.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

/** SQLite rows for issues plus WebP screenshot files on disk, capped by total file size. */
class AuditStore(
    context: Context,
    dbName: String?,
    private val shotsDir: File,
    private val capBytes: Long = 200L * 1024 * 1024,
) : SQLiteOpenHelper(context, dbName, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE issues(id INTEGER PRIMARY KEY AUTOINCREMENT, packageName TEXT, screen TEXT, " +
                "timestamp INTEGER, severity TEXT, wcagSc TEXT, wcagLevel TEXT, elementDescription TEXT, " +
                "description TEXT, suggestedFix TEXT, bx INTEGER, by INTEGER, bw INTEGER, bh INTEGER, " +
                "screenshotFile TEXT)",
        )
    }

    // Data is disposable, so a schema change just starts over.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS issues")
        onCreate(db)
    }

    @Synchronized
    fun addReport(
        packageName: String,
        screen: String?,
        timestamp: Long,
        screenshot: ByteArray?,
        issues: List<AuditIssue>,
    ): List<StoredIssue> {
        shotsDir.mkdirs()
        val file = screenshot?.let { "${UUID.randomUUID()}.webp".also { n -> File(shotsDir, n).writeBytes(it) } }
        val db = writableDatabase
        val out = try {
            db.beginTransaction()
            try {
                issues.map { i ->
                    val b = i.bounds
                    val sf = ApiJson.suggestedFix(i.fix, i.framework)
                    val id = db.insertOrThrow("issues", null, ContentValues().apply {
                        put("packageName", packageName); put("screen", screen); put("timestamp", timestamp)
                        put("severity", i.severity); put("wcagSc", i.wcagSc); put("wcagLevel", i.wcagLevel)
                        put("elementDescription", i.elementDescription); put("description", i.description)
                        put("suggestedFix", sf)
                        if (b != null) {
                            put("bx", b.left); put("by", b.top); put("bw", b.width); put("bh", b.height)
                        }
                        put("screenshotFile", file)
                    })
                    StoredIssue(
                        id, packageName, screen, timestamp, i.severity, i.wcagSc, i.wcagLevel,
                        i.elementDescription, i.description, sf,
                        b?.let { Bounds(it.left, it.top, it.width, it.height) }, file,
                    )
                }.also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            file?.let { File(shotsDir, it).delete() }
            throw e
        }
        enforceCap(db)
        return out
    }

    @Synchronized
    fun issues(): List<StoredIssue> = readableDatabase.rawQuery(
        "SELECT id, packageName, screen, timestamp, severity, wcagSc, wcagLevel, elementDescription, " +
            "description, suggestedFix, bx, by, bw, bh, screenshotFile FROM issues ORDER BY id",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                StoredIssue(
                    c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4),
                    c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9),
                    if (c.isNull(10)) null else Bounds(c.getInt(10), c.getInt(11), c.getInt(12), c.getInt(13)),
                    c.getString(14),
                ),
            )
        }
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("issues", null, null)
        shotsDir.listFiles()?.forEach { it.delete() }
    }

    fun screenshot(name: String): File? =
        if (SHOT_NAME.matches(name)) File(shotsDir, name).takeIf { it.isFile } else null

    private fun enforceCap(db: SQLiteDatabase) {
        var total = shotsDir.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= capBytes) return
        val names = db.rawQuery(
            "SELECT screenshotFile FROM issues WHERE screenshotFile IS NOT NULL " +
                "GROUP BY screenshotFile ORDER BY MIN(id)",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        for (n in names) {
            if (total <= capBytes) break
            val f = File(shotsDir, n)
            total -= f.length()
            f.delete()
            db.execSQL("UPDATE issues SET screenshotFile = NULL WHERE screenshotFile = ?", arrayOf(n))
        }
    }

    private companion object {
        val SHOT_NAME = Regex("""^[0-9a-f-]{36}[.]webp$""")
    }
}
