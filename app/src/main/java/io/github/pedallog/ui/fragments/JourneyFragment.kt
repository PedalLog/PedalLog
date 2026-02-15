package io.github.pedallog.ui.fragments

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.View
import android.view.LayoutInflater
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.pedallog.R
import io.github.pedallog.adapters.JourneyAdapter
import io.github.pedallog.databinding.DialogJourneyMapBinding
import io.github.pedallog.databinding.FragmentJourneyBinding
import io.github.pedallog.db.Journey
import io.github.pedallog.other.Constants.REQUEST_CODE_LOCATION_PERMISSION
import io.github.pedallog.other.TrackingUtility
import io.github.pedallog.ui.viewmodels.PedalLogViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import io.github.pedallog.SettingsActivity
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import javax.inject.Inject
import io.github.pedallog.ui.JourneyMapActivity
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone

// This fragment will display all the journeys tracked using our app
@AndroidEntryPoint
class JourneyFragment : Fragment(R.layout.fragment_journey) {

    val viewModel: PedalLogViewModel by viewModels()

    lateinit var binding: FragmentJourneyBinding
    lateinit var adapter: JourneyAdapter

    private var selectedDayRange: Pair<Long, Long>? = null

    companion object {
        private const val KEY_SELECTED_DAY_START = "selected_day_start"
        private const val KEY_SELECTED_DAY_END = "selected_day_end"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val foregroundLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: true
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        } else true

