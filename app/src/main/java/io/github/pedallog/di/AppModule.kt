package io.github.pedallog.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.pedallog.db.JourneyDatabase
import io.github.pedallog.other.Constants.DATABASE_NAME
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// This file contains all the functions we need as long as our application lives
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE journey ADD COLUMN routeJson TEXT")
        }
    }

    @Singleton
    @Provides
    fun provideJourneyDatabaseInstance(@ApplicationContext context: Context) =
        Room.databaseBuilder(context, JourneyDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

    @Singleton
    @Provides
    fun provideJourneyDao(db: JourneyDatabase) = db.getJourneyDao()
}
