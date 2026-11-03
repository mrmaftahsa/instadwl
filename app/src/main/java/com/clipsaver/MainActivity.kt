package com.clipsaver

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var linkInput: EditText
    private lateinit var downloadBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var ready = false
    private var busy = false
    private var lastLink = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        linkInput = findViewById(R.id.linkInput)
        downloadBtn = findViewById(R.id.downloadBtn)
        updateBtn = findViewById(R.id.updateBtn)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        downloadBtn.setOnClickListener { startDownload() }
        updateBtn.setOnClickListener { updateDownloader() }

        status.text = "Starting engine..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(application)
                    com.yausername.ffmpeg.FFmpeg.getInstance().init(application)
                }
                ready = true
                status.text = "Ready. Copy an Instagram link, then open this app."
                checkClipboard()
            } catch (e: Exception) {
                status.text = "Engine failed to start: ${e.message}"
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only allows reading the clipboard while the app is in front.
        if (hasFocus) checkClipboard()
    }

    private fun checkClipboard() {
        if (!ready || busy) return
        val link = clipboardLink() ?: return
        if (link == lastLink) return
        linkInput.setText(link)
        startDownload()
    }

    private fun clipboardLink(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return null
        return Regex("https?://[^\\s]*instagram\\.com/[^\\s]+").find(text)?.value
    }

    private fun startDownload() {
        if (!ready || busy) return
        val url = linkInput.text.toString().trim()
        if (url.isEmpty()) {
            status.text = "Paste an Instagram link first."
            return
        }
        busy = true
        lastLink = url
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = "Downloading..."

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "dl")
                    dir.deleteRecursively()
                    dir.mkdirs()

                    val request = YoutubeDLRequest(url)
                    request.addOption("-o", dir.absolutePath + "/%(id)s.%(ext)s")
                    request.addOption("-f", "best[ext=mp4]/best")
                    YoutubeDL.getInstance().execute(request) { p, _, _ ->
                        runOnUiThread { progress.progress = p.toInt() }
                    }

                    val file = dir.listFiles()?.firstOrNull { it.isFile }
                        ?: throw Exception("No file was downloaded")
                    saveToGallery(file)
                    file.delete()
                }
                status.text = "Saved to your gallery (Movies/ClipSaver)."
            } catch (e: Exception) {
                status.text = "Failed: ${e.message}\n\nTry the Update downloader button, then copy the link again."
            } finally {
                busy = false
                progress.visibility = View.GONE
            }
        }
    }

    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ClipSaver")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create gallery entry")
        resolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun updateDownloader() {
        if (!ready) {
            status.text = "Engine is not ready yet."
            return
        }
        status.text = "Updating downloader..."
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(application)
                }
                status.text = "Update finished: $result"
            } catch (e: Exception) {
                status.text = "Update failed: ${e.message}"
            }
        }
    }
}
