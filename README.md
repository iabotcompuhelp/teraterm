# OpenTermX

Multiplatform terminal emulator with a built-in MCP server.

## Build & test

Requires **JDK 21** (Temurin recommended) and **Python 3** (for the mcp-server pytest
suite, which creates its own venv and uses an embedded PostgreSQL — no external services
needed).

```bash
./gradlew check       # build + unit tests of all modules + mcp-server pytest suite
./gradlew :app:run    # launch the JavaFX app
```

CI runs `./gradlew check` on every push and pull request (`.github/workflows/ci.yml`).
Architecture overview: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Documentation

See:

- [`mcp-server/README.md`](mcp-server/README.md) — module overview and tools reference.
- [`docs/mcp/USER_GUIDE.md`](docs/mcp/USER_GUIDE.md) — user guide (español).
- [`docs/mcp/USER_GUIDE.en.md`](docs/mcp/USER_GUIDE.en.md) — user guide (English).
- [`docs/mcp/CLIENT_TEST_PLAN.md`](docs/mcp/CLIENT_TEST_PLAN.md) — checklist to validate against Claude Desktop / Cursor / Cline.
- [`docs/release/SIGNING.md`](docs/release/SIGNING.md) — code-signing for installers.
- [`docs/release/RELEASE_CHECKLIST.md`](docs/release/RELEASE_CHECKLIST.md) — release procedure.
