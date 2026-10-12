package com.maxbl4.maxlauncher.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.AtomicFile
import android.util.Log
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppRepository
import com.maxbl4.maxlauncher.widgets.WidgetHostManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns the home screen layout: loads it from a JSON file, applies edits and writes every change
 * back. The first start gets one page and a dock with the default phone, messages, browser and
 * camera apps.
 */
class HomeRepository(
    private val context: Context,
    private val appRepository: AppRepository,
    private val widgets: WidgetHostManager,
) {

    private val file = AtomicFile(File(context.filesDir, "home_layout.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _layout = MutableStateFlow<HomeLayout?>(null)

    /** Null until the layout has been loaded. */
    val layout: StateFlow<HomeLayout?> = _layout.asStateFlow()

    init {
        scope.launch {
            val loaded = kotlinx.coroutines.withContext(io) { read() }
            _layout.value = loaded ?: createDefault().also { save(it) }
        }
        scope.launch {
            appRepository.packageRemovals.collect { removal ->
                update { layout -> layout.removePackage(removal.packageName, removal.userSerial) }
            }
        }
    }

    /** Applies an edit. Must be called on the main thread. */
    fun update(edit: (HomeLayout) -> HomeLayout) {
        val old = _layout.value ?: return
        val new = edit(old)
        if (new == old) return
        _layout.value = new
        save(new)
        syncPinnedShortcuts(old, new)
        // Widgets taken off the home screen give their ids back to the host.
        val kept = new.widgets().map { it.appWidgetId }.toSet()
        old.widgets().map { it.appWidgetId }.filterNot { it in kept }.forEach(widgets::delete)
    }

    /** Replaces the whole layout (import); the current one is kept in [BACKUP_FILE]. */
    fun replace(layout: HomeLayout) {
        val current = _layout.value ?: return
        val text = json.encodeToString(current)
        scope.launch(io) {
            runCatching { File(context.filesDir, BACKUP_FILE).writeText(text) }
                .onFailure { Log.e(TAG, "Backing up the home layout failed", it) }
        }
        update { layout }
    }

    private fun save(layout: HomeLayout) {
        scope.launch(io) {
            val stream = file.startWrite()
            try {
                stream.write(json.encodeToString(layout).toByteArray())
                file.finishWrite(stream)
            } catch (e: Exception) {
                Log.e(TAG, "Saving the home layout failed", e)
                file.failWrite(stream)
            }
        }
    }

    private fun read(): HomeLayout? = try {
        if (file.baseFile.exists()) json.decodeFromString<HomeLayout>(String(file.readFully())) else null
    } catch (e: Exception) {
        Log.e(TAG, "Reading the home layout failed; starting over", e)
        null
    }

    /** Shortcuts removed from the home screen must be unpinned, or the owning app keeps them. */
    private fun syncPinnedShortcuts(old: HomeLayout, new: HomeLayout) {
        val before = old.shortcuts().groupBy { it.packageName to it.userSerial }
        val after = new.shortcuts().groupBy { it.packageName to it.userSerial }
        for (key in before.keys + after.keys) {
            val ids = after[key].orEmpty().map { it.shortcutId }.sorted()
            if (before[key].orEmpty().map { it.shortcutId }.sorted() != ids) {
                appRepository.setPinnedShortcuts(key.first, key.second, ids)
            }
        }
    }

    private suspend fun createDefault(): HomeLayout {
        var layout = HomeLayout.initial()
        val apps = withTimeoutOrNull(5_000) { appRepository.apps.first { it.isNotEmpty() } }.orEmpty()
        defaultDockPackages()
            .mapNotNull { pkg -> apps.firstOrNull { it.key.packageName == pkg && !it.isWork } }
            .distinctBy { it.ref }
            .take(layout.dockSlots)
            .forEachIndexed { slot, entry: AppEntry ->
                val (id, next) = layout.allocateId()
                layout = next.withItems(Container.Dock, next.dock + Placed(AppItem(id, entry.ref), slot, 0))
            }
        return layout
    }

    private fun defaultDockPackages(): List<String> {
        val pm = context.packageManager
        fun resolve(intent: Intent): String? =
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeIf { it != "android" } // the "choose an app" resolver
        return listOfNotNull(
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage,
            Telephony.Sms.getDefaultSmsPackage(context),
            resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)),
            resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        )
    }

    companion object {
        private const val TAG = "HomeRepository"
        const val BACKUP_FILE = "home_layout.before-import.json"
    }
}

private fun HomeLayout.shortcuts(): List<ShortcutItem> =
    containers.flatMap { items(it) }.mapNotNull { it.item as? ShortcutItem }

/** Removes apps and shortcuts of an uninstalled package. */
fun HomeLayout.removePackage(packageName: String, userSerial: Long): HomeLayout {
    val refs = containers.flatMap { items(it) }
        .flatMap { p ->
            when (val item = p.item) {
                is AppItem -> listOf(item.app)
                is FolderItem -> item.apps.map { it.app }
                else -> emptyList()
            }
        }
        .filter { it.userSerial == userSerial && it.component.substringBefore('/') == packageName }
        .distinct()
    return refs.fold(this) { layout, ref -> layout.removeApp(ref) }
        .removeShortcutsOf(packageName, userSerial)
        .removeWidgetsOf(packageName, userSerial)
}
