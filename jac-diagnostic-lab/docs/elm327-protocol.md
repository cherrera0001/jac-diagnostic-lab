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

Varias ECU se agrupan por encabezado. Una trama partida `10` / `21` se reensambla y no se lee el consecutivo como un código nuevo. Si llegan menos bytes de los que anuncia la primera trama, el resultado se marca `truncated`.

## ISO-TP y el clon v2.1

El adaptador probado entrega como máximo 4 tramas consecutivas (`21`–`24`) y nunca la `25`. Cuando eso pasa en Mode 06, la app aplica una sola vez el control de flujo estándar (`ATFCSH7E0`, `ATFCSD300000`, `ATFCSM1`) y repite la lectura. En la unidad probada no cambia el resultado: el corte es del clon. Lo truncado se muestra como incompleto, nunca como dato.

## Códigos

Cada código son dos bytes. Los dos bits altos eligen P, C, B o U. El segundo carácter sale de los dos bits siguientes. Si ese carácter es 0 o 2, se marca como genérico SAE J2012. Si es 1, como específico del fabricante. Si es 3, se advierte que algunos rangos de J2012-DA se reservan como genéricos y que esta aplicación no resuelve esa excepción. El relleno `00 00` no se muestra como `P0000`.

En CAN, cuando hay encabezado `7E8`–`7EF` o el protocolo es 6, 7, 8 o 9, el byte que sigue al servicio se trata como cantidad de códigos. En los protocolos 1 a 5 se emparejan los bytes, que es la forma del ejemplo clásico del datasheet (`43 01 33 00 00 00 00` = P0133). Si las dos lecturas no encajan, no se inventa un código.

## PID

Las fórmulas son las de SAE J1979. Desde 1.8.0 `PidCatalog` cubre los PID `0x01`–`0x8E` de uso habitual, incluidos los de varias señales de diésel: riel comandado/real (`0x6D`), turbo (`0x70`), EGR (`0x69`), DPF (`0x7A`/`0x7B`), EGT (`0x78`/`0x79`), NOx (`0x83`) y regeneración (`0x8B`). Cada señal respeta su bit de soporte. Los centinelas (lambda todo ceros, `0x8E` = `FF`) se tratan como sin dato. Un PID sin fórmula muestra los bytes. Con multi-PID la respuesta se separa por el tamaño conocido de cada PID. Un valor fuera de rango se muestra y se marca; no se reemplaza por cero.

`ATRV` se etiqueta siempre como estimación del adaptador.

## Comandos permitidos

`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATI`, `ATDP`, `ATDPN`, `ATRV`, `ATSP0`–`ATSP9`, modo `01` con 1 a 6 PID, modo `02` con PID y cuadro, `03`, `07`, `0A`, `06xx`, `09xx` y exactamente `ATFCSH7E0`, `ATFCSD300000`, `ATFCSM1`, `ATFCSM0`.

Cualquier otra cosa se rechaza antes de tocar el socket. En particular el servicio `04`, cualquier otro `ATFC…`, un comando compuesto con `;`, `ATSH`, `ATMA`, el modo `08` y los servicios UDS.
