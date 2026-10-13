package com.duoprog.app

import android.app.Application
import com.duoprog.app.data.CurriculumLoader
import com.duoprog.app.data.local.AppDatabase
import com.duoprog.app.data.repository.ProgressRepository

class DuoProgApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { ProgressRepository(database.progressDao()) }

    override fun onCreate() {
        super.onCreate()
        Thread { CurriculumLoader.load(this) }.start()
    }
}
