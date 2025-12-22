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
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, binding.etTitle.text.toString())
            put(MediaStore.Audio.Media.ARTIST, binding.etArtist.text.toString())
            put(MediaStore.Audio.Media.ALBUM, binding.etAlbum.text.toString())
            put(MediaStore.Audio.Media.GENRE, binding.etGenre.text.toString())
            put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rows = contentResolver.update(file.uri, values, null, null)
                withContext(Dispatchers.Main) {
                    if (rows > 0) {
                        Toast.makeText(this@MusicTagEditorActivity, "Saved!", Toast.LENGTH_SHORT).show()
                        // Update Repository so MainActivity reflects changes
                        val updated = file.copy(
                            title = binding.etTitle.text.toString(),
                            artist = binding.etArtist.text.toString(),
                            album = binding.etAlbum.text.toString(),
                            genre = binding.etGenre.text.toString()
                        )
                        PlaylistRepository.updateFile(updated)
                        finish()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MusicTagEditorActivity, "Error saving", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}