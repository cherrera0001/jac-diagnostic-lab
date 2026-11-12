# Protocolo ELM327 y OBD-II

La referencia del adaptador es el datasheet ELM327 de ELM Electronics. La referencia de los servicios es SAE J1979. Esta aplicación no incluye el catálogo de texto SAE J2012-DA.

## Prompt y errores

El adaptador termina una respuesta con `>`. Hasta que llega ese carácter, la respuesta se considera parcial y no se decodifica. Se distinguen:

- comando AT reconocido (`OK` o el texto de `ATI` / `ATZ`);
- `?`;
- `NO DATA`;
- `UNABLE TO CONNECT`;
- `BUS INIT: ERROR`, `BUS INIT: ...ERROR` y `BUS INIT... ERROR`;
- `CAN ERROR`;
- `STOPPED`;
- timeout sin bytes;
- respuesta parcial;
- hexadecimal malformado;
- bytes que no corresponden al servicio pedido (ECU no identificada para esa consulta).

`SEARCHING...` se ignora si después llega un resultado. `NO DATA` no se convierte en una lista vacía de códigos. Una lista vacía confirmada solo sale de una respuesta positiva, por ejemplo `43 00`.

## Encabezados, espacios y eco

Con `ATE1`, la primera línea que repite el comando se descarta. Con `ATH1`, un encabezado de 11 bits (`7E8` a `7EF`) se separa del cuerpo. El byte de longitud ISO-TP de una trama simple (`0N`) se retira cuando el largo coincide y el byte siguiente es un servicio `4x` o un negativo `7F`. `ATS0` junta los hexadecimales; el parser acepta las dos formas.

Varias ECU se agrupan por encabezado. Una trama partida `10` / `21` se reensambla y no se lee el consecutivo como un código nuevo.

## Códigos

Cada código son dos bytes. Los dos bits altos eligen P, C, B o U. El segundo carácter sale de los dos bits siguientes. Si ese carácter es 0 o 2, se marca como genérico SAE J2012. Si es 1, como específico del fabricante. Si es 3, se advierte que algunos rangos de J2012-DA se reservan como genéricos y que esta aplicación no resuelve esa excepción. El relleno `00 00` no se muestra como `P0000`.

En CAN, cuando hay encabezado `7E8`–`7EF` o el protocolo es 6, 7, 8 o 9, el byte que sigue al servicio se trata como cantidad de códigos. En los protocolos 1 a 5 se emparejan los bytes, que es la forma del ejemplo clásico del datasheet (`43 01 33 00 00 00 00` = P0133). Si las dos lecturas no encajan, no se inventa un código.

## PID

Las fórmulas usadas son las de SAE J1979 para un conjunto corto: carga, temperaturas, ajustes de combustible, presión del colector, RPM, velocidad, avance, flujo de aire, posición de acelerador, tiempos, tensión del módulo y nivel de combustible. Un PID fuera de esa lista muestra los bytes y dice que la fórmula no está incluida. Un valor fuera de rango se muestra y se marca; no se reemplaza por cero.

`ATRV` se etiqueta siempre como estimación del adaptador.

## Comandos permitidos

`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATI`, `ATDP`, `ATDPN`, `ATRV`, `ATSP0`–`ATSP9`, modo `01` con un PID, modo `02` con PID y cuadro, `03`, `07`, `0A`, `0900` y `0902`.

Cualquier otra cosa se rechaza antes de tocar el socket. En particular el servicio `04`, un comando compuesto con `;`, `ATSH`, `ATMA` y el modo `08`.
