package com.opentermx.logger

import java.nio.file.Path

data class LogConfig(
    val basePath: Path,
    val format: LogFormat,
    val timestamps: Boolean = true,
    val timestampPattern: String = "yyyy-MM-dd HH:mm:ss.SSS",
    val rotation: RotationPolicy = RotationPolicy.None,
)