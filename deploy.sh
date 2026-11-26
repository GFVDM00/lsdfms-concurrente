#!/bin/bash
# ==============================================================================
# SCRIPT DE DESPLIEGUE EN LA NUBE (AWS EC2 / GCP / Azure VM)
# Asignado a: Brandon (Modulo 2 - Servidor HTTP y Cloud)
# Requerimiento: RNF-04
# ==============================================================================

PORT=${1:-8080}
PID_FILE="server.pid"
LOG_FILE="server.log"

case "$2" in
  stop)
    if [ -f "$PID_FILE" ]; then
      PID=$(cat "$PID_FILE")
      echo "[Cloud Deploy] Deteniendo servidor LSDFMS (PID: $PID)..."
      kill "$PID" 2>/dev/null || true
      rm -f "$PID_FILE"
      echo "[Cloud Deploy] Servidor detenido."
    else
      echo "[Cloud Deploy] No se encontro archivo $PID_FILE. El servidor no parece estar corriendo."
    fi
    exit 0
    ;;
esac

echo "========================================================="
echo "  LSDFMS: Despliegue en la Nube (AWS / Azure / GCP)      "
echo "  Responsable: Brandon                                   "
echo "========================================================="

# 1. Validar instalacion de Java
if ! command -v java &> /dev/null; then
    echo "[ERROR] Java no esta instalado. Instale OpenJDK 21+ antes de continuar:"
    echo "  sudo apt update && sudo apt install -y openjdk-21-jdk"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
echo "[Cloud Deploy] Version de Java detectada: $JAVA_VERSION"

# 2. Detener instancia anterior si existia
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    echo "[Cloud Deploy] Deteniendo proceso previo PID $OLD_PID..."
    kill "$OLD_PID" 2>/dev/null || true
    sleep 1
    rm -f "$PID_FILE"
fi

# 3. Preparar directorios y compilar
echo "[Cloud Deploy] Compilando codigo Java nativo..."
mkdir -p bin data src/main/resources/public

# Buscar todos los archivos Java y compilar
find src/main/java -name "*.java" > sources.txt
javac -d bin -sourcepath src/main/java @sources.txt
rm -f sources.txt

if [ $? -ne 0 ]; then
    echo "[ERROR] Fallo la compilacion. Verifique errores de sintaxis."
    exit 1
fi
echo "[Cloud Deploy] Compilacion completada con exito."

# 4. Iniciar servidor en segundo plano
echo "[Cloud Deploy] Iniciando NativeHttpServer en el puerto $PORT..."
nohup java -cp bin pe.edu.unmsm.lsdfms.server.ServerApp "$PORT" > "$LOG_FILE" 2>&1 &
SERVER_PID=$!
echo $SERVER_PID > "$PID_FILE"
echo "[Cloud Deploy] Proceso lanzado en segundo plano con PID: $SERVER_PID"

# 5. Esperar confirmacion de salud (Health Check)
echo "[Cloud Deploy] Verificando estado en http://localhost:$PORT/api/health..."
sleep 2

HEALTH_CHECK=$(curl -s "http://localhost:$PORT/api/health" 2>/dev/null || true)
if [[ "$HEALTH_CHECK" =~ "UP" ]]; then
    echo "========================================================="
    echo "  ¡DESPLIEGUE EXITOSO EN LA NUBE!                        "
    echo "  Servidor activo en: http://localhost:$PORT/            "
    echo "  Logs en vivo:       tail -f $LOG_FILE                  "
    echo "========================================================="
else
    echo "[ALERTA] El servidor inicio con PID $SERVER_PID, pero el healthcheck no respondio aun."
    echo "Revise el archivo $LOG_FILE para mas detalles."
fi
