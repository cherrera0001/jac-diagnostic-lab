package app.jac.diagnosticlab.elm

/**
 * Separación dura entre vehículo real y laboratorio.
 * `LabController` solo acepta [ProductionVehicle].
 */
enum class TransportKind {
    /** Bluetooth clásico / BLE hacia el ELM enchufado en el T8 (solo lectura). */
    ProductionVehicle,

    /** Simulador / vcan / fixtures. Nunca sockets del teléfono al camión. */
    ResearchLab,
}
