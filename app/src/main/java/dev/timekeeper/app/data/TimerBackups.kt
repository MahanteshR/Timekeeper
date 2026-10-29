package dev.timekeeper.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.timekeeper.core.BackupRetryPolicy
import dev.timekeeper.core.TimerConfiguration
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray

data class BackupStatus(
    val connected: Boolean = false,
    val fileName: String = "Timekeeper backup",
    val savedAtMs: Long = 0,
    val changed: Boolean = false,
    val writing: Boolean = false,
    val error: String? = null,
    val retryAtMs: Long = 0,
)

/** All connection changes happen on Main; provider work cannot commit connection metadata. */
class TimerBackups(context: Context, private val store: TimekeeperStore) {
    private val resolver = context.contentResolver
    private val disk = context.getSharedPreferences("timekeeper-backup", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = ProviderIo()
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    @Volatile private var folder = disk.getString("folder", null)
    private var savedHash = disk.getString("hash", null)
    private var pendingHash: String? = null
    @Volatile private var connectingFolder: String? = null
    private var writer: Job? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(BackupStatus(folder != null,
        disk.getString("name", null) ?: "Timekeeper backup", disk.getLong("savedAt", 0),
        error = disk.getString("error", null)))
    val status = mutable.asStateFlow()
    private data class Copy(val uri: Uri, val hash: String, val at: Long)

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            store.snapshots.filter { it.storageIssue == null }.map { runCatching { store.configuration(it) } }
                .distinctUntilChangedBy { it.getOrNull() }.map { config -> config.mapCatching { hash(it) } }
                .onEach { result ->
                    result.onSuccess { pendingHash = it; mutable.update { state -> state.copy(changed = folder != null && it != savedHash) } }
                        .onFailure { mutable.update { state -> state.copy(error = "This setup could not be backed up. Your timers remain saved on this phone.") } }
                }.debounce(750).collect { if (it.isSuccess && pendingHash != savedHash) retryPending() }
        }
    }

    fun retryPending() {
        if (folder != null && !mutable.value.writing && (pendingHash != savedHash || mutable.value.error != null)) beginWrite(false)
    }

    fun saveNow() { beginWrite(true) }

    private fun beginWrite(force: Boolean) {
        val target = folder ?: return
        if (mutable.value.writing && !force) return
        connectingFolder = null
        val token = ++generation
        writer?.cancel()
        writer = scope.launch {
            var forced = force
            while (isActive && token == generation && folder == target) {
                if (!forced && pendingHash == savedHash && mutable.value.error == null) break
                retryBackup(action = {
                    mutable.update { it.copy(writing = true, retryAtMs = 0) }
                    var copy: Copy? = null
                    var accepted = false
                    try {
                        check(store.current.storageIssue == null) { "Restore saved data first." }
                        copy = createCopy(Uri.parse(target), store.configuration())
                        ensureActive()
                        check(token == generation && folder == target)
                        acceptCopy(copy, target, mutable.value.fileName, changingFolder = false)
                        accepted = true
                    } finally {
                        if (!accepted) copy?.let { discard(it.uri) }
                        if (token == generation) mutable.update { it.copy(writing = false) }
                    }
                }, failed = ::noteFailure)
                forced = false
            }
        }
    }

    private fun noteFailure(failures: Int) {
        val message = "Backup pending. Previous copies are kept. Retrying automatically; you can also choose another folder or disconnect."
        disk.edit().putString("error", message).apply()
        mutable.update { it.copy(error = message, changed = true,
            retryAtMs = System.currentTimeMillis() + BackupRetryPolicy.delayMs(failures)) }
    }

    fun connect(uri: Uri) {
        connectingFolder = uri.toString()
        val token = ++generation
        writer?.cancel()
        writer = scope.launch {
            mutable.update { it.copy(writing = true, retryAtMs = 0, error = null) }
            var copy: Copy? = null
            var accepted = false
            try {
                check(store.current.storageIssue == null)
                val name = io.run { task ->
                    resolver.takePersistableUriPermission(uri, flags)
                    task.checkActive()
                    val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    val signal = CancellationSignal(); task.onCancel { signal.cancel() }
                    resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
                        if (it.moveToFirst()) it.getString(0)?.take(200) else null
                    } ?: "Timekeeper backup"
                }
                copy = createCopy(uri, store.configuration())
                ensureActive(); check(token == generation)
                acceptCopy(copy, uri.toString(), name, changingFolder = folder != uri.toString())
                accepted = true
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                mutable.update { it.copy(error = "Folder change timed out. The previous backup connection is kept.") }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not use that folder. The previous backup connection and files are kept.") }
            } finally {
                if (token == generation) connectingFolder = null
                if (!accepted) {
                    copy?.let { discard(it.uri) }
                    if (folder != uri.toString()) release(uri)
                }
                if (token == generation) {
                    mutable.update { it.copy(writing = false) }
                    if (folder != null && pendingHash != savedHash) {
                        delay(5_000); if (token == generation) retryPending()
                    }
                }
            }
        }
    }

    fun disconnect() {
        generation++; connectingFolder = null; writer?.cancel()
        val old = folder
        if (!disk.edit().clear().commit()) {
            mutable.update { it.copy(writing = false, error = "Could not save the disconnection. Try again.") }
            error("Could not save the disconnection. Try again.")
        }
        folder = null; savedHash = null
        mutable.value = BackupStatus()
        old?.let { release(Uri.parse(it)) }
    }

    fun cancelCurrent() {
        generation++; connectingFolder = null; writer?.cancel()
        mutable.update { it.copy(writing = false, changed = folder != null && pendingHash != savedHash,
            retryAtMs = 0, error = if (folder != null) "Backup cancelled. Existing copies are kept. Tap Back up now to retry." else null) }
    }

    suspend fun read(uri: Uri): TimerBackup = io.run { task ->
        val signal = CancellationSignal(); task.onCancel { signal.cancel() }
        val descriptor = resolver.openFileDescriptor(uri, "r", signal) ?: error("The backup is unavailable.")
        task.onCancel { descriptor.close() }
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            task.onCancel { input.close() }
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                task.checkActive()
                val count = input.read(buffer); if (count == -1) break
                require(output.size() + count <= TimerBackupCodec.MAX_BYTES) { "Choose a backup smaller than 512 KB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        task.checkActive(); TimerBackupCodec.decode(bytes)
    }

    private suspend fun createCopy(tree: Uri, config: TimerConfiguration): Copy {
        val at = System.currentTimeMillis()
        val bytes = TimerBackupCodec.encode(config, at)
        val fingerprint = hash(config)
        return io.run(abandoned = { discard(it.uri) }) { task ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val date = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val uri = VerifiedBackupCopy.save(bytes,
                create = {
                    task.checkActive()
                    val created = DocumentsContract.createDocument(resolver, parent, "application/json", "Timekeeper-$date.json")
                        ?: error("Folder unavailable")
                    try { task.checkActive(); created } catch (e: Exception) { discard(created); throw e }
                },
                output = { document ->
                    task.checkActive()
                    val signal = CancellationSignal(); task.onCancel { signal.cancel() }
                    resolver.openFileDescriptor(document, "wt", signal)?.let { descriptor ->
                        task.onCancel { descriptor.close() }
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).also { stream -> task.onCancel { stream.close() } }
                    }
                },
                input = { document ->
                    task.checkActive()
                    val signal = CancellationSignal(); task.onCancel { signal.cancel() }
                    resolver.openFileDescriptor(document, "r", signal)?.let { descriptor ->
                        task.onCancel { descriptor.close() }
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).also { stream -> task.onCancel { stream.close() } }
                    }
                }, accept = { task.checkActive() }, discard = { discard(it) })
            Copy(uri, fingerprint, at)
        }
    }

    private fun acceptCopy(copy: Copy, target: String, name: String, changingFolder: Boolean) {
        val old = folder
        val previous = if (changingFolder) emptyList() else runCatching {
            val values = JSONArray(disk.getString("recent", "[]"))
            (0 until values.length()).map { values.getString(it) }
        }.getOrDefault(emptyList())
        val recent = (listOf(copy.uri.toString()) + previous).distinct()
        check(disk.edit().putString("folder", target).putString("name", name)
            .putLong("savedAt", copy.at).putString("hash", copy.hash)
            .putString("recent", JSONArray(recent.take(3)).toString()).remove("error").commit()) { "Could not save backup status." }
        folder = target; savedHash = copy.hash; pendingHash = hash(store.configuration())
        mutable.value = BackupStatus(true, name, copy.at, pendingHash != savedHash)
        if (old != null && old != target) release(Uri.parse(old))
        recent.drop(3).forEach { discard(Uri.parse(it)) }
    }

    private fun hash(config: TimerConfiguration): String = MessageDigest.getInstance("SHA-256")
        .digest(TimerBackupCodec.encode(config, 1)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun discard(uri: Uri) { ProviderIo.cleanup { DocumentsContract.deleteDocument(resolver, uri) } }
    private fun release(uri: Uri) { ProviderIo.cleanup {
        if (folder != uri.toString() && connectingFolder != uri.toString()) resolver.releasePersistableUriPermission(uri, flags)
    } }
}
