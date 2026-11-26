package io.nekohasekai.sagernet.database.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import io.nekohasekai.sagernet.SagerNet
import java.io.File

/** Rows of one table as the cursor typed them (Long, Double, String, ByteArray or null), copied column by name. */
internal class SqlTable(val columns: List<String>, val rows: MutableList<Array<Any?>> = ArrayList()) {
    fun index(column: String): Int = columns.indexOf(column)

    /** This table with [names] it lacks appended as NULL columns. */
    fun withColumns(names: List<String>): SqlTable {
        val added = names.filter { it !in columns }.distinct()
        if (added.isEmpty()) return this
        val width = columns.size + added.size
        return SqlTable(columns + added, rows.mapTo(ArrayList(rows.size)) { row -> row.copyOf(width) })
    }
}

/** An INTEGER cell as SQLite may hand it over (Long, Double or numeric text); null for anything else. */
internal fun sqlLong(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
}

/** One `PRAGMA table_info` row. */
internal class SqlColumn(val name: String, val notNull: Boolean, val defaultSql: String?)

internal fun Cursor.value(i: Int): Any? = when (getType(i)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> getLong(i)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
    Cursor.FIELD_TYPE_BLOB -> getBlob(i)
    else -> getString(i)
}

internal fun Cursor.readTable(columns: List<String>): SqlTable {
    val table = SqlTable(columns)
    while (moveToNext()) table.rows.add(Array(columns.size) { value(it) })
    return table
}

internal fun SQLiteProgram.bindValue(index: Int, v: Any?) = when (v) {
    null -> bindNull(index)
    is Long -> bindLong(index, v)
    is Int -> bindLong(index, v.toLong())
    is Double -> bindDouble(index, v)
    is ByteArray -> bindBlob(index, v)
    else -> bindString(index, v.toString())
}

internal fun SupportSQLiteProgram.bindValue(index: Int, v: Any?) = when (v) {
    null -> bindNull(index)
    is Long -> bindLong(index, v)
    is Int -> bindLong(index, v.toLong())
    is Double -> bindDouble(index, v)
    is ByteArray -> bindBlob(index, v)
    else -> bindString(index, v.toString())
}

internal fun quoted(columns: List<String>): String = columns.joinToString(",") { "`$it`" }

internal fun insertSql(table: String, columns: List<String>, verb: String = "INSERT"): String =
    "$verb INTO `$table` (${quoted(columns)}) VALUES (${columns.joinToString(",") { "?" }})"

internal fun SupportSQLiteDatabase.tableColumns(table: String): List<SqlColumn> =
    query("PRAGMA table_info(`$table`)").use { c -> c.columns() }

internal fun SQLiteDatabase.tableColumns(table: String): List<SqlColumn> =
    rawQuery("PRAGMA table_info(`$table`)", null).use { c -> c.columns() }

private fun Cursor.columns(): List<SqlColumn> {
    val name = getColumnIndexOrThrow("name")
    val notNull = getColumnIndexOrThrow("notnull")
    val default = getColumnIndexOrThrow("dflt_value")
    val out = ArrayList<SqlColumn>()
    while (moveToNext()) out.add(SqlColumn(getString(name), getInt(notNull) != 0, getString(default)))
    return out
}

internal fun SQLiteDatabase.hasTable(table: String): Boolean =
    rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

internal fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table)).use { it.moveToFirst() }

internal fun SQLiteDatabase.longQuery(sql: String, vararg args: String): Long? =
    rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

internal fun SupportSQLiteDatabase.longQuery(sql: String, vararg args: Any?): Long? =
    query(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

/** Temporary SQLite files next to the cache, removed with their journals by [deleteTemp]. */
internal object BackupTemp {
    const val EXPORT = "thrbackup-"
    const val RESTORE = "thrrestore-"

    fun newFile(prefix: String): File {
        val dir = SagerNet.application.cacheDir
        dir.mkdirs()
        return File(dir, "$prefix${System.currentTimeMillis()}.db")
    }

    fun delete(file: File?) {
        if (file != null) runCatching { SQLiteDatabase.deleteDatabase(file) }
    }

    /** Leftovers of an interrupted backup or restore older than a minute. */
    fun cleanStale() {
        val limit = System.currentTimeMillis() - 60_000L
        SagerNet.application.cacheDir.listFiles()?.forEach { file ->
            val name = file.name
            if ((name.startsWith(EXPORT) || name.startsWith(RESTORE)) && name.endsWith(".db") && file.lastModified() < limit) {
                delete(file)
            }
        }
    }
}
