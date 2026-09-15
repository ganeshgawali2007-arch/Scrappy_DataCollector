package com.scraper.classroomcapture.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// Room database v1 (P2.2, P2.5). Schema is exported to app/schemas/ (committed)
// and there is deliberately NO fallbackToDestructiveMigration: a missing
// migration must fail loudly at startup, never silently wipe classroom data.
// Every version bump ships an explicit Migration plus a migration test.
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
    version = 1,
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
        const val VERSION = 1

        fun build(context: Context): ScraperDatabase =
            Room.databaseBuilder(context, ScraperDatabase::class.java, NAME)
                // No destructive fallback by design (P2.5). Missing migrations
                // throw instead of wiping data; add explicit Migrations.
                .build()
    }
}
