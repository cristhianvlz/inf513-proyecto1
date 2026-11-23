package src.negocio;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import src.comunicacion.PasarelaPago;
import src.modelo.Adjunto;
import src.modelo.Arancel;
import src.modelo.Convenio;
import src.modelo.Estudiante;
import src.modelo.Inscripcion;
import src.modelo.InscripcionModulo;
import src.modelo.Modulo;
import src.modelo.OfertaAcademica;
import src.modelo.Pago;
import src.persistencia.ArancelDAO;
import src.persistencia.ConvenioDAO;
import src.persistencia.DocumentoDAO;
import src.persistencia.EstudianteDAO;
import src.persistencia.InscripcionDAO;
import src.persistencia.InscripcionModuloDAO;
import src.persistencia.OfertaDAO;
import src.persistencia.PagoDAO;
import src.persistencia.UsuarioDAO;
import src.utilidades.ArchivoLog;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-04: ARANCELES, BECAS, CONCILIACIÓN DE PAGOS Y PASARELA DIGITAL
// En los comandos nada se identifica por ID: un arancel por su concepto y gestión ("MATRICULA", "2-2026"),
// un convenio por su institución y un pago por su número de transacción
// =========================================================================
public class CtrlPagos extends CtrlBase {

    private static final List<String> CONCEPTOS = List.of("MATRICULA", "MODULO_MENSUAL_CIUDAD", "MODULO_MENSUAL_PROVINCIA",
            "TITULO", "CERTIFICADO", "READMISION", "TRASPASO", "CAMBIO_RESIDENCIA", "RETIRO_VOLUNTARIO", "RECUPERATORIO");

    // RN-06: estos conceptos solo se pagan con la oferta CONFIRMADA o EN_CURSO
    private static final Set<String> CONCEPTOS_DE_OFERTA = Set.of("MATRICULA", "MODULO_MENSUAL_CIUDAD",
            "MODULO_MENSUAL_PROVINCIA");

    private static final List<String> ESTADOS_ARANCEL = List.of("VIGENTE", "HISTORICO");
    private static final List<String> ESTADOS_CONVENIO = List.of("VIGENTE", "VENCIDO", "RESCINDIDO");
    private static final List<String> ESTADOS_PAGO = List.of("PENDIENTE", "CONCILIADO", "OBSERVADO", "ANULADO");

    private static final String ROL_SECRETARIA = "Secretaria";
    private static final int LARGO_CUENTA = 40;
    private static final int LARGO_NOMBRE = 150;
    private static final DateTimeFormatter VENCE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // Nombre del campo en el comando -> columna en la BD
    private static final Map<String, String> ALIAS_ARANCEL = Map.of(
            "RESOLUCION", "numero_resolucion",
            "FECHA", "fecha_resolucion");
    private static final Map<String, String> ALIAS_CONVENIO = Map.of(
            "TIPO", "tipo_convenio",
            "PORCENTAJE", "porcentaje_descuento",
            "DESCUENTO", "porcentaje_descuento",
            "INICIO", "fecha_inicio",
            "FIN", "fecha_fin");
    private static final Map<String, String> ALIAS_PAGO = Map.of(
            "TRANSACCION", "numero_transaccion",
            "FECHA", "fecha_pago",
            "DEPOSITANTE", "nombre_del_depositante",
            "NOMBRE", "nombre_del_depositante",
            "RELACION", "relacion_depositante",
            "CUENTA", "cuenta_origen");

