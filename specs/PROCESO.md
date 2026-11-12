# Cómo se construye este repositorio

El trabajo nuevo sigue dos cadenas. No se declara un comportamiento listo porque la pantalla lo muestra.

**SDD.** La spec dice qué debe ocurrir y cómo se comprueba. Vive en `specs/`, con requisitos `R1`, `R1.1`, `R2`. Un número no se reutiliza. El ADR dice por qué se eligió ese camino y qué se descartó. Vive en `adr/`.

**TDD.** La prueba se escribe contra el criterio de aceptación y nombra el requisito. Si la prueba no existe, el requisito no está verificado.

```text
Necesidad → R[n] → ADR → prueba que nombra R[n] → código → commit
```

Lo construido antes de `R1` no tiene esta cadena. No se reescribe el pasado para simular que la tuvo. Cada cambio nuevo entra por aquí.

Fuera de este proceso: el informe de una unidad. No se versiona.
