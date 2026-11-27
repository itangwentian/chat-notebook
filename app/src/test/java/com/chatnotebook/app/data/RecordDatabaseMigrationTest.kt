package com.chatnotebook.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RecordDatabaseMigrationTest {
    /** Room 2.8's MigrationTestHelper conflicts with Robolectric drivers, so v1 is built from its exported schema. */
    @Test fun migratingFromOneToNineKeepsExistingRecords() {
        val context = RuntimeEnvironment.getApplication()
        val databaseFile = context.getDatabasePath("migration-test")
        databaseFile.parentFile?.mkdirs()
        val schema = JSONObject(
            context.assets.open("com.chatnotebook.app.data.RecordDatabase/1.json")
                .bufferedReader().readText(),
        ).getJSONObject("database")

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val tableName = entity.getString("tableName")
                database.execSQL(
                    entity.getString("createSql").replace("\${TABLE_NAME}", tableName),
                )
                val indices = entity.optJSONArray("indices") ?: continue
                for (indexIndex in 0 until indices.length()) {
                    val createSql = indices.getJSONObject(indexIndex).getString("createSql")
                        .replace("\${TABLE_NAME}", tableName)
                    database.execSQL(createSql)
                }
            }
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            database.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(schema.getString("identityHash")),
            )
            database.version = 1
            database.execSQL(
                "INSERT INTO sessions (id, title, createdAt, updatedAt, isDeleted, lastOpenedAt) " +
                    "VALUES ('s1', '工作', 1, 1, 0, 1)",
            )
            database.execSQL(
                "INSERT INTO messages (id, sessionId, role, rawContent, currentContent, contentVersion, " +
                    "createdAt, updatedAt, isDeleted, processingStatus) " +
                    "VALUES ('m1', 's1', 'user', '原始内容', '原始内容', 1, 1, 1, 0, 'done')",
            )
        }

        val room = Room.databaseBuilder(context, RecordDatabase::class.java, databaseFile.absolutePath)
            .addMigrations(
                RecordDatabase.MIGRATION_1_2,
                RecordDatabase.MIGRATION_2_3,
                RecordDatabase.MIGRATION_3_4,
                RecordDatabase.MIGRATION_4_5,
                RecordDatabase.MIGRATION_5_6,
                RecordDatabase.MIGRATION_6_7,
                RecordDatabase.MIGRATION_7_8,
                RecordDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()
        try {
            room.openHelper.readableDatabase
                .query("SELECT rawContent, aiRequestId, formatSegmentsJson, attachmentsJson FROM messages WHERE id = 'm1'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("原始内容", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertEquals("", cursor.getString(2))
                    assertEquals("", cursor.getString(3))
                }
            room.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                    "('edit_history', 'quotes', 'reactions', 'llm_configs', 'merge_relations', " +
                    "'session_documents', 'reminders')",
            ).use { cursor ->
                assertEquals(7, cursor.count)
            }
            room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM session_documents")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM reminders")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
        } finally {
            room.close()
        }
    }
}
