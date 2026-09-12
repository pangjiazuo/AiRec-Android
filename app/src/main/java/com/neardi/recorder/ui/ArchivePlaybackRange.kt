package com.neardi.recorder.ui

/** 外部时间始终相对原文件；原生裁剪后的播放器从 0 开始计时。 */
data class ArchivePlaybackRange(val minimumPosition: Long = 0, val endPosition: Long? = null) {
    init {
        require(minimumPosition >= 0)
        require(endPosition == null || endPosition > minimumPosition)
    }

    fun toPlayerPosition(originalPosition: Long): Long = originalPosition
        .coerceIn(minimumPosition, endPosition?.minus(1) ?: Long.MAX_VALUE) - minimumPosition

    fun toOriginalPosition(playerPosition: Long): Long = playerPosition
        .coerceIn(0, (endPosition ?: Long.MAX_VALUE) - minimumPosition) + minimumPosition
}
