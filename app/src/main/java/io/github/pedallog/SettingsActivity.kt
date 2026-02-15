package io.github.pedallog

import android.content.res.ColorStateList
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceGroup
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.EntryPointAccessors
import io.github.pedallog.di.SettingsEntryPoint
import io.github.pedallog.other.JourneyBackup
import io.github.pedallog.services.FloatingBarService
import io.github.pedallog.services.TrackingService
import io.github.pedallog.ui.SplashActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    fun restartApp() {
        // Note: finishAffinity() will terminate all activities in the task.
        // This could cause data loss if there are unsaved changes in any activity.
        // For now, we restart immediately assuming settings changes don't leave unsaved state.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, SplashActivity::class.java)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launchIntent)
        finishAffinity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val theme = sharedPreferences.getString("app_theme", "Indigo")
        when (theme) {
            "Teal" -> setTheme(R.style.Theme_PedalLog_Teal)
            "Sunset" -> setTheme(R.style.Theme_PedalLog_Sunset)
            "Purple" -> setTheme(R.style.Theme_PedalLog_Purple)
            "Blue" -> setTheme(R.style.Theme_PedalLog_Blue)
            "Green" -> setTheme(R.style.Theme_PedalLog_Green)
            "Red" -> setTheme(R.style.Theme_PedalLog_Red)
            else -> setTheme(R.style.Theme_PedalLog)
        }
        
        val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
        WindowCompat.setDecorFitsSystemWindows(window, actionBarVisible)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Apply font and text size preferences
        applyFontAndTextSizePreferences()
        
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
            
        // Apply UI preferences immediately to maintain system bars visibility
        updateUIPreferences()
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen
    ): Boolean {
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(pref.key)
            .commit()

        return true
    }

    override fun onResume() {
        super.onResume()
        updateUIPreferences()
        applyFontAndTextSizePreferences()
    }
    
    private fun applyFontAndTextSizePreferences() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Apply font family
        val fontFamily = sharedPreferences.getString("font_family", "system")
        val typeface = when (fontFamily) {
            "roboto" -> android.graphics.Typeface.DEFAULT
            "sans_serif" -> android.graphics.Typeface.SANS_SERIF
            "serif" -> android.graphics.Typeface.SERIF
            "monospace" -> android.graphics.Typeface.MONOSPACE
            else -> null
        }
        
        // Apply text size scale
        val textSizeScale = sharedPreferences.getString("text_size_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        
        // Apply UI scale
        val uiScale = sharedPreferences.getString("ui_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        
        // Apply to root view
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        applyStyleToViewGroup(rootView, typeface, textSizeScale, uiScale)
    }
    
    private fun applyStyleToViewGroup(viewGroup: android.view.ViewGroup, typeface: android.graphics.Typeface?, textSizeScale: Float, uiScale: Float) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            
            if (child is android.widget.TextView) {
                typeface?.let { child.typeface = it }
                val currentSize = child.textSize
                child.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, currentSize * textSizeScale)
            }
            
            // Apply UI scale
            child.scaleX = uiScale
            child.scaleY = uiScale
            
            if (child is android.view.ViewGroup) {
                applyStyleToViewGroup(child, typeface, textSizeScale, uiScale)
            }
        }
    }

    fun updateUIPreferences() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
        val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

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

    class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var pendingEnableFloatingBar = false

        private val overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (!pendingEnableFloatingBar) return@registerForActivityResult
            pendingEnableFloatingBar = false

            val canDraw = canDrawOverlays()
            if (!canDraw) {
                Toast.makeText(requireContext(), getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            // Now enable preference + start service.
            preferenceManager.sharedPreferences?.edit()?.putBoolean("floating_bar_enabled", true)?.apply()
            startFloatingBarService()
        }

        private val pickMbtilesLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                preferenceManager.sharedPreferences?.edit()?.apply {
                    putString("mbtiles_file", it.toString())
                    remove("asset_mbtiles")
                    apply()
                }
                updateMapSourceSummary()
                Toast.makeText(requireContext(), getString(R.string.mbtiles_file_selected), Toast.LENGTH_SHORT).show()
            }
        }

        private val backupExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri == null) return@registerForActivityResult

            val appContext = requireContext().applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(appContext, SettingsEntryPoint::class.java)
            val dao = entryPoint.journeyDao()

            // Show progress dialog to prevent navigation during backup
            val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.backup_export_in_progress))
                .setMessage(getString(R.string.backup_export_wait))
                .setCancelable(false)
                .create()
            progressDialog.show()

            lifecycleScope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        val journeys = dao.getAllJourneysList()
                        JourneyBackup.toJsonString(journeys)
                    }

                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(json.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("Failed to open output stream")
                    }

                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.backup_export_done), Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.backup_export_failed), Toast.LENGTH_LONG).show()
                }
            }
        }

        private val backupImportLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@registerForActivityResult

            val appContext = requireContext().applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(appContext, SettingsEntryPoint::class.java)
            val dao = entryPoint.journeyDao()

            // Show progress dialog to prevent navigation during restore
            val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.backup_import_in_progress))
                .setMessage(getString(R.string.backup_import_wait))
                .setCancelable(false)
                .create()
            progressDialog.show()

            lifecycleScope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                        } ?: throw IllegalStateException("Failed to open input stream")
                    }

                    val journeys = withContext(Dispatchers.Default) {
                        JourneyBackup.fromJsonString(json)
                    }

                    withContext(Dispatchers.IO) {
                        dao.upsertJourneys(journeys)
                    }

                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.backup_import_done), Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.backup_import_failed), Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // PreferenceScreen icons are not consistently tinted under Material3 unless we do it.
            applyPreferenceIconTint()
            
            findPreference<Preference>("map_source")?.apply {
                updateMapSourceSummary()
                setOnPreferenceClickListener {
                    showMapSourceDialog()
                    true
                }
            }

            findPreference<Preference>("backup_export")?.setOnPreferenceClickListener {
                backupExportLauncher.launch("pedallog-backup.json")
                true
            }

            findPreference<Preference>("backup_import")?.setOnPreferenceClickListener {
                backupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                true
            }

            findPreference<SwitchPreference>("floating_bar_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as? Boolean ?: false
                if (enable) {
                    if (!canDrawOverlays()) {
                        pendingEnableFloatingBar = true
                        requestOverlayPermission()
                        // Keep it off until permission is granted.
                        return@setOnPreferenceChangeListener false
                    }
                    // Only show floating bar during an active journey.
                    if (TrackingService.isTracking.value == true) {
                        startFloatingBarService()
                    }
                    true
                } else {
                    stopFloatingBarService()
                    true
                }
            }
        }

        private fun applyPreferenceIconTint() {
            val colorPrimary = MaterialColors.getColor(
                requireContext(),
                androidx.appcompat.R.attr.colorPrimary,
                0
            )
            val tint = ColorStateList.valueOf(colorPrimary)
            tintIconsRecursively(preferenceScreen, tint)
        }

        private fun tintIconsRecursively(pref: Preference?, tint: ColorStateList) {
            if (pref == null) return

            pref.icon?.let { drawable ->
                val wrapped = DrawableCompat.wrap(drawable).mutate()
                DrawableCompat.setTintList(wrapped, tint)
                pref.icon = wrapped
            }

            if (pref is PreferenceGroup) {
                for (i in 0 until pref.preferenceCount) {
                    tintIconsRecursively(pref.getPreference(i), tint)
                }
            }
        }

        private fun startFloatingBarService() {
            val context = requireContext().applicationContext
            context.startService(Intent(context, FloatingBarService::class.java))
        }

        private fun stopFloatingBarService() {
            val context = requireContext().applicationContext
            context.stopService(Intent(context, FloatingBarService::class.java))
        }

        private fun canDrawOverlays(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(requireContext())
            } else {
                true
            }
        }

        private fun requestOverlayPermission() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            overlayPermissionLauncher.launch(intent)
        }

        private fun showMapSourceDialog() {
            val assets = requireContext().assets.list("tiles") ?: emptyArray()
            val mbtilesFiles = assets.filter { it.endsWith(".mbtiles") }
            
            val options = mutableListOf<String>()
            val optionValues = mutableListOf<String>()
            
            // Add built-in maps
            for (file in mbtilesFiles) {
                val date = parseDateFromFilename(file)
                val fileNameOnly = file.substringBeforeLast(".")
                val displayName = if (date != null) {
                    getString(R.string.map_internal_format, fileNameOnly, date)
                } else {
                    fileNameOnly
                }
                options.add(displayName)
                optionValues.add("asset:tiles/$file")
            }
            
            // Add custom file option
            options.add(getString(R.string.map_custom_option))
            optionValues.add("custom")
            
            // Add none option
            options.add(getString(R.string.map_internal_none))
            optionValues.add("none")
            
            val currentSelection = getCurrentMapSource()
            val selectedIndex = optionValues.indexOf(currentSelection).takeIf { it >= 0 } ?: 0
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.map_source_dialog_title)
                .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { dialog, which ->
                    when (optionValues[which]) {
                        "custom" -> {
                            dialog.dismiss()
                            pickMbtilesLauncher.launch("application/octet-stream")
                        }
                        "none" -> {
                            preferenceManager.sharedPreferences?.edit()?.apply {
                                remove("mbtiles_file")
                                remove("asset_mbtiles")
                                apply()
                            }
                            updateMapSourceSummary()
                            dialog.dismiss()
                        }
                        else -> {
                            // Asset map selected
                            val assetPath = optionValues[which].removePrefix("asset:")
                            preferenceManager.sharedPreferences?.edit()?.apply {
                                putString("asset_mbtiles", assetPath)
                                remove("mbtiles_file")
                                apply()
                            }
                            updateMapSourceSummary()
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        private fun getCurrentMapSource(): String {
            val sharedPreferences = preferenceManager.sharedPreferences
            val assetMap = sharedPreferences?.getString("asset_mbtiles", null)
            val customMap = sharedPreferences?.getString("mbtiles_file", null)
            
            return when {
                assetMap != null && assetMap != "none" -> "asset:$assetMap"
                customMap != null -> "custom"
                else -> "none"
            }
        }
        
        private fun updateMapSourceSummary() {
            val pref = findPreference<Preference>("map_source") ?: return
            val sharedPreferences = preferenceManager.sharedPreferences
            val assetMap = sharedPreferences?.getString("asset_mbtiles", null)
            val customMap = sharedPreferences?.getString("mbtiles_file", null)
            
            pref.summary = when {
                assetMap != null && assetMap != "none" -> {
                    val fileName = assetMap.substringAfterLast("/")
                    val date = parseDateFromFilename(fileName)
                    if (date != null) {
                        getString(R.string.map_internal_format, fileName.substringBeforeLast("."), date)
                    } else {
                        fileName
                    }
                }
                customMap != null -> {
                    getString(R.string.selected_format, android.net.Uri.parse(customMap).path?.substringAfterLast("/") ?: "Custom file")
                }
                else -> getString(R.string.map_internal_none)
            }
        }

        private fun parseDateFromFilename(filename: String): String? {
            // Pattern like south-korea-251203.mbtiles
            // Matches 6 digits at the end before extension
            val regex = Regex("(\\d{6})\\.mbtiles$")
            val match = regex.find(filename)
            return if (match != null) {
                val dateStr = match.groupValues[1]
                "20${dateStr.substring(0, 2)}-${dateStr.substring(2, 4)}-${dateStr.substring(4, 6)}"
            } else {
                null
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                "asset_mbtiles", "mbtiles_file" -> {
                    updateMapSourceSummary()
                }
                "theme" -> {
                    val theme = sharedPreferences?.getString(key, "light")
                    when (theme) {
                        "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }

                    // Restart app to apply theme everywhere.
                    (activity as? SettingsActivity)?.restartApp()
                }
                "app_theme" -> {
                    // Restart app to apply app color theme everywhere.
                    (activity as? SettingsActivity)?.restartApp()
                }
                "action_bar" -> {
                    // Recreate activity to apply new insets configuration
                    activity?.recreate()
                }
                "nav_bar" -> {
                    (activity as? SettingsActivity)?.updateUIPreferences()
                }
                "font_family", "text_size_scale", "ui_scale" -> {
                    // Notify user that changes will apply on next launch or recreate
                    Toast.makeText(requireContext(), "Changes will apply when you reopen this screen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}