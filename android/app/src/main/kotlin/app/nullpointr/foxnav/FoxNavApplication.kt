package app.nullpointr.foxnav

import android.app.Application
import android.content.Context

class FoxNavApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** The app-wide objects, see [AppGraph]. */
val Context.appGraph: AppGraph
    get() = (applicationContext as FoxNavApplication).graph
