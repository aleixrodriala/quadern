package io.github.aleixrodriala.noteai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Note::class, Chunk::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "notes.db")
                // WAL keeps writes durable and cheap while the recorder and worker both write.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Adds titles, summaries and tags; notes transcribed before this get summarized too. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN insightsStatus TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE notes ADD COLUMN insightsAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN insightsGen INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE notes SET insightsStatus = 'PENDING' " +
                        "WHERE transcriptionStatus = 'DONE' AND recordingState = 'RECORDED' AND transcript != ''"
                )
            }
        }
    }
}
