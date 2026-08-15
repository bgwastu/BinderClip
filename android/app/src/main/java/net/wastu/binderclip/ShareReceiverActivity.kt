package net.wastu.binderclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

sealed interface SharedPayload {
    data class Image(val value: ImagePayload) : SharedPayload
    data class Text(val value: String) : SharedPayload
}
object SharedPayloadCache { @Volatile var value: SharedPayload? = null }

/** Native Android share-sheet endpoint. It reads a one-shot URI grant while it is valid. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)
        setContent { BinderClipTheme { ShareSendingScreen() } }
        val payload = when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    ?: intent.clipData?.getItemAt(0)?.uri
                when {
                    stream != null -> ImageClipboard.readUri(this, stream, intent.type)?.let(SharedPayload::Image)
                    intent.type == "text/plain" -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        ?.takeIf { it.isNotBlank() }?.let(SharedPayload::Text)
                    else -> null
                }
            }
            else -> null
        }
        if (payload == null) {
            Log.w("BinderClip", "Share sheet did not provide supported content")
            DiagnosticLog.error("Could not read shared content")
            Toast.makeText(this, "Couldn’t send this content", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Log.i("BinderClip", "Accepted shared ${if (payload is SharedPayload.Image) "image" else "text"}")
        SharedPayloadCache.value = payload
        ContextCompat.startForegroundService(this, Intent(this, BinderClipService::class.java).setAction(BinderClipService.ACTION_SEND_SHARED))
        window.decorView.postDelayed({ finish() }, 650)
    }
}

@Composable private fun ShareSendingScreen() {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text("Sending", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
    }
}
