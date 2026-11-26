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

## 👑 2. Política de Revisión de Pull Requests (Prohibido el Auto-Merge)

Para garantizar la calidad del código, la integridad de los contratos y la ausencia de regresiones:

1. **Prohibición estricta de Auto-Merge:**
   - **Ningún integrante puede aprobar ni mergear su propio Pull Request.**
   - El autor del PR no cuenta como revisor válido.
2. **Revisión Cruzada y Rol de Integrador:**
   - Los PRs de los integrantes (**Brandon, Anderson, Jerson, Cicilis, Arancivia**) deben ser revisados, aprobados y mergeados por **Alejandro** (Líder de Integración).
   - El PR del módulo de **Alejandro** (Módulo 1: Motor LSDFMS y Metadata) debe ser revisado y aprobado por **otro integrante** (ej. Brandon o Anderson) para certificar que cumple con los contratos acordados.
3. **Validación Automática en GitHub Actions (CI):**
   - Cada PR ejecuta automáticamente un flujo de Integración Continua (`.github/workflows/ci.yml`) que compila todo el proyecto en Java 21+ y corre las pruebas de concurrencia.
   - **El PR no se puede mergear si el check de CI está en rojo (fallido).**

---

## ⚙️ 3. Configuración Obligatoria de Protección en GitHub (Branch Protection)

Las restricciones no vienen activadas por defecto en GitHub; deben configurarse desde los ajustes del repositorio:

1. Ir a **Settings** > **Branches** (o **Rulesets**).
2. Hacer clic en **Add branch protection rule** (o crear una regla sobre `main`).
3. En *Branch name pattern*, colocar: `main`.
4. Activar las siguientes casillas:
   - ✅ **Require a pull request before merging**
   - ✅ **Require approvals:** mínimo `1` revisión aprobada.
   - ✅ **Dismiss stale pull request approvals when new commits are pushed** (invalida aprobaciones si se sube nuevo código).
   - ✅ **Require status checks to pass before merging:** buscar y seleccionar `Build & Concurrency Verification`.
   - ✅ **Do not allow bypassing the above settings** (aplica la regla incluso para administradores del repo).
5. Guardar los cambios (**Save changes**). Con esto, GitHub bloqueará automáticamente cualquier intento de auto-aprobación o merge directo a `main`.

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
