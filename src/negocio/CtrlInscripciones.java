package src.negocio;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import src.modelo.Adjunto;
import src.modelo.AsignacionModulo;
import src.modelo.Estudiante;
import src.modelo.Inscripcion;
import src.modelo.InscripcionModulo;
import src.modelo.Modulo;
import src.modelo.ModuloVersion;
import src.modelo.OfertaAcademica;
import src.persistencia.AsignacionDAO;
import src.persistencia.DocumentoDAO;
import src.persistencia.EstudianteDAO;
import src.persistencia.HistorialDAO;
import src.persistencia.InscripcionDAO;
import src.persistencia.InscripcionModuloDAO;
import src.persistencia.MallaDAO;
import src.persistencia.OfertaDAO;
import src.persistencia.PagoDAO;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-03: PREINSCRIPCIÓN, EXPEDIENTE ÚNICO Y CICLO DE VIDA DEL ESTUDIANTE
// En los comandos nada se identifica por ID: el estudiante por su CI o código y
// la inscripción por el estudiante y la oferta ("7654321", "TAC", "2-2026", "A")
// =========================================================================
public class CtrlInscripciones extends CtrlBase {

    // Regla de la Dirección: módulos que un estudiante puede cursar a la vez
    private static final int MAX_MODULOS_SIMULTANEOS = 2;

    private static final String CARNET = "CARNET_IDENTIDAD";

    private static final List<String> ESTADOS = List.of("PREINSCRITO", "INSCRITO", "ABANDONO", "CONCLUIDO", "RETIRADO",
            "TRASPASADO");

    // Estados de baja que se registran con ESTINS
    private static final List<String> ESTADOS_BAJA = List.of("ABANDONO", "RETIRADO");

    // Nombre del campo en el comando -> columna en la BD
    private static final Map<String, String> ALIAS_CAMPOS = Map.of(
            "CORREO", "email",
            "EXPEDICION", "ci_expedicion",
            "FECHA_NAC", "fecha_nacimiento");

