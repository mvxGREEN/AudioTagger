package green.mobileapps.musictageditor

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
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
import android.view.Menu
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
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
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

// --- SORTING DEFINITIONS ---
enum class SortBy { DATE_ADDED, TITLE, ARTIST, ALBUM, DURATION }
data class SortState(val by: SortBy, val ascending: Boolean)
// ---------------------------

interface MusicEditListener {
    fun startEditing(position: Int)
    fun saveEditAndExit(audioFile: AudioFile, newTitle: String, newArtist: String)
}

private fun Cursor.getNullableString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getString(index) else null
}

private fun Cursor.getNullableLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getLong(index) else null
}

private fun Cursor.getNullableInt(columnName: String): Int? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getInt(index) else null
}

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

    // Album Art Metadata
    val albumId: Long?,

    // Media Store Metadata
    val album: String?,
    val albumArtist: String?,
    //val author: String?,
    val composer: String?,
    val track: Int?,
    val year: Int?,
    // REMOVED: val genre: String?,

    // File Metadata
    val size: Long?,
    val dateAdded: Long?,
    val dateModified: Long?,

    // Playback Metadata
    val bookmark: Long?,
    //val bitrate: Int?,
    // REMOVED: val sampleRate: Int?,
    // REMOVED: val bitsPerSample: Int?,

    // Classification Flags (Boolean)
    val isMusic: Boolean,
    val isPodcast: Boolean,
    val isAlarm: Boolean,
    val isNotification: Boolean,
    val isRingtone: Boolean,
    // REMOVED: val isAudiobook: Boolean,
    // REMOVED: val isRecording: Boolean,

    // Status Flags (Boolean)
    //val isDownload: Boolean,
    //val isDrm: Boolean,
    //val isFavorite: Boolean,
    //val isPending: Boolean,
    //val isTrashed: Boolean
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readParcelable(Uri::class.java.classLoader)!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readLong(),
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readString(),
        parcel.readString(),
        //parcel.readString(),
        parcel.readString(),
        parcel.readValue(Int::class.java.classLoader) as? Int,
        parcel.readValue(Int::class.java.classLoader) as? Int,
        // Removed Genre
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(Long::class.java.classLoader) as? Long,
        parcel.readValue(Long::class.java.classLoader) as? Long,
        //parcel.readValue(Int::class.java.classLoader) as? Int,
        // Removed SampleRate, BitsPerSample
        parcel.readByte() != 0.toByte(), // isMusic
        parcel.readByte() != 0.toByte(), // isPodcast
        parcel.readByte() != 0.toByte(), // isAlarm
        parcel.readByte() != 0.toByte(), // isNotification
        parcel.readByte() != 0.toByte(), // isRingtone

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
        parcel.writeString(composer)
        parcel.writeValue(track)
        parcel.writeValue(year)
        // Removed Genre, Author
        parcel.writeValue(size)
        parcel.writeValue(dateAdded)
        parcel.writeValue(dateModified)
        parcel.writeValue(bookmark)
        // Removed SampleRate, BitsPerSample, Bitrate
        parcel.writeByte(if (isMusic) 1 else 0)
        parcel.writeByte(if (isPodcast) 1 else 0)
        parcel.writeByte(if (isAlarm) 1 else 0)
        parcel.writeByte(if (isNotification) 1 else 0)
        parcel.writeByte(if (isRingtone) 1 else 0)
        // Removed isAudiobook, isRecording ...
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

fun getAlbumArtUri(albumId: Long): Uri {
    return ContentUris.withAppendedId(
        "content://media/external/audio/albumart".toUri(),
        albumId
    )
}

object PlaylistRepository {
    private val _audioFiles = MutableLiveData<List<AudioFile>>(emptyList())
    val audioFiles: LiveData<List<AudioFile>> = _audioFiles
    var currentTrackIndex: Int = -1

    fun setFiles(files: List<AudioFile>) {
        _audioFiles.postValue(files)
    }

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

    fun getCurrentTrack(): AudioFile? {
        val list = _audioFiles.value
        return if (list != null && currentTrackIndex >= 0 && currentTrackIndex < list.size) {
            list[currentTrackIndex]
        } else {
            null
        }
    }

    fun getFullPlaylist(): List<AudioFile> = _audioFiles.value ?: emptyList()
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val fullAudioList: LiveData<List<AudioFile>> = PlaylistRepository.audioFiles
    private var musicListFull: List<AudioFile> = emptyList()
    private val _filteredList = MutableLiveData<List<AudioFile>>(emptyList())
    val filteredList: LiveData<List<AudioFile>> = _filteredList

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private var currentQuery: String = ""
    private val _sortState = MutableLiveData(SortState(SortBy.DATE_ADDED, false))
    val sortState: LiveData<SortState> = _sortState

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    init {
        fullAudioList.observeForever { newList ->
            musicListFull = newList
            applySortAndFilter()
        }
    }

