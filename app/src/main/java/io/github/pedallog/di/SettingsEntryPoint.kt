package io.github.pedallog.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.pedallog.db.JourneyDao

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun journeyDao(): JourneyDao
}