        if (!foregroundLocationGranted || !notificationGranted) {
            Toast.makeText(requireContext(), getString(R.string.essential_permissions_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentJourneyBinding.bind(view)
        
        // Restore saved date filter state
        savedInstanceState?.let {
            val start = it.getLong(KEY_SELECTED_DAY_START, -1L)
            val end = it.getLong(KEY_SELECTED_DAY_END, -1L)
            if (start != -1L && end != -1L) {
                selectedDayRange = start to end
                val display = SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
                    .format(java.util.Date(start))
                binding.btnPickDate.text = display
                binding.btnClearDate.visibility = View.VISIBLE
            }
        }
        
        requestPermissions()

        adapter = JourneyAdapter()

        binding.rvJourney.adapter = adapter
        binding.rvJourney.layoutManager = LinearLayoutManager(requireContext())

        applyBottomBarPaddingToList()

        setupDateFilter()

        viewModel.journeyList.observe(viewLifecycleOwner, Observer {
            val journeys = it.orEmpty()
            val filtered = selectedDayRange?.let { (start, end) ->
                journeys.filter { j -> j.dateCreated in start..end }
            } ?: journeys
            adapter.submitList(filtered)
        })

        adapter.setOnItemClickListener {
            showJourneyDetails(it)
        }
        
        adapter.setOnDeleteClickListener {
            deleteJourney(it)
        }
        
        adapter.setOnAnalyzeClickListener {
            showJourneyAnalysis(it)
        }

        adapter.setOnMapClickListener {
            showJourneyMap(it)
        }
    }

    private fun setupDateFilter() {
        binding.btnPickDate.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(getString(R.string.journey_filter_pick_date))
                .build()

            picker.addOnPositiveButtonClickListener { selectionUtc ->
                selectedDayRange = computeLocalDayRangeFromUtcSelection(selectionUtc)
                val display = SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
                    .format(java.util.Date(selectedDayRange!!.first))
                binding.btnPickDate.text = display
                binding.btnClearDate.visibility = View.VISIBLE
                // LiveData observer will re-submit filtered list on next emission;
                // force-refresh current list for immediate feedback.
                viewModel.journeyList.value?.let { journeys ->
                    val (start, end) = selectedDayRange!!
                    adapter.submitList(journeys.filter { j -> j.dateCreated in start..end })
                }
            }

            picker.show(parentFragmentManager, "journeyDatePicker")
        }

        binding.btnClearDate.setOnClickListener {
            selectedDayRange = null
            binding.btnPickDate.setText(R.string.journey_filter_pick_date)
            binding.btnClearDate.visibility = View.GONE
            viewModel.journeyList.value?.let { adapter.submitList(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedDayRange?.let { (start, end) ->
            outState.putLong(KEY_SELECTED_DAY_START, start)
            outState.putLong(KEY_SELECTED_DAY_END, end)
        }
    }

    private fun computeLocalDayRangeFromUtcSelection(selectionUtc: Long): Pair<Long, Long> {
        // MaterialDatePicker returns midnight UTC millis. Extract Y/M/D in UTC,
        // then build local start/end-of-day for that date.
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCal.timeInMillis = selectionUtc

        val year = utcCal.get(Calendar.YEAR)
        val month = utcCal.get(Calendar.MONTH)
        val day = utcCal.get(Calendar.DAY_OF_MONTH)

        val localStart = Calendar.getInstance()
        localStart.set(Calendar.YEAR, year)
        localStart.set(Calendar.MONTH, month)
        localStart.set(Calendar.DAY_OF_MONTH, day)
        localStart.set(Calendar.HOUR_OF_DAY, 0)
        localStart.set(Calendar.MINUTE, 0)
        localStart.set(Calendar.SECOND, 0)
        localStart.set(Calendar.MILLISECOND, 0)

        val localEnd = Calendar.getInstance()
        localEnd.timeInMillis = localStart.timeInMillis
        localEnd.add(Calendar.DAY_OF_MONTH, 1)
        // Use start of next day (exclusive) - subtract 1ms for inclusive comparison
        localEnd.add(Calendar.MILLISECOND, -1)
        return localStart.timeInMillis to localEnd.timeInMillis
    }

    private fun applyBottomBarPaddingToList() {
        // BottomAppBar overlays the fragment content. Without extra bottom padding,
        // the last items can end up hidden behind the bar/FAB and look like scrolling is "stuck".
        val bottomBar = activity?.findViewById<View>(R.id.bottomAppBar)
        val initialPaddingLeft = binding.rvJourney.paddingLeft
        val initialPaddingTop = binding.rvJourney.paddingTop
        val initialPaddingRight = binding.rvJourney.paddingRight
        val initialPaddingBottom = binding.rvJourney.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.rvJourney) { listView, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomBarHeight = bottomBar?.height ?: 0
            listView.updatePadding(
                left = initialPaddingLeft + systemBars.left,
                top = initialPaddingTop,
                right = initialPaddingRight + systemBars.right,
                bottom = initialPaddingBottom + systemBars.bottom + bottomBarHeight
            )
            insets
        }

        // Re-apply after layout so bottom bar height is non-zero.
        binding.rvJourney.doOnLayout {
            ViewCompat.requestApplyInsets(binding.rvJourney)
        }
    }

    private fun applySystemBarsPreference(window: Window?) {
        if (window == null) return
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
        val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (!navBarVisible) {
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        if (!actionBarVisible) {
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    
    // Function to show journey details
    private fun showJourneyDetails(journey: Journey) {
        val dateFormat = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
        
        // Format distance
        val distanceStr = if (journey.distance < 1.0f) {
            "${(journey.distance * 1000).toInt()} ${getString(R.string.unit_m)}"
        } else {
            "${String.format("%.2f", journey.distance)} ${getString(R.string.unit_km)}"
        }
        
        val details = """
            ${getString(R.string.date)}: ${dateFormat.format(java.util.Date(journey.dateCreated))}
            ${getString(R.string.distance)}: $distanceStr
            ${getString(R.string.avg_speed)}: ${journey.speed} ${getString(R.string.unit_kmh)}
            ${getString(R.string.duration)}: ${io.github.pedallog.other.TrackingUtility.getFormattedStopwatchTime(journey.duration)}
        """.trimIndent()
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.journey_details_title))
            .setMessage(details)
            .setPositiveButton(getString(R.string.yes)) { d, _ -> d.dismiss() }
            .create()

        dialog.setOnShowListener { applySystemBarsPreference(dialog.window) }
        dialog.setOnDismissListener { applySystemBarsPreference(requireActivity().window) }
        dialog.show()
    }

    private fun showJourneyMap(journey: Journey) {
        val routeJson = journey.routeJson
        val id = journey.id
        if (!routeJson.isNullOrBlank() && id != null) {
            val intent = Intent(requireContext(), JourneyMapActivity::class.java)
                .putExtra(JourneyMapActivity.EXTRA_JOURNEY_ID, id)
            startActivity(intent)
            return
        }

        val bmp = journey.img
        if (bmp == null) {
            Toast.makeText(requireContext(), getString(R.string.journey_map_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val mapBinding = DialogJourneyMapBinding.inflate(LayoutInflater.from(requireContext()))
        mapBinding.ivJourneyMap.setImageBitmap(bmp)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.journey_map_title))
            .setView(mapBinding.root)
            .setPositiveButton(getString(R.string.ok)) { d, _ -> d.dismiss() }
            .create()

        dialog.setOnShowListener { applySystemBarsPreference(dialog.window) }
        dialog.setOnDismissListener { applySystemBarsPreference(requireActivity().window) }
        dialog.show()
    }
    
    // Function to show journey analysis
    private fun showJourneyAnalysis(journey: Journey) {
        val avgSpeedKmh = journey.speed
        val durationHours = journey.duration / 3600000f
        val caloriesBurned = (journey.distance * 50).toInt() // Simple estimation
        
        // Format distance
        val distanceStr = if (journey.distance < 1.0f) {
            "${(journey.distance * 1000).toInt()} ${getString(R.string.unit_m)}"
        } else {
            "${String.format("%.2f", journey.distance)} ${getString(R.string.unit_km)}"
        }
        
        val analysis = """
            ${getString(R.string.distance)}: $distanceStr
            ${getString(R.string.avg_speed)}: $avgSpeedKmh ${getString(R.string.unit_kmh)}
            ${getString(R.string.duration)}: ${String.format("%.2f", durationHours)} hours
            Estimated Calories: ~$caloriesBurned kcal
        """.trimIndent()
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.analyze))
            .setMessage(analysis)
            .setPositiveButton(getString(R.string.yes)) { d, _ -> d.dismiss() }
            .create()

        dialog.setOnShowListener { applySystemBarsPreference(dialog.window) }
        dialog.setOnDismissListener { applySystemBarsPreference(requireActivity().window) }
        dialog.show()
    }

    // Function to delete a journey
    private fun deleteJourney(journey: Journey) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_title))
            .setMessage(getString(R.string.delete_journey_message))
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton(getString(R.string.yes)) { d,_ ->
                viewModel.deleteJourney(journey)
                d.cancel()
            }
            .setNegativeButton(getString(R.string.no)) { d,_ -> d.cancel() }
            .create()

        dialog.setOnShowListener { applySystemBarsPreference(dialog.window) }
        dialog.setOnDismissListener { applySystemBarsPreference(requireActivity().window) }
        dialog.show()
    }


    // Function to request location permissions
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (!TrackingUtility.hasLocationPermissions(requireContext())) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (TrackingUtility.hasLocationPermissions(requireContext()) && 
                !TrackingUtility.hasBackgroundLocationPermission(requireContext())) {
                // background location should ideally be requested separately on Android 11+
                // permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!TrackingUtility.hasNotificationPermission(requireContext())) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
