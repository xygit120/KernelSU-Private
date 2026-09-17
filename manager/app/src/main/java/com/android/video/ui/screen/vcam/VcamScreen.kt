package com.android.video.ui.screen.vcam

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.android.video.R
import com.android.video.vcam.VcamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VcamScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var deployed by remember { mutableStateOf(VcamManager.isDeployed()) }

    fun run(action: String, block: (android.content.Context) -> String) {
        if (busy) return
        busy = true
        output = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { block(context) }
            output = result
            deployed = VcamManager.isDeployed()
            busy = false
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            run("import-video") { VcamManager.importMedia(it, uri, "mp4") }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            run("import-image") { VcamManager.importMedia(it, uri, "jpg") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.vcam_title), style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.vcam_summary),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (deployed) R.string.vcam_running else R.string.vcam_not_running
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (deployed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { run("deploy") { VcamManager.deploy(it) } },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.vcam_deploy))
            }
            OutlinedButton(
                onClick = { run("status") { VcamManager.status(it) } },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.vcam_status))
            }
            OutlinedButton(
                onClick = { run("rollback") { VcamManager.rollback(it) } },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.vcam_rollback))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { run("mode-real") { VcamManager.setMode(it, "real") } },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.vcam_mode_real))
            }
            OutlinedButton(
                onClick = { run("mode-virtual") { VcamManager.setMode(it, "virtual") } },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.vcam_mode_virtual))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { pickVideo.launch(arrayOf("video/*")) },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.vcam_pick_video))
            }
            OutlinedButton(
                onClick = { pickImage.launch(arrayOf("image/*")) },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.vcam_pick_image))
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Spacer(modifier = Modifier.padding(4.dp))
                Text(text = stringResource(R.string.vcam_output))
            }
        }

        if (output.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.vcam_output),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
