package io.github.pedallog.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import androidx.fragment.app.Fragment
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.db.williamchart.slidertooltip.SliderTooltip
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

    @Suppress("OPT_IN_USAGE")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentStatisticsBinding.bind(view)

        // Setting chart view visiblity to gone to avoid not initialized exception. Will set it to visible when we have atleast 2 or more journies in our database list
        binding.lineChart.visibility = View.GONE
        binding.tvStatsStatus.visibility = View.GONE

        // Line chart gradient color
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        val themeColor = typedValue.data
        
        binding.lineChart.gradientFillColors =
            intArrayOf(
                (themeColor and 0x00FFFFFF) or 0x80000000.toInt(), // 50% alpha of primary color
                Color.TRANSPARENT
            )
        binding.lineChart.lineColor = themeColor

        // Line chart animation effect duration
        binding.lineChart.animation.duration = 1000L

        // Line chart Tooltip color
        binding.lineChart.tooltip =
            SliderTooltip().also {
                requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
                it.color = typedValue.data
            }

        viewModel.journeyList.observe(viewLifecycleOwner, Observer { journeyList ->
            val journeys = journeyList.orEmpty()

            // Chart library can throw when not enough points; keep UX explicit.
            when {
                journeys.isEmpty() -> {
                    binding.lineChart.visibility = View.GONE
                    binding.tvStatsStatus.visibility = View.VISIBLE
                    binding.tvStatsStatus.text = getString(R.string.stats_empty)
                    return@Observer
                }
                journeys.size < 2 -> {
                    binding.lineChart.visibility = View.GONE
                    binding.tvStatsStatus.visibility = View.VISIBLE
                    binding.tvStatsStatus.text = getString(R.string.stats_need_more)
                    return@Observer
                }
                else -> {
                    binding.tvStatsStatus.visibility = View.GONE
                }
            }

            try {
                binding.lineChart.visibility = View.VISIBLE

                val lineSet = mutableListOf<Pair<String, Float>>()
                val dateFormatX = SimpleDateFormat("MM/dd", Locale.getDefault())
                for (journey in journeys) {
                    lineSet.add(Pair(dateFormatX.format(Date(journey.dateCreated)), journey.duration.toFloat()))
                }

                binding.lineChart.onDataPointTouchListener = { index, _, _ ->
                    val journey = journeys.getOrNull(index)
                    journey?.let { j ->
                        binding.tvSpeed.text = "${j.speed} ${getString(R.string.unit_kmh)}"
                        binding.tvDistance.text = "${j.distance} ${getString(R.string.unit_km)}"
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                        binding.tvTime.text = dateFormat.format(Date(j.dateCreated))
                        binding.tvDuration.text = TrackingUtility.getFormattedStopwatchTime(j.duration)
                    }
                }

                binding.lineChart.animate(lineSet)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to render statistics chart")
                binding.lineChart.visibility = View.GONE
                binding.tvStatsStatus.visibility = View.VISIBLE
                binding.tvStatsStatus.text = getString(R.string.stats_error)
            }
        })
    }
}
