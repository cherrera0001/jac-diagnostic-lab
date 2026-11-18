# Validación en la unidad real — 2026-11-18

JAC T8 2023 diésel del propietario, estacionada, motor en ralentí. Adaptador ELM327 v2.1 BLE (`OBDBLE`, FFF2/FFF1). Teléfono Xiaomi, Android 17. App 1.10.0 → 1.11.0.
VIN y dirección Bluetooth **no se publican** (el VIN completo está en la evidencia local `jac-diagnostic-lab/evidence/captured/`, no versionada). Año modelo según el carácter 10 del VIN (ISO 3779): 2023, coincide con lo declarado.

## Respuestas de la ECU (bytes reales)

| Comando | Respuesta | Lectura |
|---|---|---|
| ATDPN / ATDP | `A6` / `ISO 15765-4 (CAN 11/500)` | CAN 11 bit 500 kbps; ECU motor en `7E8` (coincide con tesis UPS S09 p.28) |
| 0100…0180 | 5 páginas respondidas | 72 PID declarados; 68 leídos, 82 señales |
| 03 | `43 02 05 79 22 99` | **P0579**, **P2299** almacenados |
| 07 | `47 01 05 79` | **P0579** pendiente |
| 0A | `7F 0A 13` | La ECU **rechaza** el servicio de permanentes (no es «sin códigos») |
| 0101 | `41 01 02 0E C9 00` | MIL apagada, 2 DTC, encendido por compresión |
| 020200 | `42 02 00 05 79` | Cuadro congelado guardado por P0579 |
| 0902 | VIN 17 caracteres | Año modelo 2023 |
| **0904** | **`DCM6.2`** | **CALID: familia Delphi DCM6.2** |
| 0906 | `A7BB65AE` | CVN |
| 090A | `ECM-EngineControl…` | Nombre de ECU |
| ATRV | `16.7V` | El adaptador sobreestima: PID 42 de la ECU = 14.43 V |

## Conclusiones

1. **ECU confirmada por lectura: Delphi DCM6.2.** Cierra la parte «proveedor/familia» de G-03 y respalda la mención de v1.8.0. **No** distingue DCM6.2AP de DCM6.2AP-6D: el CALID solo dice «DCM6.2». Para la variante hace falta la etiqueta física. El catálogo `jac-t8-catalog-v1` incorpora «DCM6.2» como identificador de familia (fuente S21) y el perfil pasa de `PARTIAL` a `IDENTIFIED` (familia).
2. **Falla persistente del freno:** P0579 + P2299 almacenados, P0579 vuelve a pendiente. Distancia desde borrado (PID 31) = 311 km, 7 calentamientos: alguien borró los códigos y regresaron.
3. **Cuadro congelado de P0579:** 27 km/h, 1382 rpm, carga 0,39 %, MAF 26,7 g/s, riel 32,9 MPa (rodando sin acelerar). Barométrica 0 kPa, tensión 0 V y pedales 0 % con el motor girando son imposibles: esta ECU rellena con 0 lo que no captura. Esos ceros **no** son mediciones.
4. **Sistemas en ralentí normales:** riel real = comandado (28,9 MPa), turbo sigue lo comandado, EGR error −3 %, DPF Δp 0,23 kPa y 5 % de carga de hollín, alternador 14,4 V, Mode 06: 19 pruebas dentro de límite.
5. **Aviso:** refrigerante 51 °C tras 22 min en ralentí (ambiente 26 °C). Puede ser termostato abierto; confirmar con la prueba «Calentamiento».
6. **Limitación honesta:** el estado del interruptor de freno no es un dato J1979. Con un ELM327 no se lee; la prueba «Freno contra acelerador» (v1.11.0) muestra si la ECU recibe el freno. **Pendiente de ejecutar.**

## Fallos de la app encontrados en el vehículo y corregidos

| Síntoma real | Causa | Corrección |
|---|---|---|
| Tras `020200` sin respuesta, todos los comandos «TIMEOUT 0 ms» para siempre | Android rechazaba la escritura GATT y se registraba como timeout; el ELM quedaba ocupado | 1.10.1: reintento ×4, enlace declarado caído, CR para cancelar el comando pendiente |
| Monitor en bucle infinito con lecturas inválidas | Solo se detenía con excepción | 1.10.1: se detiene tras 12 fallos |
| El clon BLE se cuelga cada pocos minutos | Adaptador | 1.11.0: reconexión automática (hasta 3) |
| Escaneo sobre enlace muerto borraba los datos buenos (`pids=0`) | Se reemplazaba sin verificar | 1.11.0: se conservan los datos anteriores |
| Perfil `PARTIAL` con CALID «DCM6.2» | Catálogo sin identificador de familia | 1.11.0: «DCM6.2» (S21) |
| P0579 duplicado, «3 códigos» | Se listaba por categoría | 1.11.0: un código con sus categorías |
| 0A `7F 0A 13` mostrado como lectura válida | Sin detección de respuesta negativa | 1.11.0: «servicio no admitido» |
| PDF guardado dos veces | Doble ejecución | 1.11.0: bloqueo mientras guarda |
| Tras reinstalar la app el adaptador no se anuncia | La conexión GATT anterior queda tomada | Operativo: desenchufar y enchufar el adaptador |

Evidencia local (no versionada, contiene VIN): `jac-diagnostic-lab/evidence/captured/2026-11-18-v1.10.1/` — PDF + `.sha256` + JSON con cada comando y sus bytes.
