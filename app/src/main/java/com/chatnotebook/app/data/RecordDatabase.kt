package com.chatnotebook.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Entity(tableName = "sessions")
data class SessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val lastOpenedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index(value = ["sessionId", "createdAt", "id"])],
)
data class MessageEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val rawContent: String,
    val currentContent: String,
    val contentVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val processingStatus: String,
    val aiRequestId: String? = null,
    val formatSegmentsJson: String = "",
    val attachmentsJson: String = "",
)

@Entity(
    tableName = "ai_results",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId"), Index(value = ["messageId", "sourceContentVersion"])],
)
data class AiResultEntity(
    @androidx.room.PrimaryKey val id: String,
    val messageId: String,
    val sourceContentVersion: Int,
    val cleanedContent: String,
    val summary: String,
    val keywordsJson: String,
    val structuredData: String,
    val processedAt: Long,
    val status: String,
    val requestId: String = "",
    val providerConfigId: String? = null,
    val modelSnapshot: String = "",
    val isMock: Boolean = false,
)

@Entity(
    tableName = "edit_history",
    primaryKeys = ["messageId", "toVersion"],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
)
data class EditHistoryEntity(
    val messageId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val oldContent: String,
    val newContent: String,
    val editedAt: Long,
)

@Entity(
    tableName = "quotes",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
)
data class QuoteEntity(
    @androidx.room.PrimaryKey val messageId: String,
    val quotedMessageId: String,
    val preview: String,
)

@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "emoji"],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
)
data class ReactionEntity(
    val messageId: String,
    val emoji: String,
)

@Entity(
    tableName = "merge_relations",
    indices = [Index("targetSessionId"), Index("targetMessageId")],
)
data class MergeRelationEntity(
    @androidx.room.PrimaryKey val id: String,
    val targetSessionId: String,
    val sourceSessionId: String,
    val sourceMessageId: String?,
    val targetMessageId: String?,
    val rootSourceSessionId: String?,
    val rootSourceMessageId: String?,
)

@Entity(
    tableName = "session_documents",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SessionDocumentEntity(
    @androidx.room.PrimaryKey val sessionId: String,
    val content: String,
    val messageCount: Int,
    val generatedAt: Long,
    val modelSnapshot: String,
    val isMock: Boolean,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("triggerAt")],
)
data class ReminderEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String?,
    val triggerAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
)

@Entity(tableName = "llm_configs")
data class LlmConfigEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val providerType: String,
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean,
    val timeoutSeconds: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MergeRootRow(
    val targetMessageId: String?,
    val rootSourceSessionId: String?,
    val sourceTitle: String?,
)

/** 待触发提醒的只读投影：带出会话标题与消息内容，供主界面提醒列表使用。 */
data class ScheduledReminderRow(
    val id: String,
    val sessionId: String,
    /** 绑定消息仍存在时的消息 id，已删除或未绑定时为空。 */
    val messageId: String?,
    val triggerAt: Long,
    val sessionTitle: String,
    val content: String?,
)

