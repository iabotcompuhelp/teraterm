package com.opentermx.app.ui.mcp

import com.opentermx.app.ui.MainWindow
import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.HostKeyVerifier
import com.opentermx.common.session.SessionId

/**
 * Abstrae la apertura de una sesión nueva desde código no-UI (servidor MCP). La implementación
 * productiva delega en [MainWindow.launchSession], que se encarga de saltar al FX thread.
 *
 * Mantener esta indirección como interfaz permite testear [JavaFxSessionOpener] sin levantar
 * JavaFX ni el resto del `MainWindow`.
 */
interface SessionLauncher {

    fun launch(config: ConnectionConfig, name: String, connection: Connection): SessionId

    fun hostKeyVerifier(): HostKeyVerifier
}

class MainWindowSessionLauncher(private val window: MainWindow) : SessionLauncher {

    override fun launch(config: ConnectionConfig, name: String, connection: Connection): SessionId =
        window.launchSession(config, name, connection)

    override fun hostKeyVerifier(): HostKeyVerifier = window.hostKeyVerifier()
}