package green.mobileapps.musictageditor

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import green.mobileapps.musictageditor.databinding.TagEditorActivityBinding
import kotlinx.coroutines.*
import java.net.InetAddress

class MusicTagEditorActivity : AppCompatActivity() {

    private lateinit var binding: TagEditorActivityBinding
    private var audioFile: AudioFile? = null
    private val artworkCache = android.util.LruCache<Long, ByteArray>(10 * 1024 * 1024)
    private var selectedImageUri: Uri? = null

    private val intentSenderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            saveMetadata()
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            Glide.with(this).load(it).into(binding.editAlbumArt)
            selectedImageUri = it
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

        binding.saveFab.setOnClickListener {
            requestWritePermission()
        }
    }

    private fun setupUI() {
        audioFile?.let { file ->
            binding.etTitle.setText(file.title)
            binding.etArtist.setText(file.artist)
            binding.etAlbum.setText(file.album)
            binding.etGenre.setText(file.genre)
            binding.etAlbumArtist.setText(file.albumArtist)
            binding.etComposer.setText(file.composer)
            binding.etYear.setText(file.year?.toString() ?: "")
            binding.etTrackNumber.setText(file.track?.toString() ?: "")

            // Handle Genre Visibility & Data based on OS Version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                binding.layoutGenre.visibility = View.VISIBLE
                binding.etGenre.setText(file.genre)
            } else {
                binding.layoutGenre.visibility = View.GONE
            }

            Log.d("MusicTagEditorActivity", "albumId: ${file.albumId}")

            val cacheKey = "${file.id}_${file.dateModified}"
            val cachedBytes = artworkCache.get(file.id)

            if (cachedBytes != null) {
                // HIT: Load directly from memory
                loadArtIntoView(cachedBytes, cacheKey)
            } else {
                // MISS: Show default and load in background
                binding.editAlbumArt.setImageResource(R.drawable.default_album_art_192px)

                val bytes = getEmbeddedPicture(binding.editAlbumArt.context, file.uri)

                if (bytes != null) {
                    artworkCache.put(file.id, bytes)
                }

                // Switch to Main thread to update UI
                loadArtIntoView(bytes, cacheKey)
            }

            // on edit art click listeners
            binding.editAlbumArt.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }

            binding.editAlbumArtFab.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
        }

        binding.etMainInput.addTextChangedListener(object : TextWatcher {
            var oldCount = 0

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                oldCount = count
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (count == 0 && oldCount != 0) {
                    killKeyboard()
                } else if (count - oldCount > 1) {

                    if (!binding.etMainInput.hasFocus()) return

                    var input = s.toString()

                    if (input.lastIndexOf("https://") != input.indexOf("https://")) {
                        input = input.substring(input.lastIndexOf("https://"))
                    }
                    val inputText = input

                    killKeyboard()

                    // Launch a coroutine to handle network operations off the Main Thread
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (isInternetAvailable()) {
                            try {
                                // 1. Setup connection and pretend to be a browser to prevent 403 errors
                                val url = java.net.URL(inputText)
                                val connection = url.openConnection() as java.net.HttpURLConnection
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                connection.instanceFollowRedirects = true
                                connection.connect()

                                // 2. Verify the URL actually points to an image
                                val contentType = connection.contentType ?: ""
                                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK || !contentType.startsWith("image/")) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MusicTagEditorActivity, "Invalid link: Please paste a direct image URL.", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }

                                // 3. Save it to a temporary cache file
                                val tempFile = java.io.File(cacheDir, "downloaded_art_${System.currentTimeMillis()}.jpg")
                                connection.inputStream.use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                // 4. Double-check the file is a valid, uncorrupted bitmap
                                val options = android.graphics.BitmapFactory.Options()
                                options.inJustDecodeBounds = true
                                android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath, options)
                                if (options.outWidth == -1 || options.outHeight == -1) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MusicTagEditorActivity, "Downloaded file is corrupted or not an image.", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }

                                val downloadedUri = Uri.fromFile(tempFile)

                                // 5. Switch back to the Main Thread to update the UI
                                withContext(Dispatchers.Main) {
                                    selectedImageUri = downloadedUri

                                    Glide.with(this@MusicTagEditorActivity)
                                        .load(downloadedUri)
                                        .transform(com.bumptech.glide.load.resource.bitmap.CircleCrop())
                                        .into(binding.editAlbumArt)

                                    Toast.makeText(this@MusicTagEditorActivity, "Artwork downloaded!", Toast.LENGTH_SHORT).show()
                                }

                            } catch (e: Exception) {
                                Log.e("MusicTagEditor", "Error downloading image from URL", e)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MusicTagEditorActivity, "Failed to download image", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MusicTagEditorActivity, getString(R.string.msg_no_internet), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable) {
                binding.btnClear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            }
        })

        binding.btnClear.setOnClickListener {
            binding.etMainInput.setText("")
        }
    }

    fun killKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(findViewById<View>(R.id.etMainInput).windowToken, 0)
    }

    fun isInternetAvailable(): Boolean {
        return try {
            val ipAddr = InetAddress.getByName("google.com")
            ipAddr.toString().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun loadArtIntoView(bytes: ByteArray?, signatureKey: String) {
        Glide.with(this)
            .load(bytes ?: R.drawable.default_album_art_192px) // Load bytes or fallback
            .signature(com.bumptech.glide.signature.ObjectKey(signatureKey))
            .transform(CircleCrop())
            .placeholder(R.drawable.default_album_art_192px)
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

    fun onPasteClick(v: View?) {
        // 1. Force the EditText to take focus so the TextWatcher accepts the input
        binding.etMainInput.requestFocus()

        binding.etMainInput.setText("")
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        var primaryStr = ""
        val primaryClip = clipboardManager.primaryClip

        if (primaryClip != null && primaryClip.itemCount > 0) {
            primaryStr = primaryClip.getItemAt(0).text.toString().trim()
            binding.etMainInput.setText(primaryStr) // 2. Now it will trigger the TextWatcher properly
        } else {
            Toast.makeText(this@MusicTagEditorActivity, "Please copy an image URL", Toast.LENGTH_LONG).show()
            binding.etMainInput.setText(primaryStr)
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

    private fun applyTagsToJAudioFile(tag: org.jaudiotagger.tag.Tag) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setTagField(org.jaudiotagger.tag.FieldKey.GENRE, binding.etGenre.text.toString())
        }

        selectedImageUri?.let { imageUri ->
            getCompressedBytes(imageUri)?.let { imageBytes ->
                val artwork = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
                artwork.binaryData = imageBytes
                tag.deleteArtworkField()
                tag.setField(artwork)
            }
        }
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

                        applyTagsToJAudioFile(tag)
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
                    val msg = e.message ?: ""
                    // Intercept our specific error message
                    if (msg.contains("Cannot edit file type")) {
                        showConversionDialog(file)
                    } else {
                        Toast.makeText(this@MusicTagEditorActivity, "Save Failed: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showConversionDialog(file: AudioFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Format Not Supported")
            .setMessage("This file format does not support tags. Would you like to convert it and save an MP3 copy instead?")
            .setPositiveButton("Save as MP3") { _, _ ->
                convertAndSaveAsMp3(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun convertAndSaveAsMp3(originalFile: AudioFile) {
        Toast.makeText(this, "Converting to MP3... Please wait.", Toast.LENGTH_LONG).show()
        binding.saveFab.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Copy source file from URI to a local temporary cache file
                val sourceFile = java.io.File(cacheDir, "source_audio_${System.currentTimeMillis()}")
                contentResolver.openInputStream(originalFile.uri)?.use { input ->
                    sourceFile.outputStream().use { output -> input.copyTo(output) }
                }

                // 2. Define the output file location for the MP3
                val mp3OutputFile = java.io.File(cacheDir, "converted_${System.currentTimeMillis()}.mp3")

                // 3. Run FFmpeg Conversion (Audio only, 192kbps)
                val session = com.arthenica.ffmpegkit.FFmpegKit.execute("-i '${sourceFile.absolutePath}' -b:a 192k '${mp3OutputFile.absolutePath}'")

                if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {

                    // 4. Apply metadata to the brand new MP3 file
                    val jaudioFile = org.jaudiotagger.audio.AudioFileIO.read(mp3OutputFile)
                    val tag = jaudioFile.tagOrCreateAndSetDefault
                    applyTagsToJAudioFile(tag)
                    jaudioFile.commit()

                    // 5. Insert the new MP3 into the MediaStore securely
                    insertNewAudioToMediaStore(mp3OutputFile)

                } else {
                    throw Exception("FFmpeg conversion failed.")
                }

                // Cleanup temp files
                sourceFile.delete()
                mp3OutputFile.delete()

            } catch (e: Exception) {
                Log.e("TagEditor", "Conversion failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MusicTagEditorActivity, "Conversion Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.saveFab.isEnabled = true
                }
            }
        }
    }

    private suspend fun insertNewAudioToMediaStore(audioFile: java.io.File) {
        val titleInput = binding.etTitle.text.toString().ifBlank { "Unknown Title" }
        val artistInput = binding.etArtist.text.toString().ifBlank { "Unknown Artist" }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${artistInput} - ${titleInput}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.TITLE, titleInput)
            put(MediaStore.Audio.Media.ARTIST, artistInput)
            put(MediaStore.Audio.Media.ALBUM, binding.etAlbum.text.toString())
            put(MediaStore.Audio.Media.YEAR, binding.etYear.text.toString().toIntOrNull())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Converted")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val newUri = contentResolver.insert(collection, values) ?: throw Exception("Failed to create MediaStore entry")

        // Write the physical bytes to the new URI
        contentResolver.openOutputStream(newUri)?.use { output ->
            audioFile.inputStream().use { input -> input.copyTo(output) }
        }

        // Release the pending status so Android media scanners can see it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(newUri, values, null, null)
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(this@MusicTagEditorActivity, "Converted and saved as a new file!", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

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
            // Conditionally update MediaStore Genre
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                put(MediaStore.Audio.Media.GENRE, binding.etGenre.text.toString())
            }
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
                genre = binding.etGenre.text.toString(),
                albumId = null,
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
        var genre: String? = null
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

            genre = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)

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
            genre = genre,
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