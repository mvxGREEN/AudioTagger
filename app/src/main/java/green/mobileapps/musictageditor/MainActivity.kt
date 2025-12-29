package green.mobileapps.musictageditor

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay // ADDED: Import delay for timing fix
import kotlin.coroutines.CoroutineContext
import androidx.activity.addCallback // Import for new back press handling
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import green.mobileapps.musictageditor.databinding.ItemMusicFileBinding
import green.mobileapps.musictageditor.databinding.MainActivityBinding
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import kotlin.apply
import kotlin.collections.filter
import kotlin.collections.find
import kotlin.collections.getOrNull
import kotlin.collections.indexOfFirst
import kotlin.collections.isNotEmpty
import kotlin.collections.orEmpty
import kotlin.collections.reversed
import kotlin.collections.sortedWith
import kotlin.collections.toMutableList
import kotlin.io.use
import kotlin.jvm.java
import kotlin.text.CASE_INSENSITIVE_ORDER
import kotlin.text.contains
import kotlin.text.isBlank
import kotlin.text.lowercase
import kotlin.text.orEmpty
import kotlin.text.trim
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.isActive

// REMOVED: import com.bumptech.glide.load.resource.bitmap.VideoDecoder // Removed unresolvable import

// --- SORTING DEFINITIONS ---
enum class SortBy { DATE_ADDED, TITLE, ARTIST, ALBUM, DURATION }
data class SortState(val by: SortBy, val ascending: Boolean)
// ---------------------------

// --- NEW: Interface for Adapter/Activity communication for editing ---
interface MusicEditListener {
    /**
     * Called by the ViewHolder on long click to initiate editing mode.
     * @param position The adapter position of the item.
     */
    fun startEditing(position: Int)

    /**
     * Called by the ViewHolder's save button or on click outside event.
     * @param audioFile The original AudioFile object.
     * @param newTitle The new title value from the EditText.
     * @param newArtist The new artist value from the EditText.
     */
    fun saveEditAndExit(audioFile: AudioFile, newTitle: String, newArtist: String)
}
// -------------------------------------------------------------------

// Helper extension function to safely get a string from a cursor
private fun Cursor.getNullableString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getString(index) else null
}

// Helper extension function to safely get a long from a cursor
private fun Cursor.getNullableLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getLong(index) else null
}

// Helper extension function to safely get an int from a cursor
private fun Cursor.getNullableInt(columnName: String): Int? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getInt(index) else null
}

// Helper extension function to safely get a boolean from a cursor (converts 0/1 to Boolean)
private fun Cursor.getNullableBoolean(columnName: String): Boolean? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getInt(index) == 1 else null
}

