package com.bnyro.wallpaper.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.wallpaper.R
import com.bnyro.wallpaper.ui.theme.WallYouTheme

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val selectedTxtUriState = mutableStateOf<String?>(null)

    private val txtFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            selectedTxtUriState.value = uri.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            WallYouTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConfigScreen(
                        onConfirm = { mode, refreshMin -> confirmWidget(mode, refreshMin) },
                        onPickTxt = { pickTxtFile() },
                        hasTxtUri = { selectedTxtUriState.value != null }
                    )
                }
            }
        }
    }

    private fun pickTxtFile() {
        txtFilePicker.launch(arrayOf("text/plain"))
    }

    private fun confirmWidget(mode: Int, refreshMinutes: Int) {
        if (mode == WidgetPrefs.MODE_TEXT && selectedTxtUriState.value == null) {
            Toast.makeText(this, R.string.widget_no_txt_selected, Toast.LENGTH_SHORT).show()
            return
        }

        WidgetPrefs.setMode(this, appWidgetId, mode)
        if (mode == WidgetPrefs.MODE_TEXT) {
            WidgetPrefs.setTxtUri(this, appWidgetId, selectedTxtUriState.value)
        }

        if (mode == WidgetPrefs.MODE_TEXT || mode == WidgetPrefs.MODE_IMAGE) {
            WidgetPrefs.setRefreshMinutes(this, appWidgetId, refreshMinutes)
        }

        val manager = AppWidgetManager.getInstance(this)
        WallpaperWidgetProvider.updateWidget(this, manager, appWidgetId)

        if (mode == WidgetPrefs.MODE_TEXT || mode == WidgetPrefs.MODE_IMAGE) {
            WallpaperWidgetProvider.scheduleRefresh(this, appWidgetId)
        }

        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

@Composable
private fun ConfigScreen(
    onConfirm: (Int, Int) -> Unit,
    onPickTxt: () -> Unit,
    hasTxtUri: () -> Boolean
) {
    var selectedMode by rememberSaveable { mutableIntStateOf(WidgetPrefs.MODE_TRANSPARENT) }
    var refreshSlider by rememberSaveable { mutableFloatStateOf(WidgetPrefs.DEFAULT_REFRESH_MIN.toFloat()) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.widget_config_title),
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(24.dp))

            ModeOption(
                label = stringResource(R.string.widget_mode_transparent),
                selected = selectedMode == WidgetPrefs.MODE_TRANSPARENT,
                onClick = { selectedMode = WidgetPrefs.MODE_TRANSPARENT }
            )

            ModeOption(
                label = stringResource(R.string.widget_mode_text),
                selected = selectedMode == WidgetPrefs.MODE_TEXT,
                onClick = { selectedMode = WidgetPrefs.MODE_TEXT }
            )

            ModeOption(
                label = stringResource(R.string.widget_mode_image),
                selected = selectedMode == WidgetPrefs.MODE_IMAGE,
                onClick = { selectedMode = WidgetPrefs.MODE_IMAGE }
            )

            if (selectedMode == WidgetPrefs.MODE_TEXT) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onPickTxt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (hasTxtUri()) {
                            stringResource(R.string.widget_txt_selected)
                        } else {
                            stringResource(R.string.widget_select_txt)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.widget_txt_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (selectedMode == WidgetPrefs.MODE_TEXT || selectedMode == WidgetPrefs.MODE_IMAGE) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.widget_refresh_interval, refreshSlider.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = refreshSlider,
                    onValueChange = { refreshSlider = it },
                    valueRange = WidgetPrefs.MIN_REFRESH_MIN.toFloat()..60f,
                    steps = 58,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onConfirm(selectedMode, refreshSlider.toInt()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.widget_confirm))
            }
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
