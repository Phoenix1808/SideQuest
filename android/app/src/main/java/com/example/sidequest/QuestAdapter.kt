package com.example.sidequest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class QuestAdapter(
    private val onDirections: (Quest) -> Unit,
    private val onSnap: (Quest) -> Unit,
    private val onPickFromGallery: (Quest) -> Unit
) : RecyclerView.Adapter<QuestAdapter.Holder>() {

    private var items: List<QuestState> = emptyList()

    fun submit(newItems: List<QuestState>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_quest, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val emojiText: TextView = view.findViewById(R.id.emojiText)
        private val titleText: TextView = view.findViewById(R.id.titleText)
        private val placeText: TextView = view.findViewById(R.id.placeText)
        private val minutesText: TextView = view.findViewById(R.id.minutesText)
        private val descriptionText: TextView = view.findViewById(R.id.descriptionText)
        private val proofText: TextView = view.findViewById(R.id.proofText)
        private val doneSection: LinearLayout = view.findViewById(R.id.doneSection)
        private val photoThumb: ImageView = view.findViewById(R.id.photoThumb)
        private val refereeText: TextView = view.findViewById(R.id.refereeText)
        private val verifiedText: TextView = view.findViewById(R.id.verifiedText)
        private val actionRow: LinearLayout = view.findViewById(R.id.actionRow)
        private val directionsButton: MaterialButton = view.findViewById(R.id.directionsButton)
        private val snapButton: MaterialButton = view.findViewById(R.id.snapButton)

        fun bind(state: QuestState) {
            val quest = state.quest
            val context = itemView.context

            emojiText.text = quest.emoji
            titleText.text = quest.title
            descriptionText.text = quest.description
            proofText.text = context.getString(R.string.quest_proof, quest.proof)
            minutesText.text = context.getString(R.string.quest_minutes, quest.minutes)

            val place = quest.place
            placeText.text = if (place != null) {
                context.getString(R.string.quest_place, place.name, formatDistance(place.distanceM), place.direction)
            } else {
                context.getString(R.string.quest_anywhere)
            }

            directionsButton.visibility = if (place != null) View.VISIBLE else View.GONE
            directionsButton.setOnClickListener { onDirections(quest) }
            snapButton.setOnClickListener { onSnap(quest) }
            // Debug builds only: long-press to test with a gallery photo from indoors
            if (BuildConfig.DEBUG) {
                snapButton.setOnLongClickListener {
                    onPickFromGallery(quest)
                    true
                }
            }

            if (state.done) {
                actionRow.visibility = View.GONE
                doneSection.visibility = View.VISIBLE
                refereeText.text = state.message
                photoThumb.setImageBitmap(state.photoPath?.let { Photos.thumbnail(it, 160) })
                verifiedText.text = state.verifiedAt?.let { context.getString(R.string.verified_at, it) }
                verifiedText.visibility = if (state.verifiedAt != null) View.VISIBLE else View.GONE
            } else {
                actionRow.visibility = View.VISIBLE
                doneSection.visibility = View.GONE
                photoThumb.setImageDrawable(null)
            }
        }
    }

    companion object {
        fun formatDistance(meters: Int): String =
            if (meters < 1000) "$meters m" else String.format("%.1f km", meters / 1000f)
    }
}
