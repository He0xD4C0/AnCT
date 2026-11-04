package dev.anct.data

import androidx.room.TypeConverter
import dev.anct.core.ActualState
import dev.anct.core.DesiredState
import dev.anct.core.RuntimeKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun runtimeKindToString(value: RuntimeKind): String = value.name

    @TypeConverter
    fun stringToRuntimeKind(value: String): RuntimeKind = RuntimeKind.valueOf(value)

    @TypeConverter
    fun desiredToString(value: DesiredState): String = value.name

    @TypeConverter
    fun stringToDesired(value: String): DesiredState = DesiredState.valueOf(value)

    @TypeConverter
    fun actualToString(value: ActualState): String = json.encodeToString(value)

    @TypeConverter
    fun stringToActual(value: String): ActualState = json.decodeFromString(value)
}

