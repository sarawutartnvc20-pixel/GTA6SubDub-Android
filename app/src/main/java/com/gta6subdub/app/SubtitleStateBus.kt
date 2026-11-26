package com.gta6subdub.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SubtitleStateBus {
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _latestThaiText = MutableStateFlow("ยังไม่ได้เริ่มแปล")
    val latestThaiText: StateFlow<String> = _latestThaiText.asStateFlow()

    private val _latestEnglishText = MutableStateFlow("")
    val latestEnglishText: StateFlow<String> = _latestEnglishText.asStateFlow()

    private val _styleVersion = MutableStateFlow(0)
    val styleVersion: StateFlow<Int> = _styleVersion.asStateFlow()

    fun setCapturing(running: Boolean) {
        _isCapturing.value = running
    }

    fun updateSubtitles(english: String, thai: String) {
        _latestEnglishText.value = english
        _latestThaiText.value = thai
    }

    fun updateThaiOnly(thai: String) {
        _latestThaiText.value = thai
    }

    fun notifyStyleChanged() {
        _styleVersion.value += 1
    }
}
