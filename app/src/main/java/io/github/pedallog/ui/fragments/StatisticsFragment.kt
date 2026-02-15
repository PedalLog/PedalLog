package io.github.pedallog.ui.fragments

import android.os.Bundle
import android.util.TypedValue
import androidx.fragment.app.Fragment
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.db.williamchart.slidertooltip.SliderTooltip
import com.db.williamchart.ExperimentalFeature
import io.github.pedallog.R
import io.github.pedallog.databinding.FragmentStatisticsBinding
import io.github.pedallog.other.TrackingUtility
import io.github.pedallog.ui.viewmodels.PedalLogViewModel
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class StatisticsFragment : Fragment(R.layout.fragment_statistics) {

    val viewModel: PedalLogViewModel by viewModels()
    lateinit var binding: FragmentStatisticsBinding

    @OptIn(ExperimentalFeature::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentStatisticsBinding.bind(view)

        // Hide chart view until we have enough data.
        binding.barChart.visibility = View.GONE
        binding.tvStatsStatus.visibility = View.GONE

        // Theme-based bar color
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        val themeColor = typedValue.data

        binding.barChart.barsColor = themeColor
        binding.barChart.animation.duration = 1000L

        // Tooltip color
        binding.barChart.tooltip =
            SliderTooltip().also {
                requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
                it.color = typedValue.data
            }

        viewModel.journeyList.observe(viewLifecycleOwner, Observer { journeyList ->
            val journeys = journeyList.orEmpty()

            // Chart library can throw when not enough points; keep UX explicit.
            when {
                journeys.isEmpty() -> {
                    binding.barChart.visibility = View.GONE
                    binding.tvStatsStatus.visibility = View.VISIBLE
                    binding.tvStatsStatus.text = getString(R.string.stats_empty)
                    return@Observer
                }
                journeys.size < 2 -> {
                    binding.barChart.visibility = View.GONE
                    binding.tvStatsStatus.visibility = View.VISIBLE
                    binding.tvStatsStatus.text = getString(R.string.stats_need_more)
                    return@Observer
                }
                else -> {
                    binding.tvStatsStatus.visibility = View.GONE
                }
            }

            try {
                binding.barChart.visibility = View.VISIBLE

                // Bar chart: distance per journey, labeled by date.
                val barSet = mutableListOf<Pair<String, Float>>()
                val dateFormatX = SimpleDateFormat("MM/dd", Locale.getDefault())
                for (journey in journeys) {
                    barSet.add(Pair(dateFormatX.format(Date(journey.dateCreated)), journey.distance))
                }

                binding.barChart.onDataPointTouchListener = { index, _, _ ->
                    val journey = journeys.getOrNull(index)
                    journey?.let { j ->
                        binding.tvSpeed.text = "${j.speed} ${getString(R.string.unit_kmh)}"
                        binding.tvDistance.text = "${j.distance} ${getString(R.string.unit_km)}"
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                        binding.tvTime.text = dateFormat.format(Date(j.dateCreated))
                        binding.tvDuration.text = TrackingUtility.getFormattedStopwatchTime(j.duration)
                    }
                }

                binding.barChart.animate(barSet)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to render statistics chart")
                binding.barChart.visibility = View.GONE
                binding.tvStatsStatus.visibility = View.VISIBLE
                binding.tvStatsStatus.text = getString(R.string.stats_error)
            }
        })
    }
}
