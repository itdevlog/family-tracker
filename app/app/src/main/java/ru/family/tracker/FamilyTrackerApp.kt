package ru.family.tracker

import android.app.Application

class FamilyTrackerApp : Application() {
    /**
     * Test seam: Robolectric tests replace the production container (encrypted settings,
     * real Room database) before the first [container] access.
     */
    @Volatile
    var containerOverride: AppContainer? = null

    val container: AppContainer by lazy { containerOverride ?: AppContainer(this) }
}
