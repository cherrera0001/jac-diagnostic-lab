# SPEC-032 — La versión en uso está siempre a la vista

**Objetivo (usuario, 2026-11-18):** «deja visiblemente la versión de la app, en un lugar o abajo central (footer) pero se debe considerar informar qué versión se está usando».

**Origen del defecto.** El pie del PDF tenía escrito a mano «JAC Diagnostic Lab 1.0.0» (`ui/Ui.kt`). Por eso un PDF de sesión decía 1.0.0 aunque la app instalada fuera otra (discrepancia citada en el prompt maestro v2, Fase 0).

### R12 — Versión instalada en pantalla y en cada archivo
**Estado:** verificado
**Comportamiento:** La versión se lee del paquete instalado (`versionName` + `versionCode`, p. ej. «v1.9.0 (22)»), no de un texto fijo. Se muestra centrada en el pie, bajo las pestañas, en todas las pantallas. El pie del PDF, la primera línea del TXT y el campo `appVersion` del JSON llevan la misma cadena.
**Criterio de aceptación:**
- [x] `AppVersion.label("1.9.0", 22)` = «v1.9.0 (22)»; sin datos → «v?».
- [x] JSON contiene `"appVersion": "v1.9.0 (22)"`; TXT empieza con «JAC Diagnostic Lab v1.9.0 (22)».
- [x] El código del PDF no contiene la versión fija «1.0.0».
- [x] Pie central visible en la barra inferior (`ui/AppShell.kt`, `VersionFooter`).
**Verificado por:** `r12_versionIsReportedOnScreenPdfJsonAndTxt` y revisión visual del pie.
