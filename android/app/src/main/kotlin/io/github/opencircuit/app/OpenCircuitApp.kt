package io.github.opencircuit.app

import android.app.Application

/** The application: builds the [AppContainer] once per process. */
class OpenCircuitApp : Application() {

    /** The app's object graph. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
