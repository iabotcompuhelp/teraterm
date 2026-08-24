package com.opentermx.server.graphql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.agent.RemoteCommandTask
import com.opentermx.telemetrydb.TelemetryDb
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.javalin.Javalin
import io.javalin.http.HttpStatus
import java.time.OffsetDateTime

data class GraphqlRequest(
    val query: String = "",
    val operationName: String? = null,
    val variables: Map<String, Any?> = emptyMap(),
)

/** Gateway GraphQL read-only. Las mutaciones de red siguen el flujo MCP con aprobación. */
class ControlPlaneGraphql(
    private val database: () -> TelemetryDb?,
    private val agents: () -> List<Map<String, Any?>>,
    private val tasks: (String?, Int) -> List<RemoteCommandTask>,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val graphQL: GraphQL = GraphQL.newGraphQL(
        SchemaGenerator().makeExecutableSchema(
            SchemaParser().parse(SCHEMA),
            RuntimeWiring.newRuntimeWiring()
                .type("Query") { type ->
                    type.dataFetcher("devices") { env ->
                        database()?.devices?.list(
                            role = env.getArgument<String>("role"),
                            site = env.getArgument<String>("site"),
                            vendor = env.getArgument<String>("vendor"),
                            limit = env.getArgument<Int>("limit") ?: 100,
                        ) ?: emptyList<Map<String, Any?>>()
                    }
                    type.dataFetcher("deviceActivity") { env ->
                        database()?.history?.deviceActivity(
                            hostname = requireNotNull(env.getArgument<String>("hostname")),
                            from = env.getArgument<String>("from")?.let(OffsetDateTime::parse),
                            to = env.getArgument<String>("to")?.let(OffsetDateTime::parse),
                            limit = (env.getArgument<Int>("limit") ?: 100).coerceIn(1, 500),
                        ) ?: emptyList<Map<String, Any?>>()
                    }
                    type.dataFetcher("agents") { agents() }
                    type.dataFetcher("remoteTasks") { env ->
                        tasks(env.getArgument<String>("agentId"), env.getArgument<Int>("limit") ?: 100)
                            .map { task ->
                                mapOf(
                                    "taskId" to task.taskId,
                                    "operationId" to task.operationId,
                                    "agentId" to task.agentId,
                                    "sessionId" to task.sessionId,
                                    "status" to task.status.name,
                                    "createdAtMillis" to task.createdAtMillis.toString(),
                                    "expiresAtMillis" to task.expiresAtMillis.toString(),
                                    "deliveryAttempt" to task.deliveryAttempt,
                                )
                            }
                    }
                }.build(),
        ),
    ).build()

    fun install(app: Javalin) {
        app.post("/graphql") { context ->
            val request = runCatching { mapper.readValue(context.body(), GraphqlRequest::class.java) }
                .getOrElse {
                    context.status(HttpStatus.BAD_REQUEST).json(mapOf("errors" to listOf(mapOf("message" to "Solicitud GraphQL inválida"))))
                    return@post
                }
            if (request.query.isBlank()) {
                context.status(HttpStatus.BAD_REQUEST).json(mapOf("errors" to listOf(mapOf("message" to "query es obligatorio"))))
                return@post
            }
            val result = graphQL.execute(
                ExecutionInput.newExecutionInput()
                    .query(request.query)
                    .operationName(request.operationName)
                    .variables(request.variables)
                    .build(),
            )
            context.json(result.toSpecification())
        }
    }

    fun execute(query: String, variables: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        graphQL.execute(ExecutionInput.newExecutionInput().query(query).variables(variables).build()).toSpecification()

    private companion object {
        val SCHEMA = """
            type Query {
              devices(role: String, site: String, vendor: String, limit: Int = 100): [Device!]!
              deviceActivity(hostname: String!, from: String, to: String, limit: Int = 100): [DeviceActivity!]!
              agents: [Agent!]!
              remoteTasks(agentId: String, limit: Int = 100): [RemoteTask!]!
            }
            type Device {
              id: ID!, hostname: String!, mgmt_address: String, port: Int, vendor: String,
              model: String, os_version: String, site: String, role: String, criticality: String, enabled: Boolean
            }
            type DeviceActivity {
              occurred_at: String, device_id: ID, device_name: String!, mgmt_address: String,
              actor: String!, activity_type: String!, summary: String!, outcome: String!, source: String!, correlation_id: String
            }
            type Agent {
              agentId: ID!, displayName: String!, platform: String!, agentVersion: String,
              protocolVersion: Int, lastRemoteAddress: String, firstSeenAt: String, lastSeenAt: String,
              lastSeenAtMillis: String, sessionCount: Int!, enabled: Boolean, online: Boolean!
            }
            type RemoteTask {
              taskId: ID!, operationId: ID, agentId: ID!, sessionId: ID!, status: String!,
              createdAtMillis: String!, expiresAtMillis: String!, deliveryAttempt: Int!
            }
        """.trimIndent()
    }
}
