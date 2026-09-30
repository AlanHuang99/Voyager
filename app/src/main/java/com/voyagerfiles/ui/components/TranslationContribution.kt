package com.voyagerfiles.ui.components

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R

@Composable
fun rememberTranslationLinkOpener(): () -> Unit {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.translation_crowdin_url)
    val error = stringResource(R.string.translation_link_unavailable)
    return {
        try {
            uriHandler.openUri(url)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun TranslationContributionButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Translate, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.translation_help), modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
fun TranslationNotice(onImprove: () -> Unit, onDismiss: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                stringResource(R.string.translation_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TranslationContributionButton(onClick = onImprove)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, stringResource(R.string.translation_dismiss))
        }
    }
}
