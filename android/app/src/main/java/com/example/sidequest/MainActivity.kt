package com.example.sidequest

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var journalButton: MaterialButton
    private lateinit var streakText: TextView
    private lateinit var weatherCard: MaterialCardView
    private lateinit var weatherText: TextView
    private lateinit var sunText: TextView
    private lateinit var statusView: LinearLayout
    private lateinit var statusSpinner: CircularProgressIndicator
    private lateinit var statusText: TextView
    private lateinit var statusButton: MaterialButton
    private lateinit var questList: RecyclerView
    private lateinit var newQuestsButton: MaterialButton

    private lateinit var store: QuestStore
    private val adapter = QuestAdapter(
        onDirections = ::openDirections,
        onSnap = ::snapProof,
        onPickFromGallery = ::pickProofFromGallery
    )
    private var board: QuestBoard? = null

    // The camera app may cause Android to recreate this activity, so these survive in onSaveInstanceState
    private var pendingQuestId: String? = null
    private var pendingPhotoPath: String? = null
    private var pendingVerifiedAt: String? = null
    private var askedForLocation = false

    // Asked when building quests
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) generateQuests() else showLocationNeeded()
        }

    // Asked when proving you're at a place (the permission may have been revoked since,
    // or only "approximate" was granted and we need to upgrade it to precise)
    private val proofLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val quest = pendingQuestId?.let(::questById)
            pendingQuestId = null
            if (quest != null && result.values.any { it }) {
                snapProof(quest)
            } else {
                Toast.makeText(this, R.string.need_location_for_proof, Toast.LENGTH_LONG).show()
            }
        }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val questId = pendingQuestId
        val path = pendingPhotoPath
        val verifiedAt = pendingVerifiedAt
        pendingQuestId = null
        pendingPhotoPath = null
        pendingVerifiedAt = null
        if (saved && questId != null && path != null) {
            checkPhoto(questId, File(path), verifiedAt)
        } else {
            path?.let { File(it).delete() }
        }
    }

    // Debug builds only (see QuestAdapter): lets you test the proof flow with an existing photo
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val questId = pendingQuestId
        pendingQuestId = null
        if (uri == null || questId == null) return@registerForActivityResult

        lifecycleScope.launch {
            val file = Photos.newPhotoFile(this@MainActivity)
            val copied = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } != null
            }
            if (copied) checkPhoto(questId, file, verifiedAt = null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        journalButton = findViewById(R.id.journalButton)
        streakText = findViewById(R.id.streakText)
        weatherCard = findViewById(R.id.weatherCard)
        weatherText = findViewById(R.id.weatherText)
        sunText = findViewById(R.id.sunText)
        statusView = findViewById(R.id.statusView)
        statusSpinner = findViewById(R.id.statusSpinner)
        statusText = findViewById(R.id.statusText)
        statusButton = findViewById(R.id.statusButton)
        questList = findViewById(R.id.questList)
        newQuestsButton = findViewById(R.id.newQuestsButton)

        store = QuestStore(this)
        questList.layoutManager = LinearLayoutManager(this)
        questList.adapter = adapter

        savedInstanceState?.let {
            pendingQuestId = it.getString(KEY_PENDING_QUEST)
            pendingPhotoPath = it.getString(KEY_PENDING_PHOTO)
            pendingVerifiedAt = it.getString(KEY_PENDING_VERIFIED)
            askedForLocation = it.getBoolean(KEY_ASKED_LOCATION)
        }

        journalButton.setOnClickListener { startActivity(Intent(this, JournalActivity::class.java)) }
        newQuestsButton.setOnClickListener { confirmNewQuests() }

        // Today's quests are kept, so reopening the app doesn't replace them
        val saved = store.loadBoard()
        if (saved != null && saved.day == QuestStore.dayKey()) showBoard(saved) else startQuestFlow()
    }

    override fun onResume() {
        super.onResume()
        updateStreak()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_QUEST, pendingQuestId)
        outState.putString(KEY_PENDING_PHOTO, pendingPhotoPath)
        outState.putString(KEY_PENDING_VERIFIED, pendingVerifiedAt)
        outState.putBoolean(KEY_ASKED_LOCATION, askedForLocation)
    }

    // ---------- getting quests ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startQuestFlow() {
        if (hasLocationPermission()) {
            generateQuests()
        } else {
            askedForLocation = true
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun showLocationNeeded() {
        showStatus(getString(R.string.need_location), loading = false, buttonText = getString(R.string.grant_location)) {
            val blocked = askedForLocation &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (blocked) {
                // Android won't ask again after "Don't allow" twice - send them to app settings
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } else {
                startQuestFlow()
            }
        }
    }

    private fun generateQuests() {
        newQuestsButton.isEnabled = false
        showStatus(getString(R.string.finding_location), loading = true)

        lifecycleScope.launch {
            try {
                val location = LocationHelper.current(this@MainActivity)
                if (location == null) {
                    showStatus(getString(R.string.location_unavailable), loading = false, buttonText = getString(R.string.retry)) {
                        startQuestFlow()
                    }
                    return@launch
                }

                showStatus(getString(R.string.making_quests), loading = true)
                val response = ApiClient.quests(QuestRequest(location.latitude, location.longitude))
                val newBoard = QuestBoard(QuestStore.dayKey(), response.weather, response.quests.map { QuestState(it) })
                store.saveBoard(newBoard)
                showBoard(newBoard)
            } catch (e: ApiException) {
                showStatus(e.message.orEmpty(), loading = false, buttonText = getString(R.string.retry)) {
                    startQuestFlow()
                }
            } finally {
                newQuestsButton.isEnabled = true
            }
        }
    }

    private fun confirmNewQuests() {
        val unfinished = board?.quests?.any { !it.done } ?: false
        if (!unfinished) {
            startQuestFlow()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_quests_title)
            .setMessage(R.string.new_quests_message)
            .setPositiveButton(R.string.replace) { _, _ -> startQuestFlow() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- showing things ----------

    private fun showBoard(newBoard: QuestBoard) {
        board = newBoard
        statusView.visibility = View.GONE
        questList.visibility = View.VISIBLE
        adapter.submit(newBoard.quests)

        val weather = newBoard.weather
        if (weather != null) {
            weatherText.text = getString(R.string.weather_line, weather.emoji, weather.tempC, weather.summary)
            sunText.text = getString(R.string.sun_line, weather.sunrise, weather.sunset)
            weatherCard.visibility = View.VISIBLE
        } else {
            weatherCard.visibility = View.GONE
        }
    }

    private fun showStatus(message: String, loading: Boolean, buttonText: String? = null, onButton: (() -> Unit)? = null) {
        questList.visibility = View.GONE
        statusView.visibility = View.VISIBLE
        statusSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        statusText.text = message
        if (buttonText != null && onButton != null) {
            statusButton.text = buttonText
            statusButton.setOnClickListener { onButton() }
            statusButton.visibility = View.VISIBLE
        } else {
            statusButton.visibility = View.GONE
        }
    }

    private fun updateStreak() {
        val done = store.journal().size
        streakText.text = if (done == 0) {
            getString(R.string.streak_none)
        } else {
            getString(R.string.streak_summary, store.streak(), done)
        }
    }

    // ---------- quest actions ----------

    private fun openDirections(quest: Quest) {
        val place = quest.place ?: return
        val uri = Uri.parse("geo:${place.lat},${place.lon}?q=${place.lat},${place.lon}(${Uri.encode(place.name)})")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun questById(id: String): Quest? = board?.quests?.find { it.quest.id == id }?.quest

    // Quests tied to a place only open the camera once GPS confirms you're there.
    private fun snapProof(quest: Quest) {
        val place = quest.place
        if (place == null) {
            openCamera(quest, verifiedAt = null)   // "anywhere nearby" quests
            return
        }

        // Permissions can be revoked in Settings at any time, so check right before using them
        if (!hasLocationPermission()) {
            pendingQuestId = quest.id
            proofLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        if (!LocationHelper.isLocationOn(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.location_off_title)
                .setMessage(getString(R.string.location_off_message, place.name))
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.checking_location, place.name))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val here = LocationHelper.fresh(this@MainActivity)
            dialog.dismiss()

            when (val result = Geofence.check(here, place)) {
                Geofence.Result.Arrived -> openCamera(quest, verifiedAt = place.name)

                is Geofence.Result.TooFar -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.too_far_title)
                    .setMessage(
                        getString(R.string.too_far_message, QuestAdapter.formatDistance(result.meters), place.name)
                    )
                    .setPositiveButton(R.string.directions) { _, _ -> openDirections(quest) }
                    .setNegativeButton(R.string.ok, null)
                    .show()

                // Asking for FINE again makes Android 12+ offer "change to precise location"
                Geofence.Result.Imprecise -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.imprecise_title)
                    .setMessage(getString(R.string.imprecise_message, place.name))
                    .setPositiveButton(R.string.allow_precise) { _, _ ->
                        pendingQuestId = quest.id
                        proofLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()

                Geofence.Result.NoFix -> showResult(getString(R.string.no_fix_title), getString(R.string.no_fix_message))
            }
        }
    }

    private fun openCamera(quest: Quest, verifiedAt: String?) {
        val file = Photos.newPhotoFile(this)
        pendingQuestId = quest.id
        pendingPhotoPath = file.path
        pendingVerifiedAt = verifiedAt
        try {
            takePicture.launch(Photos.uriFor(this, file))
        } catch (e: ActivityNotFoundException) {
            pendingQuestId = null
            pendingPhotoPath = null
            pendingVerifiedAt = null
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickProofFromGallery(quest: Quest) {
        pendingQuestId = quest.id
        Toast.makeText(this, "Debug: pick a photo to test this quest", Toast.LENGTH_SHORT).show()
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun checkPhoto(questId: String, file: File, verifiedAt: String?) {
        val state = board?.quests?.find { it.quest.id == questId } ?: return
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.checking_photo)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { Photos.shrink(file) }
                val result = ApiClient.verify(bytes, state.quest.title, state.quest.proof)
                dialog.dismiss()
                if (result.success) {
                    completeQuest(state, file, result, verifiedAt)
                    showResult(getString(R.string.success_title), result.message)
                } else {
                    file.delete()
                    showRetry(state.quest, result.message)
                }
            } catch (e: ApiException) {
                dialog.dismiss()
                Snackbar.make(root, e.message.orEmpty(), Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.retry) { checkPhoto(questId, file, verifiedAt) }
                    .show()
            }
        }
    }

    private fun completeQuest(state: QuestState, file: File, result: VerifyResponse, verifiedAt: String?) {
        val current = board ?: return
        val updated = current.copy(quests = current.quests.map {
            if (it.quest.id == state.quest.id) {
                it.copy(done = true, photoPath = file.path, message = result.message, verifiedAt = verifiedAt)
            } else {
                it
            }
        })
        store.saveBoard(updated)
        showBoard(updated)

        store.addToJournal(
            JournalEntry(
                questId = state.quest.id,
                emoji = state.quest.emoji,
                title = state.quest.title,
                placeName = state.quest.place?.name,
                photoPath = file.path,
                message = result.message,
                day = QuestStore.dayKey(),
                completedAt = System.currentTimeMillis(),
                verifiedAt = verifiedAt
            )
        )
        updateStreak()
    }

    private fun showResult(title: String, message: String): AlertDialog =
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.nice, null)
            .show()

    private fun showRetry(quest: Quest, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fail_title)
            .setMessage(message)
            .setPositiveButton(R.string.try_again) { _, _ -> snapProof(quest) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    companion object {
        private const val KEY_PENDING_QUEST = "pending_quest"
        private const val KEY_PENDING_PHOTO = "pending_photo"
        private const val KEY_PENDING_VERIFIED = "pending_verified"
        private const val KEY_ASKED_LOCATION = "asked_location"
    }
}
