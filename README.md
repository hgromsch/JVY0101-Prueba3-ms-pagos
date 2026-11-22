# ms-pagos

Cobro de sesiones y talleres. Cubre **RF-08**. **Zona PCI**: jamás se persisten datos de
tarjeta, solo el token opaco de la pasarela. La confirmación del pago llega por el webhook
público (en producción con firma validada) y gatilla el evento `pago_confirmado`.

**Puerto:** 8085 · **Base de datos:** `pagos_db` · **Swagger:** http://localhost:8085/swagger-ui.html

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/pagos` | Inicia el cobro de una cita/taller (201, queda PENDIENTE) |
| GET | `/api/pagos?estado=` | Lista pagos, filtro opcional por estado |
| GET | `/api/pagos/{id}` | Obtiene un pago (404 si no existe) |
| POST | `/api/pagos/webhook` | Confirmación/rechazo desde la pasarela |
| POST | `/api/pagos/{id}/reembolsos` | Reembolso total o parcial (201; solo pagos CONFIRMADOS) |
| GET | `/api/pagos/{id}/reembolsos` | Lista reembolsos del pago |

Estados: `PENDIENTE`, `CONFIRMADO`, `RECHAZADO`, `REEMBOLSADO`. Tipos: `CITA`, `TALLER`.
Eventos del webhook: `PAGO_CONFIRMADO`, `PAGO_RECHAZADO`.

## Cómo correr localmente

```bash
# Opción A — todo el sistema en contenedores (repo rys-infraestructura, clonado al lado de este)
cd ../rys-infraestructura && docker compose up -d --build
curl http://localhost:8085/api/pagos

# Opción B — solo este servicio con Maven (requiere PostgreSQL locales)
mvn spring-boot:run

# Opción C — solo la imagen Docker de este servicio
docker build -t rys/ms-pagos:local .
docker run --rm -p 8085:8085 -e DB_HOST=host.docker.internal rys/ms-pagos:local
curl http://localhost:8085/actuator/health
```

## Arquitectura

Capas `api` → `service` → `repository` → `domain`. Reglas de negocio: el webhook solo
procesa pagos PENDIENTES (409 si ya fue procesado) y los reembolsos acumulados no pueden
superar el monto del pago.
Relación JPA destacada: `Pago` **OneToMany** `Reembolso` (y **ManyToOne** inversa).

## Imagen Docker (EP3)

`Dockerfile` **multi-stage**: la etapa `build` (Maven + JDK 21) descarga dependencias en una capa aparte (caché)
y separa el jar en las capas de Spring Boot; la etapa `runtime` usa solo **JRE 21 sobre Alpine**, corre con un
**usuario sin privilegios** (UID 10001), expone el puerto 8085, define un `HEALTHCHECK` y ajusta la JVM al
límite de memoria del contenedor (`MaxRAMPercentage`). `.dockerignore` deja fuera `target/`, `.git/` y el resto.
Tamaño aproximado: ~145 MB comprimida (≈400 MB descomprimida, de los cuales ~180 MB son el JRE compartido).

## Despliegue en la nube (EP3)

Corre en el clúster **Docker Swarm** de AWS como el servicio `rys_ms-pagos` (definido en
`rys-infraestructura/docker-compose.yml`), detrás del **API Gateway**:
`https://<api>.execute-api.us-east-1.amazonaws.com/prod/pagos/api/pagos` (cabecera `x-api-key`).

### Pipeline CI/CD (`.github/workflows/ci-cd.yml`)

| Etapa | Cuándo | Qué hace |
|---|---|---|
| 1 · Build y pruebas | push y pull request a `main`/`develop` | `mvn verify` + reporte de pruebas |
| 2 · Recursos cloud | push a `main` | Crea/verifica el repositorio ECR `rys/ms-pagos` (análisis de vulnerabilidades al subir) |
| 3 · Imagen | push a `main` | `docker build` multi-stage y push a Amazon ECR con etiqueta = commit (+ `latest`) |
| 4 · Despliegue | push a `main` | `docker service update --image …` por SSH al manager: rolling update `start-first` con rollback automático |
| 5 · Prueba de humo | push a `main` | Por el API Gateway: `/actuator/info` muestra la versión del commit, `/actuator/health` = UP, `GET /api/pagos` = 200 y sin API key = 403 |

Secrets del repositorio: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (Learner Lab, se renuevan
en cada sesión), `SWARM_HOST`, `SWARM_SSH_KEY`, `API_URL`, `API_KEY`.

## Correcciones aplicadas sobre la EP2

- **Configuración externa:** la conexión a la base de datos (y a Redis) estaba fija en `localhost`, lo que impedía
  ejecutar el servicio en contenedores; ahora se toma de `DB_HOST`, `DB_PORT`, `DB_NAME`, `REDIS_HOST` (con
  valores por defecto para desarrollo).
- **Salud del servicio:** se agregó Spring Boot Actuator (`/actuator/health/liveness`, `/readiness`, `/info` con la
  versión desplegada), que usan el `HEALTHCHECK` de Docker, Swarm y la prueba de humo del pipeline.
- **Apagado ordenado** (`server.shutdown: graceful`): al escalar hacia abajo o actualizar, termina las peticiones en curso.
- **Secretos fuera del código:** la clave de la base de datos llega como secreto de Docker Swarm
  (`/run/secrets/spring.datasource.password`, leído con `spring.config.import: configtree`).
- `spring.jpa.open-in-view: false` y pool de conexiones acotado (`DB_POOL_SIZE`, 4 por réplica) para que
  varias réplicas compartan la misma instancia RDS.
- **Firma del webhook (antes `TODO`):** `POST /api/pagos/webhook` exige la cabecera `X-Firma-Pasarela` =
  HMAC-SHA256 de `"<pagoId>:<evento>"` con el secreto `PAGOS_WEBHOOK_SECRET` (secreto de Swarm); sin firma o con
  firma inválida responde **401**. Comparación en tiempo constante. Sin secreto (desarrollo local) no se exige.
  Pruebas nuevas en `VerificadorFirmaWebhookTest`.
