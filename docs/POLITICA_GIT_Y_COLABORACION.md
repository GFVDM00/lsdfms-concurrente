# Política de Ramas, Colaboración y Flujo Git

Para asegurar el desarrollo concurrente ordenado entre los 6 integrantes y evitar colisiones de código o conflictos de merge, se establece el siguiente estándar de trabajo en el repositorio.

---

## 🌿 1. Estructura de Ramas (Feature Branching)

- **`main` (Rama Principal de Producción):**
  - Contiene exclusivamente código estable, verificado y compilable.
  - **Queda terminantemente prohibido hacer `git push` directo sobre `main`.**
  - Todas las incorporaciones a `main` se realizan mediante **Pull Requests (PR)** aprobados e integrados por **Alejandro**.

- **Ramas de Trabajo por Integrante:**
  Cada integrante debe crear y trabajar únicamente sobre su propia rama derivada de `main`:

| Integrante | Rol / Módulo Asignado | Rama Oficial de Trabajo |
|---|---|---|
| **Alejandro** | Motor LSDFMS, Metadata e Integración | `feature/modulo-1-storage-metadata` |
| **Brandon** | Servidor HTTP, Cola Sincronizada y Cloud | `feature/modulo-2-server-queue` |
| **Anderson** | Operación Paralela `PACK()` | `feature/modulo-3-pack-parallel` |
| **Jerson** | Operación Paralela `INDEX()` y Búsqueda $O(\log n)$ | `feature/modulo-4-index-parallel` |
| **Cicilis** | Cliente Web Minimalista y UI State | `feature/modulo-5-web-client` |
| **Arancivia** | Generador Masivo, Benchmarking y QA | `feature/modulo-6-generator-qa` |

---

## 👑 2. Responsabilidad de Alejandro como Líder de Integración

De acuerdo con el documento de requerimientos del curso:
> *"Alejandro — Integración General, Motor de Almacenamiento LSDFMS y Metadata: Lidera la integración técnica de todos los módulos del sistema en Java, definiendo los contratos e interfaces internas entre el almacenamiento, los procesos paralelos y el servidor."*

Por lo tanto:
1. **Alejandro es el único encargado oficial de realizar los MERGE hacia `main`.**
2. Cada integrante, al culminar su módulo o hito, publicará su rama en GitHub y solicitará la revisión de Alejandro (vía Pull Request).
3. Alejandro compilará y verificará que no se rompan los contratos de interfaces antes de integrar los cambios a `main`.

---

## ⚡ 3. ¿Existe dependencia para iniciar a programar? (Desacoplamiento)

**NO hay bloqueo para iniciar.**  
El sistema fue diseñado con el principio de inversión de dependencias:
- Los módulos no dependen de la implementación concreta de `RandomAccessFile`, sino del contrato de la interfaz **`StorageEngine`**.
- Brandon (Servidor y Cola Sincronizada) puede implementar y probar toda la concurrencia de `ThreadSafeRequestQueue` (`ReentrantLock`, `Condition`, `await`/`signalAll`) y los endpoints HTTP utilizando un motor simulado en memoria o llamando a los métodos abstractos de `StorageEngine`.
- Anderson (`PACK`) y Jerson (`INDEX`) pueden diseñar sus algoritmos de particionamiento y ordenamiento dicotómico en paralelo sin esperar a que la cola o la web estén terminadas.
- Cicilis puede diseñar los mocks del cliente web y Arancivia puede estructurar los generadores de datos.

---

## 🚀 4. Comandos de Inicio para Cada Integrante

### Para iniciar a trabajar en tu rama:
```powershell
# 1. Asegurarse de tener lo ultimo de main
git checkout main
git pull origin main

# 2. Crear y cambiarte a tu rama respectiva (ejemplo para Brandon)
git checkout -b feature/modulo-2-server-queue

# 3. Desarrollar, hacer commits y subir a GitHub
git add .
git commit -m "feat(server): implementacion inicial de ThreadSafeRequestQueue con ReentrantLock"
git push -u origin feature/modulo-2-server-queue
```
