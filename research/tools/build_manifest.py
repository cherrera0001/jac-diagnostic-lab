"""Genera research/source-manifest.json a partir de los registros de descarga reales.

Uso (desde la raíz del repo):  python3 -I research/tools/build_manifest.py
Lee research/_fetch-log.tsv y research/_fetch-log-extra.tsv (estado HTTP, tamaño, SHA-256, fecha UTC)
y recalcula el SHA-256 de cada archivo presente en research/_downloads/ (no versionado).
Los metadatos (emisor, alcance, nivel, licencia) son revisión manual del 2026-11-18.
"""
import csv, hashlib, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DL = os.path.join(ROOT, "_downloads")

META = {
 "S01": ("JAC Chile — Manuales de propietario y pasaporte de servicio", "JAC Autos Chile (distribuidor)", "es", "OEM_OTHER_VARIANT", "Página índice; enlaza el manual S02.", "Gama Chile", "Sitio comercial; sin licencia de reutilización declarada"),
 "S02": ("Manual de usuario JAC T8 (PDF oficial Chile)", "JAC Autos Chile", "es", "OEM_OTHER_VARIANT", "98 págs. sin capa de texto (Illustrator). OCR tesseract 5.3.4 spa a 150 dpi; muestras revisadas a mano. EOBD (p.13), EPC controlada por su módulo (p.16), indicador DPF «si está configurado» (p.17), regeneración forzada (p.148). Sin PIDs ni fórmulas.", "T8 Chile, varias configuraciones; no identifica la unidad 2023", "Derechos de JAC; no se redistribuye"),
 "S03": ("JAC Chile — ficha Pickup T8", "JAC Autos Chile", "es", "OEM_OTHER_VARIANT", "Todas las versiones listadas: 2.0 CTI 147 HP / 360 Nm, 6 marchas, ESC/HBA/TCS, control crucero.", "Gama T8 Chile 2024", "Sitio comercial"),
 "S04": ("JAC Global — Download (Vehicle OBD & Repair and Maintenance)", "JAC Motors (Anhui Jianghuai)", "en", "OEM_OTHER_VARIANT", "SPA Nuxt; la lista llega por S25.", "Modelos exportación", "Sitio corporativo"),
 "S05": ("JAC Chile — Centro de ayuda", "JAC Autos Chile", "es", "OEM_OTHER_VARIANT", "Canal para solicitar manual de taller / versión ECU (ver gaps.md G-01).", "-", "Sitio comercial"),
 "S06": ("ppt-online.org/1230239 (es)", "Espejo de terceros", "es", "UNVERIFIED", "Respondió captcha aritmético; no se evadió.", "-", "-"),
 "S07": ("device.report — catálogo de funciones de diagnóstico por marca", "Espejo de terceros", "en", "UNVERIFIED", "HTTP 403. No se reintentó con evasión.", "-", "-"),
 "S08": ("UPS DSpace — registro 123456789/28396", "Universidad Politécnica Salesiana", "es", "ACADEMIC_VALIDATED", "Metadatos de la tesis S09.", "-", "Repositorio institucional abierto"),
 "S09": ("Tesis UPS 2024 TTS1919 — panel de control JAC T8 vía CAN, LabVIEW y NI 9862", "Universidad Politécnica Salesiana (Quito)", "es", "ACADEMIC_VALIDATED", "p.17 tabla PIDs (MAF escrito /32 con máx 655,35 → errata; J1979 /100). p.28: CAN 11 bit 500 kbps ISO 15765-4, 7E0/7E8. p.47: escáner Autel con perfil EURO 5 sobre unidad EURO 3 mostró IAT/MAF/ECT fuera de rango; se validó por OBD-II directo. p.54-55: dos herramientas a la vez degradan la lectura; otros módulos sin estudiar.", "Una JAC T8 diésel en Ecuador, EURO 3", "Repositorio institucional; citar"),
 "S10": ("Autotuner — fabricante JAC", "Autotuner (herramienta)", "en", "VENDOR_CATALOG", "Lista de ECUs cargada por JS; el HTML no contiene filas JAC verificables.", "-", "Sitio comercial"),
 "S11": ("Alientech — New BOOT mode protocol for Jac T8 2.0D Pro 4x4", "Alientech (herramienta)", "en", "VENDOR_CATALOG", "Delphi DCM6.2AP en JAC T8 Pro 4x4 2.0 diésel producida desde 2023.", "T8 Pro, no T8 estándar", "Sitio comercial"),
 "S12": ("OBD Lens — perfil T8 2.0 CTI (legacy-1153)", "OBD Lens (tercero)", "en", "COMMUNITY_REPRODUCED", "Motor HFC4DB2-2E diésel; 40 señales ampliadas por nombre y unidad; años no especificados; no publica request ni fórmula; «Library metadata is not a road-test claim».", "T8 2.0 CTI sin año", "Licencia personal e intransferible (S29 §5): no se importa"),
 "S13": ("Google Play — com.jac.global", "JAC ITALY SRL", "es", "OEM_OTHER_VARIANT", "App de control remoto/carga EV (bloqueo, A/C, carga). 5 mil+ descargas, act. 14-abr-2025. No es diagnóstico ni declara T8.", "Vehículos conectados/EV Europa", "Términos de Google Play"),
 "S14": ("JAC MX — términos y condiciones app", "JAC México", "es", "OEM_OTHER_VARIANT", "Compatibilidad limitada a gama México (Sei4 Pro, Sei6 Pro, Sei7 Pro, J7, E10X, E J7, E Sei4Pro). Lectura básica de datos y ubicación aproximada.", "México; no T8", "Términos JAC MX"),
 "S15": ("JAC MX — aviso de privacidad app", "JAC México", "es", "OEM_OTHER_VARIANT", "Recolecta VIN, placa, kilometraje, consumo; «diagnóstico de vida útil». Sin API pública.", "México; no T8", "Aviso de privacidad"),
 "S16": ("JAC Diagnostic System (JDS) — presentación (en.ppt-online.org)", "Espejo de terceros de un material «JAC Customer Service Department»", "en/zh", "UNVERIFIED", "La descarga automática recibió captcha. Leída por el agente web-fetch (HTTP 200, 38 diapositivas). Sin fecha ni versión. Ver jds-workflow.md.", "Distribuidores JAC; menciona S4 y K7, no T8", "Material de terceros; solo se resume"),
 "S17": ("Scribd — JAC Diagnostic System (JDS) Operation Instruction", "Scribd (terceros)", "en", "UNVERIFIED", "Contenido renderizado por JS; no se obtuvo el documento.", "-", "Scribd ToS"),
 "S18": ("XTOOL — lista de funciones JAC V21.18 (CN)", "XTOOL (herramienta)", "zh", "VENDOR_CATALOG", "Diésel JAC: Bosch EDC17C81 para HFC4DE/HFC4DA/HFC4DB (国四/国五, +SCR o +DPF) y Delphi DCM6.2AP (HFC4DB, 共轨国五); funciones de lectura y pruebas de actuador.", "Familias de motor JAC; no por VIN", "Sitio del fabricante de la herramienta"),
 "S19": ("JAC China — 江淮T8 ficha", "JAC Pickup (China)", "zh", "OEM_OTHER_VARIANT", "HFC4DB2-2E2, 1,999 L, 110 kW/3200, 400 Nm/1500-2500, 国Ⅵb.", "Mercado China", "Sitio corporativo"),
 "S20": ("AutoECUPart — ECU 28608897 / 1026400FD020 JAC T6 T8", "Vendedor de repuestos", "en", "UNVERIFIED", "HTTP 429. Solo visto en el índice del buscador.", "-", "-"),
 "S21": ("ecufiles.io — Delphi DCM6.2", "ecufiles (catálogo de archivos de tuning)", "en", "VENDOR_CATALOG", "Fila JAC T8 2.0TDI 2024, 137 hp / 320 Nm: HW 1026400FD020, SW 1MJACLSA_A010D11. La fila incluye un VIN de un tercero: no se copia.", "T8 2024 de otra potencia", "Sitio comercial"),
 "S22": ("ecutools — BitBox", "ecutools (herramienta)", "en", "VENDOR_CATALOG", "Lectura/escritura de DCM6.2AP JAC por CAN (fuera de alcance: no se escribe la ECU).", "-", "Sitio comercial"),
 "S23": ("yunshuren — 帅铃T8 2.0CTI HFC4DB2-1D1", "Portal comercial CN", "zh", "UNVERIFIED", "HTTP 403.", "-", "-"),
 "S24": ("Delaware DMV — dtc_list.pdf", "DMV Delaware", "en", "UNVERIFIED", "Devuelve 244 bytes HTML en lugar del PDF. Enlace muerto.", "-", "-"),
 "S32": ("Dimsport — aplicaciones JAC T8", "Dimsport (herramienta)", "en", "VENDOR_CATALOG", "T8 2.0L 16V TD 100 kW/134 HP → DCM6.2AP; T8 PRO 2.0L 16V TD 107 kW/144 HP → MD1CS089; T8 2.0L Turbo 130 kW/174 HP → Delphi MT80.", "Variantes T8 por potencia", "Sitio comercial"),
 "S33": ("AutoTuner — Delphi DCM3.7AP / DCM6.2AP", "AutoTuner (herramienta)", "en", "VENDOR_CATALOG", "JAC T8 (2020+) 2.0 diésel → DCM6.2AP (SPC5674), lectura OBD.", "T8 2020+", "Sitio comercial"),
 "S34": ("iMotriz — ECU JAC JT8 1026400FD020B054", "iMotriz (repuestos)", "es", "VENDOR_CATALOG", "Número de parte 1026400FD020B054. No nombra proveedor ni año.", "JT8", "Sitio comercial"),
 "S35": ("Inversiones Diesel — Computadora JAC T6 T8 Delphi 1026400FD020", "Inversiones Diesel (repuestos)", "es", "VENDOR_CATALOG", "Delphi, SKU 1026400fd020, T6/T8 sin año ni variante.", "T6/T8", "Sitio comercial"),
 "S36": ("MDC Repuestos — ECU computador de motor JAC T8-T6", "MDC Repuestos (Chile)", "es", "VENDOR_CATALOG", "«JAC Delphi DCM6.2 AP, código OEM 1026400FD020B-007». Aportada por el usuario y verificada.", "T8/T6 Chile", "Sitio comercial"),
 "S37": ("Desarmaduría IPAR — búsqueda 1026400FD020", "IPAR (desarmaduría)", "es", "UNVERIFIED", "ipar.cl devolvió portada de desarmaduriaipar.cl sin el producto; búsqueda web sin resultado. Aportada por el usuario; no verificada.", "-", "-"),
 "S25": ("JAC Global API — /index.php/api/car/carInformationList.html", "JAC Motors", "en", "OEM_OTHER_VARIANT", "GET público sin autenticación, el mismo que llama la web S04. 6 modelos con obd_path/repair_path: Sunray Pro-EU, LE796, N75EV, E30X, M3EV, e-JS4. Sin T8.", "Modelos exportación UE", "Datos RMI publicados por el fabricante"),
 "S26": ("Sunray Pro-EU — OBD information (PDF, 725 págs.)", "JAC Motors", "en", "OEM_OTHER_VARIANT", "Variante eléctrica (BMS, IPU, EV CAN). Útil como formato de documento OBD de JAC; no aplica al diésel T8.", "Sunray Pro-EU", "RMI; no se redistribuye"),
 "S27": ("LE796 — OBDinformation.xlsx", "JAC Motors", "en/zh", "OEM_OTHER_VARIANT", "«Diagnostic Specification 4-1 DTC List»: DTC, bytes H/M/L, variantes ECU, condición de activación y recuperación, acción, causas, corrección. Esquema de referencia para DiagnosticKnowledgeProvider.", "LE796", "RMI; no se redistribuye"),
 "S28": ("OBD Lens — PID ampliado Boost pressure setpoint", "OBD Lens", "en", "COMMUNITY_REPRODUCED", "«Request commands and decoding formulas are not … published here.» Estado: «vehicle response not established».", "T8 2.0 CTI", "Licencia personal"),
 "S29": ("OBD Lens — términos", "OBD Lens", "en", "COMMUNITY_REPRODUCED", "§5: licencia personal, limitada e intransferible.", "-", "-"),
 "S30": ("JAC Global — bundle JS 380638d.js (componente Download)", "JAC Motors", "js", "OEM_OTHER_VARIANT", "Análisis estático del frontend público: getList() → $axios.$get('/api/car/carInformationList.html'); campos obd_path, repair_path, maintenance_path.", "-", "Código del sitio; solo se cita"),
 "S31": ("JAC Global — bundle JS 6a87fb7.js (config axios)", "JAC Motors", "js", "OEM_OTHER_VARIANT", "baseURL por defecto https://jacen.jac.com.cn/index.php.", "-", "Código del sitio; solo se cita"),
}
EXTRA_FILES = {"S25": "jacglobal-carInformationList.json", "S26": "sunray-pro-eu-OBD-information.pdf", "S27": "LE796-OBDinformation.xlsx",
               "S28": "obdlens-pid-boost-pressure-setpoint.html", "S29": "obdlens-terms.html", "S30": "jacglobal-js/380638d.js", "S31": "jacglobal-js/6a87fb7.js"}