    public CtrlPagos(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Aranceles
    // ---------------------------------------------------------------------

    // ADDARAN[concepto, monto, gestion, numero_resolucion, fecha_resolucion]
    public String registrarArancel(String[] p) {
        if (p.length < 5) {
            return formato("ADDARAN[\"concepto\", \"monto\", \"gestion\", \"numero_resolucion\", \"fecha_resolucion\"] (conceptos: "
                    + String.join(", ", CONCEPTOS) + ")");
        }
        return enTransaccion(con -> {
            Arancel a = new Arancel();
            a.setConcepto(opcion(p[0], CONCEPTOS, "concepto"));
            a.setMonto(montoValido(p[1]));
            requerido(p[2], "gestion");
            requerido(p[3], "numero_resolucion");
            a.setGestion(p[2].trim().toUpperCase());
            a.setNumeroResolucion(p[3].trim());
            a.setFechaResolucion(fechaNoFutura(p[4], "fecha_resolucion"));

            ArancelDAO dao = new ArancelDAO(con);
            Arancel vigente = dao.buscarVigente(a.getConcepto(), a.getGestion());
            if (vigente != null) {
                throw new ReglaNegocioException("Ya existe el arancel VIGENTE " + vigente.getDescripcion() + " (Bs " + vigente.getMonto()
                        + ", resolución " + vigente.getNumeroResolucion() + "). Si no tiene pagos, corríjalo con MODARAN; "
                        + "si los tiene, páselo a HISTORICO con DELARAN y registre el nuevo.");
            }
            dao.insertar(a);
            log(con, "ARANCEL", "INSERT");
            return GeneradorHTML.mensajeExito("Registro de Arancel",
                    "Se registró el arancel " + a.getDescripcion() + " por <b>Bs " + a.getMonto() + "</b> (resolución "
                            + a.getNumeroResolucion() + " del " + a.getFechaResolucion() + ").");
        });
    }

    // LISARAN[gestion, estado]: sin estado muestra los VIGENTE
    public String listarAranceles(String[] p) {
        return enTransaccion(con -> {
            String gestion = filtroListado(p[0]);
            String estado = Validador.vacio(param(p, 1)) ? "VIGENTE" : filtroListado(p[1]);
            if (!estado.equals("*")) {
                estado = opcion(estado, ESTADOS_ARANCEL, "estado");
            }
            Tabla t = new ArancelDAO(con).listar(gestion, estado);
            log(con, "ARANCEL", "SELECT");
            return GeneradorHTML.tabla("los Aranceles " + (estado.equals("*") ? "" : estado + " ")
                    + (gestion.equals("*") ? "" : "de la gestión " + gestion.toUpperCase()), t);
        });
    }

    // MODARAN[concepto, gestion, campo, nuevo_valor]: solo si el arancel no tiene pagos
    public String modificarArancel(String[] p) {
        if (p.length < 4) {
            return formato("MODARAN[\"concepto\", \"gestion\", \"campo\", \"nuevo_valor\"] (campos: monto, numero_resolucion, fecha_resolucion)");
        }
        return enTransaccion(con -> {
            Arancel a = buscarArancelVigente(con, p[0], p[1]);
            ArancelDAO dao = new ArancelDAO(con);
            if (dao.tienePagos(a.getIdArancel())) {
                throw new ReglaNegocioException("El arancel " + a.getDescripcion() + " ya tiene pagos y no se modifica. "
                        + "Páselo a HISTORICO con DELARAN y registre el nuevo con ADDARAN.");
            }
            String campo = columna(p[2], ALIAS_ARANCEL);
            Object valor;
            switch (campo) {
                case "monto":
                    valor = montoValido(p[3]);
                    break;
                case "numero_resolucion":
                    requerido(p[3], campo);
                    valor = p[3].trim();
                    break;
                case "fecha_resolucion":
                    valor = fechaNoFutura(p[3], campo);
                    break;
                default:
                    throw new ReglaNegocioException("El campo <b>" + p[2] + "</b> no se puede modificar. Campos válidos: "
                            + "monto, numero_resolucion, fecha_resolucion.");
            }
            dao.actualizarCampo(a.getIdArancel(), campo, valor);
            log(con, "ARANCEL", "UPDATE");
            return GeneradorHTML.mensajeExito("Modificación de Arancel",
                    "Se actualizó <b>" + campo + "</b> del arancel " + a.getDescripcion() + " a <b>" + valor + "</b>.");
        });
    }

    // DELARAN[concepto, gestion]: pasa a HISTORICO, nunca se borra
    public String eliminarArancel(String[] p) {
        if (p.length < 2) {
            return formato("DELARAN[\"concepto\", \"gestion\"]");
        }
        return enTransaccion(con -> {
            Arancel a = buscarArancelVigente(con, p[0], p[1]);
            new ArancelDAO(con).cambiarEstado(a.getIdArancel(), "HISTORICO");
            log(con, "ARANCEL", "UPDATE");
            return GeneradorHTML.mensajeExito("Baja de Arancel",
                    "El arancel " + a.getDescripcion() + " pasó a <b>HISTORICO</b>. No se pueden registrar pagos de este concepto hasta que "
                            + "haya otro VIGENTE.<br>Para revertirlo envíe <code>RESARAN[\"" + a.getConcepto() + "\", \"" + a.getGestion() + "\"]</code>.");
        });
    }

    // RESARAN[concepto, gestion]: revierte DELARAN; el último arancel que pasó a HISTORICO vuelve a VIGENTE
    public String restaurarArancel(String[] p) {
        if (p.length < 2) {
            return formato("RESARAN[\"concepto\", \"gestion\"]");
        }
        return enTransaccion(con -> {
            String concepto = opcion(p[0], CONCEPTOS, "concepto");
            requerido(p[1], "gestion");
            ArancelDAO dao = new ArancelDAO(con);
            Arancel vigente = dao.buscarVigente(concepto, p[1]);
            if (vigente != null) {
                throw new ReglaNegocioException("Ya existe el arancel VIGENTE " + vigente.getDescripcion() + " (Bs " + vigente.getMonto()
                        + "). Solo puede haber uno por concepto y gestión.");
            }
            Arancel a = dao.buscarUltimoHistorico(concepto, p[1]);
            if (a == null) {
                throw new ReglaNegocioException("No hay un arancel HISTORICO de <b>" + concepto + "</b> en la gestión <b>" + p[1].trim() + "</b>.");
            }
            dao.cambiarEstado(a.getIdArancel(), "VIGENTE");
            log(con, "ARANCEL", "UPDATE");
            return GeneradorHTML.mensajeExito("Restauración de Arancel",
                    "El arancel " + a.getDescripcion() + " (Bs " + a.getMonto() + ") volvió a <b>VIGENTE</b>.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Convenios y becas
    // ---------------------------------------------------------------------

    // ADDCONV[institucion, tipo_convenio, porcentaje_descuento, fecha_inicio, fecha_fin]
    public String registrarConvenio(String[] p) {
        if (p.length < 4) {
            return formato("ADDCONV[\"institucion\", \"tipo_convenio\", \"porcentaje_descuento\", \"fecha_inicio\", \"fecha_fin (opcional)\"]");
        }
        return enTransaccion(con -> {
            requerido(p[0], "institucion");
            requerido(p[1], "tipo_convenio");
            Convenio c = new Convenio();
            c.setInstitucion(p[0].trim());
            c.setTipoConvenio(p[1].trim());
            c.setPorcentajeDescuento(porcentajeValido(p[2]));
            c.setFechaInicio(fecha(p[3], "fecha_inicio"));
            c.setFechaFin(Validador.vacio(param(p, 4)) ? null : fecha(p[4], "fecha_fin"));
            validarFechasConvenio(c);
            c.setEstado(c.estadoPorFechas());

            ConvenioDAO dao = new ConvenioDAO(con);
            dao.marcarVencidos();
            Convenio existente = dao.buscarPorInstitucion(c.getInstitucion());
            if (existente != null && "VIGENTE".equals(existente.getEstado())) {
                throw new ReglaNegocioException("<b>" + existente.getInstitucion() + "</b> ya tiene un convenio VIGENTE ("
                        + existente.getPorcentajeDescuento() + "% de descuento). Modifíquelo con MODCONV.");
            }
            dao.insertar(c);
            log(con, "CONVENIO", "INSERT");
            return GeneradorHTML.mensajeExito("Registro de Convenio",
                    "Se registró el convenio con <b>" + c.getInstitucion() + "</b> (" + c.getTipoConvenio() + ") con <b>"
                            + c.getPorcentajeDescuento() + "%</b> de descuento, desde el " + c.getFechaInicio()
                            + (c.getFechaFin() != null ? " hasta el " + c.getFechaFin() : ", sin fecha de fin") + ", en estado <b>"
                            + c.getEstado() + "</b>.");
        });
    }

    // LISCONV["*" | estado]
    public String listarConvenios(String filtro) {
        return enTransaccion(con -> {
            String estado = filtroListado(filtro);
            if (!estado.equals("*")) {
                estado = opcion(estado, ESTADOS_CONVENIO, "estado");
            }
            ConvenioDAO dao = new ConvenioDAO(con);
            dao.marcarVencidos();
            Tabla t = dao.listar(estado);
            log(con, "CONVENIO", "SELECT");
            return GeneradorHTML.tabla("los Convenios" + (estado.equals("*") ? "" : " en estado " + estado) + " con sus beneficiarios", t);
        });
    }

    // MODCONV[institucion, campo, nuevo_valor]: un cambio de porcentaje solo afecta a los pagos posteriores
    public String modificarConvenio(String[] p) {
        if (p.length < 3) {
            return formato("MODCONV[\"institucion\", \"campo\", \"nuevo_valor\"] (campos: institucion, tipo_convenio, "
                    + "porcentaje_descuento, fecha_inicio, fecha_fin)");
        }
        return enTransaccion(con -> {
            Convenio c = buscarConvenio(con, p[0]);
            if ("RESCINDIDO".equals(c.getEstado())) {
                throw new ReglaNegocioException("El convenio con <b>" + c.getInstitucion() + "</b> está RESCINDIDO. "
                        + "Para modificarlo, restáurelo primero con RESCONV.");
            }
            ConvenioDAO dao = new ConvenioDAO(con);
            String campo = columna(p[1], ALIAS_CONVENIO);
            Object valor;
            switch (campo) {
                case "institucion":
                    requerido(p[2], campo);
                    Convenio otro = dao.buscarPorInstitucion(p[2]);
                    if (otro != null && otro.getIdConvenio() != c.getIdConvenio() && "VIGENTE".equals(otro.getEstado())) {
                        throw new ReglaNegocioException("<b>" + otro.getInstitucion() + "</b> ya tiene un convenio VIGENTE.");
                    }
                    valor = p[2].trim();
                    break;
                case "tipo_convenio":
                    requerido(p[2], campo);
                    valor = p[2].trim();
                    break;
                case "porcentaje_descuento":
                    valor = porcentajeValido(p[2]);
                    break;
                case "fecha_inicio":
                    c.setFechaInicio(fecha(p[2], campo));
                    validarFechasConvenio(c);
                    valor = c.getFechaInicio();
                    break;
                case "fecha_fin":
                    c.setFechaFin(Validador.vacio(p[2]) ? null : fecha(p[2], campo));
                    validarFechasConvenio(c);
                    valor = c.getFechaFin();
                    break;
                default:
                    throw new ReglaNegocioException("El campo <b>" + p[1] + "</b> no se puede modificar. Campos válidos: "
                            + "institucion, tipo_convenio, porcentaje_descuento, fecha_inicio, fecha_fin.");
            }
            dao.actualizarCampo(c.getIdConvenio(), campo, valor);
            log(con, "CONVENIO", "UPDATE");
            // Con otra fecha de fin, el convenio puede vencer o volver a estar vigente
            String estado = c.estadoPorFechas();
            if (!estado.equals(c.getEstado())) {
                dao.cambiarEstado(c.getIdConvenio(), estado);
            }
            return GeneradorHTML.mensajeExito("Modificación de Convenio",
                    "Se actualizó <b>" + campo + "</b> del convenio con <b>" + c.getInstitucion() + "</b> a <b>"
                            + (valor != null ? valor : "sin fecha de fin") + "</b>. Estado: <b>" + estado + "</b>."
                            + (campo.equals("porcentaje_descuento") ? "<br>El nuevo porcentaje solo afecta a los pagos posteriores." : ""));
        });
    }

    // DELCONV[institucion]: pasa a RESCINDIDO, nunca se borra
    public String eliminarConvenio(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("DELCONV[\"institucion\"]");
        }
        return enTransaccion(con -> {
            Convenio c = buscarConvenio(con, p[0]);
            if ("RESCINDIDO".equals(c.getEstado())) {
                throw new ReglaNegocioException("El convenio con <b>" + c.getInstitucion() + "</b> ya está RESCINDIDO.");
            }
            ConvenioDAO dao = new ConvenioDAO(con);
            dao.cambiarEstado(c.getIdConvenio(), "RESCINDIDO");
            log(con, "CONVENIO", "UPDATE");
            return GeneradorHTML.mensajeExito("Rescisión de Convenio",
                    "El convenio con <b>" + c.getInstitucion() + "</b> pasó a <b>RESCINDIDO</b>. Sus <b>"
                            + dao.contarBeneficiarios(c.getIdConvenio()) + "</b> beneficiario(s) ya no tienen descuento en los pagos que se registren desde ahora."
                            + "<br>Para revertirlo envíe <code>RESCONV[\"" + c.getInstitucion() + "\"]</code>.");
        });
    }

    // RESCONV[institucion]: revierte DELCONV; vuelve a VIGENTE, o a VENCIDO si su fecha de fin ya pasó
    public String restaurarConvenio(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("RESCONV[\"institucion\"]");
        }
        return enTransaccion(con -> {
            Convenio c = buscarConvenio(con, p[0]);
            if (!"RESCINDIDO".equals(c.getEstado())) {
                throw new ReglaNegocioException("<b>" + c.getInstitucion() + "</b> ya tiene un convenio en estado <b>" + c.getEstado()
                        + "</b>: no hay una rescisión que restaurar.");
            }
            String estado = c.estadoPorFechas();
            new ConvenioDAO(con).cambiarEstado(c.getIdConvenio(), estado);
            log(con, "CONVENIO", "UPDATE");
            return GeneradorHTML.mensajeExito("Restauración de Convenio",
                    "El convenio con <b>" + c.getInstitucion() + "</b> volvió a estado <b>" + estado + "</b>."
                            + ("VENCIDO".equals(estado) ? " Su fecha de fin ya pasó: amplíela con MODCONV para volver a usarlo." : ""));
        });
    }

    // ASIGCONV[ci, programa, gestion, grupo, institucion]: aplica el convenio a la inscripción del beneficiario
    public String asignarConvenio(String[] p) {
        if (p.length < 5) {
            return formato("ASIGCONV[\"ci\", " + OFERTA + ", \"institucion\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            OfertaAcademica o = buscarOferta(con, p, 1);
            Inscripcion i = buscarInscripcion(con, e, o);
            if (!"PREINSCRITO".equals(i.getEstado()) && !"INSCRITO".equals(i.getEstado())) {
                throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                        + " está <b>" + i.getEstado() + "</b>.");
            }
            if (i.getIdConvenio() != null) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene un convenio en esta inscripción. "
                        + "Retírelo primero con QUICONV.");
            }
            Convenio c = buscarConvenio(con, p[4]);
            if (!"VIGENTE".equals(c.getEstado())) {
                throw new ReglaNegocioException("El convenio con <b>" + c.getInstitucion() + "</b> está <b>" + c.getEstado() + "</b>.");
            }
            new InscripcionDAO(con).asignarConvenio(i.getIdInscripcion(), c.getIdConvenio());
            log(con, "INSCRIPCION", "UPDATE");
            return GeneradorHTML.mensajeExito("Asignación de Convenio",
                    "<b>" + e.getNombreCompleto() + "</b> tiene el convenio con <b>" + c.getInstitucion() + "</b> ("
                            + c.getPorcentajeDescuento() + "% de descuento) en la " + o.getDescripcion() + ". Se aplica a los pagos que se registren desde ahora.");
        });
    }