    fun loadAudioFiles(context: Context) {
        if (_isLoading.value == true) return
        _isLoading.postValue(true)
        _statusMessage.postValue("Scanning for audio files...")

        scope.launch {
            val audioList = loadAudioFilesFromStorage(context)
            PlaylistRepository.setFiles(audioList)
            if (audioList.isEmpty()) {
                _statusMessage.postValue("No audio files found. Ensure you have MP3s in your music folder.")
            } else {
                _statusMessage.postValue("Loaded ${audioList.size} tracks.")
            }
            _isLoading.postValue(false)
        }
    }

    private fun loadAudioFilesFromStorage(context: Context): List<AudioFile> {
        val files = mutableListOf<AudioFile>()
        val contentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.BOOKMARK,
            MediaStore.Audio.Media.SIZE,
            //MediaStore.Audio.Media.BITRATE,
            // REMOVED: SAMPLERATE, BITS_PER_SAMPLE
            // REMOVED: IS_AUDIOBOOK, IS_RECORDING
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_PODCAST,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            //MediaStore.Audio.Media.AUTHOR,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            // REMOVED: GENRE
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"

        contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                val title = cursor.getNullableString(MediaStore.Audio.Media.TITLE) ?: "Unknown Title"
                val artist = cursor.getNullableString(MediaStore.Audio.Media.ARTIST) ?: "Unknown Artist"
                val duration = cursor.getNullableLong(MediaStore.Audio.Media.DURATION) ?: 0L
                val albumId = cursor.getNullableLong(MediaStore.Audio.Media.ALBUM_ID)
                val contentUri: Uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())

                val album = cursor.getNullableString(MediaStore.Audio.Media.ALBUM)
                val albumArtist = cursor.getNullableString(MediaStore.Audio.Media.ALBUM_ARTIST)
                val composer = cursor.getNullableString(MediaStore.Audio.Media.COMPOSER)
                val track = cursor.getNullableInt(MediaStore.Audio.Media.TRACK)
                val year = cursor.getNullableInt(MediaStore.Audio.Media.YEAR)
                // Removed Genre, author

                val size = cursor.getNullableLong(MediaStore.Audio.Media.SIZE)
                val dateAdded = cursor.getNullableLong(MediaStore.Audio.Media.DATE_ADDED)
                val dateModified = cursor.getNullableLong(MediaStore.Audio.Media.DATE_MODIFIED)
                val bookmark = cursor.getNullableLong(MediaStore.Audio.Media.BOOKMARK)
                // Removed SampleRate, BitsPerSample, bitrate

                // Removed isAudiobook, isRecording ...
                val isMusic = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_MUSIC) ?: false
                val isPodcast = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_PODCAST) ?: false
                val isAlarm = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_ALARM) ?: false
                val isNotification = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_NOTIFICATION) ?: false
                val isRingtone = cursor.getNullableBoolean(MediaStore.Audio.Media.IS_RINGTONE) ?: false

                if (duration > 30000
                    && album != null
                    && !album.contains("Voice Recorder")
                    // REMOVED: && !isRecording check
                    && !isRingtone
                    && !isAlarm
                    && !isNotification) {
                    files.add(
                        AudioFile(
                            id, contentUri, title, artist, duration, albumId,
                            album, albumArtist, composer, track, year,
                            // Removed Genre
                            size, dateAdded, dateModified,
                            bookmark, // bitrate,
                            // Removed SampleRate, BitsPerSample
                            isMusic, isPodcast, isAlarm, isNotification, isRingtone,
                            // Removed isAudiobook, isRecording ...
                        )
                    )
                }
            }
        }
        return files
    }

    // ... (Remainder of MainActivity logic: Sorting, Adapter, Activity methods unchanged) ...
    // Note: Since I only modified AudioFile and loading, the rest of the class functions (Adapter, etc) are compatible.
    // I am including the rest of the file structure implicitly to save space, but make sure to keep the Adapter and Activity classes.

    fun applySortAndFilter() {
        val sortedList = applySort(musicListFull, _sortState.value!!)
        val filteredList = applyFilter(sortedList, currentQuery)
        _filteredList.value = filteredList
    }

    fun filterList(query: String) {
        currentQuery = query
        applySortAndFilter()
    }

    fun setSortState(newSortState: SortState) {
        _sortState.value = newSortState
        applySortAndFilter()
    }

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
            SortBy.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.album ?: "" }
            SortBy.DURATION -> compareBy { it.duration }
            SortBy.DATE_ADDED -> compareBy { it.dateAdded ?: 0L }
        }
        val sortedList = list.sortedWith(comparator)
        return if (state.ascending) sortedList else sortedList.reversed()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}

