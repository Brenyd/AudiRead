package com.brenyd.audiread

import android.app.Application
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.data.SettingsStore

class AudiReadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LibraryRepository.init(this)
        SettingsStore.init(this)
    }
}
