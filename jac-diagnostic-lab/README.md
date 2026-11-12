# JAC Diagnostic Lab

Aplicación Android de diagnóstico **solo lectura** para una JAC T8 con adaptador ELM327 Mini Bluetooth. No borra códigos, no reprograma la ECU y no ejecuta actuadores.

Una conexión exitosa no significa que el vehículo sea seguro para circular. Las comprobaciones iniciales se hacen con el vehículo estacionado y el contacto en ON.

## Entorno comprobado el 11-11-2026

| Pieza | Versión real |
| --- | --- |
| JDK | 21.0.13 en `JAVA_HOME` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` |
| Plataforma | `platforms;android-37.0` |
| Build-tools | 37.0.0 |
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.4.21 |
| Gradle | 9.6.0 |
| Compose BOM | 2026.09.00 |
| Teléfono | Xiaomi 2512BPNDAG, Android 17, SDK 37 |

`compileSdk` y `targetSdk` son 37. `minSdk` es 26 para conservar el camino de permisos anterior a Android 12; el teléfono de trabajo es API 37.

## Compilar

Desde `jac-diagnostic-lab`, con JDK 21 y `local.properties` apuntando al SDK:

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

La APK debug queda en:

```text
app\build\outputs\apk\debug\app-debug.apk
```

También se copia a `dist\` cuando la compilación de esta sesión termina bien.

## Depuración USB en el Xiaomi

1. Ajustes, Acerca del teléfono, tocar varias veces la versión de HyperOS hasta activar las opciones de desarrollador.
2. Ajustes, Ajustes adicionales, Opciones de desarrollador.
3. Activar Depuración USB y, si aparece, Instalación vía USB.
4. Conectar el cable y aceptar la huella RSA del computador.
5. Comprobar:

```text
adb devices
```

Debe decir `device`, no `unauthorized`.

## Instalar

```text
adb install -r dist\jac-diagnostic-lab-debug.apk
```

Si el sistema pide confirmación en la pantalla del teléfono, acéptela. La aplicación no trae un botón para borrar códigos ni sesiones.

## Conectar OBDII y OBDBLE

Empareje el adaptador desde los ajustes de Bluetooth del teléfono. El PIN, si el sistema lo pide, se escribe en el diálogo de Android. Esta aplicación no lo captura ni lo guarda.

- `OBDII` se trata como Bluetooth clásico. El primer intento usa el UUID SPP `00001101-0000-1000-8000-00805F9B34FB`. Si ese socket falla, hay un reintento y después un respaldo por el canal RFCOMM 1, habitual en clones.
- `OBDBLE` no se trata como RFCOMM. La aplicación descubre servicios GATT, muestra UUID y propiedades READ, WRITE y NOTIFY, y solo escribe después de elegir una característica que realmente tenga escritura y otra que tenga notificación. La validación es un `ATI`. Si la respuesta no parece un ELM327, no se envían consultas OBD.

Cierre otras aplicaciones OBD, por ejemplo OBD Auto Doctor, antes de conectar. El adaptador suele aceptar un solo cliente.

## Qué hace la sesión

Orden de inicialización, y por qué:

1. `ATZ` reinicia el intérprete del adaptador. No es el servicio OBD `04` y no borra la memoria de la ECU.
2. `ATE0` apaga el eco para no confundir el comando con la respuesta.
3. `ATL0` apaga los saltos de línea extra.
4. `ATS0` quita espacios. El parser igual acepta espacios y encabezados, porque algunos clones ignoran `ATS0`.
5. `ATH1` deja los encabezados visibles para separar ECU.
6. `ATI` identifica el texto que reporta el adaptador. Un `v1.5` o `v2.1` no certifica un ELM327 original.
7. `ATSP0` pide el protocolo automático. El selector manual solo ofrece los protocolos 0 a 9 documentados en el datasheet. No se envían los protocolos de usuario B y C ni J1939.
8. `ATDP` y `ATDPN` describen el protocolo. Se vuelven a leer después de `0100`, porque antes de hablar con la ECU el automático puede seguir sin número.
9. `ATRV` es la tensión que estima el adaptador en el pin 16. No reemplaza un multímetro.
10. `0100` es la primera consulta a la ECU. `NO DATA`, `UNABLE TO CONNECT`, `BUS INIT ERROR` o `CAN ERROR` significan que no hubo datos utilizables. No significan que el vehículo esté sin fallas.

Después, si el adaptador respondió, se leen `03`, `07`, `0A` y, si el mapa lo permite, algunos PID estándar. El VIN solo se pide si la persona lo autoriza y si `0900` indica soporte del PID `02`.

## Evidencia

Cada sesión se guarda en el almacenamiento privado de la aplicación. Desde la pantalla Evidencia se exporta JSON, CSV o TXT con el selector de archivos de Android. Puede anonimizar el VIN y la dirección Bluetooth antes de exportar. No hay permiso de internet y la copia de seguridad automática está desactivada.

Los archivos de `evidence\samples\` son transcripciones simuladas para las pruebas. No son lecturas de la camioneta. Lo que sí ocurrió con el teléfono está en `evidence\captured\`.

## Limitaciones

- No hay catálogo SAE J2012-DA. El código se decodifica y se clasifica como genérico o de fabricante por el segundo carácter. No se inventa la descripción.
- El testigo EPC no es el MIL del PID `0101`.
- No hay un PID genérico del interruptor de freno. Las hipótesis H1 y H2 no se pueden cerrar con esta aplicación.
- `ATRV` no es una medición de batería ni de tierras.
- ABS, BCM, transmisión y buses propietarios no se declaran accesibles.
- La aplicación no recomienda cambiar piezas a partir de los síntomas.
- El resultado de la comunicación real con la camioneta está en la sección final de este archivo y solo se afirma si quedó la respuesta cruda.

## Prueba en el vehículo

Intento del 11 de noviembre de 2026, con la camioneta descrita como JAC T8 encendida y estacionada y el ELM327 Mini Bluetooth en el puerto OBD-II.

El proyecto compiló. `testDebugUnitTest` ejecutó 14 pruebas y ninguna falló. La APK debug quedó en `dist\jac-diagnostic-lab-debug.apk` (11 942 827 bytes, SHA-256 `CE0F84A1C36CAB036D06D24CF5B4FD4223B4B43F97E9037AD81773F446EBD081`).

El teléfono Xiaomi 2512BPNDAG seguía en ADB como `device`. `adb install` no instaló el paquete: HyperOS abrió `AdbInstallActivity` con la pantalla bloqueada (`isKeyguardLocked:true`, actividad no visible) y PackageManager cerró la sesión con `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. `pm path app.jac.diagnosticlab` no encontró la aplicación.

Antes de ese intento, `settings get global bluetooth_on` devolvía 0. Se ejecutó `svc bluetooth enable` y el valor pasó a 1. No hubo escaneo OBDII/OBDBLE, ni ATZ, ni 0100, ni lectura de DTC, porque la aplicación no llegó a abrirse. No hay respuesta del adaptador. Eso no dice nada sobre fallas de la camioneta.

Para continuar hace falta desbloquear el teléfono, aceptar en HyperOS la instalación por USB y volver a lanzar la actividad con el extra `autodiag`. La transcripción de este intento está en `evidence\captured\`.