// ... (MusicAdapter and MainActivity class follow, identical to previous logic except AudioFile changes don't impact them directly as they don't access the deleted fields) ...
class MusicAdapter(private val activity: MainActivity, private var musicList: List<AudioFile>, private val editListener: MusicEditListener) :
    RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {
    private val imageCache = mutableMapOf<Long, ByteArray?>()
    private val artworkCache = android.util.LruCache<Long, ByteArray>(10 * 1024 * 1024)
    private var editingPosition: Int = RecyclerView.NO_POSITION

    fun setEditingPosition(newPosition: Int) {
        val oldPosition = editingPosition
        editingPosition = newPosition
        if (oldPosition != RecyclerView.NO_POSITION) notifyItemChanged(oldPosition)
        if (newPosition != RecyclerView.NO_POSITION) notifyItemChanged(newPosition)
    }
    private fun getEmbeddedPicture(context: Context, uri: Uri): ByteArray? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
    fun getEditingPosition(): Int = editingPosition

    inner class MusicViewHolder(private val binding: ItemMusicFileBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
        fun bind(file: AudioFile, index: Int) {
            job?.cancel()
            if (index % 2 == 0) {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context, R.color.light_gray));
            } else {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context, R.color.white));
                binding.textTitle.alpha = 0.95f;
                binding.textArtist.alpha = 0.95f;
            }
            val isEditing = adapterPosition == editingPosition
            val fullTitleText = file.title
            val albumInfo = if (file.album != null) " • ${file.album}" else ""
            val fullArtistText = "${file.artist}$albumInfo"

            if (isEditing) {
                binding.textTitle.visibility = View.GONE
                binding.textArtist.visibility = View.GONE
                binding.editTextTitle.visibility = View.VISIBLE
                binding.editTextArtist.visibility = View.VISIBLE
                binding.buttonSaveEdit.visibility = View.VISIBLE
                binding.editTextTitle.setText(file.title)
                binding.editTextArtist.setText(file.artist)
                binding.editTextTitle.requestFocus()
                (itemView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(binding.editTextTitle, InputMethodManager.SHOW_IMPLICIT)
            } else {
                binding.textTitle.visibility = View.VISIBLE
                binding.textArtist.visibility = View.VISIBLE
                binding.editTextTitle.visibility = View.GONE
                binding.editTextArtist.visibility = View.GONE
                binding.buttonSaveEdit.visibility = View.GONE
                binding.textTitle.text = fullTitleText
                binding.textArtist.text = fullArtistText
            }

            val cacheKey = "${file.id}_${file.dateModified}" // Key changes when file is edited

            val cachedBytes = artworkCache.get(file.id) // Will be null now for edited files

            if (cachedBytes != null) {
                loadArtIntoView(cachedBytes, cacheKey)
            } else {
                // This block will now correctly execute for edited files
                binding.imageAlbumArt.setImageResource(R.drawable.default_album_art_144px)
                job = (itemView.context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    val bytes = getEmbeddedPicture(itemView.context, file.uri)
                    if (bytes != null) artworkCache.put(file.id, bytes)

                    if (bytes != null) {
                        artworkCache.put(file.id, bytes)
                    }

                    // Switch to Main thread to update UI
                    withContext(Dispatchers.Main) {
                        // Check if this ViewHolder is still bound to the same file
                        if (isActive) {
                            loadArtIntoView(bytes, cacheKey)
                        }
                    }
                }
            }
            binding.root.setOnClickListener {
                if (!isEditing) activity.openTagEditor(file)
            }
            binding.buttonSaveEdit.setOnClickListener {
                val newTitle = binding.editTextTitle.text.toString().trim()
                val newArtist = binding.editTextArtist.text.toString().trim()
                editListener.saveEditAndExit(file, newTitle, newArtist)
            }
        }

        private fun loadArtIntoView(bytes: ByteArray?, signatureKey: String) {
            Glide.with(itemView.context)
                .load(bytes ?: R.drawable.default_album_art_144px) // Load bytes or fallback
                .signature(com.bumptech.glide.signature.ObjectKey(signatureKey))
                .transform(CircleCrop())
                .placeholder(R.drawable.default_album_art_144px)
                .dontAnimate()
                .into(binding.imageAlbumArt)
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val binding = ItemMusicFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MusicViewHolder(binding)
    }
    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        holder.bind(musicList[position], position)
    }
    override fun getItemCount(): Int = musicList.size
    // In MainActivity.kt -> MusicAdapter class

    fun updateList(newList: List<AudioFile>) {
        // 1. Detect changed items and clear their cached artwork
        newList.forEach { newItem ->
            val oldItem = musicList.find { it.id == newItem.id }

            // If the file exists but the timestamp is different, the content changed
            if (oldItem != null && oldItem.dateModified != newItem.dateModified) {
                artworkCache.remove(newItem.id)
            }
        }

        // 2. Proceed with your existing DiffUtil logic
        val diffCallback = object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = musicList.size
            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return musicList[oldPos].id == newList[newPos].id
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldItem = musicList[oldPos]
                val newItem = newList[newPos]
                // Ensure dateModified is part of this check so onBindViewHolder is triggered
                return oldItem.title == newItem.title &&
                        oldItem.artist == newItem.artist &&
                        oldItem.dateModified == newItem.dateModified
            }
        }

        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        musicList = newList
        diffResult.dispatchUpdatesTo(this)
    }
    fun getCurrentList(): List<AudioFile> = musicList
}

