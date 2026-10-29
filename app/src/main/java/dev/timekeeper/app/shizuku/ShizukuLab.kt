package dev.timekeeper.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import dev.timekeeper.app.data.ProviderIo
import dev.timekeeper.app.data.ShizukuRecovery
import dev.timekeeper.core.ShizukuTrialPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.UUID

internal data class LabState(
    val status: String = "Not connected", val connected: Boolean = false, val busy: Boolean = false,
    val pendingPackage: String? = null, val journalIssue: Boolean = false,
    val capabilities: String = "", val inspection: String = "", val report: String = "",
    val canSuspend: Boolean = false, val canStop: Boolean = false, val canForce: Boolean = false, val canObserve: Boolean = false,
    val message: String? = null
)

/** Explicit, manual shell-only lab. It never participates in timer enforcement. Main-thread entry points. */
internal class ShizukuLab private constructor(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("shizuku_recovery", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = ProviderIo(8_000)
    private var journalIssue = false
    private var pending: ShizukuRecovery? = try { prefs.getString("pending", null)?.let(ShizukuRecovery::decode) }
        catch (_: Exception) { journalIssue = true; null }
    private val mutable = MutableStateFlow(LabState(pendingPackage = pending?.packageName, journalIssue = journalIssue))
    val state = mutable.asStateFlow()
    private var service: IShizukuLab? = null
    private var wanted = pending != null
    private var binding = false
    private var generation = 0
    private var targets = emptySet<String>()
    private val args = Shizuku.UserServiceArgs(ComponentName(this.context, ShizukuLabService::class.java))
        .tag("timekeeper-manual-lab").version(1).daemon(true).debuggable(false).processNameSuffix("shizuku_lab")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (!wanted || binder == null) return
            service = IShizukuLab.Stub.asInterface(binder)
            val epoch = ++generation
            mutable.value = mutable.value.copy(status = "Checking shell helper", connected = false)
            scope.launch { operation {
                val helper = service ?: error("Helper disconnected")
                val capabilities = call { helper.configure(targets.toTypedArray()) }
                check(capabilities.getInt("uid") == ShizukuTrialPolicy.SHELL_UID)
                val report = call { helper.report() }
                if (epoch != generation) return@operation
                mutable.value = mutable.value.copy(connected = true,
                    status = "Connected · non-root shell", capabilities = capabilities.toString(2), report = report.toString(2),
                    canSuspend = capabilities.optBoolean("suspendPermission") && capabilities.optBoolean("markerAvailable"),
                    canStop = capabilities.optBoolean("stopApp"), canForce = capabilities.optBoolean("forceStop"),
                    canObserve = capabilities.optBoolean("taskAvailable"))
                // Recover a daemon-owned trial even if the app process restarted before recording its result.
                if (pending == null && report.has("pendingPackage")) {
                    save(ShizukuRecovery(report.getString("pendingPackage"), report.getString("pendingToken")))
                }
                if (pending != null) recoverWith(helper)
            } }
        }
        override fun onServiceDisconnected(name: ComponentName?) { disconnected("Shell helper disconnected. Reconnect to check recovery.") }
        override fun onBindingDied(name: ComponentName?) { disconnected("Shell helper stopped. Reconnect to check recovery.") }
    }
    init {
        val main = Handler(Looper.getMainLooper())
        Shizuku.addBinderReceivedListenerSticky({ if (wanted) connect(false) }, main)
        Shizuku.addBinderDeadListener({ disconnected("Shizuku stopped. Restart it, then reconnect to recover any trial.") }, main)
        Shizuku.addRequestPermissionResultListener({ code, result ->
            if (code == REQUEST) {
                if (result == PackageManager.PERMISSION_GRANTED && wanted) connect(false)
                else mutable.value = mutable.value.copy(status = "Shizuku permission was not granted")
            }
        }, main)
    }
    fun setTargets(packages: Set<String>) { targets = packages.filter(ShizukuTrialPolicy::validPackage).take(500).toSet() }
    private fun disconnected(message: String) {
        generation++; service = null; binding = false
        mutable.value = mutable.value.copy(connected = false, busy = false, status = message)
    }
    fun connect(requestPermission: Boolean = true) {
        wanted = true
        if (binding || mutable.value.busy || service != null && mutable.value.connected) return
        runCatching {
            check(Shizuku.pingBinder()) { "Start Shizuku using wireless debugging, then connect here." }
            check(Shizuku.getUid() == ShizukuTrialPolicy.SHELL_UID) { "Root-backed Shizuku is not supported. Start it through wireless debugging." }
            check(android.os.Process.myUid() / 100_000 == 0) { "This lab currently supports only the primary Android user." }
            check(Shizuku.getVersion() >= 13) { "Update Shizuku before using this lab." }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                mutable.value = mutable.value.copy(status = "Allow Timekeeper in Shizuku, then connect")
                if (requestPermission) Shizuku.requestPermission(REQUEST)
                return
            }
            binding = true
            mutable.value = mutable.value.copy(status = "Connecting to shell helper…", message = null)
            Shizuku.bindUserService(args, connection)
            scope.launch { delay(12_000); if (binding) { binding = false; mutable.value = mutable.value.copy(status = "Helper did not respond. Try connecting again.") } }
        }.onFailure { binding = false; mutable.value = mutable.value.copy(status = it.message ?: "Unable to connect") }
    }
    private suspend fun call(block: () -> String): JSONObject {
        val raw = io.run { task -> task.checkActive(); block() }
        require(raw.length <= 131_072) { "Helper response was too large" }
        val result = JSONObject(raw)
        check(result.optBoolean("ok")) { result.optString("error", "Helper rejected the operation") }
        return result
    }
    private suspend fun save(record: ShizukuRecovery?) {
        record?.let { ShizukuRecovery.decode(it.encode()) }
        val written = withContext(Dispatchers.IO) {
            val edit = prefs.edit()
            if (record == null) edit.remove("pending") else edit.putString("pending", record.encode())
            edit.commit()
        }
        if (!written) {
            if (record != null) pending = record
            journalIssue = true
            mutable.value = mutable.value.copy(journalIssue = true, pendingPackage = pending?.packageName)
            error("Recovery record could not be saved. Tests are disabled until recovery is verified.")
        }
        pending = record; journalIssue = false
        mutable.value = mutable.value.copy(pendingPackage = record?.packageName, journalIssue = false)
    }
    private suspend fun operation(clearMessage: Boolean = true, action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = if (clearMessage) null else mutable.value.message)
        try { action() }
        catch (e: TimeoutCancellationException) { mutable.value = mutable.value.copy(message = "Helper timed out. A suspension record is kept until recovery is verified.") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(message = (e.cause?.message ?: e.message ?: "Operation failed").take(350)) }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }
    private fun ready(): IShizukuLab {
        check(mutable.value.connected) { "Connect to Shizuku first." }
        return service ?: error("Shell helper disconnected")
    }
    private suspend fun configure(helper: IShizukuLab) { call { helper.configure(targets.toTypedArray()) } }
    fun inspect(pkg: String) { scope.launch { operation {
        val helper = ready(); configure(helper)
        mutable.value = mutable.value.copy(inspection = call { helper.inspect(pkg) }.toString(2))
    } } }
    fun suspendTrial(pkg: String) { scope.launch { operation {
        val helper = ready(); check(!journalIssue) { "Recovery storage needs attention." }; check(pending == null) { "Recover the previous suspension first." }; configure(helper)
        val record = ShizukuRecovery(pkg, UUID.randomUUID().toString())
        // Commit before any IPC that can suspend an app, including a delayed or timed-out call.
        save(record)
        call { helper.suspendTrial(pkg, record.token) }
        mutable.value = mutable.value.copy(message = "Suspension trial started. The helper will attempt recovery after 10 seconds.")
    } } }
    fun stop(pkg: String, force: Boolean) { scope.launch { operation {
        val helper = ready(); check(!journalIssue) { "Recovery storage needs attention." }; check(pending == null) { "Recover the suspension first." }; configure(helper)
        call { helper.stopTrial(pkg, force) }
        mutable.value = mutable.value.copy(message = if (force) "Force-stop completed. Open the app yourself to restart it. Check playback and notifications."
            else "Stop-app completed. Check whether playback stopped and compare notifications.", inspection = call { helper.inspect(pkg) }.toString(2))
    } } }
    fun observe() { scope.launch { operation {
        val helper = ready(); call { helper.observe() }
        mutable.value = mutable.value.copy(message = "Recording focused tasks for 30 seconds. Switch apps, open the notification shade, then return here.")
    } } }
    private suspend fun recoverWith(helper: IShizukuLab) {
        val record = pending ?: return
        val result = call { helper.release(record.packageName, record.token) }
        check(result.getBoolean("released") && result.getBoolean("ownershipVerified")) {
            "Suspension ownership changed or cannot be verified. The record is kept; inspect the app and use manual recovery if needed."
        }
        save(null)
        mutable.value = mutable.value.copy(message = if (result.optBoolean("suspended"))
            "Timekeeper’s test suspension is cleared. Another policy still restricts the app." else "App access restored. Recovery verified.")
    }
    fun recover() { scope.launch { operation { recoverWith(ready()) } } }
    suspend fun refresh() {
        if (!mutable.value.connected || mutable.value.busy) return
        operation(clearMessage = false) {
            val helper = ready(); val report = call { helper.report() }
            if (pending == null && report.has("pendingPackage")) save(ShizukuRecovery(report.getString("pendingPackage"), report.getString("pendingToken")))
            mutable.value = mutable.value.copy(report = report.toString(2))
            if (pending != null && (!report.has("pendingPackage") || SystemClock.elapsedRealtime() >= report.optLong("untilElapsed", 0))) recoverWith(helper)
        }
    }
    fun disconnect() { scope.launch { operation {
        check(pending == null && !journalIssue) { "Verify recovery before disconnecting the helper." }
        Shizuku.unbindUserService(args, connection, true)
        wanted = false; disconnected("Disconnected")
    } } }
    companion object {
        private const val REQUEST = 4107
        // Only applicationContext is retained; the daemon connection must outlive activities.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ShizukuLab? = null
        fun get(context: Context): ShizukuLab = instance ?: synchronized(this) { instance ?: ShizukuLab(context).also { instance = it } }
    }
}
