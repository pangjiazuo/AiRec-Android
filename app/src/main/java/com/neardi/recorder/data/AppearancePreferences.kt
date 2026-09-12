package com.neardi.recorder.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppearanceMode(val id: String) {
    LIGHT("light"), DARK("dark"), SYSTEM("system");

    companion object {
        fun fromId(id: String?): AppearanceMode = entries.firstOrNull { it.id == id } ?: LIGHT
    }
}

/** 外观只保存在当前手机，不写入开发板配置；首次安装默认浅色。 */
class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("recorder_appearance", Application.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(AppearanceMode.fromId(
        runCatching { preferences.getString("mode", AppearanceMode.LIGHT.id) }.getOrNull()))
    val mode: StateFlow<AppearanceMode> = mutableMode.asStateFlow()

    fun setMode(mode: AppearanceMode) {
        if (mutableMode.value == mode) return
        preferences.edit().putString("mode", mode.id).apply()
        mutableMode.value = mode
    }
}
