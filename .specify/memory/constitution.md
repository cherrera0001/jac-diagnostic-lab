# Constitución C4A Automotive Security Research Lab

**Spec Kit CLI:** specify 1.2.0  
**Proyecto:** JAC Diagnostic Lab / C4A Automotive Security  
**Baseline etiquetado:** `v0.4.0` (`c08a425`)  
**Fecha:** 2026-11-12

Esta constitución adopta GitHub Spec Kit sobre el repositorio existente sin destruir el código funcional.

## Principio I — Evidence First

Toda afirmación técnica relevante se vincula a evidencia (sesión, hash, prueba, documento).  
No sustituir evidencia por conclusiones.

## Principio II — Specification Before Implementation

Cada capacidad nueva necesita requisitos verificables antes del código.

## Principio III — Strict TDD (componentes críticos)

1. Escribir prueba  
2. Confirmar fallo esperado  
3. Implementar mínimo  
4. Confirmar éxito  
5. Refactorizar  
6. Regresión  

Críticos: parser OBD, política de comandos, DTC, evidencias, semántica de PID.

## Principio IV — Safety by Architecture

- Vehículo real = solo lectura (`ProductionVehicleTransport` / allowlist).  
- Adversarial = aislado (`ResearchLabTransport` / `lab/`).  
- No basta un interruptor visual para activar operaciones experimentales en el vehículo.

## Principio V — Reproducibility

Experimentos con versión, parámetros, datos iniciales y resultados preservados.

## Principio VI — Separation of Concerns

Transporte · protocolo · adquisición · interpretación · análisis · evidencias · UI.

## Principio VII — No False Confidence

Estados: VERIFIED, SUPPORTED, HYPOTHESIS, UNKNOWN, NOT_TESTED, INCONCLUSIVE.

## Principio VIII — Human Validation

Hallazgos con implicación de seguridad física o modificación de hardware requieren HITL.

## Principio IX — Security by Default

Mínimos privilegios, sin secretos en repo, anonimización de VIN/MAC en exports compartidos.

## Principio X — Change Traceability

Cambio → requisito → prueba → resultado.

## Separaciones obligatorias

| No confundir | Con |
|---|---|
| Falla mecánica | Vulnerabilidad |
| Vulnerabilidad potencial | Compromiso |
| Consulta OBD exitosa | Control de ECU |
| ATRV | Multímetro |
| PID 0x11 Absolute Throttle | Pedal de acelerador |
| Simulación | Hardware real |
