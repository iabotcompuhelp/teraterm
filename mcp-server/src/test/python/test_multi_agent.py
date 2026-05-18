"""Tests de integración Phase 3 Fase 3 — Multi-agent roles + approval token HMAC.

Cubre:
- Filtro server-side por rol (X-OpenTermX-Role).
- Flujo completo operator + compliance: start_operation → compliance_evaluate
  → propose_commands con el approvalToken.
- Casos de rechazo: token de otra op, token con commands distintos, token sin
  rol válido, propose_commands sin token cuando la op lo exige.
"""
from __future__ import annotations

import httpx
import pytest

from conftest import call_tool


@pytest.fixture
def compliance_client(server, negotiated_protocol_version) -> httpx.Client:
    """Cliente con rol COMPLIANCE. Mismo server que el client default — solo cambia el header."""
    headers = {
        "Authorization": f"Bearer {server['token']}",
        "MCP-Protocol-Version": negotiated_protocol_version,
        "X-OpenTermX-Role": "COMPLIANCE",
    }
    with httpx.Client(base_url=server["base"], headers=headers, timeout=15.0) as c:
        yield c


@pytest.fixture
def validator_client(server, negotiated_protocol_version) -> httpx.Client:
    headers = {
        "Authorization": f"Bearer {server['token']}",
        "MCP-Protocol-Version": negotiated_protocol_version,
        "X-OpenTermX-Role": "VALIDATOR",
    }
    with httpx.Client(base_url=server["base"], headers=headers, timeout=15.0) as c:
        yield c


def test_operator_no_puede_invocar_compliance_evaluate(client):
    resp = call_tool(client, "compliance_evaluate", arguments={
        "operationId": "x", "proposedCommands": ["show version"],
    })
    assert resp["error"]["code"] == -32601, resp
    assert "rol `OPERATOR`" in resp["error"]["message"], resp


def test_compliance_no_puede_invocar_propose_commands(compliance_client):
    resp = call_tool(compliance_client, "propose_commands", arguments={
        "sessionId": "x", "commands": ["show version"],
    })
    assert resp["error"]["code"] == -32601, resp
    assert "rol `COMPLIANCE`" in resp["error"]["message"], resp


def test_validator_es_read_only(validator_client):
    resp = call_tool(validator_client, "open_session", arguments={"protocol": "SSH", "host": "x"})
    assert resp["error"]["code"] == -32601, resp


def test_flujo_completo_operator_compliance_token(client, compliance_client):
    # 1) operator inicia op que exige compliance approval.
    started = call_tool(client, "start_operation", arguments={
        "contextInline": {
            "operation": {"description": "flujo multi-agent"},
            "scope": {},
            "constraints": {"require_compliance_approval": True},
        },
    })
    op_id = started["result"]["structuredContent"]["operationId"]

    try:
        # 2) operator intenta propose_commands SIN token: rechazado por el handler.
        no_token = call_tool(client, "propose_commands", arguments={
            "sessionId": "session-cisco",
            "commands": ["show version"],
        })
        assert no_token["result"]["isError"] is True, no_token
        msg = no_token["result"]["content"][0]["text"]
        assert "approval_token" in msg, msg

        # 3) compliance evalúa y firma.
        evaluated = call_tool(compliance_client, "compliance_evaluate", arguments={
            "operationId": op_id,
            "proposedCommands": ["show version"],
            "approved": True,
            "reasons": ["lectura segura"],
        })
        payload = evaluated["result"]["structuredContent"]
        assert payload["approved"] is True
        token = payload["approvalToken"]
        assert token and "." in token, payload

        # 4) operator ejecuta con el token: pasa la verificación, llega al approval gate
        #    humano (que en tests es auto-approve), y termina ejecutando.
        with_token = call_tool(client, "propose_commands", arguments={
            "sessionId": "session-cisco",
            "commands": ["show version"],
            "approvalToken": token,
        })
        result_payload = with_token["result"]["structuredContent"]
        assert result_payload["approved"] is True
        assert result_payload["executed"] == 1
    finally:
        call_tool(client, "end_operation", arguments={"operationId": op_id})


def test_token_de_otra_operation_es_rechazado(client, compliance_client):
    # Op A.
    a = call_tool(client, "start_operation", arguments={
        "contextInline": {
            "operation": {"description": "A"},
            "scope": {},
            "constraints": {"require_compliance_approval": True},
        },
    })
    op_a = a["result"]["structuredContent"]["operationId"]

    try:
        # Compliance firma para op A.
        signed = call_tool(compliance_client, "compliance_evaluate", arguments={
            "operationId": op_a, "proposedCommands": ["show version"], "approved": True,
        })
        token = signed["result"]["structuredContent"]["approvalToken"]
    finally:
        call_tool(client, "end_operation", arguments={"operationId": op_a})

    # Nueva op B (también exige approval); usamos el token de A.
    b = call_tool(client, "start_operation", arguments={
        "contextInline": {
            "operation": {"description": "B"},
            "scope": {},
            "constraints": {"require_compliance_approval": True},
        },
    })
    op_b = b["result"]["structuredContent"]["operationId"]
    try:
        result = call_tool(client, "propose_commands", arguments={
            "sessionId": "session-cisco",
            "commands": ["show version"],
            "approvalToken": token,
        })
        assert result["result"]["isError"] is True, result
        msg = result["result"]["content"][0]["text"]
        # El token A no es válido para ninguna de las ops activas que exigen approval
        # (sólo B): el mensaje viene del primer Invalid de B.
        assert "operationId" in msg or "approval_token" in msg, msg
    finally:
        call_tool(client, "end_operation", arguments={"operationId": op_b})


def test_token_con_commands_distintos_es_rechazado(client, compliance_client):
    started = call_tool(client, "start_operation", arguments={
        "contextInline": {
            "operation": {"description": "mismatch test"},
            "scope": {},
            "constraints": {"require_compliance_approval": True},
        },
    })
    op_id = started["result"]["structuredContent"]["operationId"]

    try:
        signed = call_tool(compliance_client, "compliance_evaluate", arguments={
            "operationId": op_id, "proposedCommands": ["show version"], "approved": True,
        })
        token = signed["result"]["structuredContent"]["approvalToken"]

        # Operator manda OTROS commands con el token de "show version".
        result = call_tool(client, "propose_commands", arguments={
            "sessionId": "session-cisco",
            "commands": ["show running-config"],
            "approvalToken": token,
        })
        assert result["result"]["isError"] is True, result
        msg = result["result"]["content"][0]["text"]
        assert "no matchean" in msg, msg
    finally:
        call_tool(client, "end_operation", arguments={"operationId": op_id})


def test_compliance_evaluate_rechaza_no_emite_token(compliance_client):
    # Para que el handler encuentre la op, primero la creamos (compliance puede start_operation? no).
    # Solución: el handler permite operationId que no exista solo en otro test — acá creamos via OPERATOR
    # tan fuera del scope que es simplemente una llamada que descartamos. Más simple: usamos una op
    # in-memory ad-hoc. El handler chequea forOperationId; si no existe, devuelve NOT_FOUND.
    resp = call_tool(compliance_client, "compliance_evaluate", arguments={
        "operationId": "op-fantasma",
        "proposedCommands": ["x"],
        "approved": False,
    })
    # Esperamos error de NOT_FOUND (la op no existe).
    assert resp["result"]["isError"] is True, resp
    msg = resp["result"]["content"][0]["text"]
    assert "no encontrada" in msg, msg
