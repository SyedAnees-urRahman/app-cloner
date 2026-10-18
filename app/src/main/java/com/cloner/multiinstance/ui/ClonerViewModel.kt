package com.cloner.multiinstance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloner.multiinstance.data.CloneRepository
import com.cloner.multiinstance.data.ClonerDatabase
import com.cloner.multiinstance.engine.CloneEngine
import com.cloner.multiinstance.model.CloneEntity
import com.cloner.multiinstance.model.InstalledApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ClonerViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = CloneRepository(
        application,
        ClonerDatabase.get(application).cloneDao()
    )

    val clones: StateFlow<List<CloneEntity>> =
        repo.clones.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = runCatching { repo.getInstalledApps() }.getOrDefault(emptyList())
        }
    }

    fun createClone(app: InstalledApp, label: String, color: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                CloneEngine.createClone(getApplication(), repo, app.packageName, app.appName, label, color)
            }
            onDone()
        }
    }

    fun launch(clone: CloneEntity) {
        runCatching { CloneEngine.launchClone(getApplication(), clone) }
        viewModelScope.launch { repo.update(clone.copy(launchCount = clone.launchCount + 1)) }
    }

    fun rename(clone: CloneEntity, name: String) {
        viewModelScope.launch { repo.update(clone.copy(displayName = name)) }
    }

    fun clearData(clone: CloneEntity) {
        viewModelScope.launch { CloneEngine.clearCloneData(getApplication(), repo, clone) }
    }

    fun delete(clone: CloneEntity) {
        viewModelScope.launch { CloneEngine.deleteClone(getApplication(), repo, clone) }
    }
}