data class AudioFile(
    // Core fields
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val duration: Long,

    // Album Art Metadata (NEW)
    val albumId: Long?,

    // Media Store Metadata
    val album: String?,
    val albumArtist: String?,
    val author: String?,
    val composer: String?,
    val track: Int?,
    val year: Int?,
    val genre: String?,

    // File Metadata
    val size: Long?,
    val dateAdded: Long?,
    val dateModified: Long?,

    // Playback Metadata
    val bookmark: Long?,
    val sampleRate: Int?,
    val bitrate: Int?,
    val bitsPerSample: Int?,

    // Classification Flags (Boolean)
    val isAudiobook: Boolean,
    val isMusic: Boolean,
    val isPodcast: Boolean,
    val isRecording: Boolean,
    val isAlarm: Boolean,
    val isNotification: Boolean,
    val isRingtone: Boolean,

    // Status Flags (Boolean)
    val isDownload: Boolean,
    val isDrm: Boolean,
    val isFavorite: Boolean,
    val isPending: Boolean,
    val isTrashed: Boolean
) : Parcelable {

    // Parcelable implementation boilerplate
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readParcelable(Uri::class.java.classLoader)!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readLong(),
        parcel.readValue(Long::class.java.classLoader) as? Long, // albumId
        parcel.readString(),
        parcel.readString(),
        parcel.readString(),
        parcel.readString(),
        parcel.readValue(Int::class.java.classLoader) as? Int,
        parcel.readValue(Int::class.java.classLoader) as? Int,
        parcel.readString(),
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(Int::class.java.classLoader) as? Int,
        parcel.readValue(Int::class.java.classLoader) as? Int,
        parcel.readValue(Int::class.java.classLoader) as? Int,
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeParcelable(uri, flags)
        parcel.writeString(title)
        parcel.writeString(artist)
        parcel.writeLong(duration)
        parcel.writeValue(albumId)
        parcel.writeString(album)
        parcel.writeString(albumArtist)
        parcel.writeString(author)
        parcel.writeString(composer)
        parcel.writeValue(track)
        parcel.writeValue(year)
        parcel.writeString(genre)
        parcel.writeValue(size)
        parcel.writeValue(dateAdded)
        parcel.writeValue(dateModified)
        parcel.writeValue(bookmark)
        parcel.writeValue(sampleRate)
        parcel.writeValue(bitrate)
        parcel.writeValue(bitsPerSample)
        parcel.writeByte(if (isAudiobook) 1 else 0)
        parcel.writeByte(if (isMusic) 1 else 0)
        parcel.writeByte(if (isPodcast) 1 else 0)
        parcel.writeByte(if (isRecording) 1 else 0)
        parcel.writeByte(if (isAlarm) 1 else 0)
        parcel.writeByte(if (isNotification) 1 else 0)
        parcel.writeByte(if (isRingtone) 1 else 0)
        parcel.writeByte(if (isDownload) 1 else 0)
        parcel.writeByte(if (isDrm) 1 else 0)
        parcel.writeByte(if (isFavorite) 1 else 0)
        parcel.writeByte(if (isPending) 1 else 0)
        parcel.writeByte(if (isTrashed) 1 else 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<AudioFile> {
        override fun createFromParcel(parcel: Parcel): AudioFile {
            return AudioFile(parcel)
        }

        override fun newArray(size: Int): Array<AudioFile?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * Constructs the Uri for the album art image given the album ID.
 */
fun getAlbumArtUri(albumId: Long): Uri {
    return ContentUris.withAppendedId(
        "content://media/external/audio/albumart".toUri(),
        albumId
    )
}

// 1. Playlist Repository (Singleton) - Acts as the persistent store
// This is accessible by the Service, ViewModel, and Activities.
object PlaylistRepository {
    private val _audioFiles = MutableLiveData<List<AudioFile>>(emptyList())
    val audioFiles: LiveData<List<AudioFile>> = _audioFiles

    // Store the last clicked/currently playing index in the full list
    var currentTrackIndex: Int = -1

    fun setFiles(files: List<AudioFile>) {
        _audioFiles.postValue(files)
    }

    // NEW: Function to update a single file in the repository's list
    fun updateFile(updatedFile: AudioFile) {
        val currentList = _audioFiles.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedFile.id }
        if (index != -1) {
            currentList[index] = updatedFile
            _audioFiles.postValue(currentList)
            Log.d("Repository", "Updated file with ID: ${updatedFile.id}")
        } else {
            Log.w("Repository", "File with ID ${updatedFile.id} not found for update.")
        }
    }

    // Utility function for the service to get the current track
    fun getCurrentTrack(): AudioFile? {
        val list = _audioFiles.value
        return if (list != null && currentTrackIndex >= 0 && currentTrackIndex < list.size) {
            list[currentTrackIndex]
        } else {
            null
        }
    }

    // Utility function for the service to get the full list
    fun getFullPlaylist(): List<AudioFile> = _audioFiles.value ?: emptyList()
}


// 2. Music ViewModel - Used by MainActivity to load and filter the data
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    // The full, unfiltered list from the repository
    private val fullAudioList: LiveData<List<AudioFile>> = PlaylistRepository.audioFiles

    // The list maintained for filtering and sorting purposes (displayed in RecyclerView)
    private var musicListFull: List<AudioFile> = emptyList()
    private val _filteredList = MutableLiveData<List<AudioFile>>(emptyList())
    val filteredList: LiveData<List<AudioFile>> = _filteredList

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    // NEW: LiveData to indicate loading state
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // NEW: LiveData to hold the current search query
    private var currentQuery: String = ""

    private val _sortState = MutableLiveData(SortState(SortBy.DATE_ADDED, false))
    val sortState: LiveData<SortState> = _sortState

    // Coroutine setup
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    init {
        // Observe the repository's full list to manage internal list and update filtered list
        fullAudioList.observeForever { newList ->
            musicListFull = newList
            // When the full list is updated, apply the current sort and filter
            applySortAndFilter()
        }
    }

    fun loadAudioFiles(context: Context) {
        if (_isLoading.value == true) return // Prevent multiple simultaneous scans

        _isLoading.postValue(true)
        _statusMessage.postValue("Scanning for audio files...")

        scope.launch {
            val audioList = loadAudioFilesFromStorage(context)
            PlaylistRepository.setFiles(audioList) // Update the repository

            if (audioList.isEmpty()) {
                _statusMessage.postValue("No audio files found. Ensure you have MP3s in your music folder.")
            } else {
                // The filteredList observer handles the UI update
                _statusMessage.postValue("Loaded ${audioList.size} tracks.")
            }

            _isLoading.postValue(false)
        }
    }

    // Function to load audio files using ContentResolver (unchanged)
    private fun loadAudioFilesFromStorage(context: Context): List<AudioFile> {
        val files = mutableListOf<AudioFile>()
        val contentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        // Define ALL columns we want to retrieve
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,

            // Requested Fields:
            MediaStore.Audio.Media.BOOKMARK,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.SAMPLERATE,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.BITS_PER_SAMPLE,
            MediaStore.Audio.Media.IS_AUDIOBOOK,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_PODCAST,
            MediaStore.Audio.Media.IS_RECORDING,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.AUTHOR,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_DOWNLOAD,
            MediaStore.Audio.Media.IS_DRM,
            MediaStore.Audio.Media.IS_FAVORITE,
            MediaStore.Audio.Media.IS_PENDING,
            MediaStore.Audio.Media.IS_TRASHED,
            MediaStore.MediaColumns.MIME_TYPE // Include MIME_TYPE
        )

        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"

        contentResolver.query(
            uri,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->

            while (cursor.moveToNext()) {
                // --- Core Fields ---
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                val title = cursor.getNullableString(MediaStore.Audio.Media.TITLE) ?: "Unknown Title"
                val artist = cursor.getNullableString(MediaStore.Audio.Media.ARTIST) ?: "Unknown Artist"
                val duration = cursor.getNullableLong(MediaStore.Audio.Media.DURATION) ?: 0L
                val albumId = cursor.getNullableLong(MediaStore.Audio.Media.ALBUM_ID)

                val contentUri: Uri = Uri.withAppendedPath(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )

                // --- Extended Metadata Extraction using safe helpers ---
                val album = cursor.getNullableString(MediaStore.Audio.Media.ALBUM)
                val albumArtist = cursor.getNullableString(MediaStore.Audio.Media.ALBUM_ARTIST)
                val author = cursor.getNullableString(MediaStore.Audio.Media.AUTHOR)
                val composer = cursor.getNullableString(MediaStore.Audio.Media.COMPOSER)
                val track = cursor.getNullableInt(MediaStore.Audio.Media.TRACK)
                val year = cursor.getNullableInt(MediaStore.Audio.Media.YEAR)
                val genre = cursor.getNullableString(MediaStore.Audio.Media.GENRE)

                val size = cursor.getNullableLong(MediaStore.Audio.Media.SIZE)
                val dateAdded = cursor.getNullableLong(MediaStore.Audio.Media.DATE_ADDED)
                val dateModified = cursor.getNullableLong(MediaStore.Audio.Media.DATE_MODIFIED)

                val bookmark = cursor.getNullableLong(MediaStore.Audio.Media.BOOKMARK)
                val sampleRate = cursor.getNullableInt(MediaStore.Audio.Media.SAMPLERATE)
                val bitrate = cursor.getNullableInt(MediaStore.Audio.Media.BITRATE)
                val bitsPerSample = cursor.getNullableInt(MediaStore.Audio.Media.BITS_PER_SAMPLE)

                // Classification Flags (Defaulting to false if column is missing)
                val isAudiobook = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_AUDIOBOOK) ?: false
                val isMusic = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_MUSIC) ?: false
                val isPodcast = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_PODCAST) ?: false
                val isRecording = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_RECORDING) ?: false
                val isAlarm = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_ALARM) ?: false
                val isNotification = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_NOTIFICATION) ?: false
                val isRingtone = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_RINGTONE) ?: false

                // Status Flags (Defaulting to false if column is missing/for older APIs)
                val isDownload = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_DOWNLOAD) ?: false
                val isDrm = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_DRM) ?: false
                val isFavorite = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_FAVORITE) ?: false
                val isPending = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_PENDING) ?: false
                val isTrashed = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_TRASHED) ?: false


                // Add only files with a reasonable duration (e.g., over 30 seconds)
                if (duration > 30000
                    && album != null // Add null check for album
                    && !album.contains("Voice Recorder")
                    && !isRecording
                    && !isRingtone
                    && !isAlarm
                    && !isNotification) {
                    files.add(
                        AudioFile(
                            id, contentUri, title, artist, duration, albumId,
                            album, albumArtist, author, composer, track, year, genre,
                            size, dateAdded, dateModified,
                            bookmark, sampleRate, bitrate, bitsPerSample,
                            isAudiobook, isMusic, isPodcast, isRecording, isAlarm, isNotification, isRingtone,
                            isDownload, isDrm, isFavorite, isPending, isTrashed
                        )
                    )
                }
            }
        }
        return files
    }

    /**
     * Applies the current search query and the current sort state to the full list.
     */
    fun applySortAndFilter() {
        val sortedList = applySort(musicListFull, _sortState.value!!)
        val filteredList = applyFilter(sortedList, currentQuery)
        _filteredList.value = filteredList
    }

    /**
     * Updates the current search query and applies sort/filter.
     */
    fun filterList(query: String) {
        currentQuery = query
        applySortAndFilter()
    }

    /**
     * Updates the sort state and reapplies sort/filter.
     */
    fun setSortState(newSortState: SortState) {
        _sortState.value = newSortState
        applySortAndFilter()
    }

    /**
     * Toggles the ascending/descending state and reapplies sort/filter.
     */
    fun toggleSortDirection() {
        val current = _sortState.value ?: SortState(SortBy.DATE_ADDED, false)
        val newDirection = !current.ascending
        _sortState.value = current.copy(ascending = newDirection)
        applySortAndFilter()
    }

    private fun applyFilter(list: List<AudioFile>, query: String): List<AudioFile> {
        val lowerCaseQuery = query.lowercase()
        return if (lowerCaseQuery.isBlank()) {
            list
        } else {
            list.filter {
                it.title.lowercase().contains(lowerCaseQuery) ||
                        it.artist.lowercase().contains(lowerCaseQuery)
            }
        }
    }

    private fun applySort(list: List<AudioFile>, state: SortState): List<AudioFile> {
        val comparator: Comparator<AudioFile> = when (state.by) {
            SortBy.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortBy.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
            // Nullable strings need a null-safe comparison, using compareBy for default ordering
            SortBy.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.album ?: "" }
            SortBy.DURATION -> compareBy { it.duration }
            SortBy.DATE_ADDED -> compareBy { it.dateAdded ?: 0L }
        }

        val sortedList = list.sortedWith(comparator)

        // Apply the direction based on the current state
        return if (state.ascending) sortedList else sortedList.reversed()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}


