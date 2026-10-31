package dev.chudpresence.mobile.reporting

/** One elapsed counter for the current media app, independent of its items and display choices. */
internal class AppSessionTimer {
    private var packageName: String? = null
    var startEpochMs: Long? = null
        private set

    fun startFor(packageName: String, nowEpochMs: Long): Long {
        if (this.packageName != packageName || startEpochMs == null) {
            this.packageName = packageName
            startEpochMs = nowEpochMs
        }
        return startEpochMs!!
    }

    /** Brief item transitions and hidden pauses keep counting while the same session exists. */
    fun retainWhileActive(activePackages: Set<String>) {
        if (packageName !in activePackages) reset()
    }

    fun reset() {
        packageName = null
        startEpochMs = null
    }
}
