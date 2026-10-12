package com.maxbl4.maxlauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.maxbl4.maxlauncher.backup.LauncherBackup
import com.maxbl4.maxlauncher.backup.RestorePlan
import com.maxbl4.maxlauncher.backup.toRestorePlan
import com.maxbl4.maxlauncher.home.HomeLayout
import com.maxbl4.maxlauncher.home.PendingWidget
import com.maxbl4.maxlauncher.home.addWidget
import com.maxbl4.maxlauncher.home.allPlaced
import com.maxbl4.maxlauncher.home.resizeGrid
import com.maxbl4.maxlauncher.home.widgets
import com.maxbl4.maxlauncher.settings.DRAWER_GRIDS
import com.maxbl4.maxlauncher.settings.HOME_GRIDS
import com.maxbl4.maxlauncher.settings.JsonFileStore
import com.maxbl4.maxlauncher.ui.AppChecklist
import com.maxbl4.maxlauncher.ui.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** Launcher settings, opened from the edit mode bar. Also saves everything to a file and restores it. */
class SettingsActivity : WidgetBindingActivity() {

    /** A file read and waiting for the user's confirmation. */
    private var confirm by mutableStateOf<LauncherBackup?>(null)
    /** Lines shown after a restore. */
    private var report by mutableStateOf<List<String>?>(null)
    private var restoring: Restoring? = null

    private class Restoring(var layout: HomeLayout, val plan: RestorePlan, val head: List<String>)

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export(uri)
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) read(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        app,
                        onExport = { exportFile.launch("MaxLauncher-${LocalDate.now()}.json") },
                        onImport = { importFile.launch(arrayOf("*/*")) },
                    )
                    confirm?.let { backup -> ConfirmRestore(backup) }
                    report?.let { lines -> RestoreReport(lines) }
                }
            }
        }
    }

    @Composable
    private fun ConfirmRestore(backup: LauncherBackup) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_confirm_text,
                        backup.home.pages.size,
                        backup.home.allPlaced().size,
                        backup.home.widgets().size,
                        backup.drawer.folders.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    restore(backup)
                }) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.picker_cancel)) } },
        )
    }

    @Composable
    private fun RestoreReport(lines: List<String>) {
        AlertDialog(
            onDismissRequest = { report = null },
            text = { Text(lines.joinToString("\n\n")) },
            confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }

    private fun export(uri: Uri) {
        val home = app.homeRepository.layout.value ?: return
        val backup = LauncherBackup(home = home, drawer = app.drawer.value.value, settings = app.settings.value.value)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(EXPORT_JSON.encodeToString(backup).toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(this@SettingsActivity, if (saved) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun read(uri: Uri) {
        lifecycleScope.launch {
            val backup = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                    JsonFileStore.JSON.decodeFromString<LauncherBackup>(text).takeIf { it.format <= LauncherBackup.FORMAT }
                }.getOrNull()
            }
            if (backup == null) {
                Toast.makeText(this@SettingsActivity, R.string.backup_bad_file, Toast.LENGTH_LONG).show()
            } else {
                confirm = backup
            }
        }
    }

    /** Creates the widgets that are missing on this phone, then replaces everything at once. */
    private fun restore(backup: LauncherBackup) {
        val current = app.homeRepository.layout.value ?: return
        val previous = LauncherBackup(home = current, drawer = app.drawer.value.value, settings = app.settings.value.value)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(filesDir, BEFORE_RESTORE_FILE).writeText(JsonFileStore.JSON.encodeToString(previous)) }
        }

        val apps = app.appRepository.apps.value
        val refs = apps.map { it.ref }.toSet()
        val packages = refs.map { it.component.substringBefore('/') to it.userSerial }.toSet()
        val plan = backup.toRestorePlan(
            isInstalled = { it in refs },
            isPackageInstalled = { pkg, serial -> (pkg to serial) in packages },
            isWidgetBound = { item -> runCatching { app.widgets.info(item.appWidgetId)?.provider?.flattenToString() == item.provider }.getOrDefault(false) },
        )

        val me = Process.myUserHandle()
        val providers = app.widgets.providers().sortedBy { it.profile != me }
        val found = mutableListOf<Pair<PendingWidget, AppWidgetProviderInfo>>()
        val head = mutableListOf(getString(R.string.backup_done))
        if (plan.missingPackages.isNotEmpty()) head += getString(R.string.import_report_missing, plan.missingPackages.joinToString(", "))
        for (widget in plan.widgets) {
            val provider = providers.firstOrNull { it.provider.flattenToString() == widget.label }
            if (provider == null) {
                head += getString(R.string.import_widget_not_found, widget.label.substringBefore('/'))
            } else {
                found += widget.copy(label = provider.loadLabel(packageManager)) to provider
            }
        }
        restoring = Restoring(plan.home, plan, head)
        createWidgets(found)
    }

    override fun onWidgetCreated(widget: PendingWidget, appWidgetId: Int, provider: AppWidgetProviderInfo) {
        val r = restoring ?: return
        r.layout = r.layout.addWidget(
            appWidgetId, provider.provider.flattenToString(), app.widgets.userSerial(provider),
            widget.spanX, widget.spanY, widget.pageId, widget.x to widget.y,
        )
    }

    override fun onWidgetsDone(skipped: List<String>) {
        val r = restoring ?: return
        restoring = null
        app.homeRepository.replace(r.layout)
        app.drawer.update { r.plan.drawer }
        app.settings.update { r.plan.settings }
        report = r.head + skipped
    }

    private companion object {
        const val BEFORE_RESTORE_FILE = "before-restore.json"
        val EXPORT_JSON = Json(JsonFileStore.JSON) { prettyPrint = true }
    }
}

