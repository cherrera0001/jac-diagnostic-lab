# Bluetooth

Referencias usadas: la guía de Bluetooth de Android y la de permisos. En Android 12 o superior la aplicación pide `BLUETOOTH_SCAN` y `BLUETOOTH_CONNECT`. `BLUETOOTH_SCAN` va con `neverForLocation`: no se usa el escaneo para ubicación y no se pide el permiso de ubicación en el teléfono API 37. En API 30 o menor el sistema exige ubicación para descubrir dispositivos; ese permiso queda limitado a esas versiones.

No se piden contactos, teléfono ni almacenamiento total. El emparejamiento lo hace el sistema. El PIN no se lee ni se guarda.

## OBDII, clásico

1. Confirme que el adaptador está insertado y que el contacto está en ON.
2. Empareje `OBDII` en Ajustes, Bluetooth.
3. Cierre OBD Auto Doctor u otra aplicación que tenga el socket abierto.
4. En la aplicación, elija `OBDII` y pulse Conectar clásico.
5. El primer intento es `createRfcommSocketToServiceRecord` con el UUID SPP. Hay un segundo intento igual y, si ambos fallan, un respaldo por el canal RFCOMM 1.

Si el teléfono dice que el dispositivo está ocupado, apague y encienda el Bluetooth del teléfono y repita. No borre los códigos del vehículo para “reintentar”.

## OBDBLE

BLE no usa RFCOMM. La aplicación conecta GATT, lista servicios y características, y muestra READ, WRITE, WRITE_NR, NOTIFY e INDICATE.

Solo se puede pulsar “Validar BLE con ATI” después de elegir una característica con escritura y una con notificación. Esas propiedades las informa el teléfono; no hay UUID de ELM327 escritos en el código. Para suscribir la notificación se escribe el descriptor estándar Bluetooth CCCD `00002902-0000-1000-8000-00805f9b34fb`. Eso no es un comando a la ECU.

Si `ATI` no devuelve un texto con `ELM` o una versión `vN.N` y el prompt `>`, la característica queda descartada y no se envía `0100`.

## Si no conecta

| Qué se ve | Qué hacer |
| --- | --- |
| Permiso denegado | Ajustes, aplicaciones, JAC Diagnostic Lab, dispositivos cercanos, permitir. |
| Bluetooth apagado | Encenderlo desde el sistema. |
| Timeout sin `>` | Acercar el teléfono, contacto en ON, reintentar. Probar el otro nombre (`OBDII` u `OBDBLE`). |
| Socket cerrado o ocupado | Cerrar la otra aplicación OBD y reintentar. |
| GATT sin características de escritura y aviso | Ese periférico no ofrece un canal serie reconocible. No se le escribe. |
| `UNABLE TO CONNECT` o `BUS INIT ERROR` | El adaptador respondió, la ECU no. Revisar que el conector esté bien insertado y el contacto en ON. Probar el protocolo 6, CAN 11/500, que es el automático habitual en una pickup moderna. |
| `NO DATA` | No es “cero fallas”. Repetir la consulta. Si persiste, anotar el protocolo de `ATDP`. |
| `CAN ERROR` | Anotar la hora. No indica por sí solo un módulo dañado. |
| GATT código **133** | Fallo de establecimiento BLE (radio/enlace). No es un bug de la UI. Reinsertar el ELM, contacto ON, apagar/encender Bluetooth, cerrar otras apps OBD, reintentar. Si hay `OBDII` clásico emparejado, usar ese camino. |
| Escaneo sin nombre OBDII/OBDBLE | Desde 1.0.1 el autodiag reinyecta los emparejados. Si aun así falla, emparejar de nuevo en Ajustes de Android. |
| Monitor sin valores | Sin sesión ELM no hay PID vivos. Se pueden ver capturas previas o generar PDF desde una sesión guardada. |

En un Xiaomi, desactive la restricción de batería de esta aplicación si el sistema cierra el enlace al apagar la pantalla. La sesión de diagnóstico se hace estacionado; no hace falta conducir.

**Limitación de producto:** sin sensor/lector ELM327 (BLE o clásico) no hay lectura nueva. La APK sirve igual para revisar el flujo, el ciclo de taller y un informe ya guardado.