    public CtrlInscripciones(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Preinscripción
    // ---------------------------------------------------------------------

    // PREINS[ci, nombre, paterno, materno, fecha_nacimiento, telefono, ciudad, programa, gestion, grupo]
    // Público y sin cobro: no crea USUARIO. La fotocopia del CI va adjunta al correo
    public String preinscribir(String[] p, String remitente, List<Adjunto> adjuntos) {
        if (p.length < 10) {
            return formato("PREINS[\"ci\", \"nombre\", \"paterno\", \"materno\", \"fecha_nacimiento(AAAA-MM-DD)\", \"telefono\", "
                    + "\"ciudad\", " + OFERTA + "], con la fotocopia del CI adjunta");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOferta(con, p, 7);
            if (!"PREINSCRIPCIONES_ABIERTAS".equals(o.getEstado())) {
                throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado()
                        + "</b> y no recibe preinscripciones. Envíe <code>CATALOGO</code> para ver las ofertas abiertas.");
            }
            validarCupo(con, o);

            String ci = p[0].trim();
            if (!Validador.esCi(ci)) {
                throw new ReglaNegocioException("El CI <b>" + ci + "</b> no es válido. Use solo números y letras, por ejemplo 7654321 o 7654321-1A.");
            }
            requerido(p[1], "nombre");
            requerido(p[2], "paterno");
            for (int i : new int[] { 1, 2, 3, 5, 6 }) {
                if (Validador.contieneHtml(p[i])) {
                    throw new ReglaNegocioException("Los datos no pueden contener los caracteres &lt; ni &gt;.");
                }
            }
            LocalDate nacimiento = validarFechaNacimiento(p[4]);
            if (!Validador.esEmail(remitente)) {
                throw new ReglaNegocioException("No se pudo identificar el correo del remitente.");
            }
            String correo = remitente.trim().toLowerCase();

            EstudianteDAO estudianteDAO = new EstudianteDAO(con);
            InscripcionDAO inscripcionDAO = new InscripcionDAO(con);
            DocumentoDAO documentoDAO = new DocumentoDAO(con);
            Estudiante e = estudianteDAO.buscarPorCi(ci);

            // PREINS es público: nadie puede cambiar un expediente ajeno enviando su CI desde otro correo
            if (e != null && e.getEmail() != null && !e.getEmail().equalsIgnoreCase(correo)) {
                throw new ReglaNegocioException("El CI <b>" + ci + "</b> ya está registrado con otro correo. Envíe la solicitud "
                        + "desde ese correo o acérquese a ventanilla para actualizarlo.");
            }
            if (e != null) {
                rechazarSiYaInscrito(inscripcionDAO.buscar(e.getIdEstudiante(), o.getIdOferta()), o);
            }
            List<String> pendientes = inscripcionDAO.prerrequisitosPendientes(e == null ? 0 : e.getIdEstudiante(), o.getIdOferta());
            if (!pendientes.isEmpty()) {
                throw new ReglaNegocioException("No cumple el prerrequisito del programa. Debe haber concluido: <b>"
                        + String.join(", ", pendientes) + "</b>. Si tiene el título, preséntelo en ventanilla para que se cargue en su expediente.");
            }
            Adjunto copiaCi = e != null && documentoDAO.tiene(e.getIdEstudiante(), CARNET) ? null
                    : elegirArchivo(adjuntos, "fotocopia del CI");

            String cambioEstudiante = "UPDATE";
            if (e == null) {
                e = new Estudiante();
                e.setCi(ci);
                // codigo_estudiante es NOT NULL; el código definitivo se asigna en FORMINS
                e.setCodigoEstudiante("PRE-" + ci);
                llenarDatos(e, p, nacimiento, correo);
                estudianteDAO.insertar(e);
                cambioEstudiante = "INSERT";
            } else if (!e.tieneCodigoDefinitivo()) {
                llenarDatos(e, p, nacimiento, correo);
                estudianteDAO.actualizarDatosPreinscripcion(e);
            } else if (e.getEmail() == null) {
                // Los datos de un estudiante ya formalizado fueron verificados en ventanilla: solo se completa el correo
                estudianteDAO.actualizarCampo(e.getIdEstudiante(), "email", correo);
            }

            Inscripcion i = new Inscripcion(e.getIdEstudiante(), o.getIdOferta());
            inscripcionDAO.insertar(i);
            // Preinscribirse es público: HISTORIAL_ESTADO y LOG registran lo que hace un usuario del sistema.
            // No se escribe historial y el LOG solo si el remitente tiene cuenta; la fecha queda en INSCRIPCION
            log(con, "ESTUDIANTE", cambioEstudiante);
            log(con, "INSCRIPCION", "INSERT");
            if (copiaCi != null) {
                documentoDAO.insertar(CARNET, guardarDocumento(ci, CARNET, copiaCi), e.getIdEstudiante());
                log(con, "DOCUMENTO_DIGITAL", "INSERT");
            }
            return GeneradorHTML.mensajeExito("Preinscripción Registrada",
                    "<b>" + e.getNombreCompleto() + "</b> (CI " + ci + ") quedó <b>PREINSCRITO</b> en la " + o.getDescripcion()
                            + ", que inicia el " + o.getFechaInicio() + ". La preinscripción no tiene costo.<br>"
                            + "Cuando la oferta se confirme, pague la matrícula y presente sus documentos en ventanilla "
                            + "para formalizar su inscripción. Las notificaciones llegarán a <b>" + correo + "</b>.");
        });
    }

    // LISINS[programa, gestion, grupo, estado]: sin estado lista todas las inscripciones de la oferta
    public String listarInscripciones(String[] p) {
        if (p.length < 3) {
            return formato("LISINS[" + OFERTA + ", \"estado (opcional)\"]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOferta(con, p, 0);
            String estado = filtroListado(param(p, 3));
            if (!estado.equals("*")) {
                estado = opcion(estado, ESTADOS, "estado");
            }
            Tabla t = new InscripcionDAO(con).listarPorOferta(o.getIdOferta(), estado);
            log(con, "INSCRIPCION", "SELECT");
            return GeneradorHTML.tabla("las Inscripciones de la oferta " + o.getCodigoPrograma() + " " + o.getGestion()
                    + " grupo " + o.getGrupo() + (estado.equals("*") ? "" : " en estado " + estado), t);
        });
    }

    // MODPRE[ci, campo, nuevo_valor]: corrige los datos mientras haya una inscripción en PREINSCRITO
    public String modificarPreinscrito(String[] p) {
        if (p.length < 3) {
            return formato("MODPRE[\"ci\", \"campo\", \"nuevo_valor\"] (campos: ci, nombre, paterno, materno, ci_expedicion, "
                    + "fecha_nacimiento, direccion, telefono, email, nivel_escolaridad, ciudad, pais)");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            if (!new InscripcionDAO(con).tienePreinscripcion(e.getIdEstudiante())) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene ninguna inscripción en PREINSCRITO. "
                        + "Sus datos se corrigen con MODEST.");
            }
            EstudianteDAO dao = new EstudianteDAO(con);
            String campo = columna(p[1], ALIAS_CAMPOS);
            if (campo.equals("ci")) {
                corregirCi(dao, e, p[2]);
            } else if (EstudianteDAO.CAMPOS.contains(campo)) {
                dao.actualizarCampo(e.getIdEstudiante(), campo, valorEstudiante(campo, p[2]));
            } else {
                throw new ReglaNegocioException("El campo <b>" + p[1] + "</b> no se puede modificar. Campos válidos: ci, "
                        + String.join(", ", EstudianteDAO.CAMPOS) + ".");
            }
            log(con, "ESTUDIANTE", "UPDATE");
            return GeneradorHTML.mensajeExito("Corrección de Preinscripción",
                    "Se actualizó <b>" + campo + "</b> de <b>" + e.getNombreCompleto() + "</b> a <b>" + p[2].trim() + "</b>.");
        });
    }

    // ANUPRE[ci, programa, gestion, grupo, motivo]: la inscripción pasa a RETIRADO, no se borra
    public String anularPreinscripcion(String[] p) {
        if (p.length < 4) {
            return formato("ANUPRE[\"ci\", " + OFERTA + ", \"motivo (opcional)\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            OfertaAcademica o = buscarOferta(con, p, 1);
            Inscripcion i = buscarInscripcion(con, e, o);
            if (!"PREINSCRITO".equals(i.getEstado())) {
                throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                        + " está <b>" + i.getEstado() + "</b>: solo se anula una inscripción en PREINSCRITO.");
            }
            String motivo = Validador.vacio(param(p, 4)) ? "Preinscripción anulada" : "Preinscripción anulada: " + p[4].trim();
            cambiarEstado(con, e, i, "RETIRADO", motivo);
            return GeneradorHTML.mensajeExito("Anulación de Preinscripción",
                    "La preinscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion() + " pasó a <b>RETIRADO</b>.<br>"
                            + "Para revertirla envíe <code>RESPRE[\"" + e.getCi() + "\", " + o.getReferencia() + "]</code>.");
        });
    }

    // RESPRE[ci, programa, gestion, grupo]: revierte ANUPRE; la inscripción vuelve a PREINSCRITO
    public String restaurarPreinscripcion(String[] p) {
        if (p.length < 4) {
            return formato("RESPRE[\"ci\", " + OFERTA + "]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            OfertaAcademica o = buscarOferta(con, p, 1);
            Inscripcion i = buscarInscripcion(con, e, o);
            validarRestauracion(con, e, o, i, "PREINSCRITO", "RESPRE", "RESINS");
            cambiarEstado(con, e, i, "PREINSCRITO", "Preinscripción restaurada");
            return GeneradorHTML.mensajeExito("Restauración de Preinscripción",
                    "<b>" + e.getNombreCompleto() + "</b> volvió a <b>PREINSCRITO</b> en la " + o.getDescripcion() + ".");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Formalización de la inscripción
    // ---------------------------------------------------------------------

    // FORMINS[ci, programa, gestion, grupo]: con la oferta confirmada y la matrícula conciliada
    public String formalizarInscripcion(String[] p) {
        if (p.length < 4) {
            return formato("FORMINS[\"ci\", " + OFERTA + "]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            OfertaAcademica o = buscarOferta(con, p, 1);
            Inscripcion i = buscarInscripcion(con, e, o);
            if (!"PREINSCRITO".equals(i.getEstado())) {
                throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                        + " está <b>" + i.getEstado() + "</b>: solo se formaliza una inscripción en PREINSCRITO.");
            }
            // El CU-04 admite el pago de la matrícula con la oferta CONFIRMADA o EN_CURSO
            if (!"CONFIRMADA".equals(o.getEstado()) && !"EN_CURSO".equals(o.getEstado())) {
                throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado()
                        + "</b>: solo se formaliza con la oferta CONFIRMADA.");
            }
            if (!new PagoDAO(con).matriculaConciliada(i.getIdInscripcion())) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene la matrícula conciliada en esta oferta. "
                        + "La Secretaria debe registrar y conciliar el pago.");
            }

            EstudianteDAO dao = new EstudianteDAO(con);
            String codigo = e.getCodigoEstudiante();
            // Código definitivo: año de la gestión de la oferta + CI ("2-2026" y 7654321 -> 20267654321).
            // El expediente es único: si ya tiene código de otra inscripción, lo conserva
            if (!e.tieneCodigoDefinitivo()) {
                codigo = o.getAnioGestion() + e.getCi();
                dao.actualizarCodigo(e.getIdEstudiante(), codigo);
                log(con, "ESTUDIANTE", "UPDATE");
            }
            cambiarEstado(con, e, i, "INSCRITO", "Inscripción formalizada");

            String detalle = "";
            if (e.getEmail() != null) {
                notificar(e.getEmail(), "Inscripcion formalizada en la oferta " + o.getCodigoPrograma() + " " + o.getGestion() + " grupo " + o.getGrupo(),
                        GeneradorHTML.mensajeExito("Inscripción Formalizada",
                                "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: su inscripción en la " + o.getDescripcion()
                                        + " quedó formalizada.<br>Su código de estudiante es <b>" + codigo + "</b>."));
                detalle = " Se le notificó a <b>" + e.getEmail() + "</b>.";
            }
            return GeneradorHTML.mensajeExito("Formalización de Inscripción",
                    "<b>" + e.getNombreCompleto() + "</b> quedó <b>INSCRITO</b> en la " + o.getDescripcion() + " con el código <b>"
                            + codigo + "</b>." + detalle + "<br>Siguiente paso: registrar sus módulos, por ejemplo <code>INSMOD[\""
                            + codigo + "\", \"codigo_modulo\"]</code>.");
        });
    }

    // ESTINS[ci, programa, gestion, grupo, nuevo_estado, motivo]: ABANDONO o RETIRADO; sus módulos en curso pasan a ABANDONO
    public String cambiarEstadoInscripcion(String[] p) {
        if (p.length < 6) {
            return formato("ESTINS[\"ci\", " + OFERTA + ", \"nuevo_estado\", \"motivo\"] (estados: ABANDONO, RETIRADO)");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            OfertaAcademica o = buscarOferta(con, p, 1);
            Inscripcion i = buscarInscripcion(con, e, o);
            String nuevo = opcion(p[4], ESTADOS_BAJA, "nuevo_estado");
            requerido(p[5], "motivo");
            if (!"INSCRITO".equals(i.getEstado())) {
                throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                        + " está <b>" + i.getEstado() + "</b>: ESTINS solo se aplica a una inscripción en INSCRITO."
                        + ("PREINSCRITO".equals(i.getEstado()) ? " Una preinscripción se anula con ANUPRE." : ""));
            }
            cambiarEstado(con, e, i, nuevo, p[5].trim());

            // Quien deja la oferta ya no cursa sus módulos
            List<String> modulos = new InscripcionModuloDAO(con).abandonarEnCurso(i.getIdInscripcion());
            HistorialDAO historial = new HistorialDAO(con);
            for (String modulo : modulos) {
                historial.registrar("EN_CURSO", "ABANDONO", "Módulo " + modulo + ": baja de la inscripción (" + nuevo + ")",
                        e.getIdEstudiante(), i.getIdInscripcion(), idRemitente);
            }
            if (!modulos.isEmpty()) {
                log(con, "INSCRIPCION_MODULO", "UPDATE");
            }
            return GeneradorHTML.mensajeExito("Cambio de Estado",
                    "La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion() + " pasó a <b>" + nuevo + "</b>."
                            + (modulos.isEmpty() ? "" : "<br>Módulos que pasaron a ABANDONO: <b>" + String.join(", ", modulos) + "</b>.")
                            + "<br>Para revertirlo envíe <code>RESINS[\"" + e.getCi() + "\", " + o.getReferencia() + ", \"motivo\"]</code>.");
        });
    }

    // RESINS[ci, programa, gestion, grupo, motivo]: revierte ESTINS; la inscripción vuelve a INSCRITO.
    // Sus módulos siguen en ABANDONO: cada uno se restaura con RESMOD si corresponde
    public String restaurarInscripcion(String[] p) {
        if (p.length < 4) {
            return formato("RESINS[\"ci\", " + OFERTA + ", \"motivo (opcional)\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            OfertaAcademica o = buscarOferta(con, p, 1);
            Inscripcion i = buscarInscripcion(con, e, o);
            validarRestauracion(con, e, o, i, "INSCRITO", "RESINS", "RESPRE");
            String motivo = Validador.vacio(param(p, 4)) ? "Inscripción restaurada" : "Inscripción restaurada: " + p[4].trim();
            cambiarEstado(con, e, i, "INSCRITO", motivo);
            return GeneradorHTML.mensajeExito("Restauración de Inscripción",
                    "<b>" + e.getNombreCompleto() + "</b> volvió a <b>INSCRITO</b> en la " + o.getDescripcion() + ".<br>"
                            + "Sus módulos siguen en ABANDONO: restaure los que correspondan con <code>RESMOD[\""
                            + e.getCodigoEstudiante() + "\", \"codigo_modulo\"]</code>.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo C - Registro de asignaturas
    // ---------------------------------------------------------------------

    // INSMOD[estudiante, modulo, programa, gestion, grupo]: la oferta solo hace falta si el módulo está en dos de sus inscripciones
    public String inscribirModulo(String[] p) {
        if (p.length < 2) {
            return formato("INSMOD[\"codigo_estudiante o ci\", \"modulo\", " + OFERTA + " (la oferta es opcional)]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            Modulo m = buscarModulo(con, p[1]);
            Inscripcion i = inscripcionParaModulo(con, e, m, p);
            OfertaAcademica o = new OfertaDAO(con).buscarPorId(i.getIdOferta());
            if (o.estaCerrada()) {
                throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado() + "</b>.");
            }

            InscripcionModuloDAO dao = new InscripcionModuloDAO(con);
            InscripcionModulo intento = siguienteIntento(dao.intentos(e.getIdEstudiante(), m.getIdModulo()), e, m, i);

            AsignacionModulo a = new AsignacionDAO(con).buscar(o.getIdOferta(), m.getIdModulo());
            if (a == null) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> todavía no tiene profesor asignado en la "
                        + o.getDescripcion() + ".");
            }
            if (a.getFechaFin().isBefore(LocalDate.now())) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> ya terminó el " + a.getFechaFin() + ".");
            }
            validarActaAbierta(con, a, m);

            ModuloVersion mv = new MallaDAO(con).buscar(o.getIdVersion(), m.getIdModulo());
            List<String> pendientes = dao.prerrequisitosPendientes(e.getIdEstudiante(), mv.getIdModuloVersion());
            if (!pendientes.isEmpty()) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> debe aprobar antes, con nota de 51 o más: <b>"
                        + String.join(", ", pendientes) + "</b>.");
            }
            validarCarga(dao, e, a);

            // Art. 30: el módulo se registra previo pago
            PagoDAO pagoDAO = new PagoDAO(con);
            Integer pago = pagoDAO.mensualidadDisponible(i.getIdInscripcion());
            if (pago == null) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene una mensualidad conciliada disponible para este módulo. "
                        + "La Secretaria debe registrar y conciliar el pago.");
            }

            dao.insertar(intento);
            pagoDAO.asociarModulo(pago, intento.getIdInscripcionModulo());
            new HistorialDAO(con).registrar(null, "EN_CURSO", "Módulo " + m.getCodigo() + ": registrado, intento "
                    + intento.getNumeroIntento() + " (" + intento.getCondicion() + ")", e.getIdEstudiante(), i.getIdInscripcion(), idRemitente);
            log(con, "INSCRIPCION_MODULO", "INSERT");
            log(con, "PAGO", "UPDATE");
            return GeneradorHTML.mensajeExito("Registro de Módulo",
                    "<b>" + e.getNombreCompleto() + "</b> quedó <b>EN_CURSO</b> en <b>" + m.getCodigo() + " - " + m.getNombre()
                            + "</b> de la " + o.getDescripcion() + " (intento " + intento.getNumeroIntento() + ", "
                            + intento.getCondicion() + ").");
        });
    }

    // RETMOD[estudiante, modulo, motivo]: retira al estudiante de un módulo antes de su cierre; pasa a ABANDONO
    public String retirarModulo(String[] p) {
        if (p.length < 2) {
            return formato("RETMOD[\"codigo_estudiante o ci\", \"modulo\", \"motivo (opcional)\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            Modulo m = buscarModulo(con, p[1]);
            InscripcionModuloDAO dao = new InscripcionModuloDAO(con);
            InscripcionModulo intento = null;
            for (InscripcionModulo im : dao.intentos(e.getIdEstudiante(), m.getIdModulo())) {
                if ("EN_CURSO".equals(im.getEstado())) {
                    intento = im;
                }
            }
            if (intento == null) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no está cursando <b>" + m.getCodigo() + "</b>.");
            }
            OfertaAcademica o = ofertaDe(con, intento);
            validarActaAbierta(con, asignacionDe(con, o, m), m);

            dao.cambiarEstado(intento.getIdInscripcionModulo(), "ABANDONO");
            String motivo = "Módulo " + m.getCodigo() + ": retirado" + (Validador.vacio(param(p, 2)) ? "" : " (" + p[2].trim() + ")");
            new HistorialDAO(con).registrar("EN_CURSO", "ABANDONO", motivo, e.getIdEstudiante(), intento.getIdInscripcion(), idRemitente);
            log(con, "INSCRIPCION_MODULO", "UPDATE");
            return GeneradorHTML.mensajeExito("Retiro de Módulo",
                    "<b>" + e.getNombreCompleto() + "</b> fue retirado de <b>" + m.getCodigo() + "</b> en la " + o.getDescripcion()
                            + ": el módulo pasó a <b>ABANDONO</b>.<br>Para revertirlo envíe <code>RESMOD[\""
                            + e.getCodigoEstudiante() + "\", \"" + m.getCodigo() + "\"]</code>.");
        });
    }

    // RESMOD[estudiante, modulo]: revierte RETMOD; el último intento vuelve a EN_CURSO sin un pago nuevo,
    // repitiendo los controles de cierre, choque de horario y módulos simultáneos
    public String restaurarModulo(String[] p) {
        if (p.length < 2) {
            return formato("RESMOD[\"codigo_estudiante o ci\", \"modulo\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            Modulo m = buscarModulo(con, p[1]);
            InscripcionModuloDAO dao = new InscripcionModuloDAO(con);
            List<InscripcionModulo> intentos = dao.intentos(e.getIdEstudiante(), m.getIdModulo());
            if (intentos.isEmpty()) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene registrado <b>" + m.getCodigo() + "</b>.");
            }
            InscripcionModulo ultimo = intentos.get(intentos.size() - 1);
            if (!"ABANDONO".equals(ultimo.getEstado())) {
                throw new ReglaNegocioException("El último intento de <b>" + e.getNombreCompleto() + "</b> en <b>" + m.getCodigo()
                        + "</b> está <b>" + ultimo.getEstado() + "</b>: solo se restaura un módulo en ABANDONO.");
            }
            Inscripcion i = new InscripcionDAO(con).buscarPorId(ultimo.getIdInscripcion());
            OfertaAcademica o = ofertaDe(con, ultimo);
            if (!"INSCRITO".equals(i.getEstado())) {
                throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                        + " está <b>" + i.getEstado() + "</b>. Restáurela primero con <code>RESINS[\"" + e.getCi() + "\", "
                        + o.getReferencia() + "]</code>.");
            }
            AsignacionModulo a = asignacionDe(con, o, m);
            validarActaAbierta(con, a, m);
            validarCarga(dao, e, a);

            dao.cambiarEstado(ultimo.getIdInscripcionModulo(), "EN_CURSO");
            new HistorialDAO(con).registrar("ABANDONO", "EN_CURSO", "Módulo " + m.getCodigo() + ": restaurado", e.getIdEstudiante(),
                    i.getIdInscripcion(), idRemitente);
            log(con, "INSCRIPCION_MODULO", "UPDATE");
            return GeneradorHTML.mensajeExito("Restauración de Módulo",
                    "<b>" + e.getNombreCompleto() + "</b> volvió a <b>EN_CURSO</b> en <b>" + m.getCodigo() + "</b> de la "
                            + o.getDescripcion() + ".");
        });
    }

    // KARDEX[estudiante]: inscripciones, módulos con intentos, notas y asistencia, e historial de cambios de estado
    public String kardex(String valor) {
        if (Validador.vacio(valor)) {
            return formato("KARDEX[\"codigo_estudiante o ci\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, valor);
            Tabla inscripciones = new InscripcionDAO(con).listarPorEstudiante(e.getIdEstudiante());
            Tabla modulos = new InscripcionModuloDAO(con).kardex(e.getIdEstudiante());
            Tabla historial = new HistorialDAO(con).listarPorEstudiante(e.getIdEstudiante());
            log(con, "INSCRIPCION_MODULO", "SELECT");
            log(con, "HISTORIAL_ESTADO", "SELECT");
            return GeneradorHTML.tablas(new String[] {
                    "el Kardex de " + e.getNombreCompleto() + " (código " + e.getCodigoEstudiante() + ", CI " + e.getCi() + "): inscripciones",
                    "sus Módulos, intentos, notas y asistencia",
                    "su Historial de cambios de estado" }, inscripciones, modulos, historial);
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    private OfertaAcademica ofertaDe(Connection con, InscripcionModulo intento) throws SQLException {
        Inscripcion i = new InscripcionDAO(con).buscarPorId(intento.getIdInscripcion());
        return new OfertaDAO(con).buscarPorId(i.getIdOferta());
    }

    private AsignacionModulo asignacionDe(Connection con, OfertaAcademica o, Modulo m) throws SQLException {
        AsignacionModulo a = new AsignacionDAO(con).buscar(o.getIdOferta(), m.getIdModulo());
        if (a == null) {
            throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> ya no tiene asignación en la " + o.getDescripcion() + ".");
        }
        return a;
    }

    private void validarCupo(Connection con, OfertaAcademica o) throws SQLException {
        if (o.getCupoMax() != null && new OfertaDAO(con).contarPreinscritos(o.getIdOferta()) >= o.getCupoMax()) {
            throw new ReglaNegocioException("La " + o.getDescripcion() + " ya no tiene cupos: alcanzó su máximo de <b>" + o.getCupoMax() + "</b>.");
        }
    }

    private void rechazarSiYaInscrito(Inscripcion previa, OfertaAcademica o) {
        if (previa == null) {
            return;
        }
        if ("RETIRADO".equals(previa.getEstado())) {
            throw new ReglaNegocioException("Su inscripción en la " + o.getDescripcion() + " fue anulada. "
                    + "Para recuperarla, solicítelo a Secretaría.");
        }
        throw new ReglaNegocioException("Ya tiene una inscripción en la " + o.getDescripcion() + ", en estado <b>" + previa.getEstado() + "</b>.");
    }

    private static void llenarDatos(Estudiante e, String[] p, LocalDate nacimiento, String correo) {
        e.setNombre(p[1].trim());
        e.setPaterno(p[2].trim());
        e.setMaterno(nulo(p[3]));
        e.setFechaNacimiento(nacimiento);
        e.setTelefono(nulo(p[5]));
        e.setCiudad(nulo(p[6]));
        e.setEmail(correo);
    }

    // Un error de tipeo en el CI se corrige mientras el código siga siendo el provisional, que se deriva de él
    private void corregirCi(EstudianteDAO dao, Estudiante e, String valor) throws SQLException {
        String ci = valor.trim();
        if (!Validador.esCi(ci)) {
            throw new ReglaNegocioException("El CI <b>" + ci + "</b> no es válido.");
        }
        if (e.tieneCodigoDefinitivo()) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene el código definitivo " + e.getCodigoEstudiante()
                    + ": su CI ya fue verificado en ventanilla.");
        }
        if (dao.buscarPorCi(ci) != null) {
            throw new ReglaNegocioException("Ya existe un estudiante con el CI <b>" + ci + "</b>.");
        }
        dao.actualizarCiYCodigo(e.getIdEstudiante(), ci, "PRE-" + ci);
    }

    private void cambiarEstado(Connection con, Estudiante e, Inscripcion i, String nuevo, String motivo) throws SQLException {
        new InscripcionDAO(con).cambiarEstado(i.getIdInscripcion(), nuevo);
        new HistorialDAO(con).registrar(i.getEstado(), nuevo, motivo, e.getIdEstudiante(), i.getIdInscripcion(), idRemitente);
        log(con, "INSCRIPCION", "UPDATE");
        log(con, "HISTORIAL_ESTADO", "INSERT");
    }

    // RESPRE y RESINS revierten bajas distintas: el historial dice de qué estado venía la inscripción
    private void validarRestauracion(Connection con, Estudiante e, OfertaAcademica o, Inscripcion i, String estadoEsperado,
            String comando, String otroComando) throws SQLException {
        String anterior = "RETIRADO".equals(i.getEstado()) || "ABANDONO".equals(i.getEstado())
                ? new HistorialDAO(con).estadoAntesDeLaBaja(i.getIdInscripcion())
                : null;
        if (anterior == null) {
            throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                    + " está <b>" + i.getEstado() + "</b>: no hay una baja que restaurar.");
        }
        if (!anterior.equals(estadoEsperado)) {
            throw new ReglaNegocioException("Esa baja se hizo sobre una inscripción en " + anterior + ": se revierte con <b>" + otroComando
                    + "</b>, no con " + comando + ".");
        }
        if (o.estaCerrada()) {
            throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado() + "</b>: no se puede restaurar.");
        }
        validarCupo(con, o);
    }

    private Inscripcion inscripcionParaModulo(Connection con, Estudiante e, Modulo m, String[] p) throws SQLException {
        InscripcionDAO dao = new InscripcionDAO(con);
        if (p.length >= 5) {
            OfertaAcademica o = buscarOferta(con, p, 2);
            Inscripcion i = buscarInscripcion(con, e, o);
            if (!"INSCRITO".equals(i.getEstado())) {
                throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> en la " + o.getDescripcion()
                        + " está <b>" + i.getEstado() + "</b>: debe estar INSCRITO.");
            }
            if (new MallaDAO(con).buscar(o.getIdVersion(), m.getIdModulo()) == null) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> no pertenece a la malla de la " + o.getDescripcion() + ".");
            }
            return i;
        }
        List<Inscripcion> candidatas = dao.inscritasConModulo(e.getIdEstudiante(), m.getIdModulo());
        if (candidatas.isEmpty()) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene una inscripción en INSCRITO cuya malla incluya <b>"
                    + m.getCodigo() + "</b>. Revise su <code>KARDEX[\"" + e.getCi() + "\"]</code>.");
        }
        if (candidatas.size() > 1) {
            throw new ReglaNegocioException("<b>" + m.getCodigo() + "</b> está en más de una inscripción de <b>" + e.getNombreCompleto()
                    + "</b>. Indique la oferta: <code>INSMOD[\"" + e.getCi() + "\", \"" + m.getCodigo() + "\", " + OFERTA + "]</code>.");
        }
        return candidatas.get(0);
    }

    // Primer intento REGULAR. Si perdió el módulo lo repite por REPECHAJE; si lo abandonó, lo vuelve a cursar REGULAR
    private InscripcionModulo siguienteIntento(List<InscripcionModulo> previos, Estudiante e, Modulo m, Inscripcion i) {
        InscripcionModulo nuevo = new InscripcionModulo();
        nuevo.setIdInscripcion(i.getIdInscripcion());
        nuevo.setIdModulo(m.getIdModulo());
        for (InscripcionModulo previo : previos) {
            if ("EN_CURSO".equals(previo.getEstado()) || "APROBADO".equals(previo.getEstado())) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene <b>" + m.getCodigo() + "</b> en estado <b>"
                        + previo.getEstado() + "</b>.");
            }
            if (previo.getIdInscripcion() == i.getIdInscripcion() && previo.getNumeroIntento() >= nuevo.getNumeroIntento()) {
                nuevo.setNumeroIntento(previo.getNumeroIntento() + 1);
            }
            if ("REPROBADO".equals(previo.getEstado()) || "PERDIDA_POR_INASISTENCIA".equals(previo.getEstado())) {
                nuevo.setCondicion("REPECHAJE");
            }
        }
        return nuevo;
    }

    private void validarActaAbierta(Connection con, AsignacionModulo a, Modulo m) throws SQLException {
        if (!new AsignacionDAO(con).actaAbierta(a.getIdAsignacion())) {
            throw new ReglaNegocioException("El acta del módulo <b>" + m.getCodigo() + "</b> ya está cerrada.");
        }
    }

    // Regla de la Dirección (como máximo 2 módulos a la vez) y choque con los módulos que ya cursa
    private void validarCarga(InscripcionModuloDAO dao, Estudiante e, AsignacionModulo a) throws SQLException {
        if (dao.contarEnCurso(e.getIdEstudiante()) >= MAX_MODULOS_SIMULTANEOS) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya cursa " + MAX_MODULOS_SIMULTANEOS
                    + " módulos: es el máximo simultáneo permitido.");
        }
        String choque = dao.choqueHorario(e.getIdEstudiante(), a.getIdAsignacion());
        if (choque != null) {
            throw new ReglaNegocioException("Choque de horario: <b>" + e.getNombreCompleto() + "</b> ya cursa " + choque + ".");
        }
    }
}
