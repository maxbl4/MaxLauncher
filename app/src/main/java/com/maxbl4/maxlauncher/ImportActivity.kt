package com.maxbl4.maxlauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.ImportedLayout
import com.maxbl4.maxlauncher.home.PendingWidget
import com.maxbl4.maxlauncher.home.addWidget
import com.maxbl4.maxlauncher.home.allPlaced
import com.maxbl4.maxlauncher.home.appLabels
import com.maxbl4.maxlauncher.home.toHomeLayout
import com.maxbl4.maxlauncher.settings.JsonFileStore
import com.maxbl4.maxlauncher.ui.LauncherTheme
import java.util.Locale
import kotlin.math.abs

/**
 * Imports a home screen layout read from One UI Home by tools/import-oneui.ps1, which starts
 * this activity with the layout as Base64 JSON in [EXTRA_DATA]. Nothing changes until the user
 * confirms; apps with the same label are chosen by the user, the current layout is backed up.
 */
class ImportActivity : ComponentActivity() {

    private val app get() = application as LauncherApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val imported = intent.getStringExtra(EXTRA_DATA)?.let { data ->
            runCatching {
                val json = String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8)
                JsonFileStore.JSON.decodeFromString<ImportedLayout>(json)
            }.getOrNull()
        }
        if (imported == null) {
            Toast.makeText(this, R.string.import_bad_data, Toast.LENGTH_LONG).show()
            return finish()
        }
        setContent {
            LauncherTheme {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    ImportScreen(imported)
                }
            }
        }
    }

    @Composable
    private fun ImportScreen(imported: ImportedLayout) {
        val apps by app.appRepository.apps.collectAsStateWithLifecycle()
        var report by remember { mutableStateOf<List<String>?>(null) }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Text(
                stringResource(R.string.import_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
            val done = report
            when {
                apps.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                done != null -> Result(done, Modifier.weight(1f))
                else -> Confirm(imported, apps, onImport = { choices -> report = runImport(imported, choices) }, Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun Confirm(imported: ImportedLayout, apps: List<AppEntry>, onImport: (Map<String, AppRef?>) -> Unit, modifier: Modifier) {
        val candidates = remember(imported, apps) { imported.appLabels().associateWith { label -> matches(label, apps) } }
        val choices = remember(candidates) {
            mutableStateMapOf<String, AppRef?>().apply { candidates.forEach { (label, found) -> put(label, found.firstOrNull()?.ref) } }
        }
        val ambiguous = candidates.filter { it.value.size > 1 }
        val missing = candidates.filter { it.value.isEmpty() }.keys
        val items = imported.pages.flatMap { it.items }
        val summary = stringResource(
            R.string.import_summary,
            imported.pages.size,
            items.count { it.type == "app" } + imported.dock.size,
            items.count { it.type == "folder" },
            items.count { it.type == "widget" },
        )

        Column(modifier) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(summary, fontSize = 16.sp) }
                if (ambiguous.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.import_ambiguous)) }
                    ambiguous.forEach { (label, found) ->
                        item(key = "label:$label") { Text(label, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
                        items(found, key = { "$label:${it.key.id}" }) { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = choices[label] == entry.ref) { choices[label] = entry.ref },
                            ) {
                                RadioButton(selected = choices[label] == entry.ref, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Image(entry.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    entry.key.packageName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.import_missing)) }
                    item { Text(missing.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item {
                    Text(
                        stringResource(R.string.import_warning),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.picker_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onImport(choices.toMap()) }) { Text(stringResource(R.string.import_confirm)) }
            }
        }
    }

    @Composable
    private fun Result(report: List<String>, modifier: Modifier) {
        Column(modifier) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(report) { Text(it, fontSize = 16.sp) }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName))
                    finish()
                }) { Text(stringResource(R.string.import_to_home)) }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    /** Installed apps with this label; personal ones win over work profile ones. */
    private fun matches(label: String, apps: List<AppEntry>): List<AppEntry> {
        val key = label.normalized()
        val found = apps.filter { it.label.normalized() == key }
        return found.filter { !it.isWork }.ifEmpty { found }
    }

    private fun String.normalized() = trim().lowercase(Locale.ROOT)

    /** Replaces the layout, then adds widgets that can be bound right away. Returns report lines. */
    private fun runImport(imported: ImportedLayout, choices: Map<String, AppRef?>): List<String> {
        val result = imported.toHomeLayout { choices[it] }
        app.homeRepository.replace(result.layout)
        val skippedWidgets = result.widgets.filterNot(::addWidget).map { it.label }
        val placed = result.layout.allPlaced().size
        return buildList {
            add(getString(R.string.import_done, result.layout.pages.size, placed))
            if (result.missing.isNotEmpty()) add(getString(R.string.import_report_missing, result.missing.joinToString(", ")))
            if (skippedWidgets.isNotEmpty()) add(getString(R.string.import_report_widgets, skippedWidgets.joinToString(", ")))
            add(getString(R.string.import_report_backup))
        }
    }

    /** Adds a widget whose provider is found by name and can be bound without asking; false otherwise. */
    private fun addWidget(widget: PendingWidget): Boolean {
        val widgets = app.widgets
        val me = Process.myUserHandle()
        val candidates = widgets.providers().filter { it.profile == me && it.loadLabel(packageManager).trim() == widget.label.trim() }
        val provider = candidates.minByOrNull { sizeDistance(it, widget) } ?: return false
        val needsSetup = provider.configure != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0)
        if (needsSetup) return false
        val id = widgets.allocateId()
        if (!widgets.bindIfAllowed(id, provider)) {
            widgets.delete(id)
            return false
        }
        app.homeRepository.update {
            it.addWidget(id, provider.provider.flattenToString(), widgets.userSerial(provider), widget.spanX, widget.spanY, widget.pageId, widget.x to widget.y)
        }
        return true
    }

    /** Several widgets of an app may share a name; prefer the one whose default size is closest. */
    private fun sizeDistance(info: AppWidgetProviderInfo, widget: PendingWidget): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0) {
            abs(info.targetCellWidth - widget.spanX) + abs(info.targetCellHeight - widget.spanY)
        } else {
            0
        }

    companion object {
        const val EXTRA_DATA = "data"
    }
}
