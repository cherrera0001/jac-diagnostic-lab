# Seguridad operativa y de solo lectura

Esta versión solo lee.

## Permitido

Conectar Bluetooth, configurar el ELM327, consultar servicios OBD estandarizados de lectura (`01`, `02`, `03`, `06`, `07`, `09`, `0A`), registrar sensores, ejecutar pruebas guiadas que solo leen, guardar capturas antes/después, exportar PDF/JSON/CSV/TXT.

## Prohibido (bloqueado antes de escribir al socket)

- servicio OBD `04` y cualquier comando que empiece por `04`;
- borrar sesiones o DTC desde la aplicación;
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

El VIN no se pide hasta autorización explícita, y solo si `0900` indica soporte del PID `02`. La exportación puede anonimizar VIN y dirección Bluetooth. No hay permiso de internet. `android:allowBackup` está en falso.

## Qué no afirma la app

- No hay puntuación porcentual de “seguridad del vehículo”.
- `ATI` no prueba un ELM Electronics original.
- `ATRV` no sustituye un multímetro en la batería.
- Un DTC genérico no demuestra un ataque informático.
- El lab adversarial (CAN virtual) no se apunta al T8 real.
