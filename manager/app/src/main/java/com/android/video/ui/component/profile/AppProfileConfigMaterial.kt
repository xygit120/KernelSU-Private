package com.android.video.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.video.Natives
import com.android.video.R
import com.android.video.ui.component.material.SegmentedColumn
import com.android.video.ui.component.material.SegmentedSwitchItem
import com.android.video.ui.component.material.SegmentedTextField

@Composable
fun AppProfileConfigMaterial(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    Column(modifier = modifier) {
        if (!fixedName) {
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                content = listOf {
                    SegmentedTextField(
                        value = profile.name,
                        onValueChange = { onProfileChange(profile.copy(name = it)) },
                        label = stringResource(R.string.profile_name),
                        readOnly = !enabled,
                        singleLine = true
                    )
                }
            )
        }
    }
}
