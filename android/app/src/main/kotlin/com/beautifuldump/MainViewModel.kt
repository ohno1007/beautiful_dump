package com.beautifuldump

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beautifuldump.data.AppInfo
import com.beautifuldump.data.AppRepository
import com.beautifuldump.dump.DumpEvent
import com.beautifuldump.dump.DumpResult
import com.beautifuldump.dump.DumpRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val installedApps: List<AppInfo> = emptyList(),
    val selected: AppInfo? = null,
    val pickerOpen: Boolean = false,
    val running: Boolean = false,
    val progress: String? = null,
    val log: List<String> = emptyList(),
    val result: DumpResult? = null,
    val error: String? = null,
    val customMagic: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppRepository(app)
    private val runner = DumpRunner(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repo.listLaunchable() }
            }.onSuccess { list ->
                _state.update { it.copy(installedApps = list) }
            }.onFailure { e ->
                android.util.Log.e("bd", "listLaunchable failed", e)
                _state.update { it.copy(error = "枚举应用失败: ${e.message}") }
            }
        }
    }

    fun openPicker() = _state.update { it.copy(pickerOpen = true) }
    fun closePicker() = _state.update { it.copy(pickerOpen = false) }

    fun selectApp(info: AppInfo) = _state.update {
        it.copy(selected = info, pickerOpen = false, result = null, error = null, log = emptyList())
    }

    fun setMagic(s: String) = _state.update { it.copy(customMagic = s) }

    fun runDump() {
        val target = _state.value.selected ?: return
        if (_state.value.running) return
        _state.update { it.copy(running = true, progress = "准备…", log = emptyList(), error = null, result = null) }
        viewModelScope.launch {
            val magic = _state.value.customMagic.trim().ifBlank { null }
            runner.run(target.packageName, magic).collect { ev ->
                when (ev) {
                    is DumpEvent.Log -> _state.update { it.copy(log = it.log + ev.line) }
                    is DumpEvent.Progress -> _state.update { it.copy(progress = ev.message) }
                    is DumpEvent.Done -> _state.update {
                        it.copy(running = false, progress = null, result = ev.result)
                    }
                    is DumpEvent.Failure -> _state.update {
                        it.copy(running = false, progress = null, error = ev.message)
                    }
                }
            }
        }
    }
}
