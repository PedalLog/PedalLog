package io.github.pedallog.db

import androidx.lifecycle.LiveData
import androidx.room.*

// This interface contains all the functions or operations we will perform on the Journey.kt class
@Dao
interface JourneyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJourney(journey: Journey)

    @Delete
    suspend fun deleteJourney(journey: Journey)

    @Query("SELECT * FROM journey ORDER BY :orderByCriteria DESC")
    fun getAllJourneys(orderByCriteria: String): LiveData<List<Journey>>

    @Query("SELECT * FROM journey WHERE id = :id LIMIT 1")
    suspend fun getJourneyById(id: Int): Journey?


}
