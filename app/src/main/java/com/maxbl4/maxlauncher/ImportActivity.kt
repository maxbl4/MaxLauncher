package com.maxbl4.maxlauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Base64
import android.widget.Toast
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
import androidx.compose.runtime.produceState
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
import com.maxbl4.maxlauncher.drawer.DrawerLayout
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.HomeLayout
import com.maxbl4.maxlauncher.home.ImportedLayout
import com.maxbl4.maxlauncher.home.PendingWidget
import com.maxbl4.maxlauncher.home.ShortcutTarget
import com.maxbl4.maxlauncher.home.addWidget
import com.maxbl4.maxlauncher.home.allPlaced
import com.maxbl4.maxlauncher.home.appLabels
import com.maxbl4.maxlauncher.home.iconLabels
import com.maxbl4.maxlauncher.home.toDrawerLayout
import com.maxbl4.maxlauncher.home.toHomeLayout
import com.maxbl4.maxlauncher.settings.JsonFileStore
import com.maxbl4.maxlauncher.ui.LauncherTheme
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Imports a layout read from One UI Home by tools/import-oneui.ps1, which starts this activity
 * with the layout as Base64 JSON in [EXTRA_DATA]: home pages, dock and apps screen folders.
 *
 * Nothing changes until the user confirms; apps with the same label are chosen by the user, and
 * the current home layout and drawer folders are backed up. Widgets are created on the new layout
 * first (see [WidgetBindingActivity]); it replaces the current one once they are all handled.
 */
class ImportActivity : WidgetBindingActivity() {

    /** Report lines; null until the import ran. Shown once all widgets are handled. */
    private var report by mutableStateOf<List<String>?>(null)
    private var pending: Pending? = null

    /** The import result, applied once all widgets are handled. */
    private class Pending(var layout: HomeLayout, val drawer: DrawerLayout?, val head: List<String>)

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
        val shortcuts by produceState<List<Pair<Set<String>, ShortcutTarget>>?>(null, apps.isEmpty()) {
            if (apps.isNotEmpty()) value = app.appRepository.allShortcuts()
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Text(
                stringResource(R.string.import_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
            val done = report
            val found = shortcuts
            when {
                done != null -> Result(done, Modifier.weight(1f))
                creatingWidgets || apps.isEmpty() || found == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> Confirm(imported, apps, found, Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun Confirm(
        imported: ImportedLayout,
        apps: List<AppEntry>,
        shortcuts: List<Pair<Set<String>, ShortcutTarget>>,
        modifier: Modifier,
    ) {
        val candidates = remember(imported, apps) { imported.appLabels().associateWith { label -> matches(label, apps) } }
        // Standalone icons that are not apps may be app shortcuts ("Новое сообщение", ...).
        val shortcutFor = remember(imported, candidates, shortcuts) {
            imported.iconLabels()
                .filter { candidates[it].isNullOrEmpty() }
                .mapNotNull { label -> shortcuts.firstOrNull { (labels, _) -> labels.any { it.normalized() == label.normalized() } }?.let { label to it.second } }
                .toMap()
        }
        val choices = remember(candidates) {
            mutableStateMapOf<String, AppRef?>().apply { candidates.forEach { (label, found) -> put(label, found.firstOrNull()?.ref) } }
        }
        val ambiguous = candidates.filter { it.value.size > 1 }
        val missing = candidates.filter { it.value.isEmpty() && it.key !in shortcutFor }.keys
        val items = imported.pages.flatMap { it.items }
        val summary = stringResource(
            R.string.import_summary,
            imported.pages.size,
            items.count { it.type == "app" } + imported.dock.size,
            items.count { it.type == "folder" },
            items.count { it.type == "widget" },
        ) + "\n" + stringResource(R.string.import_summary_drawer, imported.drawerFolders.size)

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
                if (shortcutFor.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.import_shortcuts)) }
                    item { Text(shortcutFor.keys.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                Button(onClick = { runImport(imported, choices.toMap(), shortcutFor) }) { Text(stringResource(R.string.import_confirm)) }
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

    // --- Import ---

    /** Builds the layout, creates the widgets on it and only then replaces the current one. */
    private fun runImport(imported: ImportedLayout, choices: Map<String, AppRef?>, shortcuts: Map<String, ShortcutTarget>) {
        val result = imported.toHomeLayout({ choices[it] }, { shortcuts[it] })
        val drawer = if (imported.drawerFolders.isNotEmpty()) imported.toDrawerLayout { choices[it] } else null
        val (found, notFound) = result.widgets.map { it to findProvider(it) }.partition { it.second != null }
        val head = buildList {
            if (drawer != null) add(getString(R.string.import_report_drawer, drawer.folders.size))
            if (result.missing.isNotEmpty()) add(getString(R.string.import_report_missing, result.missing.joinToString(", ")))
            notFound.forEach { add(getString(R.string.import_widget_not_found, it.first.label)) }
        }
        pending = Pending(result.layout, drawer, head)
        createWidgets(found.map { it.first to it.second!! })
    }

    override fun onWidgetCreated(widget: PendingWidget, appWidgetId: Int, provider: AppWidgetProviderInfo) {
        val p = pending ?: return
        p.layout = p.layout.addWidget(
            appWidgetId, provider.provider.flattenToString(), app.widgets.userSerial(provider),
            widget.spanX, widget.spanY, widget.pageId, widget.x to widget.y,
        )
    }

    override fun onWidgetsDone(skipped: List<String>) {
        val p = pending ?: return
        pending = null
        app.homeRepository.replace(p.layout)
        if (p.drawer != null) {
            val previous = JsonFileStore.JSON.encodeToString(app.drawer.value.value)
            runCatching { File(filesDir, DRAWER_BACKUP_FILE).writeText(previous) }
            app.drawer.update { p.drawer }
        }
        report = listOf(getString(R.string.import_done, p.layout.pages.size, p.layout.allPlaced().size)) +
            p.head + skipped + getString(R.string.import_report_backup)
    }

    /**
     * The widget by its name; One UI shows either the widget's or the app's name. Several widgets
     * of an app may share a name: the one whose default size is closest wins.
     */
    private fun findProvider(widget: PendingWidget): AppWidgetProviderInfo? {
        val me = Process.myUserHandle()
        val providers = app.widgets.providers().filter { it.profile == me }
        val label = widget.label.normalized()
        val byWidgetName = providers.filter { it.loadLabel(packageManager).normalized() == label }
        val candidates = byWidgetName.ifEmpty {
            providers.filter { info ->
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.provider.packageName, 0)).toString().normalized() == label
                }.getOrDefault(false)
            }
        }
        return candidates.minByOrNull { sizeDistance(it, widget) }
    }

    private fun sizeDistance(info: AppWidgetProviderInfo, widget: PendingWidget): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0) {
            abs(info.targetCellWidth - widget.spanX) + abs(info.targetCellHeight - widget.spanY)
        } else {
            0
        }

    companion object {
        const val EXTRA_DATA = "data"
        private const val DRAWER_BACKUP_FILE = "drawer.before-import.json"
    }
}
