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

            Log.i("MusicTagEditorActivity", "albumId: ${file.albumId}")

            Glide.with(this)
                .load(file.albumId?.let { getAlbumArtUri(it) } ?: file.uri)
                .placeholder(R.drawable.default_album_art_144px)
                .into(binding.editAlbumArt)

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
                    val tempFile = java.io.File(cacheDir, "temp_audio.mp3")

                    contentResolver.openInputStream(file.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    val jaudioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                    val tag = jaudioFile.tagOrCreateAndSetDefault

                    // 1. Save Text Fields
                    tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, binding.etTitle.text.toString())
                    tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, binding.etArtist.text.toString())
                    tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, binding.etAlbum.text.toString())
                    tag.setField(org.jaudiotagger.tag.FieldKey.GENRE, binding.etGenre.text.toString())

                    // 2. Save Artwork if a new one was picked
                    selectedImageUri?.let { imageUri ->
                        contentResolver.openInputStream(imageUri)?.use { inputStream ->
                            val imageBytes = inputStream.readBytes()

                            // Create Artwork object
                            val artwork = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
                            artwork.binaryData = imageBytes

                            // Important: Clear existing art before adding new one
                            tag.deleteArtworkField()
                            tag.setField(artwork)
                        }
                    }

                    jaudioFile.commit()

                    // 3. Write temp file back
                    contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    }

                    tempFile.delete()
                }

                updateMediaStoreRecord(file)

            } catch (e: Exception) {
                Log.e("MusicTagEditorActivity", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MusicTagEditorActivity, "Failed to save artwork", Toast.LENGTH_SHORT).show()
                }
            }
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

    private suspend fun updateMediaStoreRecord(file: AudioFile) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, binding.etTitle.text.toString())
            put(MediaStore.Audio.Media.ARTIST, binding.etArtist.text.toString())
            put(MediaStore.Audio.Media.ALBUM, binding.etAlbum.text.toString())
            put(MediaStore.Audio.Media.GENRE, binding.etGenre.text.toString())
            // Forces system to recognize the file changed
            put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        contentResolver.update(file.uri, values, null, null)

        withContext(Dispatchers.Main) {
            val updated = file.copy(
                title = binding.etTitle.text.toString(),
                artist = binding.etArtist.text.toString(),
                album = binding.etAlbum.text.toString(),
                genre = binding.etGenre.text.toString()
            )
            PlaylistRepository.updateFile(updated)
            Toast.makeText(this@MusicTagEditorActivity, "Saved Successfully!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}