    // QUICONV[ci, programa, gestion, grupo]
    public String quitarConvenio(String[] p) {
        if (p.length < 4) {
            return formato("QUICONV[\"ci\", " + OFERTA + "]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            OfertaAcademica o = buscarOferta(con, p, 1);
            Inscripcion i = buscarInscripcion(con, e, o);
            if (i.getIdConvenio() == null) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene convenio en la " + o.getDescripcion() + ".");
            }
            new InscripcionDAO(con).asignarConvenio(i.getIdInscripcion(), null);
            log(con, "INSCRIPCION", "UPDATE");
            return GeneradorHTML.mensajeExito("Retiro de Convenio",
                    "Se retiró el convenio de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion() + ". Los pagos ya registrados no cambian.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo C - Registro del comprobante
    // ---------------------------------------------------------------------

    // ADDPAGO[ci, concepto, monto, numero_transaccion, fecha_pago, nombre_del_depositante, relacion_depositante, cuenta_origen,
    // modulo, programa, gestion, grupo], con la boleta adjunta. El módulo y la oferta son opcionales.
    // Si el monto no es el esperado, el pago se registra como OBSERVADO
    public String registrarPago(String[] p, List<Adjunto> adjuntos) {
        if (p.length < 8) {
            return formato("ADDPAGO[\"ci\", \"concepto\", \"monto\", \"numero_transaccion\", \"fecha_pago\", \"nombre_del_depositante\", "
                    + "\"relacion_depositante\", \"cuenta_origen\", \"modulo (opcional)\", " + OFERTA + " (la oferta es opcional)], con la boleta adjunta");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            Pago pago = new Pago();
            pago.setConcepto(opcion(p[1], CONCEPTOS, "concepto"));
            pago.setMonto(montoValido(p[2]));
            requerido(p[3], "numero_transaccion");
            requerido(p[5], "nombre_del_depositante");
            pago.setNumeroTransaccion(p[3].trim());
            pago.setFechaPago(fechaNoFutura(p[4], "fecha_pago"));
            pago.setNombreDepositante(p[5].trim());
            pago.setRelacionDepositante(nulo(p[6]));
            pago.setCuentaOrigen(cuentaValida(p[7]));

            PagoDAO dao = new PagoDAO(con);
            if (dao.buscarPorTransaccion(pago.getNumeroTransaccion()) != null) {
                throw new ReglaNegocioException("El número de transacción <b>" + pago.getNumeroTransaccion()
                        + "</b> ya está registrado: posible duplicidad o fraude.");
            }
            Inscripcion i = inscripcionParaPago(con, e, pago.getConcepto(), p, 9, "ADDPAGO[..., \"cuenta_origen\", \"modulo\", ");
            OfertaAcademica o = new OfertaDAO(con).buscarPorId(i.getIdOferta());
            Arancel a = new ArancelDAO(con).buscarVigente(pago.getConcepto(), o.getGestion());
            if (a == null) {
                throw new ReglaNegocioException("No hay un arancel VIGENTE de <b>" + pago.getConcepto() + "</b> para la gestión <b>"
                        + o.getGestion() + "</b>. Regístrelo con ADDARAN.");
            }
            pago.setIdInscripcion(i.getIdInscripcion());
            pago.setIdArancel(a.getIdArancel());
            if ("MATRICULA".equals(pago.getConcepto()) && dao.otraMatriculaActiva(i.getIdInscripcion(), 0, false)) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene una matrícula pendiente o conciliada en la "
                        + o.getDescripcion() + ". Consulte <code>ESTCTA[\"" + e.getCi() + "\"]</code>.");
            }
            pago.setIdInscripcionModulo(moduloDelPago(con, e, i, pago, param(p, 8)));
            Adjunto boleta = elegirArchivo(adjuntos, "boleta de pago");

            BigDecimal esperado = revisarMonto(con, pago, a);
            dao.insertar(pago);
            new DocumentoDAO(con).insertarBoleta(guardarDocumento(e.getCi(), "BOLETA_PAGO", boleta), e.getIdEstudiante(), pago.getIdPago());
            log(con, "PAGO", "INSERT");
            log(con, "DOCUMENTO_DIGITAL", "INSERT");

            boolean observado = "OBSERVADO".equals(pago.getEstadoConciliacion());
            return GeneradorHTML.mensajeExito("Registro de Pago",
                    "Se registró el pago <b>" + pago.getNumeroTransaccion() + "</b> de <b>" + e.getNombreCompleto() + "</b>: "
                            + pago.getConcepto() + " por Bs " + pago.getMonto() + " en la " + o.getDescripcion() + ". Estado: <b>"
                            + pago.getEstadoConciliacion() + "</b>.<br>"
                            + (observado ? "<b>Atención:</b> el monto esperado es <b>Bs " + esperado + "</b>. Corríjalo con <code>MODPAGO[\""
                                    + pago.getNumeroTransaccion() + "\", \"monto\", \"" + esperado + "\"]</code>."
                                    : "Tras contrastarlo con el extracto, concílielo con <code>CONCPAGO[\"" + pago.getNumeroTransaccion() + "\"]</code>.")
                            + (pago.esMensualidad() && pago.getIdInscripcionModulo() == null
                                    ? "<br>Cuando esté conciliado, respaldará el próximo módulo que se registre con INSMOD." : ""));
        });
    }

