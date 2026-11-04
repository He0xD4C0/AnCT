package dev.anct

import android.app.Application
import com.topjohnwu.superuser.Shell

class AnctApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER),
        )
        graph = AppGraph(this)
    }
}

val Application.anctGraph: AppGraph
    get() = (this as AnctApplication).graph

