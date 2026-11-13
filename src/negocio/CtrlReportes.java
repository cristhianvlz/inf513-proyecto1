package src.negocio;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import src.modelo.Adjunto;
import src.modelo.OfertaAcademica;
import src.modelo.Personal;
import src.modelo.Programa;
import src.persistencia.ActaDAO;
import src.persistencia.EnmiendaDAO;
import src.persistencia.PersonalDAO;
import src.persistencia.ProgramaDAO;
import src.persistencia.ReporteDAO;
import src.persistencia.ReporteDAO.FiltroPagos;
import src.utilidades.GeneradorHTML;
import src.utilidades.Grafico;
import src.utilidades.HojaExcel;
import src.utilidades.Reporte;
import src.utilidades.ReportePDF;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-08: REPORTES Y ESTADÍSTICAS
// Caso de uso de solo consulta: ningún reporte registra, modifica ni elimina datos (el CRUD no aplica).
// Cada reporte llega en el cuerpo del correo con cifras destacadas, gráficos de barras y tablas, y trae adjuntos
// el mismo reporte en PDF (para imprimir o archivar) y en Excel (para seguir trabajando los datos).
// Sin IDs: la oferta es programa + gestión + grupo y la asignación, oferta + módulo. Si la consulta no arroja datos
// se informa, y un parámetro inválido recibe el formato correcto.
// Nombres: REPING = REPorte de INGresos, REPACAD = REPorte ACADémico, REPDEM = REPorte de DEManda,
// REPNOTAS = REPorte de NOTAS, REPCERT = REPorte de CERTificados, REPLOG = REPorte del LOG (bitácora)
// =========================================================================
public class CtrlReportes extends CtrlModulo {

    private static final int MAXIMO_FILAS_LOG = 500;

    private static final String SINTAXIS_REPING = "REPING[\"periodo\"] | REPING[\"programa\"] | REPING[\"periodo\", \"programa\"] | "
            + "REPING[\"programa\", \"gestion\", \"grupo\"] | REPING[\"mes o fecha\", \"programa\", \"gestion\", \"grupo\"] "
            + "(periodo: gestión 2-2026, mes 2026-11 o fecha 2026-11-09)";