@Dao
interface RecordDao {
    @Query("DELETE FROM sessions") suspend fun clearSessions()
    @Query("DELETE FROM edit_history") suspend fun clearEditHistory()
    @Query("DELETE FROM quotes") suspend fun clearQuotes()
    @Query("DELETE FROM reactions") suspend fun clearReactions()
    @Query("DELETE FROM llm_configs") suspend fun clearLlmConfigs()
    @Query("DELETE FROM merge_relations") suspend fun clearMergeRelations()
    @Query("DELETE FROM session_documents") suspend fun clearSessionDocuments()
    @Query("DELETE FROM reminders") suspend fun clearReminders()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSessions(rows: List<SessionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessages(rows: List<MessageEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAiResults(rows: List<AiResultEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEditHistory(rows: List<EditHistoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertQuotes(rows: List<QuoteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertReactions(rows: List<ReactionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLlmConfigs(rows: List<LlmConfigEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMergeRelations(rows: List<MergeRelationEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSessionDocuments(rows: List<SessionDocumentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertReminders(rows: List<ReminderEntity>)
    @Query("SELECT * FROM reminders WHERE sessionId = :sessionId ORDER BY triggerAt, id")
    fun observeReminders(sessionId: String): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE status = 'scheduled' ORDER BY triggerAt, id")
    fun observeScheduledReminders(): Flow<List<ReminderEntity>>
    @Query(
        "SELECT r.id AS id, r.sessionId AS sessionId, m.id AS messageId, r.triggerAt AS triggerAt, " +
            "s.title AS sessionTitle, m.currentContent AS content " +
            "FROM reminders r INNER JOIN sessions s ON r.sessionId = s.id " +
            "LEFT JOIN messages m ON r.messageId = m.id AND m.isDeleted = 0 " +
            "WHERE r.status = 'scheduled' AND s.isDeleted = 0 ORDER BY r.triggerAt, r.id",
    )
    fun observeScheduledReminderPreviews(): Flow<List<ScheduledReminderRow>>
    @Query("SELECT * FROM session_documents WHERE sessionId = :sessionId")
    fun observeSessionDocument(sessionId: String): Flow<SessionDocumentEntity?>
    @Query("SELECT * FROM llm_configs ORDER BY createdAt, id")
    fun observeLlmConfigs(): Flow<List<LlmConfigEntity>>
    @Query(
        "SELECT r.targetMessageId AS targetMessageId, r.rootSourceSessionId AS rootSourceSessionId, " +
            "s.title AS sourceTitle FROM merge_relations r " +
            "LEFT JOIN sessions s ON r.rootSourceSessionId = s.id " +
            "WHERE r.targetSessionId = :sessionId",
    )
    fun observeMergeRoots(sessionId: String): Flow<List<MergeRootRow>>
    @Query("SELECT * FROM sessions WHERE isDeleted = 0 ORDER BY lastOpenedAt DESC, id DESC")
    fun observeSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND isDeleted = 0 ORDER BY createdAt, id")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt, id")
    suspend fun allMessages(sessionId: String): List<MessageEntity>
    @Query("SELECT * FROM edit_history WHERE messageId = :messageId ORDER BY toVersion")
    suspend fun editHistory(messageId: String): List<EditHistoryEntity>
    @Query(
        "SELECT q.* FROM quotes q INNER JOIN messages m ON q.messageId = m.id " +
            "WHERE m.sessionId = :sessionId AND m.isDeleted = 0",
    )
    fun observeQuotes(sessionId: String): Flow<List<QuoteEntity>>
    @Query(
        "SELECT r.* FROM reactions r INNER JOIN messages m ON r.messageId = m.id " +
            "WHERE m.sessionId = :sessionId AND m.isDeleted = 0",
    )
    fun observeReactions(sessionId: String): Flow<List<ReactionEntity>>
    @Query(
        "SELECT ai.* FROM ai_results ai INNER JOIN messages m ON ai.messageId = m.id " +
            "WHERE m.sessionId = :sessionId AND m.isDeleted = 0 " +
            "ORDER BY ai.processedAt, ai.id",
    )
    fun observeAiResults(sessionId: String): Flow<List<AiResultEntity>>
}

@Database(
    entities = [
        SessionEntity::class, MessageEntity::class, AiResultEntity::class,
        EditHistoryEntity::class, QuoteEntity::class, ReactionEntity::class,
        LlmConfigEntity::class, MergeRelationEntity::class, SessionDocumentEntity::class,
        ReminderEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class RecordDatabase : RoomDatabase() {
    abstract fun records(): RecordDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN aiRequestId TEXT")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_results_new` (`id` TEXT NOT NULL, " +
                        "`messageId` TEXT NOT NULL, `sourceContentVersion` INTEGER NOT NULL, " +
                        "`cleanedContent` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                        "`keywordsJson` TEXT NOT NULL, `structuredData` TEXT NOT NULL, " +
                        "`processedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`requestId` TEXT NOT NULL, `providerConfigId` TEXT, " +
                        "`modelSnapshot` TEXT NOT NULL, `isMock` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                database.execSQL(
                    "INSERT INTO `ai_results_new` (`id`, `messageId`, `sourceContentVersion`, " +
                        "`cleanedContent`, `summary`, `keywordsJson`, `structuredData`, `processedAt`, " +
                        "`status`, `requestId`, `providerConfigId`, `modelSnapshot`, `isMock`) " +
                        "SELECT `id`, `messageId`, `sourceContentVersion`, `cleanedContent`, `summary`, " +
                        "`keywordsJson`, `structuredData`, `processedAt`, `status`, '', NULL, '', 0 " +
                        "FROM `ai_results`",
                )
                database.execSQL("DROP TABLE `ai_results`")
                database.execSQL("ALTER TABLE `ai_results_new` RENAME TO `ai_results`")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_results_messageId` " +
                        "ON `ai_results` (`messageId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_results_messageId_sourceContentVersion` " +
                        "ON `ai_results` (`messageId`, `sourceContentVersion`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `edit_history` (`messageId` TEXT NOT NULL, " +
                        "`fromVersion` INTEGER NOT NULL, `toVersion` INTEGER NOT NULL, " +
                        "`oldContent` TEXT NOT NULL, `newContent` TEXT NOT NULL, `editedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`messageId`, `toVersion`), " +
                        "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quotes` (`messageId` TEXT NOT NULL, " +
                        "`quotedMessageId` TEXT NOT NULL, `preview` TEXT NOT NULL, PRIMARY KEY(`messageId`), " +
                        "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reactions` (`messageId` TEXT NOT NULL, `emoji` TEXT NOT NULL, " +
                        "PRIMARY KEY(`messageId`, `emoji`), " +
                        "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `llm_configs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`providerType` TEXT NOT NULL, `protocol` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `timeoutSeconds` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merge_relations` (`id` TEXT NOT NULL, " +
                        "`targetSessionId` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, " +
                        "`sourceMessageId` TEXT, `targetMessageId` TEXT, " +
                        "`rootSourceSessionId` TEXT, `rootSourceMessageId` TEXT, PRIMARY KEY(`id`))",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_merge_relations_targetSessionId` " +
                        "ON `merge_relations` (`targetSessionId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_merge_relations_targetMessageId` " +
                        "ON `merge_relations` (`targetMessageId`)",
                )
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN formatSegmentsJson TEXT NOT NULL DEFAULT ''",
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_documents` (`sessionId` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `messageCount` INTEGER NOT NULL, " +
                        "`generatedAt` INTEGER NOT NULL, `modelSnapshot` TEXT NOT NULL, " +
                        "`isMock` INTEGER NOT NULL, PRIMARY KEY(`sessionId`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''",
                )
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, `messageId` TEXT, `triggerAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminders_sessionId` " +
                        "ON `reminders` (`sessionId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminders_triggerAt` " +
                        "ON `reminders` (`triggerAt`)",
                )
            }
        }
    }
}

fun SessionRecord.toEntity() = SessionEntity(id, title, createdAt, updatedAt, isDeleted, lastOpenedAt)
fun MessageRecord.toEntity() = MessageEntity(
    id, sessionId, role, rawContent, currentContent, contentVersion, createdAt, updatedAt,
    isDeleted, processingStatus, aiRequestId,
    if (formatSegments.isEmpty()) "" else kotlinx.serialization.json.Json.encodeToString(formatSegments),
    if (attachments.isEmpty()) "" else kotlinx.serialization.json.Json.encodeToString(attachments),
)
fun AiResultRecord.toEntity() = AiResultEntity(
    id, messageId, sourceContentVersion, cleanedContent, summary,
    kotlinx.serialization.json.Json.encodeToString(keywords), structuredData, processedAt, status,
    requestId, providerConfigId, modelSnapshot, isMock,
)

fun SessionEntity.toRecord() = SessionRecord(id, title, createdAt, updatedAt, isDeleted, lastOpenedAt)
fun MessageEntity.toRecord() = MessageRecord(
    id, sessionId, role, rawContent, currentContent, contentVersion, createdAt, updatedAt,
    isDeleted, processingStatus, aiRequestId,
    if (formatSegmentsJson.isBlank()) emptyList()
    else kotlinx.serialization.json.Json.decodeFromString(formatSegmentsJson),
    if (attachmentsJson.isBlank()) emptyList()
    else kotlinx.serialization.json.Json.decodeFromString(attachmentsJson),
)
fun AiResultEntity.toRecord() = AiResultRecord(
    id, messageId, sourceContentVersion, cleanedContent, summary,
    kotlinx.serialization.json.Json.decodeFromString(keywordsJson), structuredData, processedAt, status,
    requestId, providerConfigId, modelSnapshot, isMock,
)
fun EditRecord.toEntity() = EditHistoryEntity(messageId, fromVersion, toVersion, oldContent, newContent, editedAt)
fun EditHistoryEntity.toRecord() = EditRecord(messageId, fromVersion, toVersion, oldContent, newContent, editedAt)
fun QuoteRecord.toEntity() = QuoteEntity(messageId, quotedMessageId, preview)
fun QuoteEntity.toRecord() = QuoteRecord(messageId, quotedMessageId, preview)
fun ReactionRecord.toEntity() = ReactionEntity(messageId, emoji)
fun ReactionEntity.toRecord() = ReactionRecord(messageId, emoji)
fun LlmConfigRecord.toEntity() = LlmConfigEntity(
    id, name, providerType, protocol, baseUrl, model, enabled, timeoutSeconds, createdAt, updatedAt,
)
fun LlmConfigEntity.toRecord() = LlmConfigRecord(
    id, name, providerType, protocol, baseUrl, model, enabled, timeoutSeconds, createdAt, updatedAt,
)
fun MergeRelationRecord.toEntity() = MergeRelationEntity(
    id, targetSessionId, sourceSessionId, sourceMessageId, targetMessageId,
    rootSourceSessionId, rootSourceMessageId,
)
fun MergeRelationEntity.toRecord() = MergeRelationRecord(
    id, targetSessionId, sourceSessionId, sourceMessageId, targetMessageId,
    rootSourceSessionId, rootSourceMessageId,
)
fun SessionDocumentRecord.toEntity() = SessionDocumentEntity(
    sessionId, content, messageCount, generatedAt, modelSnapshot, isMock,
)
fun SessionDocumentEntity.toRecord() = SessionDocumentRecord(
    sessionId, content, messageCount, generatedAt, modelSnapshot, isMock,
)
fun ReminderRecord.toEntity() = ReminderEntity(
    id, sessionId, messageId, triggerAt, createdAt, updatedAt, status,
)
fun ReminderEntity.toRecord() = ReminderRecord(
    id, sessionId, messageId, triggerAt, createdAt, updatedAt, status,
)
