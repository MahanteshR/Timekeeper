package dev.timekeeper.app.shizuku

import rikka.shizuku.ShizukuProvider

class ShellOnlyShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        // The product's experiment is explicitly non-root; do not auto-initialize Sui.
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }
}
