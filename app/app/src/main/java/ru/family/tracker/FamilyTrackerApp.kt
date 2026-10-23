package ru.family.tracker

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class FamilyTrackerApp : Application() {
    /**
     * Test seam: Robolectric tests replace the production container (encrypted settings,
     * real Room database) before the first [container] access.
     */
    @Volatile
    var containerOverride: AppContainer? = null

    val container: AppContainer by lazy { containerOverride ?: AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        initOsmdroid()
    }

    /**
     * osmdroid needs a writable tile cache before the first map is displayed. Keeping it
     * inside the app cache means uninstalling clears the tiles and no extra permission
     * (external storage) is required.
     */
    private fun initOsmdroid() {
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        config.userAgentValue = packageName

        val basePath = File(cacheDir, "osmdroid")
        val tileCache = File(basePath, "tiles")
        basePath.mkdirs()
        tileCache.mkdirs()
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache
    }
}
