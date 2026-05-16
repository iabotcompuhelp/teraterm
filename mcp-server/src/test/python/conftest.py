"""Fixtures comunes para los tests de integración del servidor MCP.

El servidor se arranca como subproceso JVM (TestServerMain), por lo cual estos tests
son verdaderamente black-box: cruzan el wire de HTTP igual que un cliente externo.
"""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
import uuid
from contextlib import closing
from pathlib import Path
from typing import Iterator

import httpx
import pytest


REPO_ROOT = Path(__file__).resolve().parents[4]
CLASSPATH_FILE = REPO_ROOT / "mcp-server" / "build" / "test-server-classpath.txt"


def _pick_free_port() -> int:
    with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _java_executable() -> str:
    """Resuelve el `java` a usar. Prioridad: env `MCP_TEST_JAVA` > `JAVA_HOME/bin/java` > `java`."""
    explicit = os.environ.get("MCP_TEST_JAVA")
    if explicit:
        return explicit
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return "java"


def _load_classpath() -> str:
    if not CLASSPATH_FILE.is_file():
        pytest.skip(
            f"No se encontró {CLASSPATH_FILE}. Corré la suite vía "
            f"`./gradlew :mcp-server:pythonTests` para que Gradle genere el classpath."
        )
    return CLASSPATH_FILE.read_text(encoding="utf-8").strip()


def _wait_for_ready(proc: subprocess.Popen, timeout: float = 30.0) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            stderr = proc.stderr.read() if proc.stderr else ""
            raise RuntimeError(
                f"El servidor de tests murió antes de quedar listo. "
                f"Exit code: {proc.returncode}. Stderr:\n{stderr}"
            )
        line = proc.stdout.readline() if proc.stdout else ""
        if not line:
            time.sleep(0.05)
            continue
        line = line.strip()
        if line.startswith("READY "):
            return line.removeprefix("READY ").strip()
    raise TimeoutError(f"El servidor MCP no imprimió READY en {timeout}s")


@pytest.fixture(scope="session")
def auth_token() -> str:
    """Token aleatorio fijo para toda la suite — usado por los tests de auth."""
    return f"test-{uuid.uuid4()}"


@pytest.fixture(scope="session")
def server(auth_token: str) -> Iterator[dict]:
    """Arranca un TestServerMain en puerto libre. Cleanup automático al salir.

    Devuelve `{"url": ..., "token": ..., "process": ...}`.
    """
    classpath = _load_classpath()
    port = _pick_free_port()
    env = {
        **os.environ,
        "MCP_TEST_PORT": str(port),
        "MCP_TEST_BIND": "127.0.0.1",
        "MCP_TEST_TOKEN": auth_token,
        # El test que prueba propose_commands aprobando todo lo pasa via header,
        # pero el approval gate se inicializa una sola vez al arrancar — así que
        # arrancamos con auto-approve y los tests de rechazo lo manejan en su tooling.
        "OPENTERMX_TEST_AUTO_APPROVE": "1",
        "JAVA_TOOL_OPTIONS": "",  # evitar contaminación del Daemon de Gradle
    }
    java = _java_executable()
    cmd = [java, "-cp", classpath, "com.opentermx.mcp.testserver.TestServerMain"]
    proc = subprocess.Popen(
        cmd,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        bufsize=1,
    )
    try:
        bind = _wait_for_ready(proc)
    except Exception:
        proc.kill()
        proc.wait(timeout=5)
        raise
    url = f"http://{bind}/mcp"
    yield {"url": url, "base": f"http://{bind}", "token": auth_token, "process": proc}
    # Shutdown limpio: línea SHUTDOWN al stdin, luego kill si no responde.
    try:
        if proc.stdin and not proc.stdin.closed:
            proc.stdin.write("SHUTDOWN\n")
            proc.stdin.flush()
    except (BrokenPipeError, OSError):
        pass
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)


@pytest.fixture(scope="session")
def negotiated_protocol_version(server) -> str:
    """Hace `initialize` una sola vez por sesión y devuelve la versión negociada.

    El servidor identifica al cliente por IP + prefijo del token, así que la negociación
    persiste para toda la suite. Después de esto, todo request con `client` lleva el
    header `MCP-Protocol-Version` correcto.
    """
    headers = {"Authorization": f"Bearer {server['token']}"}
    with httpx.Client(base_url=server["base"], headers=headers, timeout=15.0) as c:
        r = c.post("/mcp", json={
            "jsonrpc": "2.0", "id": 0, "method": "initialize",
            "params": {"protocolVersion": "2024-11-05", "clientInfo": {"name": "pytest"}},
        })
        r.raise_for_status()
        return r.json()["result"]["protocolVersion"]


@pytest.fixture
def client(server, negotiated_protocol_version) -> Iterator[httpx.Client]:
    headers = {
        "Authorization": f"Bearer {server['token']}",
        "MCP-Protocol-Version": negotiated_protocol_version,
    }
    with httpx.Client(base_url=server["base"], headers=headers, timeout=15.0) as c:
        yield c


@pytest.fixture
def anon_client(server) -> Iterator[httpx.Client]:
    """Cliente SIN token — usado por los tests de auth."""
    with httpx.Client(base_url=server["base"], timeout=15.0) as c:
        yield c


def call_tool(client: httpx.Client, name: str, arguments: dict | None = None, req_id: int = 1) -> dict:
    """Helper: invoca tools/call y devuelve el JSON-RPC response parseado."""
    body = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    r = client.post("/mcp", json=body)
    r.raise_for_status()
    return r.json()


def rpc(client: httpx.Client, method: str, params: dict | None = None, req_id: int = 1) -> dict:
    body = {"jsonrpc": "2.0", "id": req_id, "method": method}
    if params is not None:
        body["params"] = params
    r = client.post("/mcp", json=body)
    r.raise_for_status()
    return r.json()