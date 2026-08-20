package com.opentermx.app.settings

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase.FILETIME
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.Kernel32
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

interface AgentTokenStore {
    val isAvailable: Boolean
    fun read(reference: String): String?
    fun write(reference: String, token: String): Boolean
    fun delete(reference: String): Boolean
}

object AgentTokenStores {
    private val log = LoggerFactory.getLogger(javaClass)
    val system: AgentTokenStore by lazy {
        if (System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) {
            runCatching { WindowsCredentialAgentTokenStore() }
                .onFailure { log.warn("Windows Credential Manager no disponible: {}", it.message) }
                .getOrElse { UnavailableAgentTokenStore }
        } else UnavailableAgentTokenStore
    }

    fun referenceFor(agentId: String): String = "OpenTermX/edge-agent/${agentId.ifBlank { "default" }}"
}

object UnavailableAgentTokenStore : AgentTokenStore {
    override val isAvailable = false
    override fun read(reference: String): String? = null
    override fun write(reference: String, token: String) = false
    override fun delete(reference: String) = false
}

class WindowsCredentialAgentTokenStore : AgentTokenStore {
    override val isAvailable: Boolean = true

    override fun read(reference: String): String? {
        val out = PointerByReference()
        if (!CredentialApi.INSTANCE.CredReadW(WString(reference), CRED_TYPE_GENERIC, 0, out)) {
            val error = Kernel32.INSTANCE.GetLastError()
            if (error == WinError.ERROR_NOT_FOUND) return null
            throw IllegalStateException("CredRead falló (error=$error)")
        }
        return try {
            val credential = Credential(out.value)
            val size = credential.CredentialBlobSize
            val blob = credential.CredentialBlob
            if (size <= 0 || blob == null) ""
            else String(blob.getByteArray(0, size), StandardCharsets.UTF_8)
        } finally {
            CredentialApi.INSTANCE.CredFree(out.value)
        }
    }

    override fun write(reference: String, token: String): Boolean {
        val bytes = token.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 16..MAX_BLOB_BYTES) { "El token debe tener entre 16 y $MAX_BLOB_BYTES bytes" }
        val memory = Memory(bytes.size.toLong())
        memory.write(0, bytes, 0, bytes.size)
        return try {
            val credential = Credential().apply {
                Type = CRED_TYPE_GENERIC
                TargetName = WString(reference)
                CredentialBlobSize = bytes.size
                CredentialBlob = memory
                Persist = CRED_PERSIST_LOCAL_MACHINE
                UserName = WString(System.getProperty("user.name").orEmpty())
                write()
            }
            if (!CredentialApi.INSTANCE.CredWriteW(credential, 0)) {
                throw IllegalStateException("CredWrite falló (error=${Kernel32.INSTANCE.GetLastError()})")
            }
            true
        } finally {
            memory.clear(bytes.size.toLong())
            bytes.fill(0)
        }
    }

    override fun delete(reference: String): Boolean {
        if (CredentialApi.INSTANCE.CredDeleteW(WString(reference), CRED_TYPE_GENERIC, 0)) return true
        val error = Kernel32.INSTANCE.GetLastError()
        return error == WinError.ERROR_NOT_FOUND
    }

    private interface CredentialApi : StdCallLibrary {
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredWriteW(credential: Credential, flags: Int): Boolean
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
        fun CredFree(buffer: Pointer)

        companion object {
            val INSTANCE: CredentialApi = Native.load("Advapi32", CredentialApi::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }

    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize",
        "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    class Credential() : Structure() {
        @JvmField var Flags = 0
        @JvmField var Type = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWritten = FILETIME()
        @JvmField var CredentialBlobSize = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist = 0
        @JvmField var AttributeCount = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null

        constructor(pointer: Pointer) : this() {
            useMemory(pointer)
            read()
        }
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val MAX_BLOB_BYTES = 5 * 512
    }
}