// recyclerview adapter to display the list of files
class MusicAdapter(private val activity: MainActivity, private var musicList: List<AudioFile>, private val editListener: MusicEditListener) :
    RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    private val imageCache = mutableMapOf<Long, ByteArray?>()

    // NEW: Property to track which item is currently in edit mode.
    private var editingPosition: Int = RecyclerView.NO_POSITION

    fun setEditingPosition(newPosition: Int) {
        val oldPosition = editingPosition
        editingPosition = newPosition
        // We only notify if the new position is different or if the old position was valid.
        if (oldPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(oldPosition)
        }
        if (newPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(newPosition)
        }
    }

    suspend fun getEmbeddedPicture(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture // This returns the raw byte array of the image
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    // NEW: Get the currently editing position
    fun getEditingPosition(): Int = editingPosition

    inner class MusicViewHolder(private val binding: ItemMusicFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        var job: Job? = null

        fun bind(file: AudioFile, index: Int) {
            // 1. CANCEL the previous job immediately so it doesn't "shuffle" art
            job?.cancel()

            // alternate background colors
            if (index % 2 == 0) {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context,
                    R.color.light_gray));
            } else {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context,
                    R.color.white));
                binding.textTitle.alpha = 0.95f;
                binding.textArtist.alpha = 0.95f;
            }

            val isEditing = adapterPosition == editingPosition

            // --- Set Text Content (Display or Edit) ---
            val fullTitleText = file.title // Title now only contains the raw title, no prefix

            // UNDO MODIFICATION: Re-include the album name in the artist text
            val albumInfo = if (file.album != null) " • ${file.album}" else ""
            val fullArtistText = "${file.artist}$albumInfo"

            // 1. Display/Edit View Setup
            if (isEditing) {
                // Set EditTexts visible and TextViews GONE
                binding.textTitle.visibility = View.GONE
                binding.textArtist.visibility = View.GONE
                binding.editTextTitle.visibility = View.VISIBLE
                binding.editTextArtist.visibility = View.VISIBLE
                binding.buttonSaveEdit.visibility = View.VISIBLE

                // Set initial text for editing, which uses the raw title
                binding.editTextTitle.setText(file.title)
                binding.editTextArtist.setText(file.artist)

                // Request focus and show keyboard
                binding.editTextTitle.requestFocus()
                (itemView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(binding.editTextTitle, InputMethodManager.SHOW_IMPLICIT)
            } else {
                // Set TextViews visible and EditTexts GONE
                binding.textTitle.visibility = View.VISIBLE
                binding.textArtist.visibility = View.VISIBLE
                binding.editTextTitle.visibility = View.GONE
                binding.editTextArtist.visibility = View.GONE
                binding.buttonSaveEdit.visibility = View.GONE

                // Set display text
                binding.textTitle.text = fullTitleText
                // RESTORED: Setting artist and album name here
                binding.textArtist.text = fullArtistText
            }

            val cacheKey = "${file.id}_${file.dateModified}"
            val isProblematic = file.album?.lowercase() == "music"
                    || file.album?.lowercase() == "documents"
                    || file.albumId == 553547078986512838L
                    || file.artist.lowercase() == "<unknown>"

            if (isProblematic) {
                val cachedBytes = imageCache[file.id]

                if (cachedBytes != null) {
                    // INSTANT LOAD: No clearing, no placeholder needed, no flicker
                    Glide.with(itemView.context)
                        .load(cachedBytes)
                        .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
                        .transform(CircleCrop())
                        .dontAnimate()
                        .into(binding.imageAlbumArt)
                } else {
                    // FIRST LOAD: Clear and show placeholder to avoid shuffling
                    Glide.with(itemView.context).clear(binding.imageAlbumArt)
                    binding.imageAlbumArt.setImageResource(R.drawable.default_album_art_144px)

                    job = activity.lifecycleScope.launch {
                        val imageBytes = getEmbeddedPicture(itemView.context, file.uri)
                        if (imageBytes != null) {
                            imageCache[file.id] = imageBytes // Save to cache for next time
                        }

                        if (isActive) {
                            Glide.with(itemView.context)
                                .load(imageBytes)
                                .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
                                .transform(CircleCrop())
                                .placeholder(R.drawable.default_album_art_144px)
                                .dontAnimate()
                                .into(binding.imageAlbumArt)
                        }
                    }
                }
            } else {
                // Standard MediaStore loading (already fast/cached by system)
                Glide.with(itemView.context)
                    .load(getAlbumArtUri(file.albumId!!))
                    .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
                    .transform(CircleCrop())
                    .placeholder(R.drawable.default_album_art_144px)
                    .dontAnimate()
                    .into(binding.imageAlbumArt)
            }

            binding.root.setOnClickListener {
                if (!isEditing) {
                    // Option A: Open Player (Your existing code)
                    // activity.startMusicPlayback(file, adapterPosition)

                    // Option B: Open Tag Editor
                    activity.openTagEditor(file)
                }
            }

            // NEW: Save button click listener
            binding.buttonSaveEdit.setOnClickListener {
                val newTitle = binding.editTextTitle.text.toString().trim()
                val newArtist = binding.editTextArtist.text.toString().trim()
                editListener.saveEditAndExit(file, newTitle, newArtist)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val binding = ItemMusicFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MusicViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        holder.bind(musicList[position], position)
    }

    override fun getItemCount(): Int = musicList.size

    fun updateList(newList: List<AudioFile>) {
        newList.forEach { newFile ->
            val oldFile = musicList.find { it.id == newFile.id }
            if (oldFile != null && oldFile.dateModified != newFile.dateModified) {
                // This is the file that was just edited!
                // Remove it so it's forced to reload from the MediaMetadataRetriever
                imageCache.remove(newFile.id)
            }
        }

        val diffCallback = object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = musicList.size
            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                // Check if it's the same file by ID
                return musicList[oldPos].id == newList[newPos].id
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldItem = musicList[oldPos]
                val newItem = newList[newPos]
                // If dateModified changes, this returns false, and the row is re-drawn
                return oldItem.title == newItem.title &&
                        oldItem.artist == newItem.artist &&
                        oldItem.dateModified == newItem.dateModified
            }
        }

        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        musicList = newList
        diffResult.dispatchUpdatesTo(this)
    }

    // Kept for startMusicPlayback but now unnecessary if we use the Repository directly
    fun getCurrentList(): List<AudioFile> = musicList

}

