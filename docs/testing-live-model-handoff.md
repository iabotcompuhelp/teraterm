# Validación live del handoff entre modelos

`LiveModelHandoffTest` valida el paquete de handoff contra proveedores reales. Las pruebas son
opt-in: el build normal las omite, no consume créditos y no requiere secretos. Las claves solo
se leen desde variables de entorno y nunca se persisten.

## OpenAI → Ollama

```powershell
$env:OPENTERMX_LIVE_LLM_TESTS = "true"
$env:OPENAI_API_KEY = "<clave>"
$env:OPENAI_MODEL = "<modelo>"       # opcional
$env:OLLAMA_BASE_URL = "http://localhost:11434" # opcional
$env:OLLAMA_MODEL = "<modelo-local>"
.\gradlew.bat :app:test --tests "*LiveModelHandoffTest.OpenAI a Ollama*" --rerun-tasks
```

## Claude → OpenAI

```powershell
$env:OPENTERMX_LIVE_LLM_TESTS = "true"
$env:ANTHROPIC_API_KEY = "<clave>"
$env:ANTHROPIC_MODEL = "<modelo>"     # opcional
$env:OPENAI_API_KEY = "<clave>"
$env:OPENAI_MODEL = "<modelo>"        # opcional
.\gradlew.bat :app:test --tests "*LiveModelHandoffTest.Claude a OpenAI*" --rerun-tasks
```

`OPENAI_BASE_URL` y `ANTHROPIC_BASE_URL` permiten endpoints compatibles. El criterio de
aceptación es que el destino recupere objetivo, alcance, decisión, evidencia y el riesgo
`UNKNOWN`, conservando la instrucción de verificar el estado real antes de reintentar.
