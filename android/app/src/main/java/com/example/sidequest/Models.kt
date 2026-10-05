package com.example.sidequest

// Field names are camelCase here; Gson converts them to the backend's
// snake_case (distanceM <-> distance_m).

// ---------- from the backend ----------

data class QuestRequest(
    val lat: Double,
    val lon: Double,
    val tzOffsetMin: Int,   // lets the server show sunrise/sunset in local time
    val count: Int = 3,
    val radiusM: Int = 1500
)

data class Place(
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val distanceM: Int,
    val direction: String
)

data class Weather(
    val tempC: Double?,   // null when every weather service was unavailable
    val summary: String,
    val emoji: String,
    val isDay: Boolean,
    val localTime: String,
    val sunrise: String,
    val sunset: String
)

data class Quest(
    val id: String,
    val emoji: String,
    val title: String,
    val description: String,
    val proof: String,
    val minutes: Int,
    val place: Place?
)

data class QuestResponse(
    val weather: Weather?,
    val quests: List<Quest>
)

data class VerifyResponse(
    val success: Boolean,
    val caption: String,
    val message: String
)

// ---------- saved on the phone ----------

data class QuestState(
    val quest: Quest,
    val done: Boolean = false,
    val photoPath: String? = null,
    val message: String? = null,
    val verifiedAt: String? = null   // place name when GPS confirmed the player was there
)

data class QuestBoard(
    val day: String,
    val weather: Weather?,
    val quests: List<QuestState>
)

data class JournalEntry(
    val questId: String,
    val emoji: String,
    val title: String,
    val placeName: String?,
    val photoPath: String,
    val message: String,
    val day: String,
    val completedAt: Long,
    val verifiedAt: String? = null
)
