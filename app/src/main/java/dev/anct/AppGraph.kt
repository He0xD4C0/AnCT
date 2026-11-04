package dev.anct

import android.content.Context
import dev.anct.data.AppDatabase
import dev.anct.data.InstanceRepository
import dev.anct.data.OperationJournal
import dev.anct.runtime.BackupService
import dev.anct.runtime.CatalogService
import dev.anct.runtime.InstanceCoordinator
import dev.anct.runtime.RuntimeRegistry
import dev.anct.runtime.SettingsStore
import dev.anct.runtime.nativebackend.NativeRuntimeBackend
import dev.anct.runtime.proot.ProotRuntimeBackend

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.get(appContext)
    val repository = InstanceRepository(database)
    val operations = OperationJournal(database.operationDao())
    val settings = SettingsStore(appContext)
    val runtimes = RuntimeRegistry(
        listOf(
            ProotRuntimeBackend(appContext),
            NativeRuntimeBackend(appContext),
        ),
    )
    val coordinator = InstanceCoordinator(appContext, repository, operations, runtimes)
    val backups = BackupService(appContext, repository, coordinator)
    val catalog = CatalogService(appContext)
}

