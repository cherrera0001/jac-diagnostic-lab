# Safety — niveles A / B / C

## Nivel A (vehículo real)

Permitido: descubrimiento BT, emparejamiento autorizado, AT/OBD de solo lectura, DTC, evidencias.
Prohibido: servicio 04, actuadores, reprogramación, barrido CAN, mensajes arbitrarios, pruebas en circulación.
Condición: estacionado, inmovilizado, contacto ON.

## Nivel B (adaptador aislado)

ELM327 fuera del vehículo, con alimentación protegida y/o emulador.
Aquí: autenticación, reconexión, negativos de comando, malformados.
Nunca contra la camioneta conectada.

## Nivel C (lab)

Simuladores bajo `lab/`. Escenarios adversariales solo aquí.
Aislamiento: sin transporte Bluetooth/OBD real enlazado al código de simulador.
