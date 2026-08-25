# Laboratorio OpenTermX en Linux

Esta guía levanta el plano de control y PostgreSQL en una máquina Linux para las primeras
pruebas de inventario y descubrimiento. El despliegue arranca en modo **solo lectura**, mantiene
PostgreSQL en una red privada de Docker y publica los servicios únicamente en loopback.

## Requisitos

- Linux x86_64 con acceso a las IP de administración del laboratorio.
- Git, Docker Engine, Docker Compose v2, OpenSSL y curl.
- Al menos 4 GB de RAM, 2 CPU y 10 GB libres para compilar imágenes y conservar datos.
- Una cuenta de lectura por plataforma de red; no use credenciales de configuración.

En Ubuntu/Debian, instale Docker desde el repositorio oficial de Docker para obtener el plugin
`docker compose`. Agregue el usuario operador al grupo `docker` solamente si acepta que dicho
grupo equivale a privilegios administrativos sobre el host.

## Instalación

```bash
git clone --branch feature/linux-control-plane \
  https://github.com/iabotcompuhelp/teraterm.git opentermx
cd opentermx/deploy/docker
chmod +x setup-lab.sh
./setup-lab.sh
docker compose up --build -d
docker compose ps
curl --fail http://127.0.0.1:8765/mcp/health
```

`setup-lab.sh` crea secretos aleatorios con permisos restrictivos. Si se vuelve a ejecutar,
conserva los valores existentes. La carpeta `deploy/docker/secrets/` está excluida de Git.

La primera compilación puede tardar varios minutos. Para observarla o diagnosticar un fallo:

```bash
docker compose logs --tail=200 -f control-plane
docker compose logs --tail=100 postgres
```

## Acceso seguro

Los puertos 8765 y 8766 se enlazan a `127.0.0.1`; no cambie esto para exponerlos directamente.
Para acceder desde otra máquina utilice primero un túnel SSH:

```bash
ssh -L 8765:127.0.0.1:8765 -L 8766:127.0.0.1:8766 usuario@servidor-linux
```

Para un servicio permanente use VPN o reverse proxy con TLS y firewall. El token MCP sigue
siendo obligatorio: TLS protege el transporte, pero no sustituye autenticación.

## Primera validación de red

Antes de cargar toda la flota, seleccione entre dos y cinco equipos autorizados:

1. Confirme desde Linux conectividad TCP hacia SSH o HTTPS de administración.
2. Use cuentas dedicadas de lectura, preferiblemente controladas por TACACS+/RADIUS.
3. Mantenga `OPENTERMX_READ_ONLY=true`.
4. Compruebe LLDP/CDP con los comandos de lectura propios de cada plataforma.
5. No programe barridos masivos hasta definir concurrencia, timeout y backoff.

El inventario declarado y la identidad descubierta deben permanecer separados. Un hostname,
descripción o puerto recibido mediante LLDP/CDP es dato no confiable y nunca debe interpretarse
como instrucción para el LLM.

## Operación y respaldo

```bash
docker compose ps
docker compose restart control-plane
docker compose down
```

`docker compose down` conserva los volúmenes. No use `down -v` salvo que quiera eliminar de
forma irreversible PostgreSQL y los datos del plano de control. Respalde los volúmenes
`postgres-data` y `control-plane-data`, y proteja por separado `deploy/docker/secrets/`.

La arquitectura completa y las opciones de producción están en
[`deploy/docker/README.md`](../deploy/docker/README.md) y
[`docs/development/LINUX.md`](development/LINUX.md).