    public CtrlReportes(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Reporte financiero (Jefe Administrativo Financiero, Director)
    // ---------------------------------------------------------------------

    // REPING: recaudación conciliada. El periodo y el programa u oferta son opcionales, pero se indica al menos uno:
    //   REPING["2-2026"] gestión, por mes      | REPING["2026-11"] mes, por día      | REPING["2026-11-09"] un día
    //   REPING["TAC"] programa, todas sus gestiones, por mes | REPING["2-2026", "TAC"] o REPING["TAC", "2-2026"]
    //   REPING["TAC", "2-2026", "A"] una oferta, por mes      | REPING["2026-11", "TAC"] | REPING["2026-11", "TAC", "2-2026", "A"]
    // Con una gestión cuenta la gestión de la oferta del pago; con un mes o una fecha, la fecha del depósito
    public String ingresos(String[] p) {
        List<String> v = new ArrayList<>();
        for (String s : p) {
            if (s != null && !s.trim().isEmpty()) {
                v.add(s.trim());
            }
        }
        if (v.isEmpty()) {
            return formato(SINTAXIS_REPING);
        }
        return enTransaccion(con -> {
            // 1. Periodo (opcional, siempre primero)
            String gestion = "*";
            LocalDate desde = null;
            LocalDate hasta = null;
            boolean porDia = false;
            String cuando = null;
            String archivoCuando = null;
            int i = 0;
            String primero = v.get(0);
            if (primero.matches("\\d-\\d{4}")) {
                gestion = primero.toUpperCase();
                cuando = "gestión " + gestion;
                archivoCuando = gestion;
                i = 1;
            } else if (primero.matches("\\d{4}-\\d{1,2}")) {
                YearMonth mes = mes(primero);
                desde = mes.atDay(1);
                hasta = mes.atEndOfMonth();
                porDia = true;
                cuando = "mes " + mes;
                archivoCuando = mes.toString();
                i = 1;
            } else if (primero.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                LocalDate dia = fecha(primero, "fecha");
                desde = dia;
                hasta = dia;
                porDia = true;
                cuando = "día " + dia;
                archivoCuando = dia.toString();
                i = 1;
            } else if (primero.matches("[\\d/.\\-]+")) {
                throw new ReglaNegocioException("<b>" + escapar(primero) + "</b> no es un periodo válido: use una gestión (2-2026), un mes (2026-11) "
                        + "o una fecha (2026-11-09). Formato correcto: <code>" + SINTAXIS_REPING + "</code>");
            }

            // 2. Programa, programa + gestión u oferta (opcional)
            int resto = v.size() - i;
            Programa programa = null;
            OfertaAcademica oferta = null;
            if (resto == 1) {
                programa = programa(con, v.get(i));
            } else if (resto == 2) {
                if (!"*".equals(gestion)) {
                    throw new ReglaNegocioException("La gestión ya está indicada. Para un programa en una gestión envíe "
                            + "<code>REPING[\"" + gestion + "\", \"" + escapar(v.get(i).toUpperCase()) + "\"]</code>; para una oferta, "
                            + "<code>REPING[\"programa\", \"gestion\", \"grupo\"]</code>.");
                }
                programa = programa(con, v.get(i));
                String g = v.get(i + 1);
                if (!g.matches("\\d-\\d{4}")) {
                    throw new ReglaNegocioException("<b>" + escapar(g) + "</b> no es una gestión (formato 2-2026). Para una oferta indique también el grupo: "
                            + "<code>REPING[\"" + programa.getCodigo() + "\", \"2-2026\", \"A\"]</code>.");
                }
                gestion = g.toUpperCase();
                cuando = "gestión " + gestion;
                archivoCuando = (archivoCuando == null ? "" : archivoCuando + "_") + gestion;
            } else if (resto == 3) {
                if (!"*".equals(gestion)) {
                    throw new ReglaNegocioException("La oferta ya incluye su gestión: envíe la oferta sola, <code>REPING[\"programa\", \"gestion\", \"grupo\"]</code>, "
                            + "o antes un mes o una fecha: <code>REPING[\"2026-11\", \"programa\", \"gestion\", \"grupo\"]</code>.");
                }
                oferta = buscarOferta(con, v.toArray(new String[0]), i);
            } else if (resto > 3) {
                throw new ReglaNegocioException("Sobran parámetros. Formato correcto: <code>" + SINTAXIS_REPING + "</code>");
            }

            String quien = oferta != null ? "Oferta " + oferta.getCodigoPrograma() + " " + oferta.getGestion() + " grupo " + oferta.getGrupo()
                    : programa != null ? "Programa " + programa.getCodigo() + " - " + programa.getNombre() : "Todos los programas";
            String alcance = quien + (cuando != null ? " · " + cuando : oferta == null ? " · todas las gestiones" : "");

            // 3. Consultas
            ReporteDAO dao = new ReporteDAO(con);
            FiltroPagos f = new FiltroPagos(gestion, programa != null ? programa.getIdPrograma() : 0, oferta != null ? oferta.getIdOferta() : 0,
                    desde, hasta);
            Tabla detalle = dao.recaudacion(porDia ? "DIA" : "MES", f);
            Tabla estados = dao.pagosPorEstado(f);
            Tabla convenios = dao.recaudacionPorConvenio(f);
            log(con, "PAGO", "SELECT");

            int estudiantes = 0;
            int pendientes = 0;
            int observados = 0;
            BigDecimal montoPendiente = BigDecimal.ZERO;
            BigDecimal montoObservado = BigDecimal.ZERO;
            for (String[] e : estados.getFilas()) {
                switch (e[0]) {
                    case "CONCILIADO" -> estudiantes = Integer.parseInt(e[3]);
                    case "PENDIENTE" -> {
                        pendientes = Integer.parseInt(e[1]);
                        montoPendiente = numero(e[2]);
                    }
                    case "OBSERVADO" -> {
                        observados = Integer.parseInt(e[1]);
                        montoObservado = numero(e[2]);
                    }
                    default -> {
                    }
                }
            }
            String avisoPendientes = (pendientes > 0 ? " Hay " + pendientes + " pago(s) PENDIENTE(S) por Bs " + montoPendiente
                    + " que todavía no cuentan como ingreso: revíselos con <code>LISPAGO[\"PENDIENTE\"]</code> y concílielos con "
                    + "<code>CONCPAGO[\"numero_transaccion\"]</code>." : "")
                    + (observados > 0 ? " Hay " + observados + " pago(s) OBSERVADO(S) por Bs " + montoObservado
                            + ": corríjalos con <code>MODPAGO</code> (<code>LISPAGO[\"OBSERVADO\"]</code>)." : "");
            if (detalle.estaVacia()) {
                return sinDatos("No hay pagos conciliados en: " + escapar(alcance) + "." + avisoPendientes);
            }

            BigDecimal total = suma(detalle, 6);
            int pagos = suma(detalle, 5).intValue();
            Tabla porPeriodo = agrupar(detalle, new int[] { 0 }, List.of(porDia ? "dia" : "mes"), total, false);
            Tabla porOferta = agrupar(detalle, new int[] { 1, 2 }, List.of("programa", "oferta"), total, true);
            Tabla porConcepto = agrupar(detalle, new int[] { 3 }, List.of("concepto"), total, true);
            BigDecimal descuento = suma(convenios, 4);
            int beneficiarios = 0;
            boolean hayConvenios = false;
            for (String[] c : convenios.getFilas()) {
                if (!"Sin convenio".equals(c[0])) {
                    hayConvenios = true;
                    beneficiarios += Integer.parseInt(c[1]);
                }
            }

            Reporte r = new Reporte("REPING", "Reporte de ingresos: recaudación conciliada", alcance)
                    .generadoPor(dao.solicitante(idRemitente))
                    .archivo(oferta != null ? oferta.getCodigoPrograma() + "_" + oferta.getGestion() + "_" + oferta.getGrupo()
                            : programa != null ? programa.getCodigo() : "", archivoCuando)
                    .indicador("Recaudado (conciliado)", "Bs " + total, pagos + " pago(s) conciliado(s)")
                    .indicador("Estudiantes que pagaron", String.valueOf(estudiantes), "con al menos un pago conciliado")
                    .indicador("Promedio por pago", "Bs " + total.divide(new BigDecimal(pagos), 2, RoundingMode.HALF_UP), "recaudado / pagos")
                    .indicador("Descuento por convenios", "Bs " + descuento, beneficiarios + " beneficiario(s) con convenio")
                    .indicador("Por conciliar", "Bs " + montoPendiente.add(montoObservado).setScale(2, RoundingMode.HALF_UP), pendientes + " pendiente(s) y " + observados
                            + " observado(s)");

            // Evolución en el tiempo: los meses (o días) sin pagos también aparecen, en cero
            Grafico evolucion = new Grafico("Recaudación por " + (porDia ? "día" : "mes") + " (Bs)", Grafico.Tipo.COLUMNAS, "Bs")
                    .serie("Recaudado", Grafico.AZUL);
            Map<String, Double> montos = new TreeMap<>();
            for (String[] fila : sinTotal(porPeriodo)) {
                montos.put(fila[0], numero(fila[2]).doubleValue());
            }
            for (String periodo : periodos(montos.keySet(), porDia, desde, hasta)) {
                evolucion.categoria(porDia ? periodo.substring(8) + "/" + periodo.substring(5, 7) : periodo, montos.getOrDefault(periodo, 0.0));
            }
            r.grafico(evolucion);
            r.grafico(barras("Recaudación por concepto de arancel (Bs)", porConcepto, fila -> fila[0], 2));
            if (sinTotal(porOferta).size() > 1) {
                r.grafico(barras("Recaudación por oferta (Bs)", porOferta, fila -> fila[0] + " " + fila[1], 3));
            }
            if (hayConvenios) {
                Grafico g = new Grafico("Cobrado y descuento por convenio (Bs)", Grafico.Tipo.BARRAS, "Bs").agrupado()
                        .serie("Cobrado", Grafico.AZUL).serie("Descuento por convenio", Grafico.NARANJA);
                for (String[] c : convenios.getFilas()) {
                    g.categoria(c[0], numero(c[5]).doubleValue(), numero(c[4]).doubleValue());
                }
                r.grafico(g);
            }

            agregarTotal(convenios, 1, 6);
            agregarTotal(detalle, 5, 7);
            r.seccion("Recaudación por " + (porDia ? "día" : "mes"), porPeriodo)
                    .seccion("Recaudación por oferta", porOferta)
                    .seccion("Recaudación por concepto de arancel", porConcepto)
                    .seccion("Recaudación por convenio", convenios, "Descuento = monto del arancel menos el monto cobrado. Los beneficiarios son "
                            + "los estudiantes con algún pago conciliado en el convenio.")
                    .seccion("Detalle por " + (porDia ? "día" : "mes") + ", oferta, concepto y convenio", detalle);
            if (pendientes + observados > 0) {
                r.seccion("Estado de todos los pagos del mismo alcance", estados, "Solo los CONCILIADOS son ingreso; los PENDIENTE y OBSERVADO "
                        + "todavía no se verificaron contra el extracto bancario.");
            }
            r.nota("Solo cuentan como ingreso los pagos CONCILIADOS, es decir, verificados contra el extracto bancario.");
            r.nota(cuando != null && cuando.startsWith("gestión")
                    ? "Con una gestión, cada pago cuenta en la gestión de su oferta y se agrupa por el mes de su depósito."
                    : "Se toma la fecha del depósito de cada pago.");
            if (!avisoPendientes.isEmpty()) {
                r.nota(avisoPendientes.trim());
            }
            return entregar(r);
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Reportes académicos (Coordinación Académica, Director)
    // ---------------------------------------------------------------------

    // REPACAD[programa, gestion, grupo] | REPACAD[gestion]: estados de las inscripciones y resultados por módulo
    public String academico(String[] p) {
        String sintaxis = "REPACAD[" + OFERTA + "] o REPACAD[\"gestion (2-2026)\"]";
        if (Validador.vacio(param(p, 0))) {
            return formato(sintaxis);
        }
        return enTransaccion(con -> {
            int idOferta = 0;
            String gestion = "*";
            String titulo;
            String archivo;
            if (p.length >= 3) {
                OfertaAcademica o = buscarOferta(con, p, 0);
                idOferta = o.getIdOferta();
                titulo = "Oferta " + o.getCodigoPrograma() + " " + o.getGestion() + " grupo " + o.getGrupo();
                archivo = o.getCodigoPrograma() + "_" + o.getGestion() + "_" + o.getGrupo();
            } else if (p[0].trim().matches("\\d+-\\d{4}")) {
                gestion = p[0].trim().toUpperCase();
                titulo = "Gestión " + gestion;
                archivo = gestion;
            } else {
                throw new ReglaNegocioException("Indique la oferta o la gestión. Formato correcto: <code>" + sintaxis + "</code>");
            }
            ReporteDAO dao = new ReporteDAO(con);
            Tabla inscripciones = dao.inscripcionesPorOferta(idOferta, gestion);
            Tabla modulos = dao.resultadosPorModulo(idOferta, gestion);
            log(con, "INSCRIPCION", "SELECT");
            log(con, "INSCRIPCION_MODULO", "SELECT");
            if (inscripciones.estaVacia()) {
                return sinDatos("No hay ofertas en la " + titulo.toLowerCase() + ".");
            }
            int totalInscripciones = suma(inscripciones, 8).intValue();
            int aprobados = suma(modulos, 4).intValue();
            int conResultado = aprobados + suma(modulos, 5).intValue() + suma(modulos, 6).intValue();
            boolean variasOfertas = inscripciones.getFilas().size() > 1;

            Reporte r = new Reporte("REPACAD", "Reporte académico: inscripciones y resultados", titulo)
                    .generadoPor(dao.solicitante(idRemitente)).archivo(archivo)
                    .indicador("Ofertas", String.valueOf(inscripciones.getFilas().size()), "en el alcance del reporte")
                    .indicador("Inscripciones", String.valueOf(totalInscripciones), suma(inscripciones, 3).intValue() + " inscrito(s) y "
                            + suma(inscripciones, 2).intValue() + " preinscrito(s)")
                    .indicador("Concluidos", String.valueOf(suma(inscripciones, 4).intValue()), "terminaron la malla")
                    .indicador("Aprobación", conResultado == 0 ? "-" : porcentaje(aprobados, conResultado), aprobados + " aprobado(s) de "
                            + conResultado + " con resultado")
                    .indicador("Abandonos y retiros", String.valueOf(suma(inscripciones, 5).intValue() + suma(inscripciones, 6).intValue()),
                            "de las inscripciones");

            r.grafico(apilado("Estado de las inscripciones por oferta", inscripciones, fila -> fila[0],
                    new String[] { "Preinscritos", "Inscritos", "Concluidos", "Abandonos", "Retirados", "Traspasados" },
                    new int[] { 2, 3, 4, 5, 6, 7 },
                    new String[] { Grafico.AZUL, Grafico.NARANJA, Grafico.AGUAMARINA, Grafico.AMARILLO, Grafico.MAGENTA, "#008300" }));
            r.grafico(apilado("Resultados por módulo (último intento de cada estudiante)", modulos,
                    fila -> variasOfertas ? fila[0] + " · " + fila[1].split(" ")[0] : fila[1],
                    new String[] { "Aprobados", "Reprobados", "Pérdida por inasistencia", "Abandonos", "En curso" },
                    new int[] { 4, 5, 6, 7, 3 },
                    new String[] { Grafico.BIEN, Grafico.CRITICO, Grafico.SERIO, Grafico.ALERTA, Grafico.NEUTRO }));

            if (variasOfertas) {
                agregarTotal(inscripciones, 2, 9);
            }
            if (modulos.getFilas().size() > 1) {
                agregarTotal(modulos, 2, 8);
            }
            r.seccion("Estado de las inscripciones", inscripciones)
                    .seccion("Resultados por módulo (último intento de cada estudiante)", modulos,
                            "Aprobación = aprobados / (aprobados + reprobados + pérdidas por inasistencia). El promedio no cuenta a quienes siguen EN_CURSO.");
            r.nota("Quien reprobó y aprobó el recuperatorio cuenta una sola vez, como aprobado.");
            return entregar(r);
        });
    }

    // REPDEM[programa, gestion, grupo] | REPDEM: preinscritos frente al cupo mínimo para confirmar o postergar la oferta.
    // Sin oferta, todas las que esperan esa decisión
    public String demanda(String[] p) {
        return enTransaccion(con -> {
            int idOferta = 0;
            String alcance = "Ofertas que esperan confirmarse o postergarse";
            String archivo = "PENDIENTES";
            if (!Validador.vacio(param(p, 0)) && !"*".equals(p[0].trim())) {
                if (p.length < 3) {
                    throw new ReglaNegocioException("Formato correcto: <code>REPDEM[" + OFERTA + "]</code> para una oferta, o <code>REPDEM</code> "
                            + "para todas las que esperan confirmarse o postergarse.");
                }
                OfertaAcademica o = buscarOferta(con, p, 0);
                idOferta = o.getIdOferta();
                alcance = "Oferta " + o.getCodigoPrograma() + " " + o.getGestion() + " grupo " + o.getGrupo();
                archivo = o.getCodigoPrograma() + "_" + o.getGestion() + "_" + o.getGrupo();
            }
            ReporteDAO dao = new ReporteDAO(con);
            Tabla datos = dao.demanda(idOferta);
            log(con, "OFERTA_ACADEMICA", "SELECT");
            if (datos.estaVacia()) {
                return sinDatos("No hay ofertas en PLANIFICADA, PREINSCRIPCIONES_ABIERTAS ni POSTERGADA que esperen decidirse.");
            }
            List<String[]> filas = new ArrayList<>();
            int confirmar = 0;
            int postergar = 0;
            int decididas = 0;
            Grafico g = new Grafico("Preinscritos frente al cupo mínimo", Grafico.Tipo.BARRAS, "").agrupado()
                    .serie("Preinscritos", Grafico.AZUL).serie("Cupo mínimo", Grafico.NARANJA);
            for (String[] f : datos.getFilas()) {
                String referencia = "\"" + f[1] + "\", \"" + f[2] + "\", \"" + f[3] + "\"";
                int dias = Integer.parseInt(f[6]);
                int minimo = Integer.parseInt(f[7]);
                Integer maximo = f[8] == null ? null : Integer.parseInt(f[8]);
                int preinscritos = Integer.parseInt(f[9]);
                String[] decision = decision(f[4], dias, minimo, maximo, preinscritos, referencia);
                confirmar += decision[1].contains("CONFIRMADA") ? 1 : 0;
                postergar += decision[1].contains("POSTERGADA") ? 1 : 0;
                decididas += decision[0].startsWith("Oferta ") ? 1 : 0;
                filas.add(new String[] { f[0], f[4], f[5], String.valueOf(dias), f[7], f[8], f[9],
                        String.valueOf(Math.max(0, minimo - preinscritos)), decision[0], decision[1] });
                g.categoria(f[0], preinscritos, minimo);
            }
            Tabla t = Tabla.de(List.of("oferta", "estado", "inicio", "dias al inicio", "cupo minimo", "cupo maximo", "preinscritos",
                    "faltan", "situacion", "comando sugerido"), filas).numericas(3, 4, 5, 6, 7);
            Reporte r = new Reporte("REPDEM", "Reporte de demanda: preinscritos frente al cupo mínimo", alcance)
                    .generadoPor(dao.solicitante(idRemitente)).archivo(archivo)
                    .indicador("Ofertas", String.valueOf(filas.size()), "en el reporte")
                    .indicador("Pueden confirmarse", String.valueOf(confirmar), "alcanzaron el cupo mínimo")
                    .indicador("Conviene postergar", String.valueOf(postergar), "no alcanzan y están por iniciar")
                    .indicador("En espera", String.valueOf(filas.size() - confirmar - postergar - decididas), "aún sin cupo, pero hay tiempo");
            if (decididas > 0) {
                r.indicador("Ya decididas", String.valueOf(decididas), "confirmadas, en curso o cerradas");
            }
            r.grafico(g)
                    .seccion("Demanda frente al cupo mínimo", t, "Preinscritos = PREINSCRITO + INSCRITO, la misma cuenta que exige MODOFER para confirmar.")
                    .nota("El comando sugerido se envía tal cual en el asunto para confirmar o postergar la oferta.");
            return entregar(r);
        });
    }

    // ---------------------------------------------------------------------
    // Flujo C - Notas procesadas (CPD)
    // ---------------------------------------------------------------------

    // REPNOTAS[programa, gestion, grupo, modulo]: actas del módulo, sus notas y las enmiendas realizadas
    public String notas(String[] p) {
        if (p.length < 4) {
            return formato("REPNOTAS[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            ReporteDAO dao = new ReporteDAO(con);
            Tabla actas = new ActaDAO(con).listar(a.id());
            Tabla notas = dao.notasDeAsignacion(a.id());
            Tabla enmiendas = new EnmiendaDAO(con).listarPorAsignacion(a.id());
            log(con, "ACTA_CALIFICACION", "SELECT");
            log(con, "ENMIENDA_NOTA", "SELECT");
            Personal capacitador = new PersonalDAO(con).buscarPorId(a.datos.getIdPersonal());
            int aprobados = 0;
            int conResultado = 0;
            BigDecimal suma = BigDecimal.ZERO;
            Set<String> estudiantes = new HashSet<>();
            Grafico g = new Grafico("Nota final por estudiante", Grafico.Tipo.BARRAS, "").serie("Nota final", Grafico.AZUL)
                    .referencia(ResultadosModulo.NOTA_DE_APROBACION.doubleValue(), "Nota mínima de aprobación: " + ResultadosModulo.NOTA_DE_APROBACION);
            for (String[] f : notas.getFilas()) {
                estudiantes.add(f[0]);
                if (f[6] != null && !"EN_CURSO".equals(f[7])) {
                    conResultado++;
                    suma = suma.add(new BigDecimal(f[6]));
                    aprobados += "APROBADO".equals(f[7]) ? 1 : 0;
                }
                if (f[6] != null) {
                    g.categoria(f[2] + ("1".equals(f[3]) ? "" : " (intento " + f[3] + ")"), new BigDecimal(f[6]).doubleValue());
                }
            }
            String promedio = conResultado == 0 ? "-" : suma.divide(new BigDecimal(conResultado), 2, RoundingMode.HALF_UP).toPlainString();
            String modulo = a.modulo.getCodigo() + " " + a.modulo.getNombre();
            String oferta = a.oferta.getCodigoPrograma() + " " + a.oferta.getGestion() + " grupo " + a.oferta.getGrupo();
            Reporte r = new Reporte("REPNOTAS", "Reporte de notas procesadas", "Módulo " + modulo + " · oferta " + oferta)
                    .generadoPor(dao.solicitante(idRemitente))
                    .archivo(a.oferta.getCodigoPrograma(), a.oferta.getGestion(), a.oferta.getGrupo(), a.modulo.getCodigo())
                    .indicador("Estudiantes", String.valueOf(estudiantes.size()), notas.getFilas().size() + " registro(s), contando recuperatorios")
                    .indicador("Aprobados", String.valueOf(aprobados), "de " + conResultado + " con resultado")
                    .indicador("Promedio", promedio, conResultado == 0 ? "todavía sin resultados" : "de los resultados finales")
                    .indicador("Actas", String.valueOf(actas.getFilas().size()), estadoUltimaActa(actas))
                    .indicador("Enmiendas", String.valueOf(enmiendas.getFilas().size()), "notas corregidas con resolución")
                    .grafico(g)
                    .seccion("Actas del módulo", actas)
                    .seccion("Notas finales", notas)
                    .seccion("Enmiendas realizadas", enmiendas);
            if (capacitador != null) {
                r.nota("Capacitador del módulo: " + escapar(capacitador.getNombreCompleto()) + ".");
            }
            r.nota("Una nota cuenta como oficial cuando su acta está CERRADA; un acta ANULADA se reemplaza por la siguiente.");
            return entregar(r);
        });
    }

    // ---------------------------------------------------------------------
    // Flujo D - Títulos emitidos (Director)
    // ---------------------------------------------------------------------

    // REPCERT[gestion, semestre] (REPCERT["2026", "2"] o REPCERT["2-2026"]): documentos emitidos en el semestre,
    // para el informe semestral al Decano. Semestre 1: enero a junio; semestre 2: julio a diciembre
    public String certificados(String[] p) {
        String sintaxis = "REPCERT[\"gestion (2026)\", \"semestre (1 o 2)\"] o REPCERT[\"2-2026\"]";
        String a = param(p, 0).trim();
        String b = param(p, 1).trim();
        int anio;
        int semestre;
        if (a.matches("[12]-\\d{4}") && b.isEmpty()) {
            semestre = a.charAt(0) - '0';
            anio = Integer.parseInt(a.substring(2));
        } else if (a.matches("\\d{4}") && b.matches("[12]")) {
            anio = Integer.parseInt(a);
            semestre = Integer.parseInt(b);
        } else if (a.isEmpty()) {
            return formato(sintaxis);
        } else {
            return GeneradorHTML.mensajeError("Parámetro con formato inválido: la gestión es un año de 4 dígitos y el semestre es 1 o 2. "
                    + "Formato correcto: <code>" + sintaxis + "</code>");
        }
        LocalDate desde = LocalDate.of(anio, semestre == 1 ? 1 : 7, 1);
        LocalDate hasta = semestre == 1 ? LocalDate.of(anio, 6, 30) : LocalDate.of(anio, 12, 31);
        return enTransaccion(con -> {
            ReporteDAO dao = new ReporteDAO(con);
            Tabla resumen = dao.resumenCertificados(desde, hasta);
            Tabla detalle = dao.certificados(desde, hasta);
            log(con, "CERTIFICADO", "SELECT");
            if (detalle.estaVacia()) {
                return sinDatos("No se emitieron títulos, certificados ni constancias en el semestre " + semestre + " de " + anio + " ("
                        + desde + " al " + hasta + ").");
            }
            Set<String> titulares = new HashSet<>();
            for (String[] f : detalle.getFilas()) {
                titulares.add(f[4]);
            }
            Reporte r = new Reporte("REPCERT", "Reporte de títulos y certificados emitidos",
                    "Semestre " + semestre + " de " + anio + " (" + desde + " al " + hasta + ")")
                    .generadoPor(dao.solicitante(idRemitente)).archivo(semestre + "-" + anio)
                    .indicador("Emitidos", String.valueOf(suma(resumen, 3).intValue()), "en el semestre")
                    .indicador("Vigentes", String.valueOf(suma(resumen, 1).intValue()), "válidos al verificarlos")
                    .indicador("Anulados", String.valueOf(suma(resumen, 2).intValue()), "con ANUCERT")
                    .indicador("Titulares", String.valueOf(titulares.size()), "estudiantes distintos")
                    .grafico(apilado("Documentos emitidos por tipo", resumen, fila -> fila[0], new String[] { "Vigentes", "Anulados" },
                            new int[] { 1, 2 }, new String[] { Grafico.BIEN, Grafico.CRITICO }));
            agregarTotal(resumen, 1, 4);
            r.seccion("Resumen por tipo", resumen)
                    .seccion("Detalle de los documentos emitidos", detalle)
                    .nota("Informe semestral para el Decano. Semestre 1: enero a junio; semestre 2: julio a diciembre, según la fecha de emisión.");
            return entregar(r);
        });
    }

    // ---------------------------------------------------------------------
    // Flujo E - Bitácora (exclusivo del Director)
    // ---------------------------------------------------------------------

    // REPLOG[fecha_inicio, fecha_fin]: acciones de LOG con usuario, tabla, acción y fecha. Con una sola fecha, ese día
    public String bitacora(String[] p) {
        if (Validador.vacio(param(p, 0))) {
            return formato("REPLOG[\"fecha_inicio (AAAA-MM-DD)\", \"fecha_fin (AAAA-MM-DD)\"]");
        }
        return enTransaccion(con -> {
            LocalDate desde = fecha(p[0], "fecha_inicio");
            LocalDate hasta = Validador.vacio(param(p, 1)) ? desde : fecha(p[1], "fecha_fin");
            if (hasta.isBefore(desde)) {
                throw new ReglaNegocioException("La <b>fecha_fin</b> (" + hasta + ") es anterior a la <b>fecha_inicio</b> (" + desde + ").");
            }
            ReporteDAO dao = new ReporteDAO(con);
            Tabla resumen = dao.resumenBitacora(desde, hasta);
            Tabla detalle = dao.bitacora(desde, hasta, MAXIMO_FILAS_LOG);
            Tabla porDia = dao.bitacoraPorDia(desde, hasta);
            log(con, "LOG", "SELECT");
            if (detalle.estaVacia()) {
                return sinDatos("La bitácora no tiene acciones registradas del " + desde + " al " + hasta + ".");
            }
            int total = suma(resumen, 2).intValue();
            String rango = desde.equals(hasta) ? "Día " + desde : "Del " + desde + " al " + hasta;
            Reporte r = new Reporte("REPLOG", "Reporte de la bitácora (LOG)", rango)
                    .generadoPor(dao.solicitante(idRemitente)).archivo(desde.toString(), desde.equals(hasta) ? "" : hasta.toString())
                    .indicador("Acciones", String.valueOf(total), "registradas en el LOG")
                    .indicador("Usuarios", String.valueOf(resumen.getFilas().size()), "con alguna acción")
                    .indicador("Registros", String.valueOf(suma(resumen, 3).intValue()), "INSERT")
                    .indicador("Modificaciones", String.valueOf(suma(resumen, 4).intValue()), "UPDATE")
                    .indicador("Eliminaciones", String.valueOf(suma(resumen, 5).intValue()), "DELETE")
                    .indicador("Rechazados", String.valueOf(suma(resumen, 7).intValue()), "intentos sin permiso o inválidos");

            Map<String, Double> acciones = new TreeMap<>();
            for (String[] f : porDia.getFilas()) {
                acciones.put(f[0], numero(f[1]).doubleValue());
            }
            if (ChronoUnit.DAYS.between(desde, hasta) <= 62) {
                Grafico g = new Grafico("Acciones por día", Grafico.Tipo.COLUMNAS, "").serie("Acciones", Grafico.AZUL);
                for (String dia : periodos(acciones.keySet(), true, desde, hasta)) {
                    g.categoria(dia.substring(8) + "/" + dia.substring(5, 7), acciones.getOrDefault(dia, 0.0));
                }
                r.grafico(g);
            }
            r.grafico(apilado("Acciones por usuario y tipo", resumen, fila -> fila[0],
                    new String[] { "Registros", "Modificaciones", "Eliminaciones", "Consultas", "Rechazados" }, new int[] { 3, 4, 5, 6, 7 },
                    new String[] { Grafico.AZUL, Grafico.NARANJA, Grafico.AGUAMARINA, Grafico.AMARILLO, Grafico.MAGENTA }));
            agregarTotal(resumen, 2, 9);
            r.seccion("Acciones por usuario", resumen)
                    .seccion("Detalle de las acciones" + (total > MAXIMO_FILAS_LOG ? " (primeras " + MAXIMO_FILAS_LOG + ")" : ""), detalle,
                            total > MAXIMO_FILAS_LOG ? "Se muestran las primeras " + MAXIMO_FILAS_LOG + " de " + total
                                    + " acciones; acote el rango de fechas para ver el resto." : null)
                    .nota("La bitácora registra lo que hizo cada usuario del sistema; los comandos públicos de personas sin cuenta no se registran.");
            return entregar(r);
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    // El reporte va en el cuerpo del correo y, adjuntos, en PDF y en Excel. Si un archivo no se pudiera generar,
    // el reporte igual se envía en el cuerpo
    private String entregar(Reporte r) {
        String base = r.nombreArchivo();
        List<String> nombres = new ArrayList<>();
        try {
            adjuntarALaRespuesta(new Adjunto(base + ".pdf", "application/pdf", ReportePDF.generar(r)));
            nombres.add(base + ".pdf");
        } catch (IOException | RuntimeException e) {
            System.out.println(">>> [REPORTE]: No se pudo generar el PDF de " + r.getComando() + ": " + e);
        }
        try {
            adjuntarALaRespuesta(new Adjunto(base + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    HojaExcel.generar(r)));
            nombres.add(base + ".xlsx");
        } catch (IOException | RuntimeException e) {
            System.out.println(">>> [REPORTE]: No se pudo generar el Excel de " + r.getComando() + ": " + e);
        }
        return GeneradorHTML.reporte(r, nombres);
    }

    private Programa programa(java.sql.Connection con, String codigo) throws java.sql.SQLException {
        Programa pr = new ProgramaDAO(con).buscarPorCodigo(codigo);
        if (pr == null) {
            throw new ReglaNegocioException("No existe el programa <b>" + escapar(codigo.toUpperCase()) + "</b>. El primer parámetro es un periodo "
                    + "(gestión 2-2026, mes 2026-11 o fecha 2026-11-09) o el código de un programa (consulte <code>CATALOGO</code>). "
                    + "Formato correcto: <code>" + SINTAXIS_REPING + "</code>");
        }
        return pr;
    }

    // {situación, comando sugerido} de una oferta según sus preinscritos y los días que faltan para su inicio
    private static String[] decision(String estado, int dias, int minimo, Integer maximo, int preinscritos, String oferta) {
        String confirmar = "MODOFER[" + oferta + ", \"estado\", \"CONFIRMADA\"]";
        String postergar = "MODOFER[" + oferta + ", \"estado\", \"POSTERGADA\"]";
        if (!estado.equals("PREINSCRIPCIONES_ABIERTAS") && !estado.equals("POSTERGADA")) {
            return estado.equals("PLANIFICADA") ? new String[] { "Aún no abre preinscripciones", "APROFER[" + oferta + "]" }
                    : new String[] { "Oferta " + estado + ": no requiere decisión", "-" };
        }
        if (maximo != null && preinscritos >= maximo) {
            return new String[] { "CUPO LLENO: puede confirmarse", confirmar };
        }
        if (preinscritos >= minimo) {
            return new String[] { "ALCANZA el cupo mínimo: puede confirmarse", confirmar };
        }
        if (dias < 0) {
            return new String[] { "NO ALCANZA y ya debió iniciar: postergar o cancelar", postergar };
        }
        if (dias <= 3) {
            return new String[] { "NO ALCANZA y empieza en " + dias + " día(s): postergar o cancelar", postergar };
        }
        return new String[] { "Faltan " + (minimo - preinscritos) + " preinscrito(s); quedan " + dias + " día(s)", "-" };
    }

    private static YearMonth mes(String v) {
        try {
            String[] partes = v.split("-");
            return YearMonth.of(Integer.parseInt(partes[0]), Integer.parseInt(partes[1]));
        } catch (DateTimeException | NumberFormatException e) {
            throw new ReglaNegocioException("<b>" + v + "</b> no es un mes válido (el mes va de 01 a 12). Formato correcto: <code>"
                    + SINTAXIS_REPING + "</code>");
        }
    }

    // Todos los meses (o días) entre el primero y el último con datos; con un mes pedido, todos sus días
    private static List<String> periodos(Set<String> conDatos, boolean porDia, LocalDate desde, LocalDate hasta) {
        List<String> lista = new ArrayList<>();
        if (conDatos.isEmpty()) {
            return lista;
        }
        List<String> ordenados = new ArrayList<>(conDatos);
        if (porDia) {
            LocalDate inicio = desde != null ? desde : LocalDate.parse(ordenados.get(0));
            LocalDate fin = hasta != null ? hasta : LocalDate.parse(ordenados.get(ordenados.size() - 1));
            for (LocalDate d = inicio; !d.isAfter(fin); d = d.plusDays(1)) {
                lista.add(d.toString());
            }
        } else {
            YearMonth inicio = YearMonth.parse(ordenados.get(0));
            YearMonth fin = YearMonth.parse(ordenados.get(ordenados.size() - 1));
            for (YearMonth m = inicio; !m.isAfter(fin); m = m.plusMonths(1)) {
                lista.add(m.toString());
            }
        }
        return lista;
    }

    // Agrupa el detalle de REPING por las columnas clave sumando pagos (columna 5) y total (columna 6); agrega el
    // porcentaje del total y la fila TOTAL. porMonto ordena de mayor a menor recaudación
    private static Tabla agrupar(Tabla detalle, int[] claves, List<String> cabeceras, BigDecimal granTotal, boolean porMonto) {
        Map<String, String[]> grupos = new LinkedHashMap<>();
        Map<String, BigDecimal[]> sumas = new LinkedHashMap<>();
        for (String[] f : detalle.getFilas()) {
            StringBuilder clave = new StringBuilder();
            String[] valores = new String[claves.length];
            for (int k = 0; k < claves.length; k++) {
                valores[k] = f[claves[k]];
                clave.append(f[claves[k]]).append('\u0001');
            }
            grupos.putIfAbsent(clave.toString(), valores);
            BigDecimal[] s = sumas.computeIfAbsent(clave.toString(), x -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            s[0] = s[0].add(numero(f[5]));
            s[1] = s[1].add(numero(f[6]));
        }
        List<String[]> filas = new ArrayList<>();
        BigDecimal pagos = BigDecimal.ZERO;
        for (Map.Entry<String, String[]> e : grupos.entrySet()) {
            BigDecimal[] s = sumas.get(e.getKey());
            pagos = pagos.add(s[0]);
            String[] fila = new String[claves.length + 3];
            System.arraycopy(e.getValue(), 0, fila, 0, claves.length);
            fila[claves.length] = s[0].toPlainString();
            fila[claves.length + 1] = s[1].toPlainString();
            fila[claves.length + 2] = granTotal.signum() == 0 ? "0.0"
                    : s[1].multiply(new BigDecimal(100)).divide(granTotal, 1, RoundingMode.HALF_UP).toPlainString();
            filas.add(fila);
        }
        if (porMonto) {
            filas.sort(Comparator.comparing((String[] f) -> numero(f[claves.length + 1])).reversed());
        }
        String[] total = new String[claves.length + 3];
        total[0] = "TOTAL";
        for (int k = 1; k < claves.length; k++) {
            total[k] = "";
        }
        total[claves.length] = pagos.toPlainString();
        total[claves.length + 1] = granTotal.toPlainString();
        total[claves.length + 2] = "100.0";
        filas.add(total);
        List<String> columnas = new ArrayList<>(cabeceras);
        columnas.addAll(List.of("pagos", "total_bs", "porcentaje"));
        return Tabla.de(columnas, filas).numericas(claves.length, claves.length + 1, claves.length + 2);
    }

    // Barras de una tabla agrupada (sin la fila TOTAL): una categoría por fila con el valor de la columna indicada
    private static Grafico barras(String titulo, Tabla t, Function<String[], String> categoria, int columna) {
        Grafico g = new Grafico(titulo, Grafico.Tipo.BARRAS, "Bs").serie("Recaudado", Grafico.AZUL).conPorcentaje();
        for (String[] f : sinTotal(t)) {
            g.categoria(categoria.apply(f), numero(f[columna]).doubleValue());
        }
        return g;
    }

    // Barras apiladas con una serie por columna; las series sin datos se omiten y las demás conservan su color
    private static Grafico apilado(String titulo, Tabla t, Function<String[], String> categoria, String[] nombres, int[] columnas, String[] colores) {
        Grafico g = new Grafico(titulo, Grafico.Tipo.BARRAS, "").conLeyenda();
        List<Integer> usadas = new ArrayList<>();
        for (int s = 0; s < columnas.length; s++) {
            if (suma(t, columnas[s]).signum() > 0) {
                g.serie(nombres[s], colores[s]);
                usadas.add(columnas[s]);
            }
        }
        for (String[] f : sinTotal(t)) {
            double[] valores = new double[usadas.size()];
            for (int s = 0; s < usadas.size(); s++) {
                valores[s] = numero(f[usadas.get(s)]).doubleValue();
            }
            g.categoria(categoria.apply(f), valores);
        }
        return g;
    }

    private static List<String[]> sinTotal(Tabla t) {
        List<String[]> filas = new ArrayList<>();
        for (String[] f : t.getFilas()) {
            if (!(f.length > 0 && "TOTAL".equals(f[0]))) {
                filas.add(f);
            }
        }
        return filas;
    }

    private static String estadoUltimaActa(Tabla actas) {
        if (actas.estaVacia()) {
            return "sin acta todavía";
        }
        String[] ultima = actas.getFilas().get(actas.getFilas().size() - 1);
        return "la última está " + ultima[1];
    }

    private static String porcentaje(int parte, int total) {
        return new BigDecimal(100 * parte).divide(new BigDecimal(total), 1, RoundingMode.HALF_UP) + " %";
    }

    // Suma de una columna numérica (la fila TOTAL no cuenta)
    private static BigDecimal suma(Tabla t, int columna) {
        BigDecimal total = BigDecimal.ZERO;
        for (String[] f : sinTotal(t)) {
            total = total.add(numero(f[columna]));
        }
        return total;
    }

    // Fila TOTAL con la suma de las columnas de cantidades [desde, hasta)
    private static void agregarTotal(Tabla t, int desde, int hasta) {
        int columnas = t.getCabeceras().size();
        String[] total = new String[columnas];
        total[0] = "TOTAL";
        for (int c = 1; c < columnas; c++) {
            total[c] = "";
        }
        for (int c = desde; c < Math.min(hasta, columnas); c++) {
            boolean numerica = true;
            for (String[] f : t.getFilas()) {
                if (f[c] != null && !f[c].matches("-?\\d+(\\.\\d+)?")) {
                    numerica = false;
                }
            }
            if (numerica) {
                total[c] = suma(t, c).toPlainString();
            }
        }
        t.getFilas().add(total);
    }

    private static BigDecimal numero(String s) {
        try {
            return s == null ? BigDecimal.ZERO : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String sinDatos(String detalle) {
        return GeneradorHTML.mensajeAviso("La consulta no arrojó datos", detalle);
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
