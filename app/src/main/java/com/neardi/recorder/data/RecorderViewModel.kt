package com.neardi.recorder.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 独立保存结果不会被连接轮询清空，页面旋转后仍可确认是否写入成功。 */
data class ConfigSaveResult(val succeeded: Boolean, val error: String? = null, val restartRequired: Boolean = false)

data class RecorderState(
    val endpoint: String = RecorderApi.DEFAULT_ENDPOINT,
    val status: JSONObject? = null,
    val config: JSONObject? = null,
    val connected: Boolean = false,
    val error: String? = null,
    val updating: Boolean = false,
    val saving: Boolean = false,
    val saveResult: ConfigSaveResult? = null,
    val saveResults: Map<String, ConfigSaveResult> = emptyMap(),
    val lastUpdatedMillis: Long = 0L,
)

/** 仅在客户端前台轮询；地址切换后旧请求不得再更新当前页面。 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("recorder_connection", Application.MODE_PRIVATE)
    private val savedEndpoint = runCatching {
        RecorderApi.normalizeBaseUrl(preferences.getString("endpoint", RecorderApi.DEFAULT_ENDPOINT) ?: RecorderApi.DEFAULT_ENDPOINT)
    }.getOrDefault(RecorderApi.DEFAULT_ENDPOINT)
    private val mutableState = MutableStateFlow(RecorderState(endpoint = savedEndpoint))
    val state: StateFlow<RecorderState> = mutableState.asStateFlow()
    var api: RecorderApi = RecorderApi(savedEndpoint)
        private set
    private var started = false
    private var generation = 0L
    private var configRevision = 0L
    private var pollJob: Job? = null
    private var configJob: Job? = null
    private var saveJob: Job? = null

    fun start() {
        started = true
        if (pollJob?.isActive == true) return
        val token = generation
        val connection = api
        pollJob = viewModelScope.launch {
            while (isActive) {
                mutableState.update { it.copy(updating = true) }
                try {
                    val status = connection.getJson("/api/status").also(ResponseValidation::status)
                    if (token == generation && started) {
                        mutableState.update { it.copy(status = status, connected = true, error = null,
                            updating = false, lastUpdatedMillis = System.currentTimeMillis()) }
                        if (mutableState.value.config == null && configJob?.isActive != true) refreshConfig()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (token == generation && started) mutableState.update {
                        it.copy(connected = false, updating = false, error = readableError(error))
                    }
                }
                // 上一次请求完成后才等两秒，弱网络下也不堆积请求。
                delay(2_000)
            }
        }
    }

    fun stop() {
        started = false
        pollJob?.cancel()
        pollJob = null
        configJob?.cancel()
        configJob = null
        mutableState.update { it.copy(updating = false) }
    }

    fun setEndpoint(input: String): Boolean {
        val normalized = try { RecorderApi.normalizeBaseUrl(input) } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(error = readableError(error)) }
            return false
        }
        generation++
        configRevision++
        pollJob?.cancel()
        configJob?.cancel()
        saveJob?.cancel()
        pollJob = null
        configJob = null
        saveJob = null
        api = RecorderApi(normalized)
        preferences.edit().putString("endpoint", normalized).apply()
        mutableState.value = RecorderState(endpoint = normalized)
        if (started) start()
        return true
    }

    fun refreshConfig() {
        if (configJob?.isActive == true || mutableState.value.saving) return
        val token = generation
        val revision = configRevision
        val connection = api
        configJob = viewModelScope.launch {
            try {
                val config = connection.getJson("/api/config").also(ResponseValidation::config)
                if (token == generation && revision == configRevision) {
                    mutableState.update { it.copy(config = config) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (token == generation) mutableState.update { it.copy(error = readableError(error)) }
            }
        }
    }

    fun saveConfig(changes: JSONObject, onResult: (Result<JSONObject>) -> Unit = {}) {
        saveScopedConfig("global", changes, onResult)
    }

    /** 六个设置入口独立保留结果，切换通道不会将其他页面的成功误认为本次成功。 */
    fun saveScopedConfig(ownerKey: String, changes: JSONObject, onResult: (Result<JSONObject>) -> Unit = {}) {
        require(ownerKey == "global" || Regex("channel:[1-5]").matches(ownerKey)) { "设置页面标识无效" }
        if (mutableState.value.saving) {
            val error = IllegalStateException("配置正在保存，请稍候")
            mutableState.update { it.copy(saveResults = it.saveResults + (ownerKey to ConfigSaveResult(false, error.message))) }
            onResult(Result.failure(error))
            return
        }
        val token = generation
        val connection = api
        // 拷贝传入值，界面继续编辑时不会改动正在发送的配置。
        val immutableChanges = JSONObject(changes.toString())
        configRevision++
        configJob?.cancel()
        configJob = null
        mutableState.update { it.copy(saving = true, error = null, saveResult = null, saveResults = it.saveResults - ownerKey) }
        saveJob = viewModelScope.launch {
            try {
                val response = connection.saveConfigChanges(immutableChanges)
                if (token == generation) {
                    val saved = (response.optJSONObject("config") ?: connection.getJson("/api/config")).also(ResponseValidation::config)
                    if (token == generation) {
                        val completed = ConfigSaveResult(succeeded = true, restartRequired = response.optBoolean("restart_required"))
                        mutableState.update { it.copy(config = saved, saving = false,
                            saveResult = completed, saveResults = it.saveResults + (ownerKey to completed)) }
                        onResult(Result.success(response))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (token == generation) {
                    val failed = ConfigSaveResult(succeeded = false, error = readableError(error))
                    mutableState.update { it.copy(saving = false, error = readableError(error),
                        saveResult = failed, saveResults = it.saveResults + (ownerKey to failed)) }
                    onResult(Result.failure(error))
                }
            }
        }
    }

    private fun readableError(error: Exception): String = error.message?.takeIf(String::isNotBlank) ?: "请求失败，请检查网络后重试"
}
