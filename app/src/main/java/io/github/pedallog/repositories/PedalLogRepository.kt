package io.github.pedallog.repositories

import androidx.lifecycle.LiveData
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.pedallog.db.Journey
import io.github.pedallog.db.JourneyDao
import javax.inject.Inject

class PedalLogRepository @Inject constructor(val journeyDao: JourneyDao) {

    suspend fun upsertJourney(journey: Journey) = journeyDao.upsertJourney(journey)

    suspend fun deleteJourney(journey: Journey) = journeyDao.deleteJourney(journey)

    fun getAllJourneys(orderByCriteria: String) = journeyDao.getAllJourneys(orderByCriteria)

    suspend fun getJourneyById(id: Int) = journeyDao.getJourneyById(id)



}
