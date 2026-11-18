# Brechas abiertas (2026-11-18)

| Id | Brecha | Por qué importa | Quién tiene el acceso | Qué se necesita | Sustituto que permite avanzar |
|---|---|---|---|---|---|
| G-01 | Manual de taller T8 2023 Chile, mapa de módulos, versión de ECU, cobertura JDS | Sin esto no hay DIDs, rangos ni texto JAC de P0579/P2299 | JAC Autos Chile (S05) / JAC Customer Service | Solicitud técnica (borrador abajo) | Mode 09 + etiqueta ECU (SPEC-031); J1979 genérico |
| G-02 | Paquete RMI T8 en la API JAC Global | Hoy solo 6 modelos (S25) | JAC Motors | Que JAC lo publique | Reconsultar S25 en cada release |
| G-03 | Identificación de la ECU de esta unidad | Bosch EDC17C81 vs Delphi DCM6.2/6.2AP (S18, S11, S21) | Propietario | Escaneo completo (0904/090A) o foto/texto de la etiqueta | Perfil `PARTIAL` con lecturas J1979 |
| G-04 | Norma de emisiones de la unidad | La tesis S09 muestra lecturas falsas con perfil de norma equivocado | Propietario / homologación chilena | Placa de homologación o certificado | No se aplica ninguna norma por defecto |
| G-05 | Señal del interruptor de freno / crucero | P2299/P0579 apuntan a esas señales; no hay PID genérico | JAC (DIDs) | DID documentado o medición con multímetro por técnico | Medición externa registrada como evidencia distinta de OBD |
| G-06 | Codificación de señales ampliadas T8 (DPF, boost, inyección) | Diagnóstico DPF / pérdida de potencia | JAC / proveedor ECU; OBD Lens no publica | Request + fórmula + vector de prueba con fuente | `UNKNOWN_ENCODING`, no se consulta (R7) |
| G-07 | Texto J2012 oficial de P0579/P2299 | Redacción exacta | SAE (de pago) | Licencia de la norma | Redacción prudente actual de la app |
| G-08 | Material JDS vigente y con fecha | S16 sin fecha ni versión, espejo de terceros | JAC | Manual oficial de JDS | Se usa solo como patrón de UX |
| G-09 | Fuentes bloqueadas: S06 (captcha), S07 (403), S17 (JS), S20 (429), S23 (403), S24 (enlace muerto) | Posible evidencia adicional | Sitios de terceros | Acceso manual por el propietario | Se documentan; no se evaden protecciones |
| G-10 | Prueba en hardware de la v1.9.0 | Ninguna prueba de esta iteración corrió contra la camioneta | Propietario | Sesión estacionada: Capacidad → Leer mapa → Escaneo completo → Perfil | Replays JVM (96 pruebas) |

**Advertencia de operación (S09 p.54):** no conectar otro escáner al mismo tiempo que la app; la tesis observó retrasos y datos corruptos con dos herramientas consultando a la vez.

## Borrador de solicitud técnica a JAC Chile (G-01)

> Asunto: Solicitud de información técnica de diagnóstico — JAC T8 2023
>
> Soy propietario de una JAC T8 año 2023 (VIN disponible a solicitud). Para diagnosticar los códigos P0579 y P2299 con pérdida de potencia al frenar, solicito: (1) la referencia de la ECU de motor de mi unidad (proveedor y número de pieza) y su versión de software; (2) acceso, aunque sea de consulta, al manual de taller de la T8 2023 para el sistema de motor y el interruptor de freno; (3) la definición JAC de P0579 y P2299 y sus condiciones de activación; (4) si la T8 2023 está cubierta por JAC Diagnostic System (JDS) y en qué concesionario puede leerse con esa herramienta. No solicito credenciales ni software, solo la información técnica de mi propio vehículo.
