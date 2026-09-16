package io.github.moronigranja.ayvu.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One user setting (P1): match threshold (0.6 default, decisions #3), engine
 * prefs, language-pack state — a generic key-value table keeps the schema
 * stable while those surfaces land (V1, T1). Typed accessors live in
 * [SettingsStore]; unknown keys are absent rows, never null-valued ones.
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
