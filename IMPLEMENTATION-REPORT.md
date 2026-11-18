# IMPLEMENTATION-REPORT — Prompt maestro v2 (2026-11-18)

Base: rama `cursor/jac-diagnostic-lab`, HEAD `e44be61` (v1.8.0, `versionCode` 21). Resultado: v1.9.0 (`versionCode` 22). Publicado como tag `v1.9.0` y GitHub Release con APK y `.sha256`.

## Fase 0 — Baseline real

| Paso | Resultado |
|---|---|
| `git status` | 86 archivos «modificados» = solo fin de línea CRLF (`git diff --ignore-cr-at-eol` vacío). No se tocaron. |
| Versión | Gradle 1.8.0 / 21 = README = tag `v1.8.0`. El «1.0.0» del PDF de sesión venía de un texto fijo en el pie del PDF (`ui/Ui.kt`), corregido en SPEC-032. El «1.2.0» era una inspección de un checkout anterior. |
| Toolchain | Gradle 9.6.0 vía `gradlew.bat` (JDK 21 Microsoft, SDK Windows) desde WSL. JDK 17 de WSL no se usó. |
| `:app:testDebugUnitTest` | PASS — 90 pruebas, 0 fallos (baseline) |
| `:app:lintDebug` | PASS (BUILD SUCCESSFUL) |
| `:app:assembleDebug` | PASS |
| Specs desactualizadas | 022/023/024/027 tenían criterios sin marcar con pruebas ya existentes y en verde → marcados con el nombre de la prueba. |

## Fases 1–3 — Fuentes, OSINT/REA, APIs

- **37 fuentes** en `research/source-manifest.json` (fecha UTC, HTTP, bytes, SHA-256, nivel, alcance, licencia). 29 descargadas; 1 leída sin archivar (JDS, captcha a curl); 7 fallidas, documentadas sin evadir protecciones.
- **API OEM:** `GET https://jacen.jac.com.cn/index.php/api/car/carInformationList.html`, descubierta por análisis estático del frontend público. Es real y pública, pero cubre 6 modelos y **no la T8** → `NOT_T8_COMPATIBLE`. Telemática de consumidor (com.jac.global, JAC MX): no es diagnóstico y no cubre la T8. JDS: requiere cuenta de distribuidor y VCI JACT0074. **Conclusión:** no hay API oficial utilizable para esta T8; la estrategia es OBD local offline + catálogo documental versionado (`research/api-discovery.md`).
- **ECU candidatas** (ninguna confirmada en la unidad): Delphi DCM6.2AP (S18, S11, S32, S33, S36 — incluye OEM Chile 1026400FD020B-007), Bosch EDC17C81 (S18), Bosch MD1CS089 solo T8 PRO (S32), Delphi MT80 gasolina (S32), familia P/N 1026400FD020 (S21, S34, S35), DCM6.2AP-6D documentada solo para SsangYong (S22).
- **Tesis UPS 2024:** CAN 500k 7E0/7E8; un perfil de norma equivocado produjo IAT/MAF/ECT falsos; errata MAF `/32` → J1979 `/100`.
- **Manual oficial T8 Chile:** sin capa de texto; OCR (tesseract 5.3.4 spa): EOBD, EPC, DPF opcional, regeneración forzada; sin PIDs.

## Fase 4+ — Código (SPEC-031, ADR-002)