    // ESTCTA[codigo_estudiante | ci]: aranceles debidos, pagos conciliados y saldo
    public String estadoDeCuenta(String valor) {
        if (Validador.vacio(valor)) {
            return formato("ESTCTA[\"codigo_estudiante o ci\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, valor);
            PagoDAO dao = new PagoDAO(con);
            Tabla cuenta = dao.estadoDeCuenta(e.getIdEstudiante());
            Tabla pagos = dao.listarPorEstudiante(e.getIdEstudiante());
            log(con, "PAGO", "SELECT");
            BigDecimal saldo = BigDecimal.ZERO;
            for (String[] fila : cuenta.getFilas()) {
                saldo = saldo.add(new BigDecimal(fila[fila.length - 1]));
            }
            String resumen = saldo.signum() < 0 ? "saldo a favor Bs " + saldo.negate() : "saldo pendiente Bs " + saldo;
            return GeneradorHTML.tablas(new String[] {
                    "el Estado de Cuenta de " + e.getNombreCompleto() + " (CI " + e.getCi() + "): " + resumen,
                    "sus Pagos registrados" }, cuenta, pagos);
        });
    }

    // LISPAGO["*" | estado | fecha]
    public String listarPagos(String filtro) {
        return enTransaccion(con -> {
            String f = filtroListado(filtro);
            String estado = "*";
            String dia = "*";
            if (!f.equals("*")) {
                if (Validador.fecha(f) != null) {
                    dia = f;
                } else {
                    estado = opcion(f, ESTADOS_PAGO, "estado");
                }
            }
            Tabla t = new PagoDAO(con).listar(estado, dia);
            log(con, "PAGO", "SELECT");
            return GeneradorHTML.tabla("los Pagos" + (estado.equals("*") ? "" : " en estado " + estado)
                    + (dia.equals("*") ? "" : " del " + dia), t);
        });
    }

