package io.github.pedallog.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import io.github.pedallog.R
import io.github.pedallog.databinding.ActivitySetupBinding
import io.github.pedallog.other.TrackingUtility

class SetupActivity : AppCompatActivity() {

    lateinit var binding: ActivitySetupBinding

    private var defaultMbtilesName: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            updateStep1Ui()
        } else {
            Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, getString(R.string.background_location_required), Toast.LENGTH_LONG).show()
        }
        updateStep1Ui()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStep1Ui()
    }

    private val appDetailsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStep1Ui()
    }

    private fun setupMapSelection() {
        val assets = assets.list("tiles") ?: emptyArray()
        val mbtilesFiles = assets.filter { it.endsWith(".mbtiles") }
        
        if (mbtilesFiles.isNotEmpty()) {
            binding.btnDefaultMap.visibility = View.VISIBLE
            binding.btnDefaultMap.setOnClickListener {
                showBuiltInMapDialog(mbtilesFiles.toTypedArray())
            }
        } else {
            binding.btnDefaultMap.visibility = View.GONE
        }
        
        binding.pickFileButton.setOnClickListener {
            openMbtilesFileLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }
        
        binding.btnSkipMap.setOnClickListener {
            // Skip map selection and go to main activity
            val intent = Intent(this, PedalLogActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
    
    private fun showBuiltInMapDialog(mbtilesFiles: Array<String>) {
        val displayNames = mbtilesFiles.map { file ->
            val datePart = file.filter { it.isDigit() }
            val formattedDate = if (datePart.length >= 6) {
                "${datePart.substring(0,2)}/${datePart.substring(2,4)}/${datePart.substring(4,6)}"
            } else datePart
            
            if (formattedDate.isNotEmpty()) {
                "${file.substringBeforeLast(".")} ($formattedDate)"
            } else {
                file.substringBeforeLast(".")
            }
        }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.map_builtin_option)
            .setItems(displayNames) { dialog, which ->
                val selectedFile = mbtilesFiles[which]
                selectBuiltInMap(selectedFile)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    
    private fun selectBuiltInMap(assetName: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.edit().apply {
            putString("asset_mbtiles", "tiles/$assetName")
            remove("mbtiles_file")
            apply()
        }
        
        startActivity(Intent(this, PedalLogActivity::class.java))
        finish()
    }

    private val openMbtilesFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            if (uri.toString().endsWith(".mbtiles")) {
                try {
                    val contentResolver = applicationContext.contentResolver
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                    Toast.makeText(this, getString(R.string.file_permission_failed), Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }

                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                sharedPreferences.edit().apply {
                    putString("mbtiles_file", uri.toString())
                    remove("asset_mbtiles")
                    apply()
                }

                val intent = Intent(this, PedalLogActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.select_mbtiles_file), Toast.LENGTH_LONG).show()
            }
        }
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
        
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show step 0
        binding.step0.visibility = View.VISIBLE
        binding.step1.visibility = View.GONE
        binding.step2.visibility = View.GONE

        binding.startButton.setOnClickListener {
            binding.step0.visibility = View.GONE
            binding.step1.visibility = View.VISIBLE
            updateStep1Ui()
        }

        binding.grantAllPermissionsButton.setOnClickListener {
            val permissions = mutableListOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }

        binding.grantBackgroundLocationButton.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                updateStep1Ui()
                return@setOnClickListener
            }

            if (TrackingUtility.hasLocationPermissions(this).not()) {
                Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                requestBackgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                // Android 11+ requires the user to grant "Always" in system settings.
                Toast.makeText(this, getString(R.string.background_location_open_settings), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                appDetailsLauncher.launch(intent)
            }
        }

        binding.disableBatteryOptimizationButton.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                updateStep1Ui()
                return@setOnClickListener
            }

            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val ignoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            if (ignoring) {
                updateStep1Ui()
                return@setOnClickListener
            }

            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryOptimizationLauncher.launch(intent)
        }

        binding.continueButton.setOnClickListener {
            val hasForeground = TrackingUtility.hasLocationPermissions(this)
            val hasBackground = TrackingUtility.hasBackgroundLocationPermission(this)
            val hasNotification = TrackingUtility.hasNotificationPermission(this)
            val ignoringBatteryOpt = isBatteryOptimizationIgnored()
            
            if (!hasForeground) {
                // Foreground location is absolutely required
                Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            // Warn about missing optional but recommended permissions
            val missingPermissions = mutableListOf<String>()
            if (!hasBackground) missingPermissions.add(getString(R.string.background_location))
            if (!hasNotification) missingPermissions.add(getString(R.string.notifications))
            if (!ignoringBatteryOpt) missingPermissions.add(getString(R.string.battery_optimization))
            
            if (missingPermissions.isNotEmpty()) {
                val warningMessage = getString(R.string.setup_warning_missing_permissions, missingPermissions.joinToString(", "))
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.setup_warning_title)
                    .setMessage(warningMessage)
                    .setPositiveButton(R.string.continue_anyway) { _, _ ->
                        // Allow user to continue anyway
                        binding.step1.visibility = View.GONE
                        binding.step2.visibility = View.VISIBLE
                        setupMapSelection()
                    }
                    .setNegativeButton(R.string.go_back, null)
                    .show()
            } else {
                // All permissions granted, proceed
                binding.step1.visibility = View.GONE
                binding.step2.visibility = View.VISIBLE
                setupMapSelection()
            }
        }

        // Initial UI state (in case step1 is shown immediately via state restoration)
        updateStep1Ui()
    }

    override fun onResume() {
        super.onResume()
        updateStep1Ui()
    }

    private fun areSetupRequirementsSatisfied(): Boolean {
        val hasForeground = TrackingUtility.hasLocationPermissions(this)
        val hasBackground = TrackingUtility.hasBackgroundLocationPermission(this)
        val hasNotification = TrackingUtility.hasNotificationPermission(this)
        val ignoringBatteryOpt = isBatteryOptimizationIgnored()
        return hasForeground && hasBackground && hasNotification && ignoringBatteryOpt
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateStep1Ui() {
        // Only update if step1 is visible or can be interacted with.
        if (!::binding.isInitialized) return

        val hasForeground = TrackingUtility.hasLocationPermissions(this)
        val hasBackground = TrackingUtility.hasBackgroundLocationPermission(this)
        val hasNotification = TrackingUtility.hasNotificationPermission(this)
        val ignoringBatteryOpt = isBatteryOptimizationIgnored()

        binding.grantAllPermissionsButton.isEnabled = !(hasForeground && hasNotification)

        binding.grantBackgroundLocationButton.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) View.VISIBLE else View.GONE
        binding.grantBackgroundLocationButton.isEnabled = hasForeground && !hasBackground

        binding.disableBatteryOptimizationButton.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) View.VISIBLE else View.GONE
        binding.disableBatteryOptimizationButton.isEnabled = !ignoringBatteryOpt

        binding.continueButton.isEnabled = hasForeground && hasBackground && hasNotification && ignoringBatteryOpt
    }
}
