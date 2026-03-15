package com.voyagerfiles.data.local

import androidx.room.TypeConverter
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileSource

class Converters {
    @TypeConverter
    fun fromConnectionProtocol(value: ConnectionProtocol): String = value.name

    @TypeConverter
    fun toConnectionProtocol(value: String): ConnectionProtocol = ConnectionProtocol.valueOf(value)

    @TypeConverter
    fun fromFileSource(value: FileSource): String = value.name

    @TypeConverter
    fun toFileSource(value: String): FileSource = FileSource.valueOf(value)
}
