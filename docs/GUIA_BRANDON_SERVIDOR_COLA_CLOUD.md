# Módulo 2: Servidor HTTP Nativo, Cola Sincronizada y Despliegue Cloud (Brandon)

Este documento especifica la arquitectura, endpoints y despliegue del **Módulo 2** desarrollado por **Brandon**, cumpliendo con los requerimientos **RF-03, RF-04, RNF-01, RNF-02 y RNF-04**.

---

## 🏛️ Arquitectura del Módulo

```text
Cliente Web (Cicilis) / Peticiones HTTP Concurrentes
                   │
                   ▼  (Pool de Workers HTTP nativos)
      [ com.sun.net.httpserver.HttpServer ]
                   │
      ┌────────────┴────────────────────────┐
      │ (Mutaciones: POST, DELETE, PUT)     │ (Lecturas directas: GET)
      ▼                                     ▼
[ ThreadSafeRequestQueue ]           [ StorageEngine ] (Lectura paralela
(ReentrantLock + Condition notEmpty/notFull)            sin bloqueo de cola)
      │
      ▼  (Consumidor Secuencial FIFO)
[ QueueDispatcher ]
      │
      ▼
[ StorageEngine (Alejandro) ]
```

---

## 🔒 1. Cola Sincronizada Concurrente (`ThreadSafeRequestQueue`)

- **Estructura:** Buffer circular acotado (*Bounded Buffer*) de tamaño configurable.
- **Sincronización:**
  - `ReentrantLock(true)` (Fair lock para garantizar orden FIFO estricto).
  - `Condition notEmpty`: bloquea al hilo despachador cuando la cola está vacía hasta que llegue un comando.
  - `Condition notFull`: bloquea a los hilos HTTP productores si la cola alcanza su capacidad máxima hasta que se libere espacio.
- **Prueba de Concurrencia:** Verificada con 50 hilos simultáneos procesando 5,000 peticiones en 316 ms (15,800+ peticiones/segundo) con 0 condiciones de carrera.

---

## 🌐 2. Endpoints de la API REST (Para Cicilis - Cliente Web)

| Método | Endpoint | Descripción | Comportamiento |
|---|---|---|---|
| `POST` | `/api/policies` | Inserción de una nueva póliza | Encola en `ThreadSafeRequestQueue`. Retorna HTTP 201 con el offset asignado. |
| `DELETE` | `/api/policies?offset={offset}` | Borrado lógico de un registro | Encola la petición. Retorna HTTP 200 si se marcó `0x01`. |
| `GET` | `/api/policies?offset={offset}` | Lectura directa por offset | Operación de solo lectura concurrente directa sobre el storage (no bloquea la cola). |
| `GET` | `/api/status` | Estado del sistema | Retorna JSON con contadores de metadata y tamaño actual de la cola. |
| `GET` | `/api/health` | Healthcheck | Para load balancers y monitoreo en la nube (HTTP 200 `{"status":"UP"}`). |
| `GET` | `/{archivo}` | Archivos estáticos | Sirve `index.html`, `app.js` y `styles.css` del cliente web. |

### Ejemplo de JSON para `POST /api/policies`:
```json
{
  "id": 1001,
  "dni": "45892147",
  "titular": "Alejandro Morales Quispe",
  "tipoSeguro": "Vehicular Todo Riesgo",
  "departamento": "Lima",
  "prima": 1450.50,
  "fechaEmision": "2026-03-15"
}
```

---

## ☁️ 3. Guía de Despliegue en la Nube (RNF-04)

### Despliegue en AWS EC2 / Azure VM / Google Cloud Compute Engine

1. **Requisitos de la Instancia:**
   - SO: Ubuntu 22.04 LTS / Debian 12 / Amazon Linux 2023.
   - JDK: Java 21+ (`sudo apt update && sudo apt install -y openjdk-21-jdk`).
   - Puerto: Habilitar puerto `8080` (o `80`) en el Security Group / Firewall.

2. **Compilación y Empaquetado:**
   ```bash
   # Clonar el repositorio
   git clone https://github.com/GFVDM00/lsdfms-concurrente.git
   cd lsdfms-concurrente
   git checkout feature/modulo-2-server-queue

   # Compilar
   mkdir -p bin
   javac -d bin -sourcepath src/main/java $(find src/main/java -name "*.java")

   # Ejecutar el servidor en background (puerto 8080)
   nohup java -cp bin pe.edu.unmsm.lsdfms.server.ServerApp 8080 > server.log 2>&1 &
   ```

3. **Configuración como Servicio Systemd (Opcional recomendado para producción):**
   Crear `/etc/systemd/system/lsdfms.service`:
   ```ini
   [Unit]
   Description=LSDFMS Native Backend Service
   After=network.target

   [Service]
   User=ubuntu
   WorkingDirectory=/home/ubuntu/lsdfms-concurrente
   ExecStart=/usr/bin/java -cp bin pe.edu.unmsm.lsdfms.server.ServerApp 8080
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
   Habilitar:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now lsdfms
   sudo systemctl status lsdfms
   ```
