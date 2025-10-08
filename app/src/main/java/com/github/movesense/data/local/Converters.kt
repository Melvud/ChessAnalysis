package com.github.movesense.data.local

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter

@ProvidedTypeConverter
class EmptyConverters {
    @TypeConverter
    fun fromString(value: String?): String? = value
}