// 4. Main Activity with Permission and Scanning Logic
class MainActivity : AppCompatActivity(), CoroutineScope, SearchView.OnQueryTextListener, MusicEditListener {

    // Coroutine setup
    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var binding: MainActivityBinding
    private lateinit var viewModel: MusicViewModel // New ViewModel instance

    // Adapter needs access to the activity, so it must be initialized later
    public lateinit var musicAdapter: MusicAdapter
    private lateinit var sortButton: ImageButton // Reference to the new sort criterion button
    private lateinit var sortDirectionButton: ImageButton // Reference to the new sort direction button
    private lateinit var backButton: ImageButton // NEW: Reference to the new back button

    // REMOVED: currentlyEditingItem is no longer needed since we are not using dispatchTouchEvent

    // Determine the correct permission based on Android version
    private val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    // For API 33+ (Android 13) we need to request POST_NOTIFICATIONS
    private val notificationPermission = Manifest.permission.POST_NOTIFICATIONS

    // Register the permission request contract for notification permission
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            println("Notification permission granted.")
        } else {
            println("Notification permission denied. Media controls won't be visible in status bar.")
        }
        // Always proceed to scan regardless of notification permission outcome
        viewModel.loadAudioFiles(applicationContext) // Call ViewModel to scan files
    }

    private val requestWritePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Permission granted, now save the data (which is stored in temp properties)
            executePendingMetadataUpdate()
        } else {
            Toast.makeText(this, "Permission to modify file denied. Exiting editor.", Toast.LENGTH_SHORT).show()
            // Exit editing mode gracefully without saving
            exitEditingMode()
        }
    }

    // NEW: Temporary storage for the data to be saved after permission is granted
    private var pendingUpdateFile: AudioFile? = null
    private var pendingUpdateTitle: String? = null
    private var pendingUpdateArtist: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize ViewModel
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )
            .get(MusicViewModel::class.java)

        // NEW: Initialize the sort buttons from the toolbar's ConstraintLayout
        val toolbarLayout = binding.toolbarSearch.getChildAt(0) as? ViewGroup
        sortButton = toolbarLayout?.findViewById(R.id.button_sort) ?: throw kotlin.IllegalStateException(
            "Sort button not found in toolbar layout"
        )
        // NEW: Initialize the direction button
        sortDirectionButton = toolbarLayout.findViewById(R.id.button_sort_direction) ?: throw kotlin.IllegalStateException(
            "Sort direction button not found in toolbar layout"
        )
        // NEW: Initialize the back button
        backButton = toolbarLayout.findViewById(R.id.button_back_edit) ?: throw kotlin.IllegalStateException(
            "Back button not found in toolbar layout"
        )


        setupRecyclerView()
        setupSearchView()
        setupSortButton() // Setup the sort criterion button logic
        setupSortDirectionButton() // NEW: Setup the sort direction toggle logic
        setupBackButton() // NEW: Setup the back button logic for edit mode
        setupSystemBackPressHandler() // NEW: Setup system back press handler
        setupSwipeRefresh()
        setupObservers()
        checkPermissions()
    }

    // REMOVED: isTouchInsideView and dispatchTouchEvent logic

    // NEW: Function to setup the back button (in-toolbar) logic
    private fun setupBackButton() {
        backButton.setOnClickListener {
            // On back click, treat it as an implicit cancel/exit attempt, saving if changes were made.
            handleExitEditMode()
        }
    }

    // NEW: Function to handle system back presses
    private fun setupSystemBackPressHandler() {
        onBackPressedDispatcher.addCallback(this) {
            if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) {
                // We are in edit mode, handle the back press by saving/exiting
                handleExitEditMode()
            } else {
                // Not in edit mode, proceed with default back behavior (closing app/activity)
                isEnabled = false // Disable this callback temporarily
                onBackPressedDispatcher.onBackPressed() // Call system back
                isEnabled = true // Re-enable for future use
            }
        }
    }

    // NEW: Unified function to trigger the save/exit logic
    private fun handleExitEditMode() {
        val position = musicAdapter.getEditingPosition()
        if (position != RecyclerView.NO_POSITION) {
            // Get data from the view holder that is currently being edited
            val viewHolder = binding.recyclerViewMusic.findViewHolderForAdapterPosition(position) as? MusicAdapter.MusicViewHolder
            val file = musicAdapter.getCurrentList().getOrNull(position)
            val titleEditText = viewHolder?.itemView?.findViewById<EditText>(R.id.edit_text_title)
            val artistEditText = viewHolder?.itemView?.findViewById<EditText>(R.id.edit_text_artist)

            if (file != null && titleEditText != null && artistEditText != null) {
                // Pass the current EditText values to the save logic
                saveEditAndExit(file, titleEditText.text.toString().trim(), artistEditText.text.toString().trim())
            } else {
                // If we can't get the data (e.g., view recycled), just exit editing mode
                exitEditingMode()
            }
        }
    }


    // NEW: Function to setup the sort direction toggle button
    private fun setupSortDirectionButton() {
        sortDirectionButton.setOnClickListener {
            // Toggle the direction state in the ViewModel
            viewModel.toggleSortDirection()
        }
    }

    // UPDATED: Function to setup the sort criterion button and its menu
    private fun setupSortButton() {
        sortButton.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.apply {
                // Add sorting criteria (without direction toggles)
                add(0, SortBy.DATE_ADDED.ordinal, 0, "Date Added (Default)")
                add(0, SortBy.TITLE.ordinal, 1, "Title")
                add(0, SortBy.ARTIST.ordinal, 2, "Artist")
                add(0, SortBy.ALBUM.ordinal, 3, "Album")
                add(0, SortBy.DURATION.ordinal, 4, "Duration")
            }

            popup.setOnMenuItemClickListener { item: MenuItem ->
                val currentSortState = viewModel.sortState.value ?: SortState(SortBy.DATE_ADDED, false)
                val sortCriterion = SortBy.entries.find { it.ordinal == item.itemId }

                if (sortCriterion != null) {
                    // Criterion change logic:
                    // Only change the criterion. Keep the existing direction.
                    // If the criterion is the same, toggle the direction automatically as a shortcut.
                    val newAscending = if (sortCriterion == currentSortState.by) {
                        !currentSortState.ascending // Toggle direction if same is clicked
                    } else {
                        // Keep current direction if new criterion is selected, or use a default if desired
                        currentSortState.ascending
                    }

                    viewModel.setSortState(SortState(sortCriterion, newAscending))
                    true
                } else {
                    false
                }
            }
            popup.show()
        }

        // Observe the sort state to dynamically update the direction button icon
        viewModel.sortState.observe(this) { state ->
            val iconResId = if (state.ascending) {
                // Assuming R.drawable.ascending_24px exists
                R.drawable.ascending_24px
            } else {
                // Assuming R.drawable.descending_24px exists
                R.drawable.descending_24px
            }
            sortDirectionButton.setImageResource(iconResId)
        }
    }

    // NEW: Override onResume to ensure focus/keyboard are hidden when returning
    override fun onResume() {
        super.onResume()
        // Ensure the keyboard is hidden and focus is cleared when returning to the activity
        hideKeyboardAndClearFocus()
        // Ensure editing mode is reset if activity was paused during edit
        if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) {
            // Call exitEditingMode() without save logic if resumed from pause
            exitEditingMode()
        }
    }

    private fun setupObservers() {
        // Observe the filtered list and update the adapter
        viewModel.filteredList.observe(this) { audioList ->
            if (audioList.isNotEmpty()) {
                musicAdapter.updateList(audioList)
                binding.recyclerViewMusic.visibility = View.VISIBLE
                binding.textStatus.visibility = View.GONE
            } else if (PlaylistRepository.getFullPlaylist().isNotEmpty()) {
                // List is filtered to empty, but the full list exists
                musicAdapter.updateList(emptyList())
                showStatus("No tracks match your search.")
            }
        }

        // Observe status messages (e.g., scanning, permission denied)
        viewModel.statusMessage.observe(this) { message ->
            // Update status UI if needed
            if (!message.contains("Loaded")) {
                showStatus(message)
            }
        }

        // NEW: Observe loading state to control the refresh indicator
        viewModel.isLoading.observe(this) { isLoading ->
            // Assuming your layout binding has a property named `swipeRefreshLayout`
            binding.swipeRefreshLayout.isRefreshing = isLoading
        }

        // Observe status messages (e.g., scanning, permission denied)
        viewModel.statusMessage.observe(this) { message ->
            // Update status UI if needed
            if (!message.contains("Loaded")) {
                showStatus(message)
            }
        }
    }

    private fun setupRecyclerView() {
        // pass 'this' as the MusicEditListener
        musicAdapter = MusicAdapter(this, emptyList(), this)
        binding.recyclerViewMusic.adapter = musicAdapter
        // build fast scrollbar
        FastScrollerBuilder(binding.recyclerViewMusic).useMd2Style().build()
    }

    private fun setupSearchView() {
        // Set up the SearchView listener
        binding.searchViewMusic.setOnQueryTextListener(this)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            // REMOVE: musicAdapter.updateList(emptyList())
            // Keeping the list populated prevents the screen from going blank during the scan
            viewModel.loadAudioFiles(applicationContext)
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadAudioFiles(applicationContext)
        } else {
            // Request storage permission
            requestStoragePermissionLauncher.launch(mediaPermission)
        }
    }

    // Register the permission request contract for storage permission
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.loadAudioFiles(applicationContext)
        } else {
            showStatus("Storage permission denied. Cannot scan local storage.")
        }
    }

    // This function is now responsible for setting the current play state in the repository
    // and starting the service without passing large data via Intent.
    fun startMusicPlayback(file: AudioFile, filteredIndex: Int) {
        // Exit editing mode if currently active
        if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) {
            exitEditingMode()
            return // Prevent playback if exiting edit mode
        }

        // Get the currently displayed (sorted & filtered) list from the adapter
        val currentDisplayedList = musicAdapter.getCurrentList()

        if (currentDisplayedList.isEmpty()) {
            Log.e("MainActivity", "Error: Current displayed list is empty. Cannot start playback.")
            return
        }

        // Find the index of the clicked file (by ID) in the *current displayed* playlist.
        // The `filteredIndex` passed to this function is the index in `currentDisplayedList`.
        val actualIndex = filteredIndex

        // 1. Set the current track index in the persistent store
        PlaylistRepository.currentTrackIndex = actualIndex

        // 2. Set the *current displayed list* as the service's playlist in the repository.
        // This is crucial: the service must play the list the user sees (sorted/filtered).
        PlaylistRepository.setFiles(currentDisplayedList)

        // NEW: Hide the keyboard and clear focus when a track is clicked
        hideKeyboardAndClearFocus()

        // TODO Start EditorActivity without any intent extras for the playlist/file
        //val activityIntent = Intent(this, MusicActivity::class.java)
        //startActivity(activityIntent)
    }

    // Inside MainActivity class
    fun openTagEditor(file: AudioFile) {
        val intent = Intent(this, MusicTagEditorActivity::class.java).apply {
            putExtra("audio_file", file)
        }
        startActivity(intent)
    }

    override fun startEditing(position: Int) {
        val viewHolder = binding.recyclerViewMusic.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {

            // 1. Hide search and sort controls, show back button and title
            binding.searchViewMusic.visibility = View.GONE
            binding.buttonSort.visibility = View.GONE
            binding.buttonSortDirection.visibility = View.GONE
            binding.buttonBackEdit.visibility = View.VISIBLE
            binding.textEditTitle.visibility = View.VISIBLE

            // 2. Disable search view interaction
            binding.searchViewMusic.isEnabled = false
            binding.searchViewMusic.clearFocus()

            // 3. Set editing position in adapter
            musicAdapter.setEditingPosition(position)

            // 4. Give focus to the EditText
            val editText = viewHolder.itemView.findViewById<EditText>(R.id.edit_text_title)
            editText?.requestFocus()

            // Show toast message for guidance
            Toast.makeText(this, "Editing: Click save or back to finish.", Toast.LENGTH_LONG).show()
        }
    }

    override fun saveEditAndExit(audioFile: AudioFile, newTitle: String, newArtist: String) {
        val oldTitle = audioFile.title
        val oldArtist = audioFile.artist

        // 1. Check if anything was actually modified
        if (newTitle == oldTitle && newArtist == oldArtist) {
            Toast.makeText(this, "No changes detected. Exiting editor.", Toast.LENGTH_SHORT).show()
            exitEditingMode()
            return
        }

        // 2. Store the pending update
        pendingUpdateFile = audioFile
        pendingUpdateTitle = newTitle
        pendingUpdateArtist = newArtist

        // 3. Request write permission for the specific file(s)
        requestMetadataWritePermission(listOf(audioFile.uri))
    }

    // --- Metadata Write Logic ---

    private fun requestMetadataWritePermission(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use MediaStore.createWriteRequest for API 30+
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, uris)
            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            requestWritePermissionLauncher.launch(intentSenderRequest)
        } else {
            // For older APIs (29 and below), WRITE_EXTERNAL_STORAGE is required.
            // Since we rely on the modern MediaStore URI, we assume the user has the proper
            // permission (READ_EXTERNAL_STORAGE) which often implies write access to owned files
            // or we fall back to a less secure general permission request (which the app doesn't have)
            // or simply try to execute the update. We will execute directly, assuming the URI
            // is valid and permission has been handled by the system for the app's files.
            executePendingMetadataUpdate()
        }
    }

    private fun executePendingMetadataUpdate() {
        val file = pendingUpdateFile ?: return
        val newTitle = pendingUpdateTitle ?: return
        val newArtist = pendingUpdateArtist ?: return

        // Clear pending data immediately
        pendingUpdateFile = null
        pendingUpdateTitle = null
        pendingUpdateArtist = null

        launch(Dispatchers.IO) {
            try {
                // 1. Determine MIME type (crucial for MediaStore to identify file correctly)
                val mimeType = contentResolver.getType(file.uri) ?: "audio/mpeg" // Default to mp3

                // 2. Prepare ContentValues
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, newTitle)
                    put(MediaStore.Audio.Media.ARTIST, newArtist)
                    // Explicitly update the modification date (in seconds)
                    put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    // Explicitly include MIME type
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }

                // Log the values we are attempting to write
                Log.d("MainActivity", "Attempting to update URI: ${file.uri}")
                Log.d("MainActivity", "ContentValues: $contentValues")

                // CRITICAL FIX: Add a small delay to ensure the system fully applies the write permission
                // granted by the user via createWriteRequest before proceeding with the update.
                delay(500) // 500ms delay

                // Use the file's content URI to perform the update
                val rowsUpdated = contentResolver.update(file.uri, contentValues, null, null)

                withContext(Dispatchers.Main) {
                    if (rowsUpdated > 0) {
                        Toast.makeText(
                            this@MainActivity,
                            "Metadata updated successfully!",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Update the repository with the new, modified AudioFile object
                        // Note: The DATE_MODIFIED and MIME_TYPE fields are not part of the AudioFile class here,
                        // but updating the core fields (title/artist) is sufficient for UI refresh.
                        val updatedFile = file.copy(title = newTitle, artist = newArtist)
                        PlaylistRepository.updateFile(updatedFile)
                        // ViewModel will observe the change and trigger list re-render/re-sort
                    } else {
                        // Log the failure to diagnose in the console
                        Log.e(
                            "MainActivity",
                            "Update failed: Rows updated = $rowsUpdated for URI: ${file.uri}"
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "Update failed: File not found or no changes made. Check logs.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    exitEditingMode()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error updating metadata for ${file.uri}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Fatal Error: Could not save metadata. Check permissions and logs.",
                        Toast.LENGTH_LONG
                    ).show()
                    exitEditingMode()
                }
            }
        }
    }

    private fun exitEditingMode() {
        // 1. Hide the keyboard
        hideKeyboardAndClearFocus()

        // 2. Clear the editing state in the adapter
        musicAdapter.setEditingPosition(RecyclerView.NO_POSITION)

        // 3. Restore visibility of search and sort controls
        binding.searchViewMusic.visibility = View.VISIBLE
        binding.buttonSort.visibility = View.VISIBLE
        binding.buttonSortDirection.visibility = View.VISIBLE
        binding.buttonBackEdit.visibility = View.GONE
        binding.textEditTitle.visibility = View.GONE

        // 4. Re-enable the search view interaction
        binding.searchViewMusic.isEnabled = true
    }

    // --- End MusicEditListener Implementation ---

    override fun onQueryTextSubmit(query: String?): Boolean {
        // NEW: Hide the soft keyboard and clear focus on submit
        hideKeyboardAndClearFocus()
        return true
    }

    /**
     * Called when the query text is changed by the user. This is where the filtering happens.
     */
    override fun onQueryTextChange(newText: String?): Boolean {
        // Only process text changes if the search view is visible/enabled (i.e., not in edit mode)
        if (binding.searchViewMusic.isEnabled) {
            // Filter the list using the ViewModel
            viewModel.filterList(newText.orEmpty())
        }
        return true
    }

    private fun showStatus(message: String) {
        binding.recyclerViewMusic.visibility = View.GONE
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.text = message
    }

    // NEW: Helper function to hide the keyboard and clear focus
    private fun hideKeyboardAndClearFocus() {
        // 1. Clear focus from the SearchView to hide the cursor
        binding.searchViewMusic.clearFocus()

        // 2. Explicitly hide the keyboard using InputMethodManager
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // Use the currently focused view's window token, or the root view's if none.
        val windowToken = currentFocus?.windowToken ?: binding.root.windowToken
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // cancel the coroutine job when the activity is destroyed
        job.cancel()
    }

    fun onRateClick(item: MenuItem) {}
    fun onHelpClick(item: MenuItem) {}
    fun showBigFrag(item: MenuItem) {}
    }