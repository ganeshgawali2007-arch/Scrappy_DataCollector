package com.scraper.classroomcapture.data.local

import androidx.room.TypeConverter
import com.scraper.classroomcapture.domain.model.ArtifactKind
import com.scraper.classroomcapture.domain.model.ExportStatus
import com.scraper.classroomcapture.domain.model.ExportVerification
import com.scraper.classroomcapture.domain.model.JobKind
import com.scraper.classroomcapture.domain.model.JobState
import com.scraper.classroomcapture.domain.model.SampleState

// Explicit Room type converters (P2.2). Enums persist as stable names —
// never ordinals — so adding a value later cannot corrupt existing rows.
// Flag lists join on \u001F (unit separator); flags are machine tokens that
// never contain it, so no escaping layer is needed.
class Converters {
    @TypeConverter
    fun sampleStateToString(value: SampleState): String = value.name

    @TypeConverter
    fun stringToSampleState(value: String): SampleState = SampleState.valueOf(value)

    @TypeConverter
    fun artifactKindToString(value: ArtifactKind): String = value.name

    @TypeConverter
    fun stringToArtifactKind(value: String): ArtifactKind = ArtifactKind.valueOf(value)

    @TypeConverter
    fun jobKindToString(value: JobKind): String = value.name

    @TypeConverter
    fun stringToJobKind(value: String): JobKind = JobKind.valueOf(value)

    @TypeConverter
    fun jobStateToString(value: JobState): String = value.name

    @TypeConverter
    fun stringToJobState(value: String): JobState = JobState.valueOf(value)

    @TypeConverter
    fun exportStatusToString(value: ExportStatus): String = value.name

    @TypeConverter
    fun stringToExportStatus(value: String): ExportStatus = ExportStatus.valueOf(value)

    @TypeConverter
    fun exportVerificationToString(value: ExportVerification?): String? = value?.name

    @TypeConverter
    fun stringToExportVerification(value: String?): ExportVerification? = value?.let(ExportVerification::valueOf)

    @TypeConverter
    fun flagsToString(values: List<String>): String = values.joinToString(SEPARATOR)

    @TypeConverter
    fun stringToFlags(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(SEPARATOR)

    companion object {
        const val SEPARATOR = "\u001F"
    }
}