| Cambio | Ruta | Prueba |
|---|---|---|
| Huella + año VIN ISO 3779 | `app/src/main/java/app/jac/diagnosticlab/profile/VehicleProfiles.kt:117` (`VinYear`), `VehicleFingerprint.from` | `r5_vinYearMatchesOrContradictsTheDeclaredYear` |
| Resolvedor sin mezclar modelos (gana el identificador más largo; `SUPPLIER_ONLY`) | `profile/VehicleProfiles.kt:159` | `r6_ecuProfileResolvesOnlyFromADocumentedIdentifier` |
| Puerta de señales OEM | `profile/VehicleProfiles.kt:210` | `r7_oemSignalNeverActivatesWithoutSourceEncodingAndMatchingEcu` |
| Cobertura frente a la ECU | `profile/VehicleProfiles.kt:225` | `r8_coverageComparesProfileWithWhatTheEcuReported` |
| Catálogo v1 + JSON versionado | `profile/VehicleProfiles.kt:277`, `jac-diagnostic-lab/jac-t8-profiles/catalog-v1.json` | `r9_versionedCatalogFileMatchesTheAppCatalog` (comprobado que falla si el JSON se altera) |
| Mapa Mode 01 por páginas | `obd/CapabilityDiscovery.kt:185`, uso en `LabController.kt:1443` | `r10_supportPagesFollowTheNextPageBitOnly` |
| Identificación Mode 09 por ECU (raw, hora, latencia; VIN fuera del registro) | `profile/EcuIdentification.kt:28-69`, `LabController.kt:225` (`readMode09`), `:245` (`identifyEcu`) | `r11_mode09IdentificationIsReadOnlyPerEcuAndKeepsVinOut` |
| VIN sin casilla de autorización (decisión del propietario) | `LabController.kt` (`authorizeVin=true`, `readVin`), `ui/Ui.kt` (Código) | suite existente en verde |
| UI «Perfil del vehículo» | `ui/Ui.kt:817` | — (visual) |
| Exportación JSON/TXT | `LabController.kt:1302` (`profileMap`), `extended.ecuIdentification` | — |
| Versión visible: pie central + PDF/JSON/TXT (SPEC-032 R12); corrige «1.0.0» fijo del PDF | `AppVersion.kt`, `ui/AppShell.kt` (`VersionFooter`), `ui/Ui.kt` (`renderReadingPdf`), `evidence/Export.kt` | `r12_versionIsReportedOnScreenPdfJsonAndTxt` |
| CI: lint + APK + SHA-256 como artefacto | `.github/workflows/jac-diagnostic-lab-ci.yml` | se verificará en el próximo push |

## Resultado de build

| Tarea | Resultado |
|---|---|
| `:app:testDebugUnitTest` | PASS — **98 pruebas, 0 fallos, 0 omitidas** (90 previas + 7 de `VehicleProfileTest` + 1 de `AppVersionTest`) |
| `:app:lintDebug` | PASS — 8 avisos `warning`, ningún error (UseKtx ×4, UnusedAttribute, AndroidGradlePluginVersion, DataExtractionRules, ObsoleteSdkInt) |
| `:app:assembleDebug` | PASS (`--rerun-tasks`, 3 min 41 s) |
| APK | `jac-diagnostic-lab/dist/jac-diagnostic-lab-1.9.0-debug.apk`, 12 756 939 bytes, SHA-256 `f050d6985ab08d30baf5aa8678f2ecd57cd20ac89a2cc15c72a1a3f328863865` (`sha256sum -c` OK) |
| Instalación en teléfono | `adb install -r` → Success; `dumpsys package`: versionName 1.9.0, versionCode 22. pie central «JAC Diagnostic Lab v1.9.0 (22) · solo lectura» verificado en pantalla (captura + volcado uiautomator) |
| Hardware vehículo | **No ejecutado** (SKIP) |

## v1.10.0 — Informe de taller (SPEC-033)

| Cambio | Ruta | Prueba |
|---|---|---|
| Informe completo, espera lectura total, detalle por código | `jac-diagnostic-lab/app/src/main/java/app/jac/diagnosticlab/report/WorkshopReport.kt` | `r13_reportWaitsForTheFullReadAndSaysWhatIsMissing`, `r14_reportGivesTheWorkshopCodeContextFreezeLiveDataAndChecks` |
| Cuadro congelado Mode 02 (`02PP00`) | `obd/FreezeFrame.kt`, `LabController.kt` (`fullScanInternal`, `workshopInput`) | r14 (decodificación + política de comandos) |
| Tarjeta «Informe para el taller» (PDF + .sha256 + JSON) | `ui/Ui.kt` (`WorkshopReportCard`) | revisión en teléfono (captura) |
| Dimensiones: `statusBarsPadding`, pestañas 60 dp | `ui/AppShell.kt` | revisión en teléfono (captura) |

