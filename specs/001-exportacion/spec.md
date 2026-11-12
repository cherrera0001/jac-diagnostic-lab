# SPEC-001 — Cuándo se arma el archivo

**Objetivo (palabras del usuario, 2026-11-11):** saber en qué momento se procesa la lectura y poder sacar el PDF. En Copia solo aparecen CSV, JSON y TXT, y se generan archivos vacíos. Faltó la validación.

**Qué no cubre esta spec.** No rehace el enlace Bluetooth, ni la lectura de avisos, ni la pestaña Seguridad. Esos comportamientos son anteriores a `R1`. No publica el informe de una unidad.

### R1 — El PDF está junto a los otros formatos
**Estado:** verificado
**Origen:** usuario, 2026-11-11, «solo veo 3 formatos. csv, json, txt»
**Necesidad:** no buscar el informe en otro paso.
**Comportamiento:** La pestaña Copia muestra cuatro acciones: PDF, JSON, TXT y CSV. El PDF es el informe en palabras. Los otros tres son la copia cruda.
**Criterio de aceptación:**
- [x] Copia muestra un control PDF además de JSON, TXT y CSV
- [x] El PDF no se arma al abrir la app ni durante la lectura. Se arma al pulsar
- [x] La pantalla dice esa frase, no un menú de formatos sin contexto
**Fuera de alcance:** subir el archivo a un servidor
**Depende de:** ADR-001
**Verificado por:** prueba `r1_pdfJoinsTheOtherFormatsOnlyWhenThereIsContent` y la pantalla Copia

### R2 — No se crea un archivo vacío
**Estado:** verificado
**Origen:** usuario, 2026-11-11, «hay archivos que se generan vacios. Error, faltó validación.»
**Necesidad:** un archivo sin respuestas no es una lectura.
**Comportamiento:** Antes de escribir, una función pura dice si hay contenido. Si no lo hay, no se escribe y, si el selector de Android ya creó el archivo, se borra.
**Criterio de aceptación:**
- [x] JSON y TXT se rechazan si no hay intercambios, ni avisos, ni una respuesta vacía confirmada, ni un error, ni identidad del aparato
- [x] CSV se rechaza si no hay ninguna fila de sensor, aunque el resto tenga datos
- [x] PDF se rechaza con la misma barra que JSON
- [x] La copia automática de la sesión no escribe JSON ni TXT cuando esa barra no se cumple
- [x] El motivo cabe en una frase y dice que el archivo no se crea
**Fuera de alcance:** recuperar archivos vacíos ya guardados en el teléfono
**Depende de:** R1, ADR-001
**Verificado por:** prueba `r2_refusesAnEmptyExportAndSaysTheFileIsNotCreated`

### R3 — La pantalla dice el momento
**Estado:** verificado
**Origen:** usuario, 2026-11-11, «¿En qué momento procesa toda la información?»
**Necesidad:** distinguir la lectura, que ya ocurrió, del archivo, que todavía no.
**Comportamiento:** Copia dice que la lectura ocurre en Hoy, paso 5, y que el archivo se arma al pulsar. Si no hay contenido, dice por qué el botón no escribe.
**Criterio de aceptación:**
- [x] Con una sesión vacía, los cuatro botones no escriben y se ve el motivo
- [x] Con una lectura guardada y la sesión en pantalla vacía, el PDF puede usar esa lectura guardada. JSON, TXT y CSV de la sesión vacía siguen rechazados
**Fuera de alcance:** reprocesar la transcripción cruda en un servidor
**Depende de:** R1, R2
**Verificado por:** `r3_savedReadingCanFeedThePdfWhenTheScreenSessionIsEmpty` y el texto de Copia