    // MODPAGO[numero_transaccion, campo, nuevo_valor]: solo PENDIENTE u OBSERVADO; al corregirlo se vuelve a revisar el monto
    public String modificarPago(String[] p) {
        if (p.length < 3) {
            return formato("MODPAGO[\"numero_transaccion\", \"campo\", \"nuevo_valor\"] (campos: monto, numero_transaccion, "
                    + "fecha_pago, nombre_del_depositante, relacion_depositante, cuenta_origen)");
        }
        return enTransaccion(con -> {
            Pago pago = buscarPago(con, p[0]);
            if (pago.esDePasarela()) {
                throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> se hizo por la pasarela: sus datos los "
                        + "informa la pasarela y no se corrigen a mano.");
            }
            if (!"PENDIENTE".equals(pago.getEstadoConciliacion()) && !"OBSERVADO".equals(pago.getEstadoConciliacion())) {
                throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> está <b>" + pago.getEstadoConciliacion()
                        + "</b>: solo se corrige un pago PENDIENTE u OBSERVADO.");
            }
            PagoDAO dao = new PagoDAO(con);
            String campo = columna(p[1], ALIAS_PAGO);
            Object valor;
            switch (campo) {
                case "monto":
                    pago.setMonto(montoValido(p[2]));
                    valor = pago.getMonto();
                    break;
                case "numero_transaccion":
                    requerido(p[2], campo);
                    Pago otro = dao.buscarPorTransaccion(p[2]);
                    if (otro != null && otro.getIdPago() != pago.getIdPago()) {
                        throw new ReglaNegocioException("El número de transacción <b>" + p[2].trim() + "</b> ya está registrado: posible duplicidad o fraude.");
                    }
                    valor = p[2].trim();
                    break;
                case "fecha_pago":
                    pago.setFechaPago(fechaNoFutura(p[2], campo));
                    valor = pago.getFechaPago();
                    break;
                case "nombre_del_depositante":
                    requerido(p[2], campo);
                    valor = p[2].trim();
                    break;
                case "relacion_depositante":
                    valor = nulo(p[2]);
                    break;
                case "cuenta_origen":
                    pago.setCuentaOrigen(cuentaValida(p[2]));
                    valor = pago.getCuentaOrigen();
                    break;
                default:
                    throw new ReglaNegocioException("El campo <b>" + p[1] + "</b> no se puede modificar. Campos válidos: monto, "
                            + "numero_transaccion, fecha_pago, nombre_del_depositante, relacion_depositante, cuenta_origen.");
            }
            if (!campo.equals("cuenta_origen")) {
                dao.actualizarCampo(pago.getIdPago(), campo, valor);
            }
            BigDecimal esperado = revisarMonto(con, pago, new ArancelDAO(con).buscarPorId(pago.getIdArancel()));
            dao.guardarEstado(pago);
            log(con, "PAGO", "UPDATE");
            return GeneradorHTML.mensajeExito("Corrección de Pago",
                    "Se actualizó <b>" + campo + "</b> del pago <b>" + p[0].trim() + "</b> a <b>" + (valor != null ? valor : "vacío")
                            + "</b>. Estado: <b>" + pago.getEstadoConciliacion() + "</b>."
                            + ("OBSERVADO".equals(pago.getEstadoConciliacion()) ? "<br><b>Atención:</b> el monto esperado es <b>Bs " + esperado + "</b>." : ""));
        });
    }

    // ANUPAGO[numero_transaccion, motivo]: pasa a ANULADO, nunca se borra (no hay devolución y debe quedar trazabilidad)
    public String anularPago(String[] p) {
        if (p.length < 2) {
            return formato("ANUPAGO[\"numero_transaccion\", \"motivo\"]");
        }
        return enTransaccion(con -> {
            Pago pago = buscarPago(con, p[0]);
            requerido(p[1], "motivo");
            if ("ANULADO".equals(pago.getEstadoConciliacion())) {
                throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> ya está ANULADO.");
            }
            validarQueSePuedeAnular(con, pago);
            if (pago.esOrdenSinPagar()) {
                // Primero en la pasarela: si el estudiante ya pagó, la orden no se anula y el pago no se pierde
                anularEnPasarela(pasarela(), pago);
            }
            pago.setEstadoConciliacion("ANULADO");
            fijarMotivo(pago, p[1].trim());
            new PagoDAO(con).guardarEstado(pago);
            log(con, "PAGO", "UPDATE");
            return GeneradorHTML.mensajeExito("Anulación de Pago",
                    "El pago <b>" + pago.getNumeroTransaccion() + "</b> pasó a <b>ANULADO</b>. No se borra: queda para la trazabilidad."
                            + "<br>Para revertirlo envíe <code>RESPAGO[\"" + pago.getNumeroTransaccion() + "\"]</code>.");
        });
    }

    // RESPAGO[numero_transaccion]: revierte ANUPAGO; vuelve a PENDIENTE y se debe conciliar otra vez
    public String restaurarPago(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("RESPAGO[\"numero_transaccion\"]");
        }
        return enTransaccion(con -> {
            Pago pago = buscarPago(con, p[0]);
            if (!"ANULADO".equals(pago.getEstadoConciliacion())) {
                throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> está <b>" + pago.getEstadoConciliacion()
                        + "</b>: solo se restaura un pago ANULADO.");
            }
            if (pago.esDePasarela()) {
                throw new ReglaNegocioException("<b>" + pago.getNumeroTransaccion() + "</b> es una orden de la pasarela: una orden anulada o "
                        + "vencida no se restaura. Genere una nueva con GENPAGO.");
            }
            BigDecimal esperado = revisarMonto(con, pago, new ArancelDAO(con).buscarPorId(pago.getIdArancel()));
            new PagoDAO(con).guardarEstado(pago);
            log(con, "PAGO", "UPDATE");
            return GeneradorHTML.mensajeExito("Restauración de Pago",
                    "El pago <b>" + pago.getNumeroTransaccion() + "</b> volvió a <b>" + pago.getEstadoConciliacion() + "</b>."
                            + ("OBSERVADO".equals(pago.getEstadoConciliacion()) ? " El monto esperado es Bs " + esperado + "."
                                    : " Debe conciliarse otra vez con CONCPAGO."));
        });
    }

    // ---------------------------------------------------------------------
    // Flujo D - Conciliación
    // ---------------------------------------------------------------------

    // CONCPAGO[numero_transaccion]: notifica al estudiante y, si es la matrícula, a Secretaría
    public String conciliarPago(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("CONCPAGO[\"numero_transaccion\"]");
        }
        return enTransaccion(con -> {
            Pago pago = buscarPago(con, p[0]);
            ordenSinPagarNoSeTocaAMano(pago);
            if ("OBSERVADO".equals(pago.getEstadoConciliacion())) {
                // Una observación se levanta con el monto correcto; si el monto no coincide, hay que corregirlo
                BigDecimal esperado = montoEsperado(con, new ArancelDAO(con).buscarPorId(pago.getIdArancel()), pago);
                if (pago.getMonto().compareTo(esperado) != 0) {
                    throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> es de Bs " + pago.getMonto()
                            + " y el monto esperado es Bs " + esperado + ". Corríjalo con MODPAGO.");
                }
            } else if (!"PENDIENTE".equals(pago.getEstadoConciliacion())) {
                throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> está <b>" + pago.getEstadoConciliacion()
                        + "</b>: solo se concilia un pago PENDIENTE u OBSERVADO.");
            }
            pago.setEstadoConciliacion("CONCILIADO");
            pago.setMotivo(null);
            new PagoDAO(con).guardarEstado(pago);
            log(con, "PAGO", "UPDATE");

            Estudiante e = new EstudianteDAO(con).buscarPorId(new InscripcionDAO(con).buscarPorId(pago.getIdInscripcion()).getIdEstudiante());
            int secretaria = 0;
            for (String[] aviso : avisosDeConciliacion(con, pago, "fue conciliado con la cuenta fiscal")) {
                notificar(aviso[0], aviso[1], aviso[2]);
                secretaria += aviso[3].equals(ROL_SECRETARIA) ? 1 : 0;
            }
            String detalle = secretaria > 0
                    ? " Se avisó a <b>" + secretaria + "</b> cuenta(s) de Secretaría para formalizar la inscripción." : "";
            return GeneradorHTML.mensajeExito("Conciliación de Pago",
                    "El pago <b>" + pago.getNumeroTransaccion() + "</b> de <b>" + e.getNombreCompleto() + "</b> quedó <b>CONCILIADO</b>."
                            + (e.getEmail() != null ? " Se le notificó a " + e.getEmail() + "." : " El estudiante no tiene correo registrado.") + detalle);
        });
    }

    // OBSPAGO[numero_transaccion, motivo]: notifica el motivo al estudiante
    public String observarPago(String[] p) {
        if (p.length < 2) {
            return formato("OBSPAGO[\"numero_transaccion\", \"motivo\"]");
        }
        return enTransaccion(con -> {
            Pago pago = buscarPago(con, p[0]);
            requerido(p[1], "motivo");
            ordenSinPagarNoSeTocaAMano(pago);
            if (!"PENDIENTE".equals(pago.getEstadoConciliacion()) && !"OBSERVADO".equals(pago.getEstadoConciliacion())) {
                throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> está <b>" + pago.getEstadoConciliacion()
                        + "</b>: solo se observa un pago PENDIENTE.");
            }
            pago.setEstadoConciliacion("OBSERVADO");
            fijarMotivo(pago, p[1].trim());
            new PagoDAO(con).guardarEstado(pago);
            log(con, "PAGO", "UPDATE");

            Inscripcion i = new InscripcionDAO(con).buscarPorId(pago.getIdInscripcion());
            Estudiante e = new EstudianteDAO(con).buscarPorId(i.getIdEstudiante());
            if (e.getEmail() != null) {
                notificar(e.getEmail(), "Pago observado " + pago.getNumeroTransaccion(), GeneradorHTML.mensajeAviso("Pago observado",
                        "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: su pago <b>" + pago.getNumeroTransaccion() + "</b> de "
                                + pago.getConcepto() + " fue observado. Motivo: <b>" + pago.getMotivo() + "</b>. Acérquese al ICAP con su boleta original."));
            }
            return GeneradorHTML.mensajeExito("Observación de Pago",
                    "El pago <b>" + pago.getNumeroTransaccion() + "</b> quedó <b>OBSERVADO</b>: " + pago.getMotivo() + "."
                            + (e.getEmail() != null ? " Se le notificó a " + e.getEmail() + "." : " El estudiante no tiene correo registrado."));
        });
    }

    // ---------------------------------------------------------------------
    // Flujo E - Pago en línea por la pasarela
    // ---------------------------------------------------------------------

    // GENPAGO[ci, concepto, modulo, programa, gestion, grupo]: crea la orden de cobro en la pasarela y responde con el QR adjunto.
    // El monto lo calcula el sistema (arancel vigente menos el convenio); el estudiante nunca lo escribe. La orden queda en PAGO
    // como PENDIENTE y VerificadorPagos la concilia sola cuando la pasarela confirma el pago. La envía el estudiante desde el
    // correo de su expediente (solo para sí mismo) o la Secretaria, que además le hace llegar el QR al estudiante.
    // Las mismas reglas que ADDPAGO: arancel vigente, RN-06 (matrícula y mensualidad con la oferta CONFIRMADA o EN_CURSO)
    // y una sola matrícula por inscripción. Una orden anterior sin pagar del mismo cobro se reemplaza por la nueva
    public String generarOrdenPago(String[] p) {
        if (p.length < 2 || Validador.vacio(p[0]) || Validador.vacio(p[1])) {
            return formato("GENPAGO[\"ci\", \"concepto\", \"modulo (opcional)\", " + OFERTA + " (la oferta es opcional)]");
        }
        return enTransaccion(con -> {
            PasarelaPago pasarela = pasarela();
            Estudiante e = buscarEstudiante(con, p[0]);
            Pago pago = new Pago();
            pago.setConcepto(opcion(p[1], CONCEPTOS, "concepto"));
            pago.setFechaPago(LocalDate.now());
            pago.setRelacionDepositante(Pago.PASARELA);
            pago.setNombreDepositante(recortar(e.getNombreCompleto(), LARGO_NOMBRE));
            Inscripcion i = inscripcionParaPago(con, e, pago.getConcepto(), p, 3, "GENPAGO[\"ci\", \"concepto\", \"modulo\", ");
            OfertaAcademica o = new OfertaDAO(con).buscarPorId(i.getIdOferta());
            Arancel a = new ArancelDAO(con).buscarVigente(pago.getConcepto(), o.getGestion());
            if (a == null) {
                throw new ReglaNegocioException("No hay un arancel VIGENTE de <b>" + pago.getConcepto() + "</b> para la gestión <b>"
                        + o.getGestion() + "</b>: todavía no se puede pagar. Consulte en Secretaría.");
            }
            pago.setIdInscripcion(i.getIdInscripcion());
            pago.setIdArancel(a.getIdArancel());
            pago.setIdInscripcionModulo(moduloDelPago(con, e, i, pago, param(p, 2)));

            PagoDAO dao = new PagoDAO(con);
            Pago anterior = dao.ordenSinPagar(i.getIdInscripcion(), pago.getConcepto(), pago.getIdInscripcionModulo());
            if ("MATRICULA".equals(pago.getConcepto())
                    && dao.otraMatriculaActiva(i.getIdInscripcion(), anterior == null ? 0 : anterior.getIdPago(), false)) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene la matrícula de la " + o.getDescripcion()
                        + " pagada o un depósito en espera de conciliar. Consulte <code>ESTCTA[\"" + e.getCi() + "\"]</code>.");
            }
            BigDecimal monto = montoEsperado(con, a, pago);
            if (monto.signum() <= 0) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene nada que pagar por <b>" + pago.getConcepto()
                        + "</b>: su convenio cubre el 100%.");
            }
            pago.setMonto(monto);

            // Si la orden anterior ya se pagó, no se cobra dos veces: la confirma VerificadorPagos
            if (anterior != null && consultarPasarela(pasarela, anterior).estado() == PasarelaPago.Estado.PAGADA) {
                throw new ReglaNegocioException("La orden <b>" + anterior.getNumeroTransaccion() + "</b> de " + anterior.getConcepto()
                        + " ya fue pagada. El sistema la confirmará en unos minutos y le avisará por correo.");
            }
            PasarelaPago.Orden orden = crearEnPasarela(pasarela, e.getCi() + " " + pago.getConcepto(), monto,
                    pago.getConcepto() + " - " + e.getNombreCompleto() + " - " + o.getDescripcion(), e.getEmail());
            if (anterior != null) {
                anularEnPasarela(pasarela, anterior);
                anterior.setEstadoConciliacion("ANULADO");
                fijarMotivo(anterior, "Reemplazada por la orden " + orden.id());
                dao.guardarEstado(anterior);
            }
            pago.setNumeroTransaccion(orden.id());
            pago.setEstadoConciliacion("PENDIENTE");
            dao.insertar(pago);
            log(con, "PAGO", "INSERT");

            Adjunto qr = new Adjunto("QR_" + orden.id() + ".png", "image/png", orden.qrPng());
            String modulo = Validador.vacio(param(p, 2)) ? "" : " del módulo <b>" + param(p, 2).trim().toUpperCase() + "</b>";
            String descuento = monto.compareTo(a.getMonto()) == 0 ? ""
                    : " (arancel Bs " + a.getMonto() + " con el descuento de su convenio)";
            String instrucciones = "Pague escaneando el código QR adjunto desde la aplicación de su banco"
                    + (orden.enlace() != null ? " o en <a href='" + orden.enlace() + "'>" + orden.enlace() + "</a>" : "")
                    + " antes del <b>" + orden.vence().format(VENCE) + "</b>.<br>"
                    + "No necesita presentar boleta: cuando la pasarela confirme el pago, el sistema lo registra como CONCILIADO y "
                    + "le avisa por correo. Puede ver su estado de cuenta con <code>ESTCTA[\"" + e.getCi() + "\"]</code>."
                    + (pasarela.esDePrueba() ? "<br><b>Pasarela de prueba:</b> no cobra dinero real; el pago se simula con "
                            + "<code>deploy/SimularPago.java " + orden.id() + "</code>." : "");
            String detalleOrden = "Orden de pago <b>" + orden.id() + "</b>: " + pago.getConcepto() + modulo + " en la " + o.getDescripcion()
                    + ", por <b>Bs " + monto + "</b>" + descuento + ".<br>";
            String reemplazo = anterior == null ? ""
                    : "<br>Reemplaza a la orden <b>" + anterior.getNumeroTransaccion() + "</b>, que quedó ANULADA.";

            adjuntarALaRespuesta(qr);
            boolean loPidioElEstudiante = idRemitente == 0;
            String copia = "";
            if (!loPidioElEstudiante) {
                if (e.getEmail() != null) {
                    notificar(e.getEmail(), "Orden de pago " + orden.id(), GeneradorHTML.mensajeExito("Orden de Pago",
                            "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: " + detalleOrden + instrucciones), List.of(qr));
                    copia = "<br>Se le envió la orden con el QR a <b>" + e.getEmail() + "</b>.";
                } else {
                    copia = "<br>El estudiante no tiene correo registrado: entréguele el QR adjunto.";
                }
            }
            return GeneradorHTML.mensajeExito("Orden de Pago", (loPidioElEstudiante ? "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: "
                    : "Estudiante <b>" + e.getNombreCompleto() + "</b> (CI " + e.getCi() + "). ") + detalleOrden + instrucciones + reemplazo + copia);
        });
    }

    // Avisos de un pago que quedó CONCILIADO: al estudiante y, si es la matrícula de un preinscrito, a Secretaría para
    // que formalice la inscripción. Los usan CONCPAGO y la confirmación automática de la pasarela (VerificadorPagos).
    // Cada aviso: {destinatario, asunto, html, "Estudiante" o "Secretaria"}
    static List<String[]> avisosDeConciliacion(Connection con, Pago pago, String como) throws SQLException {
        List<String[]> avisos = new ArrayList<>();
        Inscripcion i = new InscripcionDAO(con).buscarPorId(pago.getIdInscripcion());
        Estudiante e = new EstudianteDAO(con).buscarPorId(i.getIdEstudiante());
        OfertaAcademica o = new OfertaDAO(con).buscarPorId(i.getIdOferta());
        if (e.getEmail() != null) {
            avisos.add(new String[] { e.getEmail(), "Pago conciliado " + pago.getNumeroTransaccion(), GeneradorHTML.mensajeExito("Pago Conciliado",
                    "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: su pago <b>" + pago.getNumeroTransaccion() + "</b> de "
                            + pago.getConcepto() + " por Bs " + pago.getMonto() + " " + como + "."), "Estudiante" });
        }
        if ("MATRICULA".equals(pago.getConcepto()) && "PREINSCRITO".equals(i.getEstado())) {
            for (String correo : new UsuarioDAO(con).correosActivosPorRol(ROL_SECRETARIA)) {
                avisos.add(new String[] { correo, "Matricula conciliada: " + e.getCi(), GeneradorHTML.mensajeAviso("Matrícula conciliada",
                        "La matrícula de <b>" + e.getNombreCompleto() + "</b> (CI " + e.getCi() + ") en la " + o.getDescripcion()
                                + " " + como + ". Puede formalizar su inscripción con <code>FORMINS[\"" + e.getCi() + "\", "
                                + o.getReferencia() + "]</code>."), ROL_SECRETARIA });
            }
        }
        return avisos;
    }

    // Avisos de un pago de la pasarela que quedó OBSERVADO (la pasarela cobró otro monto): al estudiante y a Secretaría
    static List<String[]> avisosDeObservacion(Connection con, Pago pago) throws SQLException {
        List<String[]> avisos = new ArrayList<>();
        Inscripcion i = new InscripcionDAO(con).buscarPorId(pago.getIdInscripcion());
        Estudiante e = new EstudianteDAO(con).buscarPorId(i.getIdEstudiante());
        if (e.getEmail() != null) {
            avisos.add(new String[] { e.getEmail(), "Pago observado " + pago.getNumeroTransaccion(), GeneradorHTML.mensajeAviso("Pago observado",
                    "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: su pago <b>" + pago.getNumeroTransaccion() + "</b> de "
                            + pago.getConcepto() + " quedó observado. Motivo: <b>" + pago.getMotivo() + "</b>. Secretaría revisará el caso."),
                    "Estudiante" });
        }
        for (String correo : new UsuarioDAO(con).correosActivosPorRol(ROL_SECRETARIA)) {
            avisos.add(new String[] { correo, "Pago por pasarela observado: " + pago.getNumeroTransaccion(), GeneradorHTML.mensajeAviso(
                    "Pago por pasarela observado", "El pago <b>" + pago.getNumeroTransaccion() + "</b> de <b>" + e.getNombreCompleto()
                            + "</b> (CI " + e.getCi() + ") por " + pago.getConcepto() + " quedó OBSERVADO: " + pago.getMotivo()
                            + ". Revíselo con <code>LISPAGO[\"OBSERVADO\"]</code>."), ROL_SECRETARIA });
        }
        return avisos;
    }

    // Aviso al estudiante de una orden que venció sin pago
    static List<String[]> avisosDeVencimiento(Connection con, Pago pago) throws SQLException {
        Estudiante e = new EstudianteDAO(con).buscarPorId(new InscripcionDAO(con).buscarPorId(pago.getIdInscripcion()).getIdEstudiante());
        if (e.getEmail() == null) {
            return List.of();
        }
        return List.<String[]>of(new String[] { e.getEmail(), "Orden de pago vencida " + pago.getNumeroTransaccion(),
                GeneradorHTML.mensajeAviso("Orden de pago vencida", "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: su orden de pago <b>"
                        + pago.getNumeroTransaccion() + "</b> de " + pago.getConcepto() + " por Bs " + pago.getMonto()
                        + " venció sin pago y quedó anulada. Si aún desea pagar, genere una nueva con <code>GENPAGO[\"" + e.getCi()
                        + "\", \"" + pago.getConcepto() + "\"]</code> o pague con boleta en el ICAP."), "Estudiante" });
    }

    private static PasarelaPago pasarela() {
        try {
            return PasarelaPago.configurada();
        } catch (IllegalStateException ex) {
            throw new ReglaNegocioException("La pasarela de pago no está configurada: " + ex.getMessage());
        }
    }

    private static PasarelaPago.Orden crearEnPasarela(PasarelaPago pasarela, String referencia, BigDecimal monto, String descripcion,
            String correo) {
        try {
            return pasarela.crearOrden(referencia, monto, descripcion, correo);
        } catch (IOException ex) {
            ArchivoLog.error("Pasarela de pago: no se pudo crear la orden", ex.getMessage());
            throw new ReglaNegocioException("La pasarela de pago no respondió. Intente más tarde o pague con boleta en el ICAP.");
        }
    }

    private static PasarelaPago.Consulta consultarPasarela(PasarelaPago pasarela, Pago orden) {
        try {
            return pasarela.consultar(orden.getNumeroTransaccion());
        } catch (IOException ex) {
            ArchivoLog.error("Pasarela de pago: no se pudo consultar la orden " + orden.getNumeroTransaccion(), ex.getMessage());
            throw new ReglaNegocioException("La pasarela de pago no respondió. Intente más tarde.");
        }
    }

    // Si la pasarela ya la cobró, no se anula: el pago lo confirma VerificadorPagos
    private static void anularEnPasarela(PasarelaPago pasarela, Pago orden) {
        if (consultarPasarela(pasarela, orden).estado() == PasarelaPago.Estado.PAGADA) {
            throw new ReglaNegocioException("La orden <b>" + orden.getNumeroTransaccion() + "</b> ya fue pagada en la pasarela: no se anula. "
                    + "El sistema la confirmará en unos minutos.");
        }
        try {
            pasarela.anular(orden.getNumeroTransaccion());
        } catch (IOException ex) {
            ArchivoLog.error("Pasarela de pago: no se pudo anular la orden " + orden.getNumeroTransaccion(), ex.getMessage());
            throw new ReglaNegocioException("La pasarela de pago no respondió al anular la orden <b>" + orden.getNumeroTransaccion()
                    + "</b>. Intente más tarde.");
        }
    }

    // Una orden sin pagar solo la confirma la pasarela: conciliarla u observarla a mano daría por pagado algo que no se cobró
    private static void ordenSinPagarNoSeTocaAMano(Pago pago) {
        if (pago.esOrdenSinPagar()) {
            throw new ReglaNegocioException("<b>" + pago.getNumeroTransaccion() + "</b> es una orden de la pasarela que todavía no se pagó: "
                    + "el sistema la concilia solo cuando la pasarela confirma el pago. Si ya no corresponde, anúlela con ANUPAGO.");
        }
    }

    private static String recortar(String s, int largo) {
        return s == null || s.length() <= largo ? s : s.substring(0, largo);
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    private static BigDecimal montoValido(String texto) {
        BigDecimal monto = decimal(texto, "monto");
        if (monto.signum() < 0) {
            throw new ReglaNegocioException("El <b>monto</b> no puede ser negativo.");
        }
        return monto.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal porcentajeValido(String texto) {
        BigDecimal porcentaje = decimal(texto, "porcentaje_descuento");
        if (porcentaje.signum() < 0 || porcentaje.compareTo(new BigDecimal(100)) > 0) {
            throw new ReglaNegocioException("El <b>porcentaje_descuento</b> debe estar entre 0 y 100.");
        }
        return porcentaje;
    }

    private static LocalDate fechaNoFutura(String texto, String nombre) {
        LocalDate f = fecha(texto, nombre);
        if (f.isAfter(LocalDate.now())) {
            throw new ReglaNegocioException("<b>" + nombre + "</b> (" + f + ") no puede ser una fecha futura.");
        }
        return f;
    }

    private static String cuentaValida(String texto) {
        String cuenta = nulo(texto);
        if (cuenta != null && cuenta.length() > LARGO_CUENTA) {
            throw new ReglaNegocioException("La <b>cuenta_origen</b> no puede superar los " + LARGO_CUENTA + " caracteres.");
        }
        return cuenta;
    }

    private static void validarFechasConvenio(Convenio c) {
        if (c.getFechaFin() != null && c.getFechaFin().isBefore(c.getFechaInicio())) {
            throw new ReglaNegocioException("La fecha de fin (" + c.getFechaFin() + ") es anterior a la de inicio (" + c.getFechaInicio() + ").");
        }
    }

    // El motivo se guarda en concepto_pago, que tiene un largo máximo
    private static void fijarMotivo(Pago pago, String motivo) {
        pago.setMotivo(null);
        int disponible = Pago.LARGO_DETALLE - pago.getConceptoPago().length() - " | Motivo: ".length();
        if (motivo.length() > disponible) {
            throw new ReglaNegocioException("El <b>motivo</b> no puede superar los " + disponible + " caracteres.");
        }
        pago.setMotivo(motivo);
    }

    private Arancel buscarArancelVigente(Connection con, String concepto, String gestion) throws SQLException {
        String c = opcion(concepto, CONCEPTOS, "concepto");
        requerido(gestion, "gestion");
        Arancel a = new ArancelDAO(con).buscarVigente(c, gestion);
        if (a == null) {
            throw new ReglaNegocioException("No hay un arancel VIGENTE de <b>" + c + "</b> en la gestión <b>" + gestion.trim().toUpperCase()
                    + "</b>. Consulte <code>LISARAN[\"" + gestion.trim().toUpperCase() + "\", \"*\"]</code>.");
        }
        return a;
    }

    private Convenio buscarConvenio(Connection con, String institucion) throws SQLException {
        requerido(institucion, "institucion");
        ConvenioDAO dao = new ConvenioDAO(con);
        dao.marcarVencidos();
        Convenio c = dao.buscarPorInstitucion(institucion);
        if (c == null) {
            throw new ReglaNegocioException("No existe un convenio con <b>" + institucion.trim() + "</b>. Consulte LISCONV[\"*\"].");
        }
        return c;
    }

    private Pago buscarPago(Connection con, String numeroTransaccion) throws SQLException {
        requerido(numeroTransaccion, "numero_transaccion");
        Pago pago = new PagoDAO(con).buscarPorTransaccion(numeroTransaccion);
        if (pago == null) {
            throw new ReglaNegocioException("No existe un pago con la transacción <b>" + numeroTransaccion.trim() + "</b>. Consulte LISPAGO[\"*\"].");
        }
        return pago;
    }

    // Monto del arancel menos el descuento del convenio en vigor en la fecha del depósito
    private BigDecimal montoEsperado(Connection con, Arancel a, Pago pago) throws SQLException {
        BigDecimal descuento = new ConvenioDAO(con).descuentoEn(pago.getIdInscripcion(), pago.getFechaPago());
        return a.getMonto().multiply(new BigDecimal(100).subtract(descuento)).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }

    // Con el monto esperado el pago queda PENDIENTE de conciliar; si no, OBSERVADO con el motivo. Devuelve el monto esperado
    private BigDecimal revisarMonto(Connection con, Pago pago, Arancel a) throws SQLException {
        BigDecimal esperado = montoEsperado(con, a, pago);
        if (pago.getMonto().compareTo(esperado) == 0) {
            pago.setEstadoConciliacion("PENDIENTE");
            pago.setMotivo(null);
        } else {
            pago.setEstadoConciliacion("OBSERVADO");
            pago.setMotivo("Monto esperado Bs " + esperado + ", recibido Bs " + pago.getMonto());
        }
        return esperado;
    }

    // La oferta solo hace falta si el estudiante tiene varias inscripciones que podrían recibir el pago.
    // Va en los tres valores desde inicioOferta (ADDPAGO: 9; GENPAGO: 3); plantilla es el comando hasta ahí, para el mensaje
    private Inscripcion inscripcionParaPago(Connection con, Estudiante e, String concepto, String[] p, int inicioOferta, String plantilla)
            throws SQLException {
        boolean exigeOferta = CONCEPTOS_DE_OFERTA.contains(concepto);
        if (p.length >= inicioOferta + 3) {
            OfertaAcademica o = buscarOferta(con, p, inicioOferta);
            Inscripcion i = buscarInscripcion(con, e, o);
            if (exigeOferta) {
                if (!"CONFIRMADA".equals(o.getEstado()) && !"EN_CURSO".equals(o.getEstado())) {
                    throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado() + "</b>: la matrícula y la "
                            + "mensualidad solo se pagan con la oferta CONFIRMADA o EN_CURSO.");
                }
                if (!"PREINSCRITO".equals(i.getEstado()) && !"INSCRITO".equals(i.getEstado())) {
                    throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                            + " está <b>" + i.getEstado() + "</b>.");
                }
            }
            return i;
        }
        List<Inscripcion> candidatas = new InscripcionDAO(con).paraPago(e.getIdEstudiante(), exigeOferta);
        if (candidatas.isEmpty()) {
            throw new ReglaNegocioException(exigeOferta
                    ? "<b>" + e.getNombreCompleto() + "</b> no tiene una inscripción vigente en una oferta CONFIRMADA o EN_CURSO: "
                            + "la matrícula y la mensualidad solo se pagan en ese caso."
                    : "<b>" + e.getNombreCompleto() + "</b> no tiene inscripciones.");
        }
        if (candidatas.size() > 1) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> tiene varias inscripciones. Indique la oferta al final del comando: "
                    + "<code>" + plantilla + OFERTA + "]</code>.");
        }
        return candidatas.get(0);
    }

    // Una mensualidad puede indicar el módulo que el estudiante ya cursa. Sin módulo queda libre y la toma el próximo INSMOD
    private Integer moduloDelPago(Connection con, Estudiante e, Inscripcion i, Pago pago, String codigoModulo) throws SQLException {
        if (Validador.vacio(codigoModulo)) {
            return null;
        }
        if (!pago.esMensualidad()) {
            throw new ReglaNegocioException("Solo una mensualidad se asocia a un módulo; <b>" + pago.getConcepto() + "</b> no lleva módulo.");
        }
        Modulo m = buscarModulo(con, codigoModulo);
        for (InscripcionModulo im : new InscripcionModuloDAO(con).intentos(e.getIdEstudiante(), m.getIdModulo())) {
            if (im.getIdInscripcion() == i.getIdInscripcion() && "EN_CURSO".equals(im.getEstado())) {
                return im.getIdInscripcionModulo();
            }
        }
        throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no está cursando <b>" + m.getCodigo() + "</b> en esta inscripción. "
                + "Registre el pago sin módulo: lo tomará el próximo INSMOD.");
    }

    // No se anula lo que respalda algo ya otorgado: un certificado, un módulo en curso o una inscripción formalizada
    private void validarQueSePuedeAnular(Connection con, Pago pago) throws SQLException {
        if (pago.getIdCertificado() != null) {
            throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> respalda un certificado emitido y no se puede anular.");
        }
        PagoDAO dao = new PagoDAO(con);
        String modulo = dao.moduloEnCursoRespaldado(pago.getIdPago());
        if (modulo != null) {
            throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> respalda el módulo <b>" + modulo
                    + "</b>, que está EN_CURSO. Secretaría debe retirarlo primero con RETMOD.");
        }
        if ("MATRICULA".equals(pago.getConcepto()) && "CONCILIADO".equals(pago.getEstadoConciliacion())
                && "INSCRITO".equals(new InscripcionDAO(con).buscarPorId(pago.getIdInscripcion()).getEstado())
                && !dao.otraMatriculaActiva(pago.getIdInscripcion(), pago.getIdPago(), true)) {
            throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> es la matrícula de una inscripción ya formalizada. "
                    + "Secretaría debe darla de baja primero con ESTINS.");
        }
    }
}
