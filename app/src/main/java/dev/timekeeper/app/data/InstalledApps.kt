package dev.timekeeper.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.flow.asStateFlow

data class InstalledApp(val packageName: String, val label: String)

object AppIcons {
    private val states = LruCache<String, Drawable.ConstantState>(80)
    private val mutableRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()
    fun clear() { states.evictAll(); mutableRevision.value++ }
    fun cached(packageName: String): Drawable? = states.get(packageName)?.newDrawable()
    fun load(context: Context, packageName: String): Drawable? = cached(packageName) ?: runCatching {
        context.packageManager.getApplicationIcon(packageName).also { icon ->
            icon.constantState?.let { states.put(packageName, it) }
        }
    }.getOrNull()
}

fun installedApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }.toSet()
    val collator = java.text.Collator.getInstance(java.util.Locale.getDefault())
    return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName !in homes }
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .sortedWith { a, b ->
            val group = compareValues(if (appInitial(a.label) == "#") 0 else 1, if (appInitial(b.label) == "#") 0 else 1)
            if (group != 0) group else collator.compare(a.label, b.label)
        }
}

fun appInitial(label: String): String {
    val first = java.text.Normalizer.normalize(label.trim(), java.text.Normalizer.Form.NFD)
        .firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}
