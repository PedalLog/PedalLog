package io.github.pedallog.db

import android.graphics.Bitmap
import androidx.room.Entity
import androidx.room.PrimaryKey

// This class defines a single journey of our app
@Entity(tableName = "journey")
data class Journey(
    var dateCreated: Long = 0L,
    var speed: Long = 0L, // In km/h
    var distance: Float = 0f, // In kilometers
    var duration: Long = 0L, // In milliseconds
    var img: Bitmap ?= null,
    var routeJson: String? = null
) {
    @PrimaryKey(autoGenerate = true)
    var id: Int? = null // id is declared inside the body of class, not inside the constructor so that we won't have to assign it every time we create an instance of this class
}