Build: 100 pruebas, 0 fallos; lint 8 avisos, 0 errores; APK `jac-diagnostic-lab-1.10.0-debug.apk` 12817802 bytes, SHA-256 `5c60b0445a231e40da36e263fad14448688d22fd65fece45da326ac40b6f81ed`; `adb install -r` Success.

## v1.10.1–1.11.0 — Validación en la unidad real (2026-11-18)

Detalle y bytes: `research/validation/2026-11-18-unidad-real.md`. ECU **Delphi DCM6.2** confirmada (0904), P0579 alm.+pend., P2299 alm., 0A rechazado, códigos borrados hace 311 km. Fallos de la app encontrados en el vehículo y corregidos: escritura GATT rechazada tomada como timeout (1.10.1), monitor sin fin (1.10.1), escaneo vacío que borraba datos, perfil `PARTIAL` con CALID real, duplicados (1.11.0).

| Cambio | Ruta | Prueba |
|---|---|---|
| Documento estructurado + diagnóstico principal | `report/WorkshopReport.kt` (`buildDoc`) | `r13`, `r14`, `r16_realSessionIssuesAreHandled` |
| PDF profesional (2 pasadas, página X de Y) | `report/WorkshopPdf.kt` | PDF real revisado (6 páginas) |
| Familia DCM6.2 en catálogo | `profile/VehicleProfiles.kt`, `jac-t8-profiles/catalog-v1.json` | `r6` (CALID real «DCM6.2») |
| Prueba freno contra acelerador | `dynamic/GuidedTests.kt` (`brake`) | pendiente en unidad |
| Detalle tocable en Estado | `ui/AppShell.kt` (`DtcDetail`, hallazgos) | revisión en teléfono |
| GATT rechazado → enlace caído, CR de cancelación | `bt/Bluetooth.kt` | sesión real |
| Monitor 12 fallos + reconexión automática; escaneo vacío no borra | `LabController.kt` | sesión real |

Build 1.11.0: 101 pruebas, 0 fallos; APK 12884904 bytes, SHA-256 `623c0faf089e6dec9ecc0f3e31a155709367e69ccd1ca9195f18363ce4d64d7a`.

## Qué no fue posible verificar

- **Nada se probó en hardware en esta iteración.** Todas las pruebas son replays JVM con fixtures sintéticos etiquetados.
- La ECU de la unidad (G-03). La mención «Delphi DCM6.2» del CHANGELOG v1.8.0 no tiene respaldo Mode 09 archivado.
- APK de com.jac.global y de JDS: no obtenidos de fuente legítima; no se analizaron.
- Texto J2012 de P0579/P2299 (norma de pago).

## Próxima validación (en este orden, vehículo estacionado)

1. Instalar `dist/jac-diagnostic-lab-1.9.0-debug.apk` y comprobar `sha256sum -c`.
2. Contacto ON o motor en ralentí, estacionado. Capacidad → «Leer mapa PID» → «Identificar ECU (Mode 09)».
3. Leer en pantalla el estado del perfil y los CALID por ECU. Si queda `PARTIAL`, escribir el texto de la etiqueta de la ECU.
4. Exportar JSON: `extended.ecuIdentification` + `extended.profile` cierran G-03.

## Reproducir

```bash
cd jac-diagnostic-lab
cmd.exe /c "gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain"
# regenerar el catálogo versionado tras cambiar VehicleProfiles.kt:
cmd.exe /c "set JAC_WRITE_CATALOG=1&& gradlew.bat :app:testDebugUnitTest --tests app.jac.diagnosticlab.VehicleProfileTest"
python3 -I ../research/tools/build_manifest.py
```
