package com.scraper.classroomcapture.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Room database v2 (P2.2, P2.5, P6.1). Schema is exported to app/schemas/
// (committed) and there is deliberately NO fallbackToDestructiveMigration:
// a missing migration must fail loudly at startup, never silently wipe
// classroom data. Every version bump ships an explicit Migration plus a
// migration test.
//
// v2: unique index on processing_jobs(sampleId, kind) — one job row per
// (sample, kind) for the life of the sample, so retries reuse the row and a
// crash can never duplicate work.
@Database(
    entities = [
        SessionEntity::class,
        SampleEntity::class,
        ArtifactEntity::class,
        ProcessingJobEntity::class,
        DeviceEventEntity::class,
        ExportRecordEntity::class,
        ExportSessionCrossRef::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ScraperDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    abstract fun sampleDao(): SampleDao

    abstract fun artifactDao(): ArtifactDao

    abstract fun processingJobDao(): ProcessingJobDao

    abstract fun deviceEventDao(): DeviceEventDao

    abstract fun exportDao(): ExportDao

    companion object {
        const val NAME = "scraper.db"
        const val VERSION = 2

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_processing_jobs_sampleId_kind` " +
                            "ON `processing_jobs` (`sampleId`, `kind`)",
                    )
                }
            }

        fun build(context: Context): ScraperDatabase =
            Room.databaseBuilder(context, ScraperDatabase::class.java, NAME)
                // No destructive fallback by design (P2.5). Missing migrations
                // throw instead of wiping data; add explicit Migrations.
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
