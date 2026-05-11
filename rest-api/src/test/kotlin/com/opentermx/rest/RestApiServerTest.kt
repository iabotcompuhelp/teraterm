package com.opentermx.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RestApiServerTest {

    private lateinit var server: RestApiServer
    private val mapper = ObjectMapper().registerKotlinModule()
    private val token = "test-token-123"
    private val hooks = StubHooks()
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = RestApiServer(
            hooks,
            RestApiConfig(bindHost = "127.0.0.1", port = 0, token = token, requireAuth = true),
        )
        server.start()
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun base() = "http://127.0.0.1:${server.effectivePort}"

    @Test
    fun healthEndpointIsPublic() {
        val resp = client.newCall(Request.Builder().url("${base()}/api/health").build()).execute()
        assertEquals(200, resp.code)
        val body = resp.body!!.string()
        assertTrue(body.contains("\"status\":\"ok\""))
    }

    @Test
    fun protectedEndpointWithoutTokenReturns401() {
        val resp = client.newCall(Request.Builder().url("${base()}/api/sessions").build()).execute()
        assertEquals(401, resp.code)
    }

    @Test
    fun listSessionsWithTokenReturnsHookResult() {
        hooks.sessions = listOf(
            SessionInfo("s-1", "switch-edge", "SSH", "10.0.0.1", 22, "admin"),
        )
        val resp = client.newCall(
            Request.Builder()
                .url("${base()}/api/sessions")
                .header("Authorization", "Bearer $token")
                .build()
        ).execute()
        assertEquals(200, resp.code)
        val json = mapper.readTree(resp.body!!.byteStream())
        val list = json.path("sessions")
        assertEquals(1, list.size())
        assertEquals("s-1", list[0].path("id").asText())
        assertEquals("admin", list[0].path("username").asText())
    }

    @Test
    fun terminalSendReachesHookWithSessionIdAndText() {
        hooks.sendResult = SendTerminalResponse("ok", 12)
        val body = """{"sessionId":"s-1","text":"show version\n"}""".toRequestBody("application/json".toMediaType())
        val resp = client.newCall(
            Request.Builder()
                .url("${base()}/api/terminal/send")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        ).execute()
        assertEquals(200, resp.code)
        assertEquals("s-1", hooks.lastSendSession)
        assertEquals("show version\n", hooks.lastSendText)
    }

    @Test
    fun terminalBufferReturnsHookLines() {
        hooks.bufferLines = listOf("router#", "show ip int", "10.0.0.1/24")
        val resp = client.newCall(
            Request.Builder()
                .url("${base()}/api/terminal/buffer?sessionId=s-1&lines=10")
                .header("Authorization", "Bearer $token")
                .build()
        ).execute()
        assertEquals(200, resp.code)
        val json = mapper.readTree(resp.body!!.byteStream())
        assertEquals(3, json.path("lines").size())
        assertEquals("router#", json.path("lines")[0].asText())
        assertEquals("s-1", hooks.lastBufferSession)
        assertEquals(10, hooks.lastBufferLines)
    }

    @Test
    fun tftpStartStopRoundtrip() {
        hooks.tftpStatus = TftpStatusResponse(false)
        val startReq = """{"port":6969,"rootDir":"C:/tmp","allowGet":true,"allowPut":false}"""
        val startResp = client.newCall(
            Request.Builder()
                .url("${base()}/api/tftp/start")
                .header("Authorization", "Bearer $token")
                .post(startReq.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        assertEquals(200, startResp.code)
        assertEquals(6969, hooks.lastTftpStart?.port)
        assertEquals("C:/tmp", hooks.lastTftpStart?.rootDir)

        val stopResp = client.newCall(
            Request.Builder()
                .url("${base()}/api/tftp/stop")
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        ).execute()
        assertEquals(200, stopResp.code)
        assertTrue(hooks.tftpStopCalled)
    }

    @Test
    fun executeMacroPassesScriptAndSession() {
        hooks.executeResult = ExecuteMacroResponse("ok", 42, listOf("[done]"))
        val payload = """{"script":"sendln 'show ver'","sessionId":"s-1","timeoutSeconds":30}"""
        val resp = client.newCall(
            Request.Builder()
                .url("${base()}/api/macro/execute")
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        assertEquals(200, resp.code)
        assertEquals("s-1", hooks.lastExecuteRequest?.sessionId)
        assertEquals("sendln 'show ver'", hooks.lastExecuteRequest?.script)
        assertEquals(30, hooks.lastExecuteRequest?.timeoutSeconds)
    }

    private class StubHooks : RestApiHooks {
        var sessions: List<SessionInfo> = emptyList()
        var bufferLines: List<String> = emptyList()
        var sendResult: SendTerminalResponse = SendTerminalResponse("ok", 0)
        var executeResult: ExecuteMacroResponse = ExecuteMacroResponse("ok", 0, emptyList())
        var tftpStatus: TftpStatusResponse = TftpStatusResponse(false)

        var lastSendSession: String? = null
        var lastSendText: String? = null
        var lastBufferSession: String? = null
        var lastBufferLines: Int = 0
        var lastExecuteRequest: ExecuteMacroRequest? = null
        var lastTftpStart: TftpStartRequest? = null
        var tftpStopCalled: Boolean = false

        override fun executeMacro(request: ExecuteMacroRequest): ExecuteMacroResponse {
            lastExecuteRequest = request; return executeResult
        }
        override fun sendToTerminal(sessionId: String, text: String): SendTerminalResponse {
            lastSendSession = sessionId; lastSendText = text; return sendResult
        }
        override fun terminalBuffer(sessionId: String, lines: Int): TerminalBufferResponse {
            lastBufferSession = sessionId; lastBufferLines = lines
            return TerminalBufferResponse(sessionId, bufferLines)
        }
        override fun listSessions(): List<SessionInfo> = sessions
        override fun startTftpServer(request: TftpStartRequest): TftpStatusResponse {
            lastTftpStart = request
            return TftpStatusResponse(running = true, port = request.port, rootDir = request.rootDir)
        }
        override fun stopTftpServer(): TftpStatusResponse {
            tftpStopCalled = true; return TftpStatusResponse(false)
        }
        override fun tftpStatus(): TftpStatusResponse = tftpStatus
    }
}
