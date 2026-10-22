package com.aashish.myrings.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashish.myrings.data.KeyBackup
import com.aashish.myrings.service.RingUi

/**
 * Lets the person keep a copy of the ring's key. Without it, a lost or wiped phone means another factory reset.
 * [fresh] is true right after pairing, when the wording is a prompt rather than a menu entry.
 */
@Composable
fun KeyBackupDialog(ring: RingUi, fresh: Boolean, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val a = MaterialTheme.aura
    val text = remember(ring.row.serial, ring.row.keyHex) { KeyBackup.build(ring.row.name, ring.row.serial, ring.row.keyHex) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
        onMessage(if (ok) "Key backup saved" else "Could not save the file")
        if (ok) onClose()
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        onDismissRequest = onClose,
        title = { Text(if (fresh) "Back up your ring's key" else "Back up this ring's key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The key is the only way back into ${ring.row.name}. If this phone is wiped or the app is uninstalled without a copy, the ring needs another factory reset.",
                    fontSize = 14.sp,
                )
                Text("Keep the copy private, for example in a password manager. Anyone who has it can control the ring.", fontSize = 12.5.sp, color = a.ink2)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { saver.launch(KeyBackup.fileName(ring.row.serial)) }) { Text("Save to file") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "My Rings key backup") }
                    ctx.startActivity(Intent.createChooser(send, "Share key backup"))
                    onClose()
                }) { Text("Share") }
                TextButton(onClick = { clip.setText(AnnotatedString(text)); onMessage("Key backup copied"); onClose() }) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(if (fresh) "Later" else "Close") } },
    )
}
