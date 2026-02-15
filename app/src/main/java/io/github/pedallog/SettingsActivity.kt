package io.github.pedallog

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            findPreference<Preference>("map_source")?.apply {
                updateMapSourceSummary()
                setOnPreferenceClickListener {
                    showMapSourceDialog()
                    true
                }
            }
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
                }
                "app_theme" -> {
                    activity?.recreate()
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