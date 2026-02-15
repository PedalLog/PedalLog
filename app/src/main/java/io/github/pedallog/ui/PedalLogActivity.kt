package io.github.pedallog.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.NavigationUI
import androidx.preference.PreferenceManager
import io.github.pedallog.R
import io.github.pedallog.SettingsActivity
import io.github.pedallog.databinding.ActivityPedalLogBinding
import io.github.pedallog.other.Constants
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PedalLogActivity : AppCompatActivity() {

    // View Binding Variable
    lateinit var binding: ActivityPedalLogBinding

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
        
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
        WindowCompat.setDecorFitsSystemWindows(window, actionBarVisible)

        binding = ActivityPedalLogBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        // Handle Window Insets for edge-to-edge
        if (!actionBarVisible) {
            // When action bar is hidden, handle insets for edge-to-edge
            ViewCompat.setOnApplyWindowInsetsListener(binding.topAppBar) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val displayCutout = insets.displayCutout
                
                // Apply top padding for status bar and notch
                val topInset = if (displayCutout != null) {
                    // If there's a notch, ensure we have enough space
                    Math.max(systemBars.top, displayCutout.safeInsetTop)
                } else {
                    systemBars.top
                }
                
                view.setPadding(systemBars.left, topInset, systemBars.right, 0)
                insets
            }
            
            // When action bar is hidden, manually handle bottom insets
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomAppBar) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(
                    left = systemBars.left, 
                    right = systemBars.right,
                    bottom = systemBars.bottom
                )
                insets
            }
        } else {
            // When action bar is visible, let system handle insets automatically
            ViewCompat.setOnApplyWindowInsetsListener(binding.topAppBar, null)
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomAppBar, null)
        }

        // This is the function we created at bottom
        startTrackingActivityIfNeeded(intent)

        // Bottom Navigation Setup
        binding.bottomNavigationView.background = null

        // Connecting bottom navigation view with navController
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHostFragment?.navController
        
        if (navController != null) {
            binding.bottomNavigationView.setupWithNavController(navController)
            
            // Setup Action Bar with Navigation Controller and Drawer
            setSupportActionBar(binding.topAppBar)
            val appBarConfiguration = AppBarConfiguration(
                setOf(R.id.journeyFragment, R.id.statisticsFragment),
                binding.drawerLayout
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
            
            // Link NavigationView with NavController if applicable
            // For settings, we might need a manual listener if it's not in nav_graph
            binding.navigationView.setNavigationItemSelectedListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.nav_settings -> {
                        val intent = Intent(this, SettingsActivity::class.java)
                        startActivity(intent)
                        binding.drawerLayout.closeDrawers()
                        true
                    }
                    R.id.nav_github -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))
                        try {
                            startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // No browser (or handler) available.
                        }
                        binding.drawerLayout.closeDrawers()
                        true
                    }
                    else -> false
                }
            }
        }

        // When fab button is clicked, start Tracking Activity
        binding.btnNewJourney.setOnClickListener {
            val intent = Intent(this,TrackingActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUIPreferences()
    }

    private fun updateUIPreferences() {
        if (!::binding.isInitialized) return
        
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

        // Do NOT hide the bottom app bar even if system nav bar is hidden
        binding.bottomAppBar.visibility = View.VISIBLE
    }

    // In case our service is running and user closes app and clicks on notification, we want the tracking activity to be started. We can do it using this function
    private fun startTrackingActivityIfNeeded(intent: Intent?) {
        if(intent?.action== Constants.ACTION_SHOW_TRACKING_ACTIVITY) {
            val trackingActivityIntent = Intent(this,TrackingActivity::class.java)
            startActivity(trackingActivityIntent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHostFragment?.navController
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.journeyFragment, R.id.statisticsFragment),
            binding.drawerLayout
        )
        return NavigationUI.navigateUp(navController!!, appBarConfiguration) || super.onSupportNavigateUp()
    }
}
