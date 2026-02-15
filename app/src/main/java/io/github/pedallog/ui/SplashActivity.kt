package io.github.pedallog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val mbtilesFile = sharedPreferences.getString("mbtiles_file", null)
        val assetMbtiles = sharedPreferences.getString("asset_mbtiles", null)

        val locationPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasMap = mbtilesFile != null || (assetMbtiles != null && assetMbtiles != "none")

        if (locationPermissionGranted && hasMap) {
            val intent = Intent(this, PedalLogActivity::class.java)
            startActivity(intent)
        } else {
            val intent = Intent(this, SetupActivity::class.java)
            startActivity(intent)
        }
        finish()
    }
}