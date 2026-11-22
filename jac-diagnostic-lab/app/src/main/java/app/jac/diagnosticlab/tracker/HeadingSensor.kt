package app.jac.diagnosticlab.tracker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Rumbo de la brújula (0–360°, norte magnético) con el teléfono plano delante del cuerpo. Filtro circular. */
class HeadingSensor(private val context: Context) : SensorEventListener {
    val heading = MutableStateFlow<Float?>(null)
    val available = MutableStateFlow(true)
    private var sx = 0.0
    private var sy = 0.0
    private var seeded = false

    fun start() {
        val sm = context.getSystemService(SensorManager::class.java)
        val s = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) { available.value = false; return }
        seeded = false
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() { context.getSystemService(SensorManager::class.java)?.unregisterListener(this); heading.value = null }

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        SensorManager.getOrientation(r, o)
        val a = o[0].toDouble()
        if (!seeded) { sx = cos(a); sy = sin(a); seeded = true } else { sx += 0.2 * (cos(a) - sx); sy += 0.2 * (sin(a) - sy) }
        heading.value = ((Math.toDegrees(atan2(sy, sx)) + 360) % 360).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
