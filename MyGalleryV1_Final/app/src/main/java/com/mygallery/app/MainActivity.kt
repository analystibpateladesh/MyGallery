package com.mygallery.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentSender
import android.graphics.Bitmap
import android.util.Size
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

private data class Incoming(val uri: Uri, val name: String, val mime: String?)

private data class MediaRow(
    val uri: Uri,
    val name: String,
    val path: String,
    val mime: String?,
    val dateAdded: Long
) {
    val isVideo: Boolean get() = mime?.startsWith("video/") == true
    val folder: String get() = extractFolder(path)
}

private fun extractFolder(path: String): String {
    val normalized = path.trim('/')
    val prefix = when {
        normalized.startsWith("Pictures/MyGallery/") -> "Pictures/MyGallery/"
        normalized.startsWith("Movies/MyGallery/") -> "Movies/MyGallery/"
        else -> return ""
    }
    return normalized.removePrefix(prefix).trim('/')
}

private fun safeFolderName(value: String): String =
    value.trim().replace(Regex("[/\\\\:*?\"<>|]"), "-").replace(Regex("\\s+"), " ").trim()

private fun safeFileName(value: String, original: String): String {
    var name = value.trim().replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
    if (name.isBlank()) name = original
    val originalExtension = original.substringAfterLast('.', "")
    if (originalExtension.isNotBlank() && !name.contains('.')) name += ".${originalExtension}"
    return name
}

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<List<Incoming>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = readIncoming(intent)
        setContent { MyGalleryApp(incoming) { incoming = it } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = readIncoming(intent)
    }

    private fun readIncoming(i: Intent): List<Incoming> {
        val out = mutableListOf<Incoming>()
        when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += describe(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { out += describe(it) }
        }
        return out.distinctBy { it.uri }
    }

    private fun describe(uri: Uri): Incoming {
        var name = "IMG_${System.currentTimeMillis()}.jpg"
        val mime = contentResolver.getType(uri)
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return Incoming(uri, name, mime)
    }

    private fun mediaRows(): List<MediaRow> {
        val result = mutableListOf<MediaRow>()
        val collections = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        collections.forEach { collection ->
            contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED
                ),
                null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val n = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val p = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val m = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val d = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    result += MediaRow(
                        Uri.withAppendedPath(collection, c.getLong(id).toString()),
                        c.getString(n) ?: "",
                        c.getString(p) ?: "",
                        c.getString(m),
                        c.getLong(d)
                    )
                }
            }
        }
        return result.sortedByDescending { it.dateAdded }
    }

    private fun savedFolders(): MutableSet<String> =
        getSharedPreferences("mygallery", MODE_PRIVATE).getStringSet("folders", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun allFolders(rows: List<MediaRow>): List<String> =
        (rows.map { it.folder }.filter { it.isNotBlank() } + savedFolders())
            .map(::safeFolderName).filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase(Locale.getDefault()) }

    private fun saveFolder(folder: String): Boolean {
        val clean = safeFolderName(folder)
        if (clean.isBlank()) return false
        val folders = savedFolders()
        folders.add(clean)
        getSharedPreferences("mygallery", MODE_PRIVATE).edit().putStringSet("folders", folders).apply()
        return true
    }

    private fun organize(item: Incoming, folder: String, newName: String): Boolean {
        val isVideo = item.mime?.startsWith("video/") == true
        val base = if (isVideo) "Movies/MyGallery/" else "Pictures/MyGallery/"
        val cleanFolder = safeFolderName(folder)
        val targetPath = base + if (cleanFolder.isBlank()) "" else "$cleanFolder/"
        val displayName = safeFileName(newName, item.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
        }
        return try {
            if (item.uri.scheme == "content" && item.uri.authority?.contains("media", ignoreCase = true) == true) {
                val changed = contentResolver.update(item.uri, values, null, null) > 0
                if (changed && cleanFolder.isNotBlank()) saveFolder(cleanFolder)
                changed
            } else {
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                values.put(MediaStore.MediaColumns.MIME_TYPE, item.mime ?: if (isVideo) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val dest = contentResolver.insert(collection, values) ?: return false
                try {
                    contentResolver.openInputStream(item.uri).use { input ->
                        contentResolver.openOutputStream(dest).use { output ->
                            if (input == null || output == null) throw IllegalStateException("Cannot open media")
                            input.copyTo(output)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        contentResolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    }
                    if (cleanFolder.isNotBlank()) saveFolder(cleanFolder)
                    true
                } catch (e: Exception) {
                    contentResolver.delete(dest, null, null)
                    false
                }
            }
        } catch (_: Exception) { false }
    }

    private fun renameMedia(row: MediaRow, requestedName: String): Boolean {
        val newName = safeFileName(requestedName, row.name)
        if (newName.isBlank() || newName == row.name) return true
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        return contentResolver.update(row.uri, values, null, null) > 0
    }

    private fun moveMedia(row: MediaRow, folder: String): Boolean {
        val clean = safeFolderName(folder)
        val base = if (row.isVideo) "Movies/MyGallery/" else "Pictures/MyGallery/"
        val targetPath = base + if (clean.isBlank()) "" else "$clean/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
        }
        val changed = contentResolver.update(row.uri, values, null, null) > 0
        if (changed && clean.isNotBlank()) saveFolder(clean)
        return changed
    }

    private fun deleteMedia(row: MediaRow): Boolean =
        contentResolver.delete(row.uri, null, null) > 0

    private fun copyMediaTo(row: MediaRow, folder: String, newName: String = row.name): Boolean {
        // Kept as a last-resort migration path for unusual MediaStore providers.
        val isVideo = row.isVideo
        val base = if (isVideo) "Movies/MyGallery/" else "Pictures/MyGallery/"
        val clean = safeFolderName(folder)
        val targetPath = base + if (clean.isBlank()) "" else "$clean/"
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeFileName(newName, row.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
            put(MediaStore.MediaColumns.MIME_TYPE, row.mime ?: if (isVideo) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val dest = contentResolver.insert(collection, values) ?: return false
        return try {
            contentResolver.openInputStream(row.uri).use { input ->
                contentResolver.openOutputStream(dest).use { output ->
                    if (input == null || output == null) throw IllegalStateException("Cannot open media")
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            if (clean.isNotBlank()) saveFolder(clean)
            true
        } catch (e: Exception) {
            contentResolver.delete(dest, null, null)
            false
        }
    }

    private fun refreshFoldersAndMedia(): List<MediaRow> = mediaRows()

    @Composable
    private fun MediaThumbnail(uri: Uri, isVideo: Boolean, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
            value = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(360, 360), null)
                    } else null
                } catch (_: Exception) { null }
            }
        }
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            if (isVideo) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(7.dp).size(34.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = .62f)
                ) {
                    Icon(Icons.Default.PlayArrow, "Video", tint = Color.White, modifier = Modifier.padding(7.dp))
                }
            }
        }
    }

    private fun requestWriteAccessAndRun(
        rows: List<MediaRow>,
        operation: () -> Boolean,
        onFinished: (Boolean) -> Unit
    ) {
        val uris = rows.map { it.uri }.distinct()
        if (uris.isEmpty()) { onFinished(false); return }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (operation()) {
                    onFinished(true)
                    return
                }
            } catch (_: SecurityException) {
                // Ask Android for write access below.
            }
            pendingMediaAction = {
                val success = try { operation() } catch (_: Exception) { false }
                onFinished(success)
            }
            val request = MediaStore.createWriteRequest(contentResolver, uris)
            mediaWriteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            try {
                onFinished(operation())
            } catch (e: android.app.RecoverableSecurityException) {
                pendingMediaAction = {
                    val success = try { operation() } catch (_: Exception) { false }
                    onFinished(success)
                }
                mediaWriteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } catch (_: SecurityException) {
                onFinished(false)
            }
        }
    }

    private fun requestDeleteAccessAndRun(
        rows: List<MediaRow>,
        onFinished: (Boolean) -> Unit,
        operation: (() -> Boolean)? = null
    ) {
        val uris = rows.map { it.uri }.distinct()
        if (uris.isEmpty()) { onFinished(false); return }
        val deleteOperation = operation ?: { rows.count { deleteMedia(it) } == rows.size }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (deleteOperation()) { onFinished(true); return }
            } catch (_: SecurityException) { }
            pendingMediaAction = {
                val success = try { deleteOperation() } catch (_: Exception) { false }
                onFinished(success)
            }
            val request = MediaStore.createTrashRequest(contentResolver, uris, true)
            mediaWriteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            try {
                onFinished(deleteOperation())
            } catch (e: android.app.RecoverableSecurityException) {
                pendingMediaAction = {
                    val success = try { deleteOperation() } catch (_: Exception) { false }
                    onFinished(success)
                }
                mediaWriteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } catch (_: SecurityException) {
                onFinished(false)
            }
        }
    }

    private var pendingMediaAction: (() -> Unit)? = null
    private val mediaWriteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pendingMediaAction
        pendingMediaAction = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun MyGalleryApp(items: List<Incoming>, reset: (List<Incoming>) -> Unit) {
        var granted by remember { mutableStateOf(checkPermission()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = checkPermission() }
        if (!granted) {
            LaunchedEffect(Unit) {
                launcher.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }

        var tab by remember { mutableIntStateOf(0) }
        var search by remember { mutableStateOf("") }
        var rows by remember { mutableStateOf<List<MediaRow>>(emptyList()) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(granted) {
            if (granted) rows = withContext(Dispatchers.IO) { mediaRows() }
        }
        fun refresh() { scope.launch { rows = withContext(Dispatchers.IO) { mediaRows() } } }
        var selected by remember { mutableStateOf<MediaRow?>(null) }
        var renameTarget by remember { mutableStateOf<MediaRow?>(null) }
        var moveTarget by remember { mutableStateOf<MediaRow?>(null) }
        var deleteTarget by remember { mutableStateOf<MediaRow?>(null) }
        var detailTarget by remember { mutableStateOf<MediaRow?>(null) }
        var viewerTarget by remember { mutableStateOf<MediaRow?>(null) }
        var bulkMove by remember { mutableStateOf(false) }
        var bulkDelete by remember { mutableStateOf(false) }
        var bulkShare by remember { mutableStateOf(false) }
        var createFolder by remember { mutableStateOf(false) }
        var selectMode by remember { mutableStateOf(false) }
        var selectedUris by remember { mutableStateOf(setOf<String>()) }
        var toast by remember { mutableStateOf("") }
        var mediaAccessDialog by remember { mutableStateOf(false) }

        fun clearSelection() { selectedUris = emptySet(); selectMode = false }
        fun selectedRows(): List<MediaRow> = rows.filter { it.uri.toString() in selectedUris }

        MaterialTheme {
            if (items.isNotEmpty()) {
                ImportScreen(
                    items = items,
                    folders = allFolders(rows),
                    onSave = { folder, names ->
                        var ok = 0
                        items.forEachIndexed { index, item -> if (organize(item, folder, names[index])) ok++ }
                        reset(emptyList()); refresh(); toast = "$ok of ${items.size} item(s) added to MyGallery"
                    },
                    onCancel = { reset(emptyList()) }
                )
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (selectMode) "${selectedUris.size} selected" else "MyGallery") },
                            navigationIcon = {
                                if (selectMode) IconButton(onClick = { clearSelection() }) { Icon(Icons.Default.Close, "Cancel selection") }
                            },
                            actions = {
                                if (selectMode) {
                                    IconButton(enabled = selectedUris.isNotEmpty(), onClick = { bulkShare = true }) { Icon(Icons.Default.Share, "Share") }
                                    IconButton(enabled = selectedUris.isNotEmpty(), onClick = { bulkMove = true }) { Icon(Icons.Default.DriveFileMove, "Move") }
                                    IconButton(enabled = selectedUris.isNotEmpty(), onClick = { bulkDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                                    TextButton(onClick = { rows.forEach { selectedUris = selectedUris + it.uri.toString() } }) { Text("All") }
                                } else {
                                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                                    IconButton(onClick = { mediaAccessDialog = true }) { Icon(Icons.Default.Settings, "Media access") }
                                }
                            }
                        )
                    },
                    floatingActionButton = {
                        if (tab == 3 && !selectMode) FloatingActionButton(onClick = { createFolder = true }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
                    }
                ) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            placeholder = { Text("Search photos, videos or folders") },
                            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
                        )
                        val tabs = listOf("All", "Photos", "Videos", "Folders")
                        PrimaryTabRow(selectedTabIndex = tab) {
                            tabs.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { if (!selectMode) tab = i }, text = { Text(label) }) }
                        }

                        val filtered = rows.filter { search.isBlank() || it.name.contains(search, true) || it.folder.contains(search, true) }
                            .filter { tab == 0 || (tab == 1 && !it.isVideo) || (tab == 2 && it.isVideo) }

                        if (tab == 3 && !selectMode) {
                            val folders = allFolders(rows).filter { search.isBlank() || it.contains(search, true) }
                            if (folders.isEmpty()) {
                                EmptyFolderState(onCreate = { createFolder = true })
                            } else {
                                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                                    items(folders) { folder ->
                                        val count = rows.count { it.folder == folder }
                                        ListItem(
                                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(
                                                onClick = { search = folder; tab = 0 },
                                                onLongClick = { createFolder = true }
                                            ),
                                            leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                            headlineContent = { Text(folder) },
                                            supportingContent = { Text("$count item(s)") },
                                            trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                                        )
                                    }
                                }
                            }
                        } else if (filtered.isEmpty()) {
                            EmptyState("Your gallery is empty", "Share photos or videos to MyGallery to organize them.")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(110.dp), modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                gridItems(filtered, key = { it.uri.toString() }) { row ->
                                    GalleryTile(
                                        row = row,
                                        selected = row.uri.toString() in selectedUris,
                                        selectMode = selectMode,
                                        onClick = {
                                            if (selectMode) {
                                                val key = row.uri.toString()
                                                selectedUris = if (key in selectedUris) selectedUris - key else selectedUris + key
                                            } else selected = row
                                        },
                                        onLongClick = {
                                            if (!selectMode) {
                                                selectMode = true
                                                selectedUris = setOf(row.uri.toString())
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        selected?.let { row ->
            MediaMenuDialog(row, { selected = null }, { selected = null; viewerTarget = row }, { selected = null; shareMedia(this@MainActivity, row) }, { selected = null; renameTarget = row }, { selected = null; moveTarget = row }, { selected = null; detailTarget = row }, { selected = null; deleteTarget = row })
        }

        renameTarget?.let { row ->
            TextInputDialog(
                title = "Rename",
                label = "New filename",
                initial = row.name,
                onConfirm = { value ->
                    val finalName = safeFileName(value, row.name)
                    renameTarget = null
                    requestWriteAccessAndRun(
                        rows = listOf(row),
                        operation = { renameMedia(row, finalName) },
                        onFinished = { success ->
                            if (success) { refresh(); toast = "Renamed to $finalName" }
                            else toast = "Rename was not completed. Please allow MyGallery to modify this media when Android asks."
                        }
                    )
                },
                onDismiss = { renameTarget = null }
            )
        }

        moveTarget?.let { row ->
            FolderDialog(
                title = "Move to folder",
                folders = allFolders(rows),
                current = row.folder,
                onConfirm = { folder ->
                    moveTarget = null
                    requestWriteAccessAndRun(
                        rows = listOf(row),
                        operation = { moveMedia(row, folder) },
                        onFinished = { success ->
                            if (success) { refresh(); toast = "Moved to ${folder.ifBlank { "MyGallery" }}" }
                            else toast = "Move was not completed. Please allow MyGallery to modify this media."
                        }
                    )
                },
                onDismiss = { moveTarget = null }
            )
        }

        deleteTarget?.let { row ->
            ConfirmDeleteDialog(
                title = "Delete item?",
                message = "This will remove \"${row.name}\" from your device gallery.",
                onConfirm = {
                    deleteTarget = null
                    requestDeleteAccessAndRun(
                        rows = listOf(row),
                        onFinished = { success ->
                            if (success) { refresh(); toast = "Deleted" }
                            else toast = "Delete was not completed. Please allow MyGallery to modify this media."
                        }
                    )
                },
                onDismiss = { deleteTarget = null }
            )
        }

        if (bulkMove) {
            FolderDialog(
                title = "Move ${selectedUris.size} items",
                folders = allFolders(rows),
                current = "",
                onConfirm = { folder ->
                    val targets = selectedRows()
                    bulkMove = false
                    clearSelection()
                    var moved = 0
                    requestWriteAccessAndRun(
                        rows = targets,
                        operation = {
                            moved = targets.count { moveMedia(it, folder) }
                            moved == targets.size
                        },
                        onFinished = { success ->
                            refresh()
                            toast = if (success) "$moved item(s) moved to ${folder.ifBlank { "MyGallery" }}" else "$moved of ${targets.size} item(s) moved"
                        }
                    )
                },
                onDismiss = { bulkMove = false }
            )
        }

        if (bulkDelete) {
            ConfirmDeleteDialog(
                title = "Delete ${selectedUris.size} items?",
                message = "The selected photos/videos will be removed from your device gallery.",
                onConfirm = {
                    val targets = selectedRows()
                    bulkDelete = false
                    clearSelection()
                    var deleted = 0
                    requestDeleteAccessAndRun(
                        rows = targets,
                        onFinished = { success ->
                            deleted = if (success) targets.size else deleted
                            refresh()
                            toast = if (success) "$deleted item(s) deleted" else "Delete was not completed. Please allow MyGallery to modify the selected media."
                        },
                        operation = {
                            deleted = targets.count { deleteMedia(it) }
                            deleted == targets.size
                        }
                    )
                },
                onDismiss = { bulkDelete = false }
            )
        }

        if (bulkShare) {
            LaunchedEffect(selectedUris) {
                val targets = selectedRows()
                if (targets.isNotEmpty()) shareMedia(this@MainActivity, targets)
                bulkShare = false
                clearSelection()
            }
        }

        if (createFolder) {
            TextInputDialog(
                title = "New MyGallery folder",
                label = "Folder name",
                initial = "",
                onConfirm = { value ->
                    val clean = safeFolderName(value)
                    if (saveFolder(clean)) toast = "Folder created: $clean" else toast = "Enter a valid folder name"
                    createFolder = false
                },
                onDismiss = { createFolder = false }
            )
        }

        if (mediaAccessDialog) {
            AlertDialog(
                onDismissRequest = { mediaAccessDialog = false },
                title = { Text("Media management access") },
                text = {
                    Text("Android protects photos and videos created by other apps. Granting MyGallery media-management access lets Rename, Move and Delete work more like a normal gallery without asking for approval on every operation. You can change this later in Android Settings.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        mediaAccessDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try {
                                startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                            } catch (_: Exception) {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                            }
                        } else {
                            toast = "Android media management access is available on Android 12 and newer."
                        }
                    }) { Text("Open settings") }
                },
                dismissButton = { TextButton(onClick = { mediaAccessDialog = false }) { Text("Not now") } }
            )
        }

        viewerTarget?.let { row -> FullscreenViewer(row) { viewerTarget = null } }

        if (toast.isNotBlank()) {
            LaunchedEffect(toast) { kotlinx.coroutines.delay(2200); toast = "" }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(modifier = Modifier.padding(16.dp).navigationBarsPadding(), shape = RoundedCornerShape(50), tonalElevation = 6.dp) { Text(toast, Modifier.padding(horizontal = 18.dp, vertical = 11.dp)) }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GalleryTile(row: MediaRow, selected: Boolean, selectMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
        Box(
            Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            MediaThumbnail(row.uri, row.isVideo, Modifier.fillMaxSize())
            if (selectMode) {
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = .55f)) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.size(34.dp), colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, uncheckedColor = Color.White))
                }
            }
        }
    }

    @Composable
    private fun EmptyFolderState(onCreate: () -> Unit) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp)); Text("No folders yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp)); Text("Create a folder first, then move or add photos and videos to it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp)); Button(onClick = onCreate) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Create new folder") }
            }
        }
    }

    @Composable
    private fun EmptyState(title: String, subtitle: String) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(5.dp)); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun MediaMenuDialog(row: MediaRow, onDismiss: () -> Unit, onOpen: () -> Unit, onShare: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDetails: () -> Unit, onDelete: () -> Unit) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(if (row.isVideo) "Video" else "Photo") }, text = {
            Column {
                Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                MenuAction("Open", Icons.Default.OpenInNew, onOpen)
                MenuAction("Share", Icons.Default.Share, onShare)
                MenuAction("Rename", Icons.Default.Edit, onRename)
                MenuAction("Move to folder", Icons.Default.DriveFileMove, onMove)
                MenuAction("Details", Icons.Default.Info, onDetails)
                MenuAction("Delete", Icons.Default.Delete, onDelete)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }

    @Composable
    private fun MenuAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = action).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(16.dp)); Text(label)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun ImportScreen(items: List<Incoming>, folders: List<String>, onSave: (String, List<String>) -> Unit, onCancel: () -> Unit) {
        var folder by remember { mutableStateOf("") }
        var createFolder by remember { mutableStateOf(false) }
        var names by remember(items) { mutableStateOf(items.map { it.name }) }
        var renameIndex by remember { mutableStateOf<Int?>(null) }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Add to MyGallery") }, navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") } }) },
            bottomBar = {
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(14.dp)) {
                        Text("${items.size} selected • ${if (folder.isBlank()) "MyGallery root" else folder}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onSave(folder, names) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("Add ${items.size} item(s) to MyGallery") }
                    }
                }
            }
        ) { pad ->
            LazyColumn(modifier = Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    Text("Selected media", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    LazyVerticalGrid(columns = GridCells.Adaptive(105.dp), modifier = Modifier.height(((items.size + 2) / 3 * 125).coerceAtMost(500).dp), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        gridItems(items.withIndex().toList()) { indexed ->
                            val i = indexed.index; val item = indexed.value
                            Column(Modifier.combinedClickable(onClick = { renameIndex = i }, onLongClick = { renameIndex = i })) {
                                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(9.dp))) {
                                    AsyncImage(item.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    if (item.mime?.startsWith("video/") == true) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                                }
                                Text(names[i], maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    Text("Tap a thumbnail to change its filename. Original names are kept by default.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    HorizontalDivider(); Text("Folder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
                item { FolderChoice("MyGallery root", folder.isBlank()) { folder = "" } }
                items(folders) { f -> FolderChoice(f, folder == f) { folder = f } }
                item {
                    OutlinedButton(onClick = { createFolder = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Create / use new folder") }
                }
            }
        }

        renameIndex?.let { index ->
            TextInputDialog(
                title = "Change filename",
                label = "Filename",
                initial = names[index],
                onConfirm = { value ->
                    names = names.toMutableList().also { list -> list[index] = safeFileName(value, list[index]) }
                    renameIndex = null
                },
                onDismiss = { renameIndex = null }
            )
        }
        if (createFolder) {
            TextInputDialog(
                title = "New MyGallery folder",
                label = "Folder name",
                initial = "",
                onConfirm = { value -> folder = safeFolderName(value); createFolder = false },
                onDismiss = { createFolder = false }
            )
        }
    }

    @Composable
    private fun FolderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick); Icon(Icons.Default.Folder, null, modifier = Modifier.padding(horizontal = 8.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun TextInputDialog(title: String, label: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
        var value by remember(initial) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    }

    @Composable
    private fun FolderDialog(title: String, folders: List<String>, current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
        var folder by remember(current) { mutableStateOf(current) }
        var newFolder by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
            Column {
                FolderChoice("MyGallery root", folder.isBlank()) { folder = "" }
                folders.forEach { f -> FolderChoice(f, folder == f) { folder = f } }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { newFolder = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Create new folder") }
            }
        }, confirmButton = { TextButton(onClick = { onConfirm(safeFolderName(folder)) }) { Text("Move") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })

        if (newFolder) {
            TextInputDialog(
                title = "New folder",
                label = "Folder name",
                initial = "",
                onConfirm = { value ->
                    val clean = safeFolderName(value)
                    if (saveFolder(clean)) folder = clean
                    newFolder = false
                },
                onDismiss = { newFolder = false }
            )
        }
    }

    @Composable
    private fun ConfirmDeleteDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    }

    @Composable
    private fun FullscreenViewer(row: MediaRow, onDismiss: () -> Unit) {
        val context = LocalContext.current
        AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.fillMaxWidth(), title = { Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (row.isVideo) {
                    AndroidView(factory = { ctx -> VideoView(ctx).apply { setVideoURI(row.uri); setMediaController(MediaController(ctx)); setOnPreparedListener { it.isLooping = false; start() } } }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 500.dp))
                } else AsyncImage(row.uri, row.name, Modifier.fillMaxWidth().heightIn(max = 500.dp), contentScale = ContentScale.Fit)
            }
        }, confirmButton = { TextButton(onClick = { shareMedia(context, row) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text("Share") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }

    private fun shareMedia(context: Context, row: MediaRow) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = row.mime ?: if (row.isVideo) "video/*" else "image/*"; putExtra(Intent.EXTRA_STREAM, row.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = android.content.ClipData.newRawUri(row.name, row.uri) }
        context.startActivity(Intent.createChooser(intent, "Share ${if (row.isVideo) "video" else "photo"}"))
    }

    private fun shareMedia(context: Context, rows: List<MediaRow>) {
        if (rows.isEmpty()) return
        if (rows.size == 1) { shareMedia(context, rows.first()); return }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (rows.all { it.isVideo }) "video/*" else if (rows.all { !it.isVideo }) "image/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(rows.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(rows.first().name, rows.first().uri)
            rows.drop(1).forEach { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        context.startActivity(Intent.createChooser(intent, "Share ${rows.size} items"))
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}
