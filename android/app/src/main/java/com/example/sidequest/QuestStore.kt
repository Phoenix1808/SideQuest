package com.example.sidequest

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Keeps today's quests and the journal on the phone, so reopening the app
// doesn't spend another request and finished quests are never lost.
class QuestStore(context: Context) {

    private val prefs = context.getSharedPreferences("sidequest", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadBoard(): QuestBoard? =
        prefs.getString(KEY_BOARD, null)?.let { gson.fromJson(it, QuestBoard::class.java) }

    fun saveBoard(board: QuestBoard) {
        prefs.edit { putString(KEY_BOARD, gson.toJson(board)) }
    }

    fun journal(): List<JournalEntry> =
        prefs.getString(KEY_JOURNAL, null)
            ?.let { gson.fromJson(it, Array<JournalEntry>::class.java).toList() }
            .orEmpty()

    fun addToJournal(entry: JournalEntry) {
        prefs.edit { putString(KEY_JOURNAL, gson.toJson(journal() + entry)) }
    }

    // Days in a row with at least one finished quest, counting back from today
    // (or from yesterday, so the streak isn't "broken" before today's walk).
    fun streak(): Int {
        val days = journal().map { it.day }.toSet()
        val cal = Calendar.getInstance()
        if (dayKey(cal.time) !in days) cal.add(Calendar.DAY_OF_YEAR, -1)

        var streak = 0
        while (dayKey(cal.time) in days) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    companion object {
        private const val KEY_BOARD = "board"
        private const val KEY_JOURNAL = "journal"

        fun dayKey(date: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
    }
}
