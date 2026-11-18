# Cómo usar, compartir y contribuir

El código es de acceso libre bajo [Apache-2.0](LICENSE). Puede usarlo, copiarlo, modificarlo y redistribuirlo, también con fines comerciales, si conserva `LICENSE` y `NOTICE` e indica qué cambió.

## Lo que nunca se sube

El repositorio es público. Antes de un commit, revise que no incluya:

- VIN, matrícula o número de chasis de una unidad real;
- dirección Bluetooth (MAC) de un adaptador o teléfono real;
- sesiones exportadas (`sessions/*.json`), PDF de informes, trazas CSV o capturas de pantalla con datos de una unidad;
- correos, teléfonos o nombres de dueños o talleres;
- llaves de firma, `local.properties`, `.env` o credenciales.

Si necesita una muestra para una prueba, anonimícela: VIN reemplazado por `XXXXXXXXXXXXXXXXX`, MAC por `00:11:22:33:44:55`. Los bytes de respuesta OBD (por ejemplo `7E8 06 41 0C ...`) sin VIN pueden quedar, porque son lo que hace reproducible una prueba.

Si encuentra un dato personal ya publicado, avise en un issue sin copiar el dato.

## Reglas del código

1. **Solo lectura.** Toda petición pasa por `ObdCommandPolicy`, una lista cerrada. No se agregan el servicio `04`, servicios UDS de escritura, `ATSH`, `ATMA` ni actuadores. Ampliar la lista exige un test que pruebe el permiso y el rechazo.
2. **Nada simulado en la ruta real.** `ResearchLabTransport` y el laboratorio CAN nunca se conectan al vehículo.
3. **Medido, documental o desconocido.** Un valor sin respuesta cruda no se presenta como medido. Un rango genérico se dice genérico.
4. **Sin porcentaje de seguridad** ni recomendación de piezas.
5. Requisito → decisión (`adr/`) → test que nombra el requisito → código. Ver [specs/PROCESO.md](specs/PROCESO.md).

## Enviar un cambio

1. Cree una rama desde `cursor/jac-diagnostic-lab`.
2. Compile y pruebe desde `jac-diagnostic-lab`:

   ```text
   gradlew.bat :app:testDebugUnitTest :app:assembleDebug
   ```

3. Agregue la entrada en `CHANGELOG.md` y abra un pull request explicando qué lee el cambio y con qué unidad o adaptador lo probó.

Al enviar un pull request acepta que su aporte se publique bajo la misma licencia Apache-2.0.

## Reportar un problema de seguridad

Si encuentra una forma de que la app envíe un comando fuera de la lista, no publique el método. Abra un issue que solo diga "problema de seguridad en la política de comandos" y se coordinará el detalle por un canal privado.
