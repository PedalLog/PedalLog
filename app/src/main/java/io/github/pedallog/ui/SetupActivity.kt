package io.github.pedallog.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import io.github.pedallog.R
import io.github.pedallog.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    lateinit var binding: ActivitySetupBinding

    private var defaultMbtilesName: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            // Move to step 2: Map selection
            binding.step1.visibility = View.GONE
            binding.step2.visibility = View.VISIBLE
            setupMapSelection()
        } else {
            Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_LONG).show()
        }
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
    }
}