@Composable
private fun SettingsScreen(app: LauncherApplication, onExport: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val settings by app.settings.value.collectAsStateWithLifecycle()
    val layout by app.homeRepository.layout.collectAsStateWithLifecycle()
    val apps by app.appRepository.apps.collectAsStateWithLifecycle()
    var homeGridDialog by remember { mutableStateOf(false) }
    var drawerGridDialog by remember { mutableStateOf(false) }
    var hiddenPicker by remember { mutableStateOf(false) }
    var lockServiceEnabled by remember { mutableStateOf(LockScreenService.isEnabled(context)) }
    var lockServiceDialog by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { lockServiceEnabled = LockScreenService.isEnabled(context) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )

        Section(stringResource(R.string.settings_home))
        layout?.let { home ->
            ValueRow(stringResource(R.string.settings_grid), "${home.columns} × ${home.rows}") { homeGridDialog = true }
        }
        SwitchRow(
            title = stringResource(R.string.settings_lock_layout),
            subtitle = stringResource(R.string.settings_lock_layout_summary),
            checked = settings.lockLayout,
        ) { checked -> app.settings.update { it.copy(lockLayout = checked) } }
        SwitchRow(
            title = stringResource(R.string.settings_dim_wallpaper),
            subtitle = null,
            checked = settings.dimWallpaperInDark,
        ) { checked -> app.settings.update { it.copy(dimWallpaperInDark = checked) } }

        Section(stringResource(R.string.settings_drawer))
        ValueRow(stringResource(R.string.settings_grid), "${settings.drawerColumns} × ${settings.drawerRows}") { drawerGridDialog = true }
        ValueRow(
            stringResource(R.string.settings_hidden_apps),
            if (settings.hiddenApps.isEmpty()) stringResource(R.string.settings_none) else settings.hiddenApps.size.toString(),
        ) { hiddenPicker = true }

        Section(stringResource(R.string.settings_gestures))
        SwitchRow(
            title = stringResource(R.string.settings_double_tap_lock),
            subtitle = when {
                !settings.doubleTapToLock -> null
                lockServiceEnabled -> null
                else -> stringResource(R.string.settings_lock_service_needed)
            },
            checked = settings.doubleTapToLock,
            onSubtitleClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        ) { checked ->
            app.settings.update { it.copy(doubleTapToLock = checked) }
            if (checked && !lockServiceEnabled) lockServiceDialog = true
        }

        Section(stringResource(R.string.settings_backup))
        ValueRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_summary), onExport)
        ValueRow(stringResource(R.string.settings_import), null, onImport)

        HorizontalDivider(Modifier.padding(top = 16.dp))
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(24.dp),
        )
    }

    if (homeGridDialog) {
        val current = layout?.let { it.columns to it.rows }
        GridDialog(HOME_GRIDS, current, onDismiss = { homeGridDialog = false }) { (columns, rows) ->
            app.homeRepository.update { it.resizeGrid(columns, rows) }
        }
    }
    if (drawerGridDialog) {
        GridDialog(DRAWER_GRIDS, settings.drawerColumns to settings.drawerRows, onDismiss = { drawerGridDialog = false }) { (columns, rows) ->
            app.settings.update { it.copy(drawerColumns = columns, drawerRows = rows) }
        }
    }
    if (lockServiceDialog) {
        AlertDialog(
            onDismissRequest = { lockServiceDialog = false },
            title = { Text(stringResource(R.string.settings_double_tap_lock)) },
            text = { Text(stringResource(R.string.lock_service_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    lockServiceDialog = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(stringResource(R.string.lock_service_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { lockServiceDialog = false }) { Text(stringResource(R.string.picker_cancel)) }
            },
        )
    }
    if (hiddenPicker) {
        AppChecklist(
            title = stringResource(R.string.settings_hidden_apps),
            apps = apps,
            selected = settings.hiddenApps,
            dark = isSystemInDarkTheme(),
            onDone = { refs ->
                hiddenPicker = false
                app.settings.update { it.copy(hiddenApps = refs.toSet()) }
            },
            onCancel = { hiddenPicker = false },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ValueRow(title: String, value: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(title, fontSize = 17.sp)
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onSubtitleClick: (() -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = if (onSubtitleClick != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GridDialog(
    options: List<Pair<Int, Int>>,
    current: Pair<Int, Int>?,
    onDismiss: () -> Unit,
    onSelect: (Pair<Int, Int>) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_grid)) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == current) {
                                onSelect(option)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text("${option.first} × ${option.second}", fontSize = 17.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.picker_cancel)) } },
    )
}
