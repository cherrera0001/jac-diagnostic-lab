# ADR-001 — El archivo se arma al pulsar, no al leer

**Estado:** aceptado
**Fecha:** 2026-11-11
**Decide:** el uso en el teléfono. El informe de la unidad no sale del equipo.
**Consultados:** la spec SPEC-001, requisitos R1, R2 y R3
**Relacionados:** R1, R2, R3

## Contexto

La lectura OBD ya queda en memoria y, si tiene contenido, en el almacenamiento privado. El PDF se había puesto en el paso 7 de Hoy. En Copia el usuario ve CSV, JSON y TXT. El selector de Android crea el archivo al confirmar el nombre, antes de que la app escriba. Una sesión sin respuestas igual producía un archivo.

No se sabe todavía cuántos archivos vacíos quedaron de sesiones anteriores. Esta decisión no los borra.

## Decisión

Los cuatro formatos se piden desde Copia. El contenido se valida con una función pura antes de escribir. Si no hay contenido, no se escribe y se borra el archivo que el selector haya creado. El PDF de una sesión en pantalla vacía puede salir de la última lectura guardada que sí tenga contenido.

## Alternativas consideradas

### Armar el PDF al terminar la lectura automática
- **Qué era:** escribir el PDF en Descargas en cuanto la computadora contesta.
- **Por qué se descartó:** crea archivos que nadie pidió, y una lectura fallida deja un archivo vacío.
- **Volvería a ser correcta si:** el usuario pide un archivo único, siempre, al terminar, y acepta que una lectura fallida también deje constancia.

### Dejar el PDF solo en el paso 7 de Hoy
- **Qué era:** el comportamiento de la versión 0.5.0.
- **Por qué se descartó:** quien busca los formatos mira Copia y no encuentra el PDF.
- **Volvería a ser correcta si:** Copia desaparece y Hoy es la única salida.

### No hacer nada
- **Qué era:** explicar en el chat dónde está el paso 7.
- **Por qué se descartó:** el archivo vacío sigue creándose y la pantalla sigue mostrando tres formatos.
- **Volvería a ser correcta si:** no hubiera archivos vacíos y el PDF ya estuviera en Copia.

## Consecuencias

**Positivas.**
- El momento queda dicho en la pantalla: leer es el paso 5, exportar es el botón.
- La misma función decide en la prueba, en Copia y en la copia automática.

**Negativas y costos aceptados.**
- Una sesión que solo abrió el enlace, sin identidad y sin error, no deja archivo. El rastro de ese intento no se guarda.
- JSON, TXT y CSV no se rellenan con la lectura anterior. Solo el PDF puede usarla, para no mezclar dos sesiones en la copia cruda.

## Cumplimiento y verificación

La prueba `r2_refusesAnEmptyExportAndSaysTheFileIsNotCreated` recorre los cuatro formatos vacíos. La prueba `r3_savedReadingCanFeedThePdfWhenTheScreenSessionIsEmpty` fija el caso de la lectura guardada. El código que escribe pasa por `payloadHasContent` y, si el selector ya creó el archivo, lo borra.
