# Arquitectura y flujo de datos — Nivel A

Conceptual. Componentes marcados **DESCONOCIDO** no están medidos en esta unidad.

```mermaid
flowchart TB
  subgraph trustPhone["Frontera: teléfono del dueño"]
    APP[JAC Diagnostic Lab APK]
    POL[ObdCommandPolicy allowlist]
    EV[(Evidencia local)]
    APP --> POL
    APP --> EV
  end

  subgraph trustRf["Frontera: RF Bluetooth"]
    BT[Classic OBDII / BLE OBDBLE]
  end

  subgraph trustDongle["Frontera: adaptador"]
    ELM[ELM327 Mini ATI ELM327 v2.1 imitable]
  end

  subgraph trustObd["Frontera: conector OBD-II J1962"]
    OBD[Puerto OBD-II]
  end

  subgraph trustVehicle["Frontera: vehículo JAC T8 — parcial"]
    GW["Gateway independiente — DOCUMENTAL de línea, NO verificado en esta unidad"]
    ECU[ECU motor responde 7E8]
    BUS["Buses internos Power/Body/Chassis/Entertainment — DOCUMENTAL de línea"]
  end

  APP -->|GATT FFF2 write / FFF1 notify| BT
  BT --> ELM
  ELM --> OBD
  OBD --> GW
  GW -.->|no medido| BUS
  OBD --> ECU
```

## Límites de confianza medidos vs desconocidos

| Límite | Estado |
|---|---|
| App → adaptador (BLE) | MEDIDO (sesión 2136-8803) |
| Adaptador → ECU vía OBD genérico | MEDIDO (`0100`, `03`) |
| OBD → buses propietarios / gateway | DESCONOCIDO en esta unidad |
| Autenticación frente a 3.er dispositivo | NO MEDIDO |

## Separación Nivel A / B / C

- **Nivel A:** solo `jac-diagnostic-lab` + allowlist. Vehículo estacionado.
- **Nivel B/C:** código bajo `c4a-automotive-security/lab/`. No enlazado al transporte real del vehículo.
