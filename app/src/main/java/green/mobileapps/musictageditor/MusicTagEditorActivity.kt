package green.mobileapps.musictageditor

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import green.mobileapps.musictageditor.databinding.TagEditorActivityBinding
import kotlinx.coroutines.*

class MusicTagEditorActivity : AppCompatActivity() {

    private lateinit var binding: TagEditorActivityBinding
    private var audioFile: AudioFile? = null

    private val intentSenderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            saveMetadata()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TagEditorActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Handle "Share" Intent (ACTION_SEND)
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (uri != null) {
                lifecycleScope.launch {
                    audioFile = createAudioFileFromUri(uri)
                    setupUI()
                }
            }
        }
        // 2. Handle "Open With" Intent (ACTION_VIEW / EDIT)
        else if (intent.data != null && (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT)) {
            val uri = intent.data!!
            lifecycleScope.launch {
                audioFile = createAudioFileFromUri(uri)
                setupUI()
            }
        }
        // 3. Handle Standard Internal Launch
        else {
            audioFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("audio_file", AudioFile::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("audio_file")
            }
            setupUI()
        }

        binding.buttonSave.setOnClickListener {
            requestWritePermission()
        }
    }

    private fun setupUI() {
        audioFile?.let { file ->
            binding.etTitle.setText(file.title)
            binding.etArtist.setText(file.artist)
            binding.etAlbum.setText(file.album)
            // REMOVED: binding.etGenre.setText(file.genre)
            binding.etAlbumArtist.setText(file.albumArtist)
            binding.etComposer.setText(file.composer)
            binding.etYear.setText(file.year?.toString() ?: "")
            binding.etTrackNumber.setText(file.track?.toString() ?: "")

            Log.d("MusicTagEditorActivity", "albumId: ${file.albumId}")

            val cacheKey = "${file.id}_${file.dateModified}"
            val useEmbeddedArt = file.albumId == null ||
                    file.album?.lowercase() == "music" ||
                    file.album?.lowercase() == "documents" ||
                    file.albumId == 553547078986512838L ||
                    file.artist.lowercase() == "<unknown>"

            if (useEmbeddedArt) {
                this.lifecycleScope.launch {
                    val imageBytes = getEmbeddedPicture(file.uri)
                    Glide.with(this@MusicTagEditorActivity)
                        .load(imageBytes)
                        .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
                        .placeholder(R.drawable.default_album_art_192px)
                        .into(binding.editAlbumArt)
                }
            } else {
                Glide.with(this@MusicTagEditorActivity)
                    .load(file.albumId?.let { getAlbumArtUri(it) })
                    .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
                    .placeholder(R.drawable.default_album_art_192px)
                    .into(binding.editAlbumArt)
            }

            binding.editAlbumArt.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
        }
    }

    private fun requestWritePermission() {
        val uri = audioFile?.uri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, listOf(uri))
            val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            intentSenderLauncher.launch(request)
        } else {
            saveMetadata()
        }
    }

    private fun saveMetadata() {
        val file = audioFile ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                contentResolver.openFileDescriptor(file.uri, "rw")?.use { pfd ->
                    val tempFile = java.io.File(cacheDir, "edit_${System.currentTimeMillis()}.mp3")
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Could not read original file")

                    val jaudioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                    val tag = jaudioFile.tagOrCreateAndSetDefault

                    fun setTagField(key: org.jaudiotagger.tag.FieldKey, value: String) {
                        if (value.isNotBlank()) {
                            tag.setField(key, value)
                        } else {
                            tag.deleteField(key)
                        }
                    }

                    setTagField(org.jaudiotagger.tag.FieldKey.TITLE, binding.etTitle.text.toString())
                    setTagField(org.jaudiotagger.tag.FieldKey.ARTIST, binding.etArtist.text.toString())
                    setTagField(org.jaudiotagger.tag.FieldKey.ALBUM, binding.etAlbum.text.toString())
                    setTagField(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, binding.etAlbumArtist.text.toString())
                    setTagField(org.jaudiotagger.tag.FieldKey.TRACK, binding.etTrackNumber.text.toString())
                    // REMOVED: Genre tag field
                    setTagField(org.jaudiotagger.tag.FieldKey.COMPOSER, binding.etComposer.text.toString())
                    setTagField(org.jaudiotagger.tag.FieldKey.YEAR, binding.etYear.text.toString())

                    selectedImageUri?.let { imageUri ->
                        getCompressedBytes(imageUri)?.let { imageBytes ->
                            val artwork = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
                            artwork.binaryData = imageBytes
                            tag.deleteArtworkField()
                            tag.setField(artwork)
                        }
                    }

                    if (jaudioFile is org.jaudiotagger.audio.mp3.MP3File) {
                        if (jaudioFile.hasID3v2Tag()) {
                            val v2tag = jaudioFile.iD3v2Tag
                            if (v2tag is org.jaudiotagger.tag.id3.ID3v24Tag) {
                                // Convert the v2.4 tag to v2.3 (which uses TYER instead of TDRC)
                                jaudioFile.iD3v2Tag = org.jaudiotagger.tag.id3.ID3v23Tag(v2tag)
                            }
                        }
                    }

                    jaudioFile.commit()

                    contentResolver.openOutputStream(file.uri, "rwt")?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Could not open output stream")

                    tempFile.delete()
                    updateMediaStoreRecord(file)
                }
            } catch (e: Exception) {
                Log.e("MusicTagEditorActivity", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MusicTagEditorActivity, "Failed to save tags", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun getEmbeddedPicture(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(this@MusicTagEditorActivity, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            Glide.with(this).load(it).into(binding.editAlbumArt)
            selectedImageUri = it
        }
    }

    private var selectedImageUri: Uri? = null

    private fun getCompressedBytes(uri: Uri): ByteArray? {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream) ?: return null

        val width = originalBitmap.width
        val height = originalBitmap.height
        val newDimension = if (width < height) width else height
        val xOffset = (width - newDimension) / 2
        val yOffset = (height - newDimension) / 2

        val croppedBitmap = android.graphics.Bitmap.createBitmap(
            originalBitmap,
            xOffset,
            yOffset,
            newDimension,
            newDimension
        )

        val outputStream = java.io.ByteArrayOutputStream()
        croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream)

        if (originalBitmap != croppedBitmap) originalBitmap.recycle()

        return outputStream.toByteArray()
    }

    private suspend fun updateMediaStoreRecord(file: AudioFile) {
        val newTimestamp = System.currentTimeMillis() / 1000

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, binding.etTitle.text.toString())
            put(MediaStore.Audio.Media.ARTIST, binding.etArtist.text.toString())
            put(MediaStore.Audio.Media.ALBUM, binding.etAlbum.text.toString())
            put(MediaStore.Audio.Media.ALBUM_ARTIST, binding.etAlbumArtist.text.toString())
            put(MediaStore.Audio.Media.COMPOSER, binding.etComposer.text.toString())
            put(MediaStore.Audio.Media.YEAR, binding.etYear.text.toString().toIntOrNull())
            put(MediaStore.Audio.Media.TRACK, binding.etTrackNumber.text.toString().toIntOrNull())
            // REMOVED: Genre update
            put(MediaStore.Audio.Media.DATE_MODIFIED, newTimestamp)
        }

        try {
            // Only attempt to update MediaStore if we have a valid ID or if it's a content URI we can write to
            if (file.id != -1L) {
                contentResolver.update(file.uri, values, null, null)
            }
        } catch (e: Exception) {
            Log.w("MusicTagEditor", "Could not update MediaStore index (File might be external): ${e.message}")
        }

        withContext(Dispatchers.Main) {
            val updated = file.copy(
                title = binding.etTitle.text.toString(),
                artist = binding.etArtist.text.toString(),
                album = binding.etAlbum.text.toString(),
                albumArtist = binding.etAlbumArtist.text.toString(),
                composer = binding.etComposer.text.toString(),
                year = binding.etYear.text.toString().toIntOrNull(),
                track = binding.etTrackNumber.text.toString().toIntOrNull(),
                // Removed Genre from copy
                dateModified = newTimestamp
            )

            PlaylistRepository.updateFile(updated)
            Toast.makeText(this@MusicTagEditorActivity, "Saved Successfully!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun createAudioFileFromUri(uri: Uri): AudioFile = withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever()
        var title = "Unknown Title"
        var artist = "Unknown Artist"
        var album = ""
        var albumArtist = ""
        var composer = ""
        var year: Int? = null
        var track: Int? = null
        var duration = 0L

        try {
            retriever.setDataSource(this@MusicTagEditorActivity, uri)
            title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: getFileName(uri) ?: "Unknown"
            artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            albumArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: ""
            composer = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: ""

            val yearStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
            year = yearStr?.toIntOrNull()

            val trackStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            // Handle formats like "1/12"
            track = trackStr?.split("/")?.firstOrNull()?.trim()?.toIntOrNull()

            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration = durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e("TagEditor", "Error parsing metadata from URI", e)
        } finally {
            retriever.release()
        }

        // Construct a "dummy" AudioFile.
        // We set ID to -1 and AlbumID to null because this file might not be in the MediaStore.
        AudioFile(
            id = -1L,
            uri = uri,
            title = title,
            artist = artist,
            duration = duration,
            albumId = null, // Will force fallback to embedded picture in setupUI
            album = album,
            albumArtist = albumArtist,
            composer = composer,
            track = track,
            year = year,
            size = null,
            dateAdded = System.currentTimeMillis() / 1000,
            dateModified = System.currentTimeMillis() / 1000,
            bookmark = null,
            isMusic = true,
            isPodcast = false,
            isAlarm = false,
            isNotification = false,
            isRingtone = false
        )
    }

    // Helper to get filename if metadata title is missing
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}