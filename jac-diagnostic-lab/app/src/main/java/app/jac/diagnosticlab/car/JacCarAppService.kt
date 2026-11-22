package app.jac.diagnosticlab.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.jac.diagnosticlab.JacApplication
import app.jac.diagnosticlab.LabController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Servicio Android Auto (Car App Library) para JAC Diagnostic Lab.
 * Expone un tablero horizontal pausado (grilla 3×2 + vistas de detalle y estado) conectado al mismo
 * LabController de la app, sin abrir un segundo socket Bluetooth.
 */
class JacCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = JacCarSession()
}

class JacCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val lab = (carContext.applicationContext as JacApplication).lab
        return JacCarDashboardScreen(carContext, lab)
    }
}

/**
 * Pantalla principal en Android Auto: grilla horizontal 3×2 con refresco pausado (1,2 s) para evitar parpadeos.
 */
class JacCarDashboardScreen(
    carContext: CarContext,
    private val lab: LabController,
) : Screen(carContext), DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var snapshot: CarDashboardSnapshot = CarDashboardModel.from(lab.state.value)

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                val next = CarDashboardModel.from(lab.state.value)
                if (next != snapshot) {
                    snapshot = next
                    invalidate()
                }
                delay(1_200L)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        watchJob?.cancel()
        watchJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
    }

    private fun onPrimaryAction() {
        val snap = snapshot
        when {
            !snap.connected -> {
                lab.startAutodiag()
                CarToast.makeText(carContext, "Buscando adaptador OBD…", CarToast.LENGTH_SHORT).show()
            }
            snap.liveRunning -> {
                lab.stopGuidedTest()
                CarToast.makeText(carContext, "Lectura en vivo pausada.", CarToast.LENGTH_SHORT).show()
            }
            else -> {
                lab.startGuidedTest("LIVE")
                CarToast.makeText(carContext, "Iniciando lectura en vivo…", CarToast.LENGTH_SHORT).show()
            }
        }
        snapshot = CarDashboardModel.from(lab.state.value)
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val snap = snapshot
        val listBuilder = ItemList.Builder()
        snap.tiles.forEach { tile ->
            listBuilder.addItem(
                GridItem.Builder()
                    .setTitle(tile.title)
                    .setText(tile.subtitle)
                    .setImage(CarTileRenderer.iconFor(tile), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener {
                        when (tile.target) {
                            CarTileTarget.DETAIL -> screenManager.push(JacCarDetailScreen(carContext, lab))
                            CarTileTarget.STATUS -> screenManager.push(JacCarStatusScreen(carContext, lab))
                        }
                    }
                    .build(),
            )
        }

        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(snap.primaryActionLabel)
                    .setOnClickListener { onPrimaryAction() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Estado")
                    .setOnClickListener { screenManager.push(JacCarStatusScreen(carContext, lab)) }
                    .build(),
            )
            .build()

        return GridTemplate.Builder()
            .setTitle(snap.headerTitle)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(strip)
            .setSingleList(listBuilder.build())
            .build()
    }
}

/** Vista de detalle calmado (máx 4 filas) de pedal, riel, turbo y DPF. */
class JacCarDetailScreen(
    carContext: CarContext,
    private val lab: LabController,
) : Screen(carContext), DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var snapshot: CarDashboardSnapshot = CarDashboardModel.from(lab.state.value)

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                val next = CarDashboardModel.from(lab.state.value)
                if (next.detailRows != snapshot.detailRows || next.liveRunning != snapshot.liveRunning) {
                    snapshot = next
                    invalidate()
                }
                delay(1_200L)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        watchJob?.cancel()
        watchJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
    }

    override fun onGetTemplate(): Template {
        val snap = snapshot
        val pane = Pane.Builder()
        snap.detailRows.take(4).forEach { r ->
            val rb = Row.Builder().setTitle(r.title).addText(r.line1)
            r.line2?.let { rb.addText(it) }
            pane.addRow(rb.build())
        }
        pane.addAction(
            Action.Builder()
                .setTitle(snap.primaryActionLabel)
                .setOnClickListener {
                    if (!snap.connected) lab.startAutodiag()
                    else if (snap.liveRunning) lab.stopGuidedTest()
                    else lab.startGuidedTest("LIVE")
                    snapshot = CarDashboardModel.from(lab.state.value)
                    invalidate()
                }
                .build(),
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle("Motor y DPF · Detalle")
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** Vista de estado, DTCs y botón rápido «Marcar» para eventos en ruta. */
class JacCarStatusScreen(
    carContext: CarContext,
    private val lab: LabController,
) : Screen(carContext), DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var snapshot: CarDashboardSnapshot = CarDashboardModel.from(lab.state.value)

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                val next = CarDashboardModel.from(lab.state.value)
                if (next.statusRows != snapshot.statusRows) {
                    snapshot = next
                    invalidate()
                }
                delay(1_500L)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        watchJob?.cancel()
        watchJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
    }

    override fun onGetTemplate(): Template {
        val snap = snapshot
        val list = ItemList.Builder()
        snap.statusRows.take(6).forEach { r ->
            val rb = Row.Builder().setTitle(r.title).addText(r.line1)
            r.line2?.let { rb.addText(it) }
            list.addItem(rb.build())
        }
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Marcar evento")
                    .setOnClickListener {
                        lab.markEvent("Marcado desde Android Auto")
                        CarToast.makeText(carContext, "Evento marcado.", CarToast.LENGTH_SHORT).show()
                    }
                    .build(),
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("Estado y Códigos")
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip)
            .setSingleList(list.build())
            .build()
    }
}
