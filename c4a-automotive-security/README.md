# C4A Automotive Security Lab

Auditoría integral, diagnóstico y hardening de **JAC T8**, basada en evidencia.

El informe de una medición no se sube al repositorio. Queda en el equipo local.  
Cómo se usa la app y cuál es el proceso: [README de la raíz](../README.md).

- **Metodología de referencia:** ISO/SAE 21434 (TARA), STRIDE, SDD/TDD, Evidence-Driven Security.
- **No se declara cumplimiento formal** de ISO/SAE 21434 solo por usarla como guía.
- **Niveles:** A = vehículo real solo lectura · B = adaptador aislado · C = laboratorio simulado.

## Relación con JAC Diagnostic Lab

La aplicación Android operativa vive en [`../jac-diagnostic-lab`](../jac-diagnostic-lab) (**v1.2.0**).  
APK pública para dueño/mecánico: [release v1.2.0](https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.2.0).  
Este directorio concentra investigación, TARA, lab aislado, hallazgos y hardening. **No** transmite al vehículo.

Módulos en la APK (`app.jac.diagnosticlab.security` y evidencia):

- `BluetoothSecurityAuditor`
- `Elm327SecurityAnalyzer`
- `ObdCommandPolicy` (vía `ReadOnlyGate`)
- `VehicleFaultCorrelation`
- `ThreatModelEngine` / `HardeningAdvisor` / `SecurityAssessment`
- `WorkshopGuide` + monitor ordenado (UI **Monitor** / **Taller**)
- Validaciones de seguridad embebidas en **Avisos** (sin %)

## Qué no se publica

Las lecturas de una camioneta (códigos, transcripciones, identificadores de sesión y el PDF del informe) no se versionan. Una conexión exitosa no prueba una vulnerabilidad ni autoriza a circular. Un código de falla no demuestra un ataque.

## Arranque rápido

1. Abrir y construir `jac-diagnostic-lab` (ver su README).
2. Leer `research/vehicle-inventory/inventory.json`.
3. Leer hallazgos en `reports/findings/`.
4. TARA: `docs/tara/TARA-2026-11-11.md`.
5. Lab adversarial: solo bajo `lab/` (nunca contra el vehículo).

## Estructura

```text
c4a-automotive-security/
  research/          inventario y fuentes
  docs/              arquitectura, TARA, hardening, safety, hitos
  lab/               simuladores Nivel B/C (esqueleto)
  reports/findings/  C4A-AUTO-2026-XXX
  android/           puntero al app real
```

## Próximo paso verificable

1. Confirmar año/VIN de la unidad (con autorización).  
2. Prueba Nivel A con **segundo teléfono propio** (T-001), vehículo estacionado.  
3. Hito 5: emulador ELM/ECU en `lab/` con aislamiento comprobable.
