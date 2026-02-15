package io.github.pedallog.ui.viewmodels

import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pedallog.db.Journey
import io.github.pedallog.repositories.PedalLogRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PedalLogViewModel @Inject constructor(val pedalLogRepository: PedalLogRepository): ViewModel() {

    fun insertJourney(journey: Journey) = viewModelScope.launch {
        pedalLogRepository.upsertJourney(journey)
    }

    fun deleteJourney(journey: Journey) = viewModelScope.launch {
        pedalLogRepository.deleteJourney(journey)
    }

    val journeyList = pedalLogRepository.getAllJourneys("dateCreated")

}