class MainActivity : AppCompatActivity(), CoroutineScope, SearchView.OnQueryTextListener, MusicEditListener {
    private var job: Job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job
    private lateinit var binding: MainActivityBinding
    private lateinit var viewModel: MusicViewModel
    public lateinit var musicAdapter: MusicAdapter
    private lateinit var sortButton: ImageButton
    private lateinit var sortDirectionButton: ImageButton
    private lateinit var backButton: ImageButton
    private val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    private val notificationPermission = Manifest.permission.POST_NOTIFICATIONS
    private val requestNotificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        viewModel.loadAudioFiles(applicationContext)
    }
    private val requestWritePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) executePendingMetadataUpdate() else {
            Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show()
            exitEditingMode()
        }
    }
    private var pendingUpdateFile: AudioFile? = null
    private var pendingUpdateTitle: String? = null
    private var pendingUpdateArtist: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application)).get(MusicViewModel::class.java)
        setSupportActionBar(binding.toolbarSearch)
        sortButton = binding.buttonSort
        sortDirectionButton = binding.buttonSortDirection
        backButton = binding.buttonBackEdit
        setupRecyclerView()
        setupSearchView()
        setupSortButton()
        setupSortDirectionButton()
        setupSystemBackPressHandler()
        setupSwipeRefresh()
        setupObservers()
        checkPermissions()
    }
    private fun setupSystemBackPressHandler() {
        onBackPressedDispatcher.addCallback(this) {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }
    private fun setupSortDirectionButton() {
        sortDirectionButton.setOnClickListener { viewModel.toggleSortDirection() }
    }
    private fun setupSortButton() {
        sortButton.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.apply {
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
                    val newAscending = if (sortCriterion == currentSortState.by) !currentSortState.ascending else currentSortState.ascending
                    viewModel.setSortState(SortState(sortCriterion, newAscending))
                    true
                } else false
            }
            popup.show()
        }
        viewModel.sortState.observe(this) { state ->
            sortDirectionButton.setImageResource(if (state.ascending) R.drawable.ascending_24px else R.drawable.descending_24px)
        }
    }
    override fun onResume() {
        super.onResume()
        hideKeyboardAndClearFocus()
        if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) exitEditingMode()
    }
    private fun setupObservers() {
        viewModel.filteredList.observe(this) { audioList ->
            if (audioList.isNotEmpty()) {
                musicAdapter.updateList(audioList)
                binding.recyclerViewMusic.visibility = View.VISIBLE
                binding.textStatus.visibility = View.GONE
            } else if (PlaylistRepository.getFullPlaylist().isNotEmpty()) {
                musicAdapter.updateList(emptyList())
                showStatus("No tracks match your search.")
            }
        }
        viewModel.statusMessage.observe(this) { message -> if (!message.contains("Loaded")) showStatus(message) }
        viewModel.isLoading.observe(this) { binding.swipeRefreshLayout.isRefreshing = it }
    }
    private fun setupRecyclerView() {
        musicAdapter = MusicAdapter(this, emptyList(), this)
        binding.recyclerViewMusic.adapter = musicAdapter
        FastScrollerBuilder(binding.recyclerViewMusic).useMd2Style().build()
    }
    private fun setupSearchView() { binding.searchViewMusic.setOnQueryTextListener(this) }
    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.loadAudioFiles(applicationContext) }
    }
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadAudioFiles(applicationContext)
        } else {
            requestStoragePermissionLauncher.launch(mediaPermission)
        }
    }
    private val requestStoragePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) viewModel.loadAudioFiles(applicationContext) else showStatus("Storage permission denied. Cannot scan local storage.")
    }
    fun openTagEditor(file: AudioFile) {
        val intent = Intent(this, MusicTagEditorActivity::class.java).apply { putExtra("audio_file", file) }
        startActivity(intent)
    }
    override fun startEditing(position: Int) {
        val viewHolder = binding.recyclerViewMusic.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {
            binding.searchViewMusic.visibility = View.GONE
            binding.buttonSort.visibility = View.GONE
            binding.buttonSortDirection.visibility = View.GONE
            binding.buttonBackEdit.visibility = View.VISIBLE
            binding.textEditTitle.visibility = View.VISIBLE
            binding.searchViewMusic.isEnabled = false
            binding.searchViewMusic.clearFocus()
            musicAdapter.setEditingPosition(position)
            viewHolder.itemView.findViewById<EditText>(R.id.edit_text_title)?.requestFocus()
            Toast.makeText(this, "Editing: Click save or back to finish.", Toast.LENGTH_LONG).show()
        }
    }
    override fun saveEditAndExit(audioFile: AudioFile, newTitle: String, newArtist: String) {
        if (newTitle == audioFile.title && newArtist == audioFile.artist) {
            exitEditingMode()
            return
        }
        pendingUpdateFile = audioFile
        pendingUpdateTitle = newTitle
        pendingUpdateArtist = newArtist
        requestMetadataWritePermission(listOf(audioFile.uri))
    }
    private fun requestMetadataWritePermission(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, uris)
            requestWritePermissionLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            executePendingMetadataUpdate()
        }
    }
    private fun executePendingMetadataUpdate() {
        val file = pendingUpdateFile ?: return
        val newTitle = pendingUpdateTitle ?: return
        val newArtist = pendingUpdateArtist ?: return
        pendingUpdateFile = null
        pendingUpdateTitle = null
        pendingUpdateArtist = null
        launch(Dispatchers.IO) {
            try {
                val mimeType = contentResolver.getType(file.uri) ?: "audio/mpeg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, newTitle)
                    put(MediaStore.Audio.Media.ARTIST, newArtist)
                    put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
                delay(500)
                val rowsUpdated = contentResolver.update(file.uri, contentValues, null, null)
                withContext(Dispatchers.Main) {
                    if (rowsUpdated > 0) {
                        Toast.makeText(this@MainActivity, "Metadata updated successfully!", Toast.LENGTH_SHORT).show()
                        val updatedFile = file.copy(title = newTitle, artist = newArtist)
                        PlaylistRepository.updateFile(updatedFile)
                    } else {
                        Toast.makeText(this@MainActivity, "Update failed.", Toast.LENGTH_LONG).show()
                    }
                    exitEditingMode()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Fatal Error.", Toast.LENGTH_LONG).show()
                    exitEditingMode()
                }
            }
        }
    }
    private fun exitEditingMode() {
        hideKeyboardAndClearFocus()
        musicAdapter.setEditingPosition(RecyclerView.NO_POSITION)
        binding.searchViewMusic.visibility = View.VISIBLE
        binding.buttonSort.visibility = View.VISIBLE
        binding.buttonSortDirection.visibility = View.VISIBLE
        binding.buttonBackEdit.visibility = View.GONE
        binding.textEditTitle.visibility = View.GONE
        binding.searchViewMusic.isEnabled = true
    }
    override fun onQueryTextSubmit(query: String?): Boolean {
        hideKeyboardAndClearFocus()
        return true
    }
    override fun onQueryTextChange(newText: String?): Boolean {
        if (binding.searchViewMusic.isEnabled) viewModel.filterList(newText.orEmpty())
        return true
    }
    private fun showStatus(message: String) {
        binding.recyclerViewMusic.visibility = View.GONE
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.text = message
    }
    private fun hideKeyboardAndClearFocus() {
        binding.searchViewMusic.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val windowToken = currentFocus?.windowToken ?: binding.root.windowToken
        imm.hideSoftInputFromWindow(windowToken, 0)
    }
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean { return super.onOptionsItemSelected(item) }
    fun onAboutClick(menuItem: MenuItem?) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/")))
    }
    fun onPrivacyClick(menuItem: MenuItem?) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/privacy-policy")))
    }
    fun onRateClick(menuItem: MenuItem?) {
        val appPackageName = getPackageName()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appPackageName)))
        } catch (anfe: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + appPackageName)))
        }
    }
}