package com.android.video.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import com.android.video.ui.LocalUiMode
import com.android.video.ui.UiMode

@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}
