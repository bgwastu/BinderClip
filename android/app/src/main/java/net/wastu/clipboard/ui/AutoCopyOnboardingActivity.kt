package net.wastu.clipboard.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.wastu.clipboard.R
import net.wastu.clipboard.settings.ClipboardSettingsStore

class AutoCopyOnboardingActivity : ComponentActivity() {
    private lateinit var settingsStore: ClipboardSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        settingsStore = ClipboardSettingsStore(this)

        setContent {
            ClipboardTheme {
                var showDisclosure by remember { mutableStateOf(false) }
                if (showDisclosure) {
                    AccessibilityDisclosureDialog(
                        onAllow = {
                            showDisclosure = false
                            enableAutoCopy()
                        },
                        onDeny = { showDisclosure = false }
                    )
                }
                TipsScreen(
                    onEnableAutoCopy = { showDisclosure = true },
                    onDone = { dismiss() }
                )
            }
        }
    }

    private fun enableAutoCopy() {
        settingsStore.setAutoCopyEnabled(true)
        settingsStore.setAutoCopyOnboardingShown(true)
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        setResult(RESULT_OK)
        finish()
    }

    private fun dismiss() {
        settingsStore.setAutoCopyOnboardingShown(true)
        setResult(RESULT_OK)
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TipsScreen(onEnableAutoCopy: () -> Unit, onDone: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tips") },
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_binder_clip),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp).size(24.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            item {
                Text(
                    text = "Choose the sharing method that fits the moment.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { Spacer(Modifier.size(8.dp)) }
            item { TipsSectionLabel(stringResource(R.string.onboarding_always_available)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.onboarding_share_sheet_title)) },
                    supportingContent = {
                        Text("Select text, tap Share, then choose BinderClip.")
                    },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) }
                )
            }
            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.onboarding_tile_title)) },
                    supportingContent = { Text(stringResource(R.string.onboarding_tile_desc)) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_binder_clip),
                            contentDescription = null
                        )
                    }
                )
            }
            item { Spacer(Modifier.size(8.dp)) }
            item { TipsSectionLabel(stringResource(R.string.onboarding_auto_section)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.onboarding_auto_title)) },
                    supportingContent = { Text(stringResource(R.string.onboarding_auto_subtitle)) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_binder_clip),
                            contentDescription = null
                        )
                    }
                )
            }
            item {
                Text(
                    text = listOf(
                        stringResource(R.string.onboarding_auto_caveat_accessibility),
                        stringResource(R.string.onboarding_auto_caveat_notification),
                        stringResource(R.string.onboarding_auto_caveat_reliability)
                    ).joinToString(separator = "\n") { "• $it" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            item {
                FilledTonalButton(onClick = onEnableAutoCopy, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable auto-copy")
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
            item {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_got_it))
                }
            }
        }
    }
}

@Composable
private fun TipsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}
