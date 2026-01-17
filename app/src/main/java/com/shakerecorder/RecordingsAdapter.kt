package com.shakerecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    private val onItemClick: (File) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private val recordings = mutableListOf<File>()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun updateRecordings(files: List<File>) {
        recordings.clear()
        recordings.addAll(files.sortedByDescending { it.lastModified() })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = recordings[position]
        holder.bind(file)
        holder.itemView.setOnClickListener { onItemClick(file) }
    }

    override fun getItemCount() = recordings.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.recordingName)
        private val dateText: TextView = itemView.findViewById(R.id.recordingDate)

        fun bind(file: File) {
            nameText.text = file.name
            dateText.text = dateFormat.format(Date(file.lastModified()))
        }
    }
}
