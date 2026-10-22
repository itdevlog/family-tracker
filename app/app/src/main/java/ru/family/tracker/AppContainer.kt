package ru.family.tracker

import android.content.Context
import ru.family.tracker.data.api.ApiClient

class AppContainer(context: Context) {
    val settings: Settings = Settings.create(context)

    val api: ApiClient by lazy {
        ApiClient({ settings.baseUrl }, { settings.token })
    }
}
