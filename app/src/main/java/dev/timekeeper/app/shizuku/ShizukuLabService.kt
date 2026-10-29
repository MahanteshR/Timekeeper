package dev.timekeeper.app.shizuku

import android.app.TaskInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.*
import android.system.Os
import androidx.annotation.Keep
import dev.timekeeper.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs only as adb shell, and exposes a fixed set of manual experiments to our own UID. */
@Keep
class ShizukuLabService @Keep constructor(private val context: Context) : IShizukuLab.Stub() {
    private val clientUid = context.applicationInfo.uid
    private val userId = clientUid / 100_000
    private val operationLock = Any()
    @Volatile private var targets = emptySet<String>()
    @Volatile private var lastError: String? = null
    private val readers = Executors.newFixedThreadPool(2)
    private val guard = Executors.newSingleThreadScheduledExecutor()
    private val observer = Executors.newSingleThreadScheduledExecutor()
    private val samples = ArrayDeque<JSONObject>()
    @Volatile private var observeUntil = 0L
    private fun now() = SystemClock.elapsedRealtime()
    private val backend = object : SuspensionBackend {
        override fun state(packageName: String): SuspensionState {
            try { context.packageManager.getApplicationInfo(packageName, 0) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { return SuspensionState(false, null) }
            return SuspensionState(isSuspended(packageName), marker(packageName))
        }
        override fun suspend(packageName: String, token: String) {
            command("package", "suspend", "--user", userId.toString(), "--les", ShizukuTrialPolicy.MARKER,
                token, "--dialogMessage", "Timekeeper test: access normally returns in 10 seconds. If needed, reopen Timekeeper > Shizuku lab to recover.", packageName)
        }
        override fun unsuspend(packageName: String) { command("package", "unsuspend", "--user", userId.toString(), packageName) }
    }
    private val trial = SuspensionTrial(backend, ::now)

    init {
        check(ShizukuTrialPolicy.supportedIdentity(Os.getuid(), clientUid)) { "Only non-root shell in the primary user is supported." }
        guard.scheduleWithFixedDelay({
            runCatching { synchronized(operationLock) { trial.expire() } }
                .onFailure { lastError = "Automatic recovery needs attention: ${message(it)}" }
        }, 250, 250, TimeUnit.MILLISECONDS)
        observer.scheduleWithFixedDelay({
            if (now() < observeUntil) runCatching {
                val sample = focusedTask().put("elapsed", now())
                synchronized(samples) { if (samples.size >= 60) samples.removeFirst(); samples.addLast(sample) }
            }.onFailure { lastError = "Task observation unavailable: ${message(it)}"; observeUntil = 0 }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun caller() {
        check(ShizukuTrialPolicy.supportedIdentity(Os.getuid(), clientUid)) { "Shell identity required." }
        if (Binder.getCallingUid() != clientUid) throw SecurityException("Only the Timekeeper app may call this helper.")
    }
    private fun response(block: () -> JSONObject): String {
        caller()
        val identity = Binder.clearCallingIdentity()
        return try { block().put("ok", true).toString() }
        catch (e: Exception) { lastError = message(e); JSONObject().put("ok", false).put("error", lastError).toString() }
        finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun message(e: Throwable): String = (e.cause?.message ?: e.message ?: e.javaClass.simpleName).take(250)

    override fun configure(packages: Array<out String>): String = response {
        require(packages.size <= ShizukuTrialPolicy.MAX_TARGETS && packages.all(ShizukuTrialPolicy::validPackage))
        targets = packages.toSet()
        // Unsupported observation/command probes must not prevent reconnect recovery.
        val helpProbe = runCatching { command("activity", "help") }
        val activityHelp = helpProbe.getOrDefault("")
        val markerProbe = runCatching { marker(context.packageName); true }
        val taskProbe = runCatching { focusedTask(); true }
        JSONObject().put("uid", Os.getuid()).put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER)
            .put("stopApp", activityHelp.contains("stop-app ["))
            .put("forceStop", activityHelp.contains("force-stop ["))
            .put("suspendPermission", context.checkPermission("android.permission.SUSPEND_APPS", Os.getpid(), Os.getuid()) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            .put("markerAvailable", markerProbe.getOrDefault(false)).put("taskAvailable", taskProbe.getOrDefault(false))
            .put("commandError", helpProbe.exceptionOrNull()?.let(::message) ?: JSONObject.NULL)
            .put("markerError", markerProbe.exceptionOrNull()?.let(::message) ?: JSONObject.NULL)
            .put("taskError", taskProbe.exceptionOrNull()?.let(::message) ?: JSONObject.NULL)
    }
    override fun inspect(packageName: String): String = response {
        validateSyntax(packageName)
        require(packageName in targets) { "Choose an app in App limits." }
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val state = backend.state(packageName)
        JSONObject().put("package", packageName).put("enabled", info.enabled)
            .put("suspended", state.suspended).put("stopped", info.flags and ApplicationInfo.FLAG_STOPPED != 0)
            .put("eligible", rejection(packageName, info, state.suspended) == null)
            .put("reason", rejection(packageName, info, state.suspended) ?: "Eligible for a manual test")
    }
    override fun suspendTrial(packageName: String, token: String): String = response {
        synchronized(operationLock) {
            validateSyntax(packageName); require(ShizukuTrialPolicy.validToken(token))
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            check(rejection(packageName, info, isSuspended(packageName)) == null) {
                rejection(packageName, info, isSuspended(packageName)) ?: "App cannot be tested."
            }
            marker(packageName) // Refuse before mutation when the ownership-query API is unavailable.
            val lease = trial.start(packageName, token)
            JSONObject().put("package", lease.packageName).put("untilElapsed", lease.untilElapsedMs)
        }
    }
    override fun release(packageName: String, token: String): String = response {
        synchronized(operationLock) {
            validateSyntax(packageName); require(ShizukuTrialPolicy.validToken(token))
            val result = trial.release(packageName, token)
            JSONObject().put("released", result.finished).put("suspended", result.stillSuspended)
                .put("ownershipVerified", result.ownershipVerified)
        }
    }
    override fun stopTrial(packageName: String, force: Boolean): String = response {
        synchronized(operationLock) {
            check(trial.pending == null) { "Recover the suspension test first." }
            validateSyntax(packageName)
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            check(rejection(packageName, info, isSuspended(packageName)) == null) {
                rejection(packageName, info, isSuspended(packageName)) ?: "App cannot be tested."
            }
            command("activity", if (force) "force-stop" else "stop-app", "--user", userId.toString(), packageName)
            JSONObject().put("package", packageName).put("operation", if (force) "force-stop" else "stop-app")
        }
    }
    override fun observe(): String = response {
        focusedTask() // Verify access before starting the observation window.
        synchronized(samples) { samples.clear() }
        observeUntil = now() + ShizukuTrialPolicy.OBSERVATION_MS
        JSONObject().put("untilElapsed", observeUntil)
    }
    override fun report(): String = response {
        JSONObject().put("uid", Os.getuid()).put("observing", now() < observeUntil)
            .put("samples", synchronized(samples) { JSONArray(samples.toList()) })
            .put("error", lastError ?: JSONObject.NULL).apply {
                trial.pending?.let { put("pendingPackage", it.packageName); put("pendingToken", it.token); put("untilElapsed", it.untilElapsedMs) }
            }
    }

    private fun protectedPackages(): Set<String> = setOf(context.packageName, "moe.shizuku.privileged.api",
        "com.android.shell", "com.android.systemui", "com.android.settings") + context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }
    private fun rejection(packageName: String, info: ApplicationInfo, suspended: Boolean): String? =
        ShizukuTrialPolicy.rejection(packageName, targets, protectedPackages(),
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            info.enabled, info.uid / 100_000 == userId, suspended)
    private fun validateSyntax(packageName: String) { require(ShizukuTrialPolicy.validPackage(packageName)) }

    private fun remoteService(name: String, stub: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as? IBinder
            ?: error("Android service $name is unavailable")
        return Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }
    private fun isSuspended(packageName: String): Boolean {
        val pm = remoteService("package", "android.content.pm.IPackageManager\$Stub")
        return pm.javaClass.getMethod("isPackageSuspendedForUser", String::class.java, Int::class.javaPrimitiveType)
            .invoke(pm, packageName, userId) as Boolean
    }
    private fun marker(packageName: String): String? {
        val launcher = remoteService("launcherapps", "android.content.pm.ILauncherApps\$Stub")
        val method = launcher.javaClass.methods.firstOrNull { it.name == "getSuspendedPackageLauncherExtras" && it.parameterCount == 2 }
            ?: error("Suspension ownership queries are not supported on this Android build.")
        val extras = method.invoke(launcher, packageName, UserHandle.getUserHandleForUid(clientUid)) as? Bundle
        return extras?.getString(ShizukuTrialPolicy.MARKER)
    }
    private fun focusedTask(): JSONObject {
        val manager = remoteService("activity_task", "android.app.IActivityTaskManager\$Stub")
        val task = manager.javaClass.getMethod("getFocusedRootTaskInfo").invoke(manager) as? TaskInfo
        val top: ComponentName? = task?.topActivity
        return JSONObject().put("package", top?.packageName ?: JSONObject.NULL)
            .put("activity", top?.className ?: JSONObject.NULL)
    }
    private fun command(vararg args: String): String {
        // No shell interpolation; only fixed command families and validated positional arguments reach here.
        val process = ProcessBuilder(listOf("/system/bin/cmd") + args).redirectErrorStream(true).start()
        val read = readers.submit<String> {
            process.inputStream.bufferedReader().use { input ->
                val result = StringBuilder(); val chars = CharArray(2048)
                while (true) { val n = input.read(chars); if (n < 0) break
                    if (result.length < 32_768) result.append(chars, 0, minOf(n, 32_768 - result.length)) }
                result.toString()
            }
        }
        try {
            check(process.waitFor(4, TimeUnit.SECONDS)) { "Android operation timed out. Check recovery status." }
            val output = read.get(1, TimeUnit.SECONDS)
            check(process.exitValue() == 0 && !output.contains("Exception:") && !output.startsWith("Error:")) {
                "Android rejected this operation: ${output.take(180)}"
            }
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }; read.cancel(true)
        }
    }
    override fun destroy() {
        val uid = Binder.getCallingUid()
        if (uid != clientUid && uid != ShizukuTrialPolicy.SHELL_UID) throw SecurityException("Caller not allowed")
        val identity = Binder.clearCallingIdentity()
        try { synchronized(operationLock) { trial.pending?.let { runCatching { trial.release(it.packageName, it.token) } } } }
        finally { Binder.restoreCallingIdentity(identity) }
        if (trial.pending != null) return // Preserve the rollback guard when cleanup has not been verified.
        guard.shutdownNow(); observer.shutdownNow(); readers.shutdownNow()
        kotlin.system.exitProcess(0)
    }
}
