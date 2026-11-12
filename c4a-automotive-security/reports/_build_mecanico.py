# PDF local para el mecanico. No versionar el PDF (INFORME-*).
from hashlib import sha256
from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.enums import TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle

OUT = Path(__file__).with_name("INFORME-MECANICO-T8-2026-11-11.pdf")
SIDE = Path(__file__).with_name("INFORME-MECANICO-T8-2026-11-11.pdf.sha256")

NAVY = colors.HexColor("#1C242E")
GOLD = colors.HexColor("#8A6A2F")
RULE = colors.HexColor("#D9D3C7")
INK = colors.HexColor("#1A1A1A")
ALERT = colors.HexColor("#8B1E1E")
MUTED = colors.HexColor("#5C6570")

styles = getSampleStyleSheet()
h1 = ParagraphStyle("h1", parent=styles["Heading1"], fontName="Times-Bold", fontSize=18, leading=22, textColor=NAVY, spaceAfter=4)
h2 = ParagraphStyle("h2", parent=styles["Heading2"], fontName="Times-Bold", fontSize=13, leading=16, textColor=NAVY, spaceBefore=11, spaceAfter=5)
body = ParagraphStyle("body", parent=styles["Normal"], fontName="Times-Roman", fontSize=10.5, leading=14, textColor=INK, alignment=TA_JUSTIFY, spaceAfter=5)
alert = ParagraphStyle("alert", parent=body, textColor=ALERT, fontName="Times-Bold")
small = ParagraphStyle("small", parent=body, fontSize=9, leading=12, textColor=MUTED, alignment=TA_LEFT)
cell = ParagraphStyle("cell", parent=body, fontSize=8.5, leading=11, spaceAfter=0, alignment=TA_LEFT)
cellb = ParagraphStyle("cellb", parent=cell, fontName="Times-Bold")


def P(t, s=body):
    return Paragraph(t, s)


