package com.example.sidequest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JournalActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var statsText: TextView
    private lateinit var emptyText: TextView
    private lateinit var journalList: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)

        backButton = findViewById(R.id.backButton)
        statsText = findViewById(R.id.statsText)
        emptyText = findViewById(R.id.emptyText)
        journalList = findViewById(R.id.journalList)

        backButton.setOnClickListener { finish() }

        val store = QuestStore(this)
        val entries = store.journal().sortedByDescending { it.completedAt }

        statsText.text = if (entries.isEmpty()) "" else getString(R.string.streak_summary, store.streak(), entries.size)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        journalList.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        journalList.layoutManager = GridLayoutManager(this, 2)
        journalList.adapter = JournalAdapter(entries)
    }

    private class JournalAdapter(private val entries: List<JournalEntry>) :
        RecyclerView.Adapter<JournalAdapter.Holder>() {

        private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_journal, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.photo.setImageBitmap(Photos.thumbnail(entry.photoPath, 300))
            holder.title.text = "${entry.emoji} ${entry.title}"
            val where = entry.verifiedAt?.let { "📍 $it" } ?: entry.placeName
            holder.meta.text = listOfNotNull(dateFormat.format(Date(entry.completedAt)), where)
                .joinToString(" · ")
            holder.message.text = entry.message
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView = view.findViewById(R.id.photo)
            val title: TextView = view.findViewById(R.id.title)
            val meta: TextView = view.findViewById(R.id.meta)
            val message: TextView = view.findViewById(R.id.message)
        }
    }
}
