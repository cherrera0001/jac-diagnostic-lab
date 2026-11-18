# SPEC-033 — Informe de taller después de leer todo

**Objetivo (usuario, 2026-11-18):** «que el reporte o informe se genere posterior a recopilar todos los datos, logs y más información, y que el detalle sea suficiente para que el mecánico o el taller puedan determinar el problema y aplicar una solución». También: «ajustar la dimensión de la app».

**Límite honesto.** La app solo puede entregar lo que la ECU responde por OBD-II (J1979) y definiciones públicas (J2012). El manual de taller JAC con valores nominales y procedimientos no es público (`research/gaps.md` G-01). El informe dice de dónde sale cada dato y no prescribe piezas.

### R13 — El informe espera la lectura completa
**Comportamiento:** «Leer todo y generar informe» ejecuta el escaneo completo si falta algo y genera el PDF al terminar. Se considera completo si hay escaneo (todos los PID declarados, monitores, Mode 06), las tres categorías de DTC y la identificación Mode 09. Si se genera igual, el informe dice «INCOMPLETO» y lo que falta.
**Verificado por:** `r13_reportWaitsForTheFullReadAndSaysWhatIsMissing`

### R14 — Detalle suficiente para el taller
**Comportamiento:** El informe trae: vehículo y ECU (VIN y año ISO 3779, CALID/CVN por ECU, perfil con fuentes), resumen (MIL, DTC con categoría, hallazgos de salud, monitores incompletos, Mode 06 fuera de límite), detalle por código (datos relacionados ahora y en el cuadro congelado, pasos de verificación genéricos), cuadro congelado completo (Mode 02, `02PP00`, solo lectura), todas las señales con bytes, monitores, Mode 06, salud y registro de comunicación (comandos por tipo, latencias, fallos). Se guarda PDF + `.sha256` + JSON completo de la sesión.
**Verificado por:** `r14_reportGivesTheWorkshopCodeContextFreezeLiveDataAndChecks`

### R15 — Dimensiones
**Comportamiento:** la cabecera usa el alto real de la barra de estado del teléfono (`statusBarsPadding`) en vez de 36 dp fijos; la barra de pestañas mide 60 dp y el pie de versión queda sobre la barra de navegación del sistema.
**Verificado por:** revisión visual en el teléfono (captura).
