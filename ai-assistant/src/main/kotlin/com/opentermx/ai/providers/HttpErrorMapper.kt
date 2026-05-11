package com.opentermx.ai.providers

import com.opentermx.common.ai.LlmErrorMapper

/**
 * Alias interno hacia el mapper público en `common/ai`. Lo mantenemos por compatibilidad
 * con los providers ya escritos; el contrato real vive en [LlmErrorMapper].
 */
internal object HttpErrorMapper {
    fun fromHttp(code: Int) = LlmErrorMapper.fromHttp(code)
    fun fromException(t: Throwable) = LlmErrorMapper.fromException(t)
}
