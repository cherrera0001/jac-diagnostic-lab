# SPEC-002 — Modelo y año los escribe el dueño

**Objetivo (palabras del usuario, 2026-11-11):** poder ingresar el modelo y el año. La unidad es una JAC T8 año 2023.

**Qué no cubre.** Escribir el año no lee el programa de la computadora ni confirma una compuerta de seguridad. No se publica este dato como medición.

### R4 — Hay dónde escribir modelo y año
**Estado:** verificado
**Origen:** usuario, 2026-11-11. Dijo que es una JAC T8 año 2023.
**Necesidad:** el enchufe no entrega el modelo ni el año, y la pantalla no dejaba escribirlos.
**Comportamiento:** Hoy muestra dos campos, Modelo y Año. Al abrir por primera vez vienen llenos con JAC T8 y 2023. Se pueden cambiar. El paso 8 de Seguridad dice «Declarado por ti» y aclara que no salió de la computadora.
**Criterio de aceptación:**
- [x] Con modelo y año escritos, el paso 8 dice Declarado por ti, cita el año y dice que no salió de la computadora
- [x] Con los dos campos vacíos, el paso 8 dice Sin comprobar y pide ingresarlos
- [x] El texto no dice que la compuerta quedó verificada
**Fuera de alcance:** leer el año desde la computadora
**Depende de:** R3
**Verificado por:** prueba `r4_typedYearStaysDeclaredAndDoesNotVerifyTheGateway`
