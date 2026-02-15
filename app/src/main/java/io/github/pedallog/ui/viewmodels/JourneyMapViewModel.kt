package io.github.pedallog.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pedallog.db.Journey
import io.github.pedallog.repositories.PedalLogRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JourneyMapViewModel @Inject constructor(
    private val repository: PedalLogRepository
) : ViewModel() {

    private val _journey = MutableLiveData<Journey?>()
    val journey: LiveData<Journey?> = _journey

    fun loadJourney(id: Int) {
        viewModelScope.launch {
            _journey.postValue(repository.getJourneyById(id))
        }
    }
}
