package com.opentermx.logger

sealed interface RotationPolicy {
    data object None : RotationPolicy
    data class BySize(val maxBytes: Long) : RotationPolicy
    data class ByTime(val intervalMillis: Long) : RotationPolicy
}