# Plan de diagnóstico de la JAC T8

Vehículo: JAC T8. Año, motor y versión de ECU siguen sin identificarse por una lectura. El contexto de esta salida es: camioneta encendida, estacionada, ELM327 Mini Bluetooth en el puerto OBD-II.

Síntomas informados por el conductor, no medidos por la aplicación:

1. Se ilumina el indicador EPC.
2. Aparece una advertencia de falla de motor.
3. A veces, al pisar el freno, baja la potencia.
4. Al apagar y volver a encender, el comportamiento puede desaparecer.
5. Puede volver al usar el freno.

El EPC no identifica un componente. Tampoco es lo mismo que el MIL del PID `0101`.

## Cómo se evalúa

No hay un porcentaje de confianza. Un hallazgo confirmado es un byte leído: un código, el bit del MIL, la respuesta de `0100` o el número de `ATRV`. Una hipótesis queda en datos insuficientes, evidencia a favor o evidencia en contra. El relato del conductor no condena una pieza.

| Id | Hipótesis | Qué puede ver esta aplicación | Qué no puede cerrar |
| --- | --- | --- | --- |
| H1 | Señales del interruptor de freno | Solo el relato de pérdida de potencia al frenar | No hay PID genérico del interruptor ni de la señal redundante. No se hace una prueba de freno. |
| H2 | Freno y acelerador electrónico | Si el PID de acelerador está soportado, su valor con el vehículo quieto | No cruza ese valor con el pedal de freno. |
| H3 | Limitación de potencia de la ECU | MIL del PID `0101` y códigos almacenados | No lee el modo de limitación de par ni el testigo EPC. |
| H4 | Alimentación o tierras | `ATRV`, marcado como estimación del adaptador | No mide batería ni tierras. Con el motor en marcha, menos de 13 V llama la atención; no dice qué pieza cambiar. |
| H5 | Comunicación entre módulos | `CAN ERROR`, `UNABLE TO CONNECT`, `BUS INIT ERROR` y códigos U | No interroga ABS, BCM ni transmisión. |
| H6 | Otras fallas indicadas por DTC | Los códigos presentes y su clasificación genérico o fabricante | Sin J2012-DA no hay texto de falla. `NO DATA` no es cero códigos. |

## Orden de trabajo, estacionado

1. Contacto en ON, freno de estacionamiento aplicado, adaptador insertado.
2. Identificar el adaptador con `ATI` y el protocolo con `ATDP` / `ATDPN`.
3. Leer `0100`. Si no hay respuesta de la ECU, el informe es de comunicación, no de “sin fallas”.
4. Leer `03`, `07` y `0A` antes de cualquier otra cosa. No borrar la memoria.
5. Si el mapa lo permite, leer RPM, refrigerante, carga, velocidad, acelerador y colector. La velocidad debería ser cero si el vehículo sigue quieto.
6. `ATRV` se anota aparte de cualquier medición con multímetro.
7. Lo que pida un escáner JAC queda escrito como verificación de taller: interruptor de freno, señal redundante, limitación de par y redes que el OBD genérico no muestra.

No se recomienda sustituir el interruptor, el acelerador, la ECU ni el alternador solo por estos síntomas.
