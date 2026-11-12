# Seguridad

Esta versión solo lee.

Permitido: conectar Bluetooth, configurar el ELM327, consultar servicios OBD estandarizados de lectura, guardar y exportar la transcripción.

Prohibido, y bloqueado en `ReadOnlyGate` antes de escribir al socket:

- servicio OBD `04` y cualquier comando que empiece por `04`;
- borrar sesiones desde la aplicación;
- cabeceras CAN (`ATSH`), monitor de bus (`ATMA`) y controles del adaptador;
- modo `08` y cualquier comando que no esté en la lista;
- comandos compuestos con `;` o con más de una línea, para que un `ATZ` no pueda ir acompañado de un borrado;
- reprogramar, rutinas UDS, inmovilizador, actuadores de freno o acelerador, y pruebas de conducción.

`ATZ` está permitido porque reinicia el intérprete del adaptador. No borra la memoria de averías de la ECU.

El VIN no se pide hasta que la persona marca la autorización, y solo si `0900` dice que el PID `02` está soportado. La exportación puede sustituir el VIN y la dirección Bluetooth.

No hay permiso de internet. `android:allowBackup` está en falso para no subir la sesión a la copia de seguridad de la cuenta.

La pantalla muestra en todo momento que una conexión no autoriza a circular. El trabajo inicial es con el vehículo estacionado y el contacto en ON.
