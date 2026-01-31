package green.mobileapps.musictageditor

import android.content.ContentValues
import android.content.Context
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
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import green.mobileapps.musictageditor.databinding.TagEditorActivityBinding
import kotlinx.coroutines.*

class MusicTagEditorActivity : AppCompatActivity() {

    private lateinit var binding: TagEditorActivityBinding
    private var audioFile: AudioFile? = null
    private val artworkCache = android.util.LruCache<Long, ByteArray>(10 * 1024 * 1024)

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
            val cachedBytes = artworkCache.get(file.id)

            if (cachedBytes != null) {
                // HIT: Load directly from memory
                loadArtIntoView(cachedBytes, cacheKey)
            } else {
                // MISS: Show default and load in background
                binding.editAlbumArt.setImageResource(R.drawable.default_album_art_144px)

                val bytes = getEmbeddedPicture(binding.editAlbumArt.context, file.uri)

                if (bytes != null) {
                    artworkCache.put(file.id, bytes)
                }

                // Switch to Main thread to update UI
                loadArtIntoView(bytes, cacheKey)
            }

            binding.editAlbumArt.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
        }
    }

    private fun loadArtIntoView(bytes: ByteArray?, signatureKey: String) {
        Glide.with(this)
            .load(bytes ?: R.drawable.default_album_art_144px) // Load bytes or fallback
            .signature(com.bumptech.glide.signature.ObjectKey(signatureKey))
            .transform(CircleCrop())
            .placeholder(R.drawable.default_album_art_144px)
            .dontAnimate()
            .into(binding.editAlbumArt)
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

    private fun detectTrueExtension(uri: Uri): String {
        // 1. Try to read the file header (Magic Numbers)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(12)
                if (input.read(header) >= 4) {
                    // MP3 (ID3v2) -> "ID3"
                    if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) return "mp3"

                    // MP3 (Frame Sync) -> FF FB or FF F3
                    if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) return "mp3"

                    // M4A/MP4 -> "ftyp" usually at index 4
                    if (header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                        header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) return "m4a"

                    // OGG -> "OggS"
                    if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                        header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) return "ogg"

                    // FLAC -> "fLaC"
                    if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                        header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) return "flac"
                }
            }
        } catch (e: Exception) {
            Log.w("TagEditor", "Header sniff failed", e)
        }

        // 2. Fallback: Check the original filename (Crucial for Raw AAC)
        val fileName = getFileName(uri)
        if (!fileName.isNullOrEmpty() && fileName.contains(".")) {
            val ext = fileName.substringAfterLast(".").lowercase()
            return when (ext) {
                "aac" -> "m4a"   // Treat .aac as .m4a so jaudiotagger tries to read it
                "opus" -> "ogg"  // Treat .opus as .ogg
                else -> ext
            }
        }

        // 3. Last Resort: Default to mp3 ONLY if we are truly clueless
        return "mp3"
    }

    private fun saveMetadata() {
        val file = audioFile ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                contentResolver.openFileDescriptor(file.uri, "rw")?.use { pfd ->

                    // FIX: Use the new detector
                    val extension = detectTrueExtension(file.uri)
                    val tempFile = java.io.File(cacheDir, "edit_${System.currentTimeMillis()}.$extension")

                    // Copy original file to temp
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Could not read original file")

                    // Try to edit tags
                    try {
                        val jaudioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                        val tag = jaudioFile.tagOrCreateAndSetDefault

                        // --- YOUR TAG SETTING LOGIC GOES HERE ---
                        fun setTagField(key: org.jaudiotagger.tag.FieldKey, value: String) {
                            if (value.isNotBlank()) tag.setField(key, value) else tag.deleteField(key)
                        }
                        setTagField(org.jaudiotagger.tag.FieldKey.TITLE, binding.etTitle.text.toString())
                        setTagField(org.jaudiotagger.tag.FieldKey.ARTIST, binding.etArtist.text.toString())
                        setTagField(org.jaudiotagger.tag.FieldKey.ALBUM, binding.etAlbum.text.toString())
                        setTagField(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, binding.etAlbumArtist.text.toString())
                        setTagField(org.jaudiotagger.tag.FieldKey.TRACK, binding.etTrackNumber.text.toString())
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
                        // ----------------------------------------

                        jaudioFile.commit()

                    } catch (e: Exception) {
                        // Check for specific file format errors
                        val msg = e.message ?: ""
                        if (msg.contains("No Reader associated") || msg.contains("not appear to be an Mp4")) {
                            throw Exception("Cannot edit file type; Convert to MP3 or M4A.")
                        } else if (msg.contains("No audio header found")) {
                            throw Exception("File corrupted or extension mismatch.")
                        }
                        throw e
                    }

                    // Write temp file back to original Uri
                    contentResolver.openOutputStream(file.uri, "rwt")?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw Exception("Could not open output stream")

                    tempFile.delete()
                    updateMediaStoreRecord(file)
                }
            } catch (e: Exception) {
                Log.e("MusicTagEditorActivity", "Error saving", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MusicTagEditorActivity, "Save Failed: ${e.message}", Toast.LENGTH_LONG).show()
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

                albumId = null,

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