EXTRA_URL = {"S25": "https://jacen.jac.com.cn/index.php/api/car/carInformationList.html",
             "S28": "https://obdlens.com/pids/enhanced/ecm-boost-pressure-setpoint-pid-627cd3b5104ca98179be52d3/",
             "S29": "https://obdlens.com/terms/", "S30": "https://jacen.jac.com.cn/_nuxt/380638d.js", "S31": "https://jacen.jac.com.cn/_nuxt/6a87fb7.js"}

# HTTP 200 pero el cuerpo no es el documento (captcha, shell JS, enlace muerto).
FAILED_CONTENT = ("S06", "S16", "S17", "S24", "S37")

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

rows = {}
with open(os.path.join(ROOT, "_fetch-log.tsv"), encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t"):
        sid, when, code, size, ctype, digest, fname, url = r
        rows[sid] = dict(retrieved_at=when, http_status=int(code), bytes=int(size), content_type=ctype, file=fname, url=url, sha256_logged=digest)
extra = {}
with open(os.path.join(ROOT, "_fetch-log-extra.tsv"), encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t"):
        when, code, size, ctype, digest, fname, url = r
        extra[fname] = dict(retrieved_at=when, http_status=int(code), bytes=int(size), content_type=ctype, url=url)
for sid, fname in EXTRA_FILES.items():
    e = extra.get(fname, {})
    p = os.path.join(DL, fname)
    rows[sid] = dict(retrieved_at=e.get("retrieved_at", "2026-11-18"), http_status=e.get("http_status", 200),
                     bytes=os.path.getsize(p) if os.path.exists(p) else None, content_type=e.get("content_type"),
                     file=fname, url=e.get("url") or EXTRA_URL[sid])

out = []
for sid in sorted(rows, key=lambda s: int(s[1:])):
    r = rows[sid]; title, issuer, lang, level, finding, applies, lic = META[sid]
    p = os.path.join(DL, r["file"])
    out.append({
        "id": sid, "title": title, "issuer": issuer, "language": lang, "url": r["url"],
        "retrieved_at": r["retrieved_at"], "http_status": r["http_status"], "bytes": r["bytes"], "content_type": r["content_type"],
        "sha256": sha(p) if os.path.exists(p) and r["http_status"] == 200 and sid not in FAILED_CONTENT else None,
        "local_copy": "research/_downloads/" + r["file"] + " (no versionado)",
        "status": "READ_NOT_ARCHIVED" if sid == "S16" else ("DOWNLOADED" if r["http_status"] == 200 and sid not in FAILED_CONTENT else "FAILED"),
        "level": level, "applies_to": applies, "engine_family": "HFC4DB2" if sid in ("S12", "S18", "S19", "S21", "S32", "S33", "S36") else None,
        "ECU_fingerprint": None, "finding": finding, "license": lic,
        "validated_at": None, "reviewer": "Claude Code (agente), revisión pendiente del propietario",
        "confidence_reason": "Documental de línea ≠ verificado en unidad." if level != "UNVERIFIED" else "No obtenido o no verificable.",
    })
json.dump({"generated_by": "research/tools/build_manifest.py", "date": "2026-11-18", "sources": out}, open(os.path.join(ROOT, "source-manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(len(out), "fuentes;", sum(1 for o in out if o["status"] == "DOWNLOADED"), "descargadas")
