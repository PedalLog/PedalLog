package io.github.pedallog.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.pedallog.R
import io.github.pedallog.databinding.JourneyBinding
import io.github.pedallog.db.Journey
import io.github.pedallog.other.TrackingUtility
import java.text.SimpleDateFormat
import java.util.*

class JourneyAdapter: RecyclerView.Adapter<JourneyAdapter.JourneyViewHolder>() {
    inner class JourneyViewHolder(val binding: JourneyBinding): RecyclerView.ViewHolder(binding.root)

    private val differ = AsyncListDiffer(this,object: DiffUtil.ItemCallback<Journey>() {

        override fun areItemsTheSame(oldItem: Journey, newItem: Journey): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Journey, newItem: Journey): Boolean {
            return oldItem.hashCode() == newItem.hashCode()
        }
    })

    fun submitList(journeyList: List<Journey>) = differ.submitList(journeyList)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JourneyViewHolder {
        val view = JourneyBinding.inflate(LayoutInflater.from(parent.context),parent,false)
        return JourneyViewHolder(view)
    }

    private var onItemClickListener: ((Journey)->Unit) ?= null
    private var onDeleteClickListener: ((Journey)->Unit) ?= null
    private var onAnalyzeClickListener: ((Journey)->Unit) ?= null
    private var onMapClickListener: ((Journey)->Unit) ?= null

    fun setOnItemClickListener(listener: (Journey)->Unit) {
        onItemClickListener = listener
    }
    
    fun setOnDeleteClickListener(listener: (Journey)->Unit) {
        onDeleteClickListener = listener
    }
    
    fun setOnAnalyzeClickListener(listener: (Journey)->Unit) {
        onAnalyzeClickListener = listener
    }

    fun setOnMapClickListener(listener: (Journey) -> Unit) {
        onMapClickListener = listener
    }

    override fun onBindViewHolder(holder: JourneyViewHolder, position: Int) {
        val journey = differ.currentList[position]
        holder.binding.apply {
            val bmp = journey.img
            if (bmp != null) {
                ivMap.setImageBitmap(bmp)
            } else {
                ivMap.setImageResource(R.drawable.ic_journey)
            }

            ivMap.setOnClickListener {
                onMapClickListener?.invoke(journey)
            }
            tvSpeed.text = "${journey.speed} ${root.context.getString(R.string.unit_kmh)}"
            
            // Format distance: show meters if less than 1km, otherwise km
            tvDistance.text = if (journey.distance < 1.0f) {
                "${(journey.distance * 1000).toInt()} ${root.context.getString(R.string.unit_m)}"
            } else {
                "${String.format("%.1f", journey.distance)} ${root.context.getString(R.string.unit_km)}"
            }

            val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
            tvTime.text = dateFormat.format(Date(journey.dateCreated))

            tvDuration.text = TrackingUtility.getFormattedStopwatchTime(journey.duration)
            
            // Set up button click listeners
            btnDetails.setOnClickListener {
                onItemClickListener?.invoke(journey)
            }
            
            btnDelete.setOnClickListener {
                onDeleteClickListener?.invoke(journey)
            }
            
            btnAnalyze.setOnClickListener {
                onAnalyzeClickListener?.invoke(journey)
            }
        }
    }

    override fun getItemCount(): Int {
        return differ.currentList.size
    }



}
