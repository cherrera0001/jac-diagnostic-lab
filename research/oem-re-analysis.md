# OSINT / REA — qué se analizó, con qué acceso y qué no

Revisión 2026-11-18. Regla: solo material obtenido legítimamente; sin evadir autenticación, captcha, pinning ni acceso a backends privados; sin copiar ni redistribuir código propietario.

## 1. Análisis estático realizado

| Objeto | Acceso | Método | Resultado | Hash |
|---|---|---|---|---|
| Frontend público JAC Global (Nuxt) | Público: los mismos bundles que descarga cualquier navegador | Lectura de `380638d.js` (componente Download) y `6a87fb7.js` (config axios) | `getList()` llama `GET /api/car/carInformationList.html` con `baseURL https://jacen.jac.com.cn/index.php`; campos `obd_path`, `repair_path`, `maintenance_path`, etc. Llevó a S25 | S30 `8e5582fe…`, S31 `0afdfaca…` |
| Respuesta API S25 | Público, sin cookies ni token | GET único con `Accept: application/json` y `Referer` de la página | 6 modelos; sin T8 | `b01a7a99…` |
| `LE796-OBDinformation.xlsx` (S27) | Enlace público de S25 | Lectura de `sharedStrings.xml` | Esquema «Diagnostic Specification 4-1 DTC List»: DTC, bytes H/M/L, variante ECU, condición de activación/recuperación, acción, causa, corrección | `1473d7d5…` |
| `Sunray Pro-EU OBD information` (S26) | Enlace público de S25 | `pdftotext` | Variante EV (BMS/IPU), 725 págs. Solo formato | `17fda86c…` |
| Manual T8 Chile (S02) | Público | OCR (no tenía texto) | EOBD, EPC, DPF opcional, regeneración forzada | `6f7b20c3…` |

## 2. Apps oficiales — inventario

| App | Publicador | Package | Tienda | Últ. act. | Conexión | Vehículos declarados | Diagnóstico | Fuente |
|---|---|---|---|---|---|---|---|---|
| JAC (JAC MOTORS / Lifestyle) | JAC ITALY SRL | `com.jac.global` | Google Play | 14-abr-2025 | Cloud / vehículo conectado | EV/conectados (carga remota, A/C, ventanas) | No (control remoto) | S13 |
| JAC MX | JAC México | (no obtenido) | — | — | Cloud + último contacto con el vehículo | Lista cerrada México, sin T8 | «Lectura básica» y vida útil | S14, S15 |
| JDS | JAC Customer Service | (APK entregado a distribuidores) | No pública | — | VCI Bluetooth + servidor | S4, K7 (material sin fecha) | Sí, taller | S16 |

**Separación:** JDS (taller) ≠ apps de consumidor/EV. Ninguna declara soporte T8 2023 Chile.

## 3. Análisis no realizado y por qué

| Análisis pedido | Estado | Motivo | Vía legítima |
|---|---|---|---|
| Estático de APK `com.jac.global` | NO REALIZADO | No hay APK obtenido de fuente legítima en este equipo; los espejos de APK no son fuente legítima. La app además no es de diagnóstico | Propietario: instalar desde Play en su teléfono y extraer con `adb shell pm path com.jac.global` + `adb pull` para análisis local con `apkanalyzer`/JADX, sin redistribuir |
| Estático/dinámico de JDS | NO REALIZADO | APK y cuenta solo para distribuidores (S16 diap. 3, 10) | Solicitud a JAC Chile (gaps G-01) |
| Dinámico con cuenta propia | NO REALIZADO | No hay cuenta ni vehículo conectado compatible | — |
| Captura de tráfico de backends | NO REALIZADO | Requiere cuenta propia y autorización; sin ella sería acceso no permitido | — |

## 4. Modelo funcional extraído

Ver `app-capability-matrix.csv`. Componentes de referencia y equivalente propio:

- **Vehicle Discovery / VIN-ECU Fingerprinting** → `profile/VehicleProfiles.kt` (`VehicleFingerprint`, `ProfileResolver`). IMPLEMENTADO 1.9.0.
- **Module/ECU Inventory** → solo ECU motor (7E8) por OBD genérico; otras ECUs NO VERIFICADO.
- **Live Data Catalog** → `PidCatalog` + `jac-t8-profiles/catalog-v1.json` con procedencia.
- **Freeze Frame & DTC** → existente (Modes 02/03/07/0A).
- **Diagnostic Dataset Update** → catálogo versionado en el repo con prueba de deriva (R9); paquetes firmados descargables: pendiente.
- **Offline Data Cache** → la app es offline por diseño (JDS no lo es).
- **Service Report** → PDF/JSON/CSV/TXT existentes; JSON incluye ahora `extended.profile`.