def table(headers, rows, widths):
    data = [[P(h, cellb) for h in headers]]
    for row in rows:
        data.append([P(c, cell) for c in row])
    t = Table(data, colWidths=widths, repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), NAVY),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F7F4EE")]),
        ("GRID", (0, 0), (-1, -1), 0.3, RULE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    return t


def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(GOLD)
    canvas.setLineWidth(1.4)
    canvas.line(18 * mm, A4[1] - 14 * mm, A4[0] - 18 * mm, A4[1] - 14 * mm)
    canvas.setFillColor(NAVY)
    canvas.setFont("Times-Bold", 8)
    canvas.drawString(18 * mm, A4[1] - 12 * mm, "JAC T8 2023  ·  ficha para taller  ·  solo lectura")
    canvas.setStrokeColor(RULE)
    canvas.line(18 * mm, 14 * mm, A4[0] - 18 * mm, 14 * mm)
    canvas.setFillColor(MUTED)
    canvas.setFont("Times-Roman", 8)
    canvas.drawString(18 * mm, 9 * mm, "No autoriza a circular. No borrar DTC antes de copiar. No probar el corte en la calle.")
    canvas.drawRightString(A4[0] - 18 * mm, 9 * mm, str(doc.page))
    canvas.restoreState()


w = A4[0] - 36 * mm
story = []
story.append(Spacer(1, 4 * mm))
story.append(P("Informe para el mecánico — JAC T8", h1))
story.append(P("Unidad declarada por el dueño: JAC T8, año 2023. Sesión OBD <b>2026-11-11T2233-288</b>. Teléfono Xiaomi, adaptador OBDBLE (ATI ELM327 v2.1, texto imitable). Protocolo A6 CAN 11/500. ECU 7E8.", body))
story.append(P("ALERTA: el conductor reporta corte de potencia/energía al frenar en marcha. No probar ese corte en la vía. Leer y medir con la camioneta quieta.", alert))

story.append(P("1. Qué debe hacer el taller, en orden", h2))
for i, t in enumerate([
    "Conectar escáner JAC. Leer almacenados, pendientes y permanentes <b>sin borrar</b>.",
    "Confirmar si aparecen <b>P0579</b> y <b>P2299</b>. Anotar el texto exacto del escáner de marca.",
    "Leer freeze frame / cuadro congelado y comparar con 7E8054202000579 (PID 02 → P0579 en esta app).",
    "Contacto ON, motor en ralentí o según procedimiento JAC: medir interruptor de freno suelto y pisado.",
    "Medir las dos pistas del pedal de acelerador (APP) sin pisar y pisando. No usar 750 mV de la N56.",
    "Multímetro en batería y masas. Ignorar ATRV 16,9 V del adaptador como medición de batería.",
    "Si se ajusta o cambia una pieza: repetir lectura DTC + medidores de la app (pestaña Monitor) y guardar PDF de después.",
], 1):
    story.append(P(f"<b>{i}.</b>  {t}", body))

story.append(P("2. Códigos leídos en esta unidad", h2))
story.append(table(
    ["Código", "Origen", "Definición usada aquí", "Qué NO hacer"],
    [
        ["P0579", "Modo 03, bytes 05 79", "Genérica: entrada multifunción del crucero / a menudo interruptor que corta con el freno", "No condenar el interruptor solo por el número"],
        ["P2299", "Modo 03, bytes 22 99", "Genérica: freno y acelerador incompatibles", "No aplicar voltajes de otro modelo JAC"],
        ["Permanentes", "7F 0A 13", "La ECU rechazó el modo 0A", "No interpretar el rechazo como «sin fallas»"],
        ["MIL genérico", "0101 apagado", "No explica la luz EPC", "No decir que el vehículo está sano"],
    ],
    [28 * mm, 38 * mm, w - 100 * mm, 34 * mm],
))

story.append(P("3. Sensores de la misma sesión (ventana 755 ms)", h2))
story.append(P("Ojo: el CSV antiguo rotuló el PID 0x11 como «acelerador». En la app 1.x ese PID es <b>posición absoluta de mariposa</b>, no el pedal.", small))
story.append(table(
    ["Orden", "PID", "Señal correcta", "Valor", "Raw", "Lectura"],
    [
        ["1", "0x0C", "RPM", "786,75 rpm", "0C 4B", "Motor en marcha / ralentí"],
        ["2", "0x05", "Refrigerante", "53 °C", "5D", "Normal en calentamiento"],
        ["3", "0x04", "Carga calculada", "23,5 %", "3C", "Carga baja en vacío"],
        ["4", "0x0D", "Velocidad", "0 km/h", "00", "Detenido"],
        ["5", "0x11", "Mariposa absoluta (NO pedal)", "89,8 %", "E5", "Plausible pero raro a ralentí: revisar mapeo; no auto-falla"],
        ["6", "0x0B", "MAP", "102 kPa", "66", "Cerca de atmósfera / sin carga"],
        ["—", "ATRV", "Estimación del dongle", "16,9 V", "ATRV", "REQUIRES_INDEPENDENT_MEASUREMENT"],
    ],
    [16 * mm, 18 * mm, 48 * mm, 28 * mm, 22 * mm, w - 132 * mm],
))

story.append(P("4. Hipótesis abierta (no demostrada)", h2))
story.append(P("La coincidencia entre el relato (corte al frenar) y P2299+P0579 apunta a desacuerdo freno/acelerador o al interruptor asociado al crucero/freno. Eso <b>no</b> demuestra la pieza. Tampoco demuestra un ataque informático.", body))

story.append(P("5. Cómo controlar un ajuste", h2))
story.append(P("Antes del trabajo: PDF + Monitor en la app (valores en orden). Después del ajuste: misma lectura quieta, mismos PID, nuevo PDF. Comparar DTC (¿siguen?), mariposa 0x11, APP 0x49/0x4A/0x4B si aparecen, RPM y velocidad 0. Si el corte al frenar sigue, no entregar el vehículo para circular.", body))

story.append(P("6. Integridad", h2))
facts = "sesion=2026-11-11T2233-288;dtc=P0579,P2299;freeze_hint=P0579;rpm=786.75;speed=0;pid11=89.8%_throttle_not_pedal;atrv=16.9_requires_multimeter;power_loss_on_brake=declared"
fh = sha256(facts.encode()).hexdigest()
story.append(P(f"Huella de hechos (SHA-256): {fh}", ParagraphStyle("hash", parent=small, fontName="Courier", fontSize=7.5, textColor=INK)))
story.append(P("La huella del archivo PDF está en el .sha256 al lado. VIN omitido en este papel.", small))

doc = SimpleDocTemplate(str(OUT), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=20 * mm, bottomMargin=20 * mm,
                        title="Informe mecanico JAC T8", author="Lectura local")
doc.build(story, onFirstPage=footer, onLaterPages=footer)
digest = sha256(OUT.read_bytes()).hexdigest()
SIDE.write_text(f"{digest}  {OUT.name}\n", encoding="utf-8")
print(OUT)
print(digest)
