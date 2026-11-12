# Arquitectura

La aplicación es un módulo Android. El protocolo OBD no importa clases de Android, así las pruebas JUnit corren en la JVM sin el teléfono.

```text
Pantallas Compose
    DiagnosticViewModel
        LabController          sesión, permisos, archivo de evidencia
            BluetoothRepository
            ClassicElmTransport    RFCOMM SPP
            BleInspector           GATT, sin escritura hasta validar ATI
            ElmSession
                ReadOnlyGate       una sola lista de comandos
                ElmParser
                DtcDecoder / PidDecoder
                JacAnalysis
                Export JSON CSV TXT
```

`LabController` vive en la `Application`, no en la actividad. Girar la pantalla no corta el socket. Un servicio en primer plano de tipo `connectedDevice` intenta mantener el proceso mientras la sesión está abierta. Si el sistema lo rechaza, la sesión sigue dentro de la aplicación y el fallo queda en la transcripción.

Hay una sola solicitud activa: el transporte usa un mutex. El registro de sensores espera a que cada PID termine antes de enviar el siguiente.

La secuencia automática (`autodiag`) es la misma sesión de solo lectura que los botones. Primero busca, prefiere `OBDII` por RFCOMM y solo inspecciona `OBDBLE` si el clásico no identificó un adaptador. No presenta datos simulados en esa ruta. `FakeObdTransport` existe únicamente en las pruebas.

La evidencia se escribe después de cada tramo en el almacenamiento privado y, si Android lo permite, en el directorio externo de la aplicación. No hay borrado dentro de la aplicación.
