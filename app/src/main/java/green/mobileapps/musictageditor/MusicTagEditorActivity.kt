package green.mobileapps.musictageditor

import android.content.ContentValues
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

    // Permission launcher for Android 11+ (Scoped Storage)
    private val intentSenderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            saveMetadata() // Retry saving after permission granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TagEditorActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("audio_file", AudioFile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("audio_file")
        }

        setupUI()

        binding.buttonSave.setOnClickListener {
            requestWritePermission()
        }
    }

    private fun setupUI() {
        audioFile?.let { file ->
            binding.etTitle.setText(file.title)
            binding.etArtist.setText(file.artist)
            binding.etAlbum.setText(file.album)
            binding.etGenre.setText(file.genre)

            Log.d("MusicTagEditorActivity", "albumId: ${file.albumId}")

            // Replicated logic from MainActivity/MusicAdapter
            val cacheKey = "${file.id}_${file.dateModified}"
            val isProblematic = file.album?.lowercase() == "music" ||
                    file.album?.lowercase() == "documents" ||
                    file.albumId == 553547078986512838L ||
                    file.artist.lowercase() == "<unknown>"

            if (isProblematic) {
                // Path A: Manual extraction for problematic files
                this.lifecycleScope.launch {
                    val imageBytes = getEmbeddedPicture(file.uri)
                    Glide.with(this@MusicTagEditorActivity)
                        .load(imageBytes)
                        .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
                        .placeholder(R.drawable.default_album_art_144px)
                        .into(binding.editAlbumArt)
                }
            } else {
                // Path B: Standard MediaStore loading for clean metadata
                Glide.with(this@MusicTagEditorActivity)
                    .load(file.albumId?.let { getAlbumArtUri(it) })
                    .signature(com.bumptech.glide.signature.ObjectKey(cacheKey))
                    .placeholder(R.drawable.default_album_art_144px)
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
                    // 1. Create a workspace in cache
                    val tempFile = java.io.File(cacheDir, "edit_${System.currentTimeMillis()}.mp3")

                    // 2. Copy original to temp FIRST
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Could not read original file")

                    // 3. Edit the temp file
                    val jaudioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                    val tag = jaudioFile.tagOrCreateAndSetDefault

                    // 1. Save Text Fields
                    tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, binding.etTitle.text.toString())
                    tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, binding.etArtist.text.toString())
                    tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, binding.etAlbum.text.toString())
                    tag.setField(org.jaudiotagger.tag.FieldKey.GENRE, binding.etGenre.text.toString())

                    // Handle Artwork with a size check to prevent OOM
                    selectedImageUri?.let { imageUri ->
                        // Call the compression function here instead of inputStream.readBytes()
                        val imageBytes = getCompressedBytes(imageUri)

                        if (imageBytes != null) {
                            val artwork = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
                            artwork.binaryData = imageBytes

                            // Clear existing and set new compressed artwork
                            tag.deleteArtworkField()
                            tag.setField(artwork)
                        } else {
                            throw Exception("Failed to process selected image.")
                        }
                    }

                    // commit to temp file
                    jaudioFile.commit()

                    // 3. Write temp file back
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

    // Inside MusicTagEditorActivity class
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            // Load the preview in the UI immediately
            Glide.with(this).load(it).into(binding.editAlbumArt)
            // Store the selected URI to be used during saveMetadata()
            selectedImageUri = it
        }
    }

    private var selectedImageUri: Uri? = null

    private fun getCompressedBytes(uri: Uri): ByteArray? {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)

        // Scale down if the image is excessively large
        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, 1000, 1000, true)

        val outputStream = java.io.ByteArrayOutputStream()
        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
        return outputStream.toByteArray()
    }

    private suspend fun updateMediaStoreRecord(file: AudioFile) {
        // Generate a fresh timestamp in seconds
        val newTimestamp = System.currentTimeMillis() / 1000

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, binding.etTitle.text.toString())
            put(MediaStore.Audio.Media.ARTIST, binding.etArtist.text.toString())
            put(MediaStore.Audio.Media.ALBUM, binding.etAlbum.text.toString())
            put(MediaStore.Audio.Media.GENRE, binding.etGenre.text.toString())
            // Update the system record's modification date
            put(MediaStore.Audio.Media.DATE_MODIFIED, newTimestamp)
        }

        contentResolver.update(file.uri, values, null, null)

        withContext(Dispatchers.Main) {
            // IMPORTANT: Create a copy with the NEW metadata AND the NEW timestamp
            val updated = file.copy(
                title = binding.etTitle.text.toString(),
                artist = binding.etArtist.text.toString(),
                album = binding.etAlbum.text.toString(),
                genre = binding.etGenre.text.toString(),
                dateModified = newTimestamp // This triggers the UI refresh
            )

            // Push the update to the repository
            PlaylistRepository.updateFile(updated)
            Toast.makeText(this@MusicTagEditorActivity, "Saved Successfully!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}