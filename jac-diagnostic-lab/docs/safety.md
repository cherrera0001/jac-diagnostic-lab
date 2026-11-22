# Seguridad operativa y control de comandos

La operación normal es de **solo lectura**. Desde `v1.12.0`/`v1.12.1`, la única excepción controlada es **«Borrar y limpiar»** (servicio OBD `04` detrás de `ClearDtcGate`), que exige confirmación, respaldo automático previo en la bóveda privada, ECU respondiendo y vehículo detenido (velocidad 0), seguido de relectura 03/07/0A.

## Permitido

Conectar Bluetooth, configurar el ELM327, consultar servicios OBD estandarizados de lectura (`01`, `02`, `03`, `06`, `07`, `09`, `0A`), ejecutar «Borrar y limpiar» (`04` puntual autorizado por `ClearDtcGate`), registrar sensores, ejecutar pruebas guiadas que solo leen, guardar capturas antes/después, escanear anuncios BLE pasivos en la pestaña **Tags** y exportar PDF/JSON/CSV/TXT/`.jacenc`/GeoJSON/GPX.

## Prohibido (bloqueado antes de escribir al socket)

- servicio OBD `04` fuera de una autorización puntual vigente de `ClearDtcGate` y cualquier forma extendida (`0400`, `04;03`, etc.);
- borrar archivos de la bóveda privada (`filesDir/vault/`);
- cabeceras CAN (`ATSH`), monitor de bus (`ATMA`) y controles del adaptador fuera de lista;
- modo `08` y cualquier comando que no esté en la lista;
- comandos compuestos con `;` o con más de una línea;
- reprogramar, rutinas UDS, inmovilizador, actuadores;
- controles de flujo ISO-TP distintos de los cuatro exactos de la lista.

## Pruebas guiadas (1.8.0)

Ninguna prueba envía un comando distinto de las lecturas permitidas. La app no acelera, no frena ni mueve nada: pide al operador una acción y registra.

- **Estacionarias:** freno de mano, punto muerto, nadie delante del vehículo.
- **Ruta / banco:** con SAFE-001 abierto, solo en banco de rodillos o pista cerrada, con acompañante que opera el teléfono. Nunca en la vía pública. El conductor no mira la pantalla.

`ATZ` está permitido porque reinicia el intérprete del adaptador. **No** borra la memoria de averías de la ECU.

## SAFE-001 — corte de potencia al frenar

Si el conductor reporta corte de potencia/energía al frenar en marcha:

1. **No** probar ese corte en la vía.
2. **No** borrar códigos antes de que el servicio JAC los lea.
3. Generar PDF y llevarlo al taller con escáner de marca.
4. Esta app **no certifica** aptitud para circular.

El banner en la UI y el ciclo **Taller** repiten esa regla.

## Datos sensibles

El VIN se consulta cuando `0900` declara soporte del PID `02`, y la exportación permite anonimizar VIN y dirección Bluetooth o empaquetar cifrado con AES-256-GCM (`.jacenc`). La pestaña **Tags** solicita ubicación en ejecución solo para registrar avistamientos locales y triangular por GPS + RSSI en el teléfono, sin servicios en la nube. No hay permiso de internet. `android:allowBackup` está en falso.

## Qué no afirma la app

- No hay puntuación porcentual de “seguridad del vehículo”.
- `ATI` no prueba un ELM Electronics original.
- `ATRV` no sustituye un multímetro en la batería.
- Un DTC genérico no demuestra un ataque informático.
- El lab adversarial (CAN virtual) no se apunta al T8 real.
