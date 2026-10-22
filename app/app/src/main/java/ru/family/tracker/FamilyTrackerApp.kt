package ru.family.tracker

import android.app.Application

class FamilyTrackerApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
