package src.negocio;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import src.modelo.Adjunto;
import src.modelo.Usuario;
import src.persistencia.ConexionBD;
import src.persistencia.EstudianteDAO;
import src.persistencia.LogDAO;
import src.persistencia.RolDAO;
import src.persistencia.UsuarioDAO;
import src.utilidades.ArchivoLog;
import src.utilidades.GeneradorHTML;

public class ProcesadorComandos {

    // Comandos implementados (se amplía con cada caso de uso)
    private static final Set<String> COMANDOS = Set.of(
            // CU-01: Gestión de Usuarios
            "ADDPER", "LISPER", "MODPER", "DELPER", "RESPER",
            "ADDCAP", "LISCAP", "MODCAP", "DELCAP", "RESCAP",
            "ADDEST", "LISEST", "MODEST", "DELEST",
            "ASIGROL", "LISROL", "QUIROL", "LISUSR",
            "MODCLAVE", "RESCLAVE",
            // CU-02: Catálogo de Programas, Versionado de Mallas y Ofertas Académicas
            "ADDPROG", "LISPROG", "MODPROG", "DELPROG",
            "ADDVER", "LISVER", "MODVER", "DELVER", "APRVER",
            "ADDMALLA", "LISMALLA", "MODMALLA", "DELMALLA",
            "ADDPRE", "LISPRE", "DELPRE",
            "ADDMOD", "LISMOD", "MODMOD", "DELMOD",
            "ADDOFER", "LISOFER", "MODOFER", "DELOFER", "APROFER", "CATALOGO",
            "ADDASIG", "LISASIG", "MODASIG", "DELASIG",
            "ADDHOR", "LISHOR", "MODHOR", "DELHOR",
            // CU-03: Preinscripción, Expediente Único y Ciclo de Vida del Estudiante
            "PREINS", "LISINS", "MODPRE", "ANUPRE", "RESPRE",
            "FORMINS", "ESTINS", "RESINS",
            "INSMOD", "RETMOD", "RESMOD", "KARDEX",
            // CU-04: Aranceles, Becas, Conciliación de Pagos y Pasarela Digital
            "ADDARAN", "LISARAN", "MODARAN", "DELARAN", "RESARAN",
            "ADDCONV", "LISCONV", "MODCONV", "DELCONV", "RESCONV", "ASIGCONV", "QUICONV",
            "ADDPAGO", "ESTCTA", "LISPAGO", "MODPAGO", "ANUPAGO", "RESPAGO", "CONCPAGO", "OBSPAGO", "GENPAGO",
            // CU-05: Asistencia, Evaluaciones y Flujo de Corrección de Notas
            "ADDACT", "LISACT", "MODACT", "DELACT",
            "ADDSES", "LISSES", "MODSES", "ADDASIS", "LISASIS", "MODASIS",
            "ADDNOTA", "LISNOTA", "MODNOTA",
            "VERACTA", "LISACTA", "CIEACTA", "HABREC", "ENMIENDA", "LISENM",
            // CU-06: Validar Requisitos y Emitir Certificados Digitales con QR y Código Único
            "SOLCERT", "LISELEG", "GENCERT", "LISCERT", "ANUCERT", "VERCERT",
            // CU-07: Repositorio Documental Histórico
            "ADDDOC", "LISDOC", "MODDOC", "DELDOC", "BUSDOC",
            // CU-08: Reportes y Estadísticas
            "REPING", "REPACAD", "REPDEM", "REPNOTAS", "REPCERT", "REPLOG");

    // Comandos públicos: cualquier remitente, aunque no tenga cuenta
    private static final Set<String> PUBLICOS = Set.of("CATALOGO", "PREINS", "SOLCERT", "VERCERT");

    // Rol del actor Estudiante (ROL / ROL_PERMISO): no se asigna en ROL_USUARIO porque el estudiante no tiene USUARIO
    static final String ROL_ESTUDIANTE = "Estudiante";

    // Cantidad máxima de valores de cada comando (contando los opcionales). Con más valores se rechaza sin tocar la base;
    // si faltan, cada controlador responde con la plantilla de campos. Así el comando debe llegar con la cantidad exacta
    private static final Map<String, Integer> MAXIMO_VALORES = maximos(
            0, "CATALOGO",
            1, "LISPER DELPER RESPER LISCAP DELCAP RESCAP LISEST DELEST LISROL LISUSR RESCLAVE LISPROG DELPROG LISVER LISMOD "
                    + "DELMOD LISOFER KARDEX LISCONV DELCONV RESCONV ESTCTA LISPAGO RESPAGO CONCPAGO LISCERT VERCERT",
            2, "ASIGROL QUIROL MODCLAVE DELVER LISMALLA LISPRE ADDMOD RESMOD LISARAN DELARAN RESARAN ANUPAGO OBSPAGO HABREC "
                    + "ANUCERT BUSDOC REPCERT REPLOG",
            3, "MODPER MODCAP MODEST ADDVER MODPROG DELMALLA MODMOD DELOFER APROFER LISHOR MODPRE RETMOD MODCONV "
                    + "MODPAGO LISELEG GENCERT ADDDOC LISDOC MODDOC DELDOC REPACAD REPDEM",
            4, "LISASIG ADDPROG MODVER ADDPRE DELPRE DELASIG LISINS RESPRE FORMINS MODARAN QUICONV LISACT LISSES LISASIS LISNOTA "
                    + "VERACTA LISACTA CIEACTA ENMIENDA LISENM SOLCERT REPING REPNOTAS",
            5, "APRVER ADDMALLA MODMALLA MODOFER DELHOR ANUPRE RESINS INSMOD ADDARAN ADDCONV ASIGCONV DELACT",
            6, "MODASIG ESTINS MODSES ADDASIS ADDNOTA GENPAGO",
            7, "MODHOR MODACT ADDSES MODASIS MODNOTA",
            8, "ADDPER ADDHOR ADDACT",
            9, "ADDASIG",
            10, "ADDOFER PREINS ADDCAP",
            12, "ADDEST ADDPAGO");

    private static Map<String, Integer> maximos(Object... pares) {
        Map<String, Integer> mapa = new HashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            for (String c : ((String) pares[i + 1]).split(" ")) {
                mapa.put(c, (Integer) pares[i]);
            }
        }
        // Un comando nuevo sin su máximo se detecta al arrancar, no cuando alguien lo envía
        for (String c : COMANDOS) {
            if (!mapa.containsKey(c)) {
                throw new IllegalStateException("Falta la cantidad máxima de valores del comando " + c);
            }
        }
        return mapa;
    }

    public String ejecutar(String asuntoBruto) {
        return ejecutar(null, asuntoBruto);
    }

    public String ejecutar(String remitente, String asuntoBruto) {
        return ejecutar(remitente, asuntoBruto, List.of());
    }

    // Archivos que van con la respuesta del último comando (MainICAP los adjunta al correo de respuesta)
    private List<Adjunto> adjuntosRespuesta = List.of();

    public List<Adjunto> getAdjuntosRespuesta() {
        return adjuntosRespuesta;
    }

    // Avisos a otras personas que dejaron los comandos confirmados (por ejemplo, al estudiante cuando se aprueba su pago).
    // MainICAP los envía después de responder al remitente, para que él no tenga que esperarlos
    public static void enviarNotificaciones() {
        CtrlBase.enviarNotificacionesPendientes();
    }

    // Ningún correo se queda sin respuesta: un error inesperado se registra en logs/error.log y se responde con un error
    public String ejecutar(String remitente, String asuntoBruto, List<Adjunto> adjuntos) {
        adjuntosRespuesta = List.of();
        ArchivoLog.contexto(remitente, asuntoBruto == null ? "" : asuntoVisible(asuntoBruto));
        try {
            return ejecutarComando(remitente, asuntoBruto, adjuntos);
        } catch (RuntimeException e) {
            adjuntosRespuesta = List.of();
            ArchivoLog.error("Error interno al ejecutar el comando", e);
            System.out.println(">>> [ERROR]: " + e + " (detalle en logs/error.log)");
            return GeneradorHTML.mensajeError("Ocurrió un error interno al procesar el comando y no se realizó ningún cambio. "
                    + "Quedó registrado para su revisión; intente nuevamente más tarde.");
        } finally {
            CtrlBase.estudianteRemitente(null);
            ArchivoLog.limpiarContexto();
        }
    }

    private String ejecutarComando(String remitente, String asuntoBruto, List<Adjunto> adjuntos) {
        String asunto = normalizar(asuntoBruto);
        if (asunto.isEmpty()) {
            return GeneradorHTML.mensajeError("El asunto (Subject) del correo está vacío.");
        }

        if (asunto.equalsIgnoreCase(GeneradorHTML.COMANDO_AYUDA) || asunto.equalsIgnoreCase("HELP")) {
            return GeneradorHTML.manualAyudaHTML();
        }

        int idxAbre = asunto.indexOf('[');
        int idxCierra = asunto.lastIndexOf(']');

        String accion;
        String[] params;
        int cantidad;
        if (idxAbre == -1 && idxCierra == -1) {
            // Comando sin parámetros, por ejemplo: CATALOGO
            accion = asunto.toUpperCase();
            params = new String[] { "" };
            cantidad = 0;
        } else if (idxAbre == -1 || idxCierra == -1 || idxCierra < idxAbre) {
            return GeneradorHTML.mensajeError("Sintaxis inválida en comando: <b>" + escapar(asunto)
                    + "</b>. Use el formato COMANDO[\"param1\", \"param2\"].");
        } else {
            accion = asunto.substring(0, idxAbre).trim().toUpperCase();
            String sobra = asunto.substring(idxCierra + 1).trim();
            if (!sobra.isEmpty() && COMANDOS.contains(accion)) {
                return GeneradorHTML.mensajeError("Sintaxis inválida: después del corchete de cierre sobra <b>" + escapar(sobra)
                        + "</b>. Todo el comando va dentro de <code>" + accion + "[...]</code>.");
            }
            String contenido = asunto.substring(idxAbre + 1, idxCierra);
            try {
                params = separarParametros(contenido);
            } catch (SintaxisInvalida e) {
                if (!COMANDOS.contains(accion)) {
                    params = null;
                } else {
                    return GeneradorHTML.mensajeError("Sintaxis inválida en <b>" + accion + "</b>: " + e.getMessage() + formas(accion));
                }
            }
            cantidad = contenido.trim().isEmpty() || params == null ? 0 : params.length;
        }

        if (!COMANDOS.contains(accion)) {
            // El pie del mensaje de error ya indica que helpme! muestra la ayuda
            return GeneradorHTML.mensajeError("El comando <b>" + escapar(accion) + "</b> no existe."
                    + sugerencia(accion, idxAbre == -1 ? "" : asunto.substring(idxAbre)));
        }

        int maximo = MAXIMO_VALORES.get(accion);
        if (cantidad > maximo) {
            return GeneradorHTML.mensajeError("El comando <b>" + accion + "</b> " + (maximo == 0 ? "no lleva valores"
                    : "recibe como máximo <b>" + maximo + "</b> valor" + (maximo == 1 ? "" : "es")) + " y el asunto trae <b>" + cantidad
                    + "</b>." + formas(accion));
        }

        // VALIDACIÓN DE SEGURIDAD: el remitente debe ser un USUARIO ACTIVO con un rol que tenga el permiso.
        // En los comandos públicos solo se identifica al remitente (0 si no tiene cuenta)
        int idRemitente = 0;
        try {
            Connection con = ConexionBD.conectar();
            UsuarioDAO usuarioDAO = new UsuarioDAO(con);
            Usuario u = remitente == null ? null : usuarioDAO.buscarPorEmail(remitente);
            if (PUBLICOS.contains(accion)) {
                if (u != null && u.estaActivo()) {
                    idRemitente = u.getIdUsuario();
                }
            } else if (u != null && u.estaActivo() && usuarioDAO.tienePermiso(u.getIdUsuario(), accion)) {
                idRemitente = u.getIdUsuario();
            } else if (remitente != null && new EstudianteDAO(con).existeConEmail(remitente)
                    && new RolDAO(con).rolTienePermiso(ROL_ESTUDIANTE, accion)) {
                // Actor Estudiante: no tiene USUARIO; se lo reconoce por el correo de su expediente y solo consulta sus datos.
                // LOG exige un usuario, así que sus consultas quedan solo en logs/access.log
                CtrlBase.estudianteRemitente(remitente.trim());
            } else {
                if (u != null) {
                    // LOG no tiene columna de detalle: en un rechazo se guarda el comando intentado
                    new LogDAO(con).registrar(u.getIdUsuario(), accion, "RECHAZADO");
                }
                return GeneradorHTML.mensajeError("Acceso denegado: El remitente <b>"
                        + (remitente != null ? remitente : "desconocido")
                        + "</b> no tiene permisos para ejecutar el comando <b>" + accion + "</b>.");
            }
        } catch (SQLException e) {
            ArchivoLog.error("Base de datos: no se pudo verificar el permiso del remitente", e);
            return GeneradorHTML.mensajeError("Error de conexión a la Base de Datos: " + e.getMessage());
        }

        CtrlUsuarios usuarios = new CtrlUsuarios(idRemitente);
        CtrlCatalogo catalogo = new CtrlCatalogo(idRemitente);
        CtrlOfertas ofertas = new CtrlOfertas(idRemitente);
        CtrlInscripciones inscripciones = new CtrlInscripciones(idRemitente);
        CtrlPagos pagos = new CtrlPagos(idRemitente);
        CtrlEvaluacion evaluacion = new CtrlEvaluacion(idRemitente);
        CtrlActas actas = new CtrlActas(idRemitente);
        CtrlCertificados certificados = new CtrlCertificados(idRemitente);
        CtrlDocumentos documentos = new CtrlDocumentos(idRemitente);
        CtrlReportes reportes = new CtrlReportes(idRemitente);

        switch (accion) {
            // CU-01 Flujo A: Personal administrativo
            case "ADDPER":
                return usuarios.registrarPersonal(params);
            case "LISPER":
                return usuarios.listarPersonal(params[0]);
            case "MODPER":
                return usuarios.modificarPersonal(params);
            case "DELPER":
                return usuarios.eliminarPersonal(params);
            case "RESPER":
                return usuarios.restaurarPersonal(params);
            // CU-01 Flujo B: Capacitadores
            case "ADDCAP":
                return usuarios.registrarCapacitador(params, adjuntos);
            case "LISCAP": {
                // Con un CI, el CV del profesor va adjunto en la respuesta
                String r = usuarios.listarCapacitadores(params[0]);
                adjuntosRespuesta = usuarios.getAdjuntosRespuesta();
                return r;
            }
            case "MODCAP":
                return usuarios.modificarCapacitador(params, adjuntos);
            case "DELCAP":
                return usuarios.eliminarCapacitador(params);
            case "RESCAP":
                return usuarios.restaurarCapacitador(params);
            // CU-01 Flujo C: Estudiantes
            case "ADDEST":
                return usuarios.registrarEstudiante(params);
            case "LISEST":
                return usuarios.listarEstudiantes(params[0]);
            case "MODEST":
                return usuarios.modificarEstudiante(params);
            case "DELEST":
                return usuarios.eliminarEstudiante(params);
            // CU-01 Flujo D y E: Roles y cuentas
            case "ASIGROL":
                return usuarios.asignarRol(params);
            case "LISROL":
                return usuarios.listarRoles(params[0]);
            case "QUIROL":
                return usuarios.quitarRol(params);
            case "LISUSR":
                return usuarios.listarUsuarios(params[0]);
            // CU-01 Flujo F: Claves de acceso
            case "MODCLAVE":
                return usuarios.cambiarClave(params);
            case "RESCLAVE":
                return usuarios.restablecerClave(params);

            // CU-02 Flujo A: Programas
            case "ADDPROG":
                return catalogo.registrarPrograma(params);
            case "LISPROG":
                return catalogo.listarProgramas(params[0]);
            case "MODPROG":
                return catalogo.modificarPrograma(params);
            case "DELPROG":
                return catalogo.eliminarPrograma(params);
            // CU-02 Flujo B: Versiones y malla curricular
            case "ADDVER":
                return catalogo.registrarVersion(params);
            case "LISVER":
                return catalogo.listarVersiones(params[0]);
            case "MODVER":
                return catalogo.modificarVersion(params);
            case "DELVER":
                return catalogo.eliminarVersion(params);
            case "APRVER":
                return catalogo.aprobarVersion(params);
            case "ADDMALLA":
                return catalogo.registrarMalla(params);
            case "LISMALLA":
                return catalogo.listarMalla(params);
            case "MODMALLA":
                return catalogo.modificarMalla(params);
            case "DELMALLA":
                return catalogo.eliminarMalla(params);
            case "ADDPRE":
                return catalogo.registrarPrerrequisito(params);
            case "LISPRE":
                return catalogo.listarPrerrequisitos(params);
            case "DELPRE":
                return catalogo.eliminarPrerrequisito(params);
            // CU-02 Flujo C: Módulos
            case "ADDMOD":
                return catalogo.registrarModulo(params);
            case "LISMOD":
                return catalogo.listarModulos(params[0]);
            case "MODMOD":
                return catalogo.modificarModulo(params);
            case "DELMOD":
                return catalogo.eliminarModulo(params);
            // CU-02 Flujo D: Ofertas académicas
            case "ADDOFER":
                return ofertas.registrarOferta(params);
            case "LISOFER":
                return ofertas.listarOfertas(params[0]);
            case "MODOFER":
                return ofertas.modificarOferta(params);
            case "DELOFER":
                return ofertas.eliminarOferta(params);
            case "APROFER":
                return ofertas.aprobarOferta(params);
            case "CATALOGO":
                return ofertas.catalogo();
            // CU-02 Flujo E: Asignación de capacitadores
            case "ADDASIG":
                return ofertas.registrarAsignacion(params, adjuntos);
            case "LISASIG": {
                // Con el módulo, su programa analítico y su propuesta técnica van adjuntos en la respuesta
                String r = ofertas.listarAsignaciones(params);
                adjuntosRespuesta = ofertas.getAdjuntosRespuesta();
                return r;
            }
            case "MODASIG":
                return ofertas.modificarAsignacion(params, adjuntos);
            case "DELASIG":
                return ofertas.eliminarAsignacion(params);
            // CU-02 Flujo F: Horarios
            case "ADDHOR":
                return ofertas.registrarHorario(params);
            case "LISHOR":
                return ofertas.listarHorarios(params);
            case "MODHOR":
                return ofertas.modificarHorario(params);
            case "DELHOR":
                return ofertas.eliminarHorario(params);

            // CU-03 Flujo A: Preinscripción
            case "PREINS":
                return inscripciones.preinscribir(params, remitente, adjuntos);
            case "LISINS":
                return inscripciones.listarInscripciones(params);
            case "MODPRE":
                return inscripciones.modificarPreinscrito(params);
            case "ANUPRE":
                return inscripciones.anularPreinscripcion(params);
            case "RESPRE":
                return inscripciones.restaurarPreinscripcion(params);
            // CU-03 Flujo B: Formalización y ciclo de vida
            case "FORMINS":
                return inscripciones.formalizarInscripcion(params);
            case "ESTINS":
                return inscripciones.cambiarEstadoInscripcion(params);
            case "RESINS":
                return inscripciones.restaurarInscripcion(params);
            // CU-03 Flujo C: Registro de asignaturas
            case "INSMOD":
                return inscripciones.inscribirModulo(params);
            case "RETMOD":
                return inscripciones.retirarModulo(params);
            case "RESMOD":
                return inscripciones.restaurarModulo(params);
            case "KARDEX":
                return inscripciones.kardex(params[0]);

            // CU-04 Flujo A: Aranceles
            case "ADDARAN":
                return pagos.registrarArancel(params);
            case "LISARAN":
                return pagos.listarAranceles(params);
            case "MODARAN":
                return pagos.modificarArancel(params);
            case "DELARAN":
                return pagos.eliminarArancel(params);
            case "RESARAN":
                return pagos.restaurarArancel(params);
            // CU-04 Flujo B: Convenios y becas
            case "ADDCONV":
                return pagos.registrarConvenio(params);
            case "LISCONV":
                return pagos.listarConvenios(params[0]);
            case "MODCONV":
                return pagos.modificarConvenio(params);
            case "DELCONV":
                return pagos.eliminarConvenio(params);
            case "RESCONV":
                return pagos.restaurarConvenio(params);
            case "ASIGCONV":
                return pagos.asignarConvenio(params);
            case "QUICONV":
                return pagos.quitarConvenio(params);
            // CU-04 Flujo C: Registro del comprobante
            case "ADDPAGO":
                return pagos.registrarPago(params, adjuntos);
            case "ESTCTA":
                return pagos.estadoDeCuenta(params[0]);
            case "LISPAGO":
                return pagos.listarPagos(params[0]);
            case "MODPAGO":
                return pagos.modificarPago(params);
            case "ANUPAGO":
                return pagos.anularPago(params);
            case "RESPAGO":
                return pagos.restaurarPago(params);
            // CU-04 Flujo D: Conciliación
            case "CONCPAGO":
                return pagos.conciliarPago(params);
            case "OBSPAGO":
                return pagos.observarPago(params);
            // CU-04 Flujo E: Pago en línea por la pasarela (la orden va con su QR adjunto)
            case "GENPAGO": {
                String r = pagos.generarOrdenPago(params);
                adjuntosRespuesta = pagos.getAdjuntosRespuesta();
                return r;
            }

            // CU-05 Flujo A: Plan de evaluación
            case "ADDACT":
                return evaluacion.registrarActividad(params);
            case "LISACT":
                return evaluacion.listarActividades(params);
            case "MODACT":
                return evaluacion.modificarActividad(params);
            case "DELACT":
                return evaluacion.eliminarActividad(params);
            // CU-05 Flujo B: Sesiones y asistencia
            case "ADDSES":
                return evaluacion.registrarSesion(params);
            case "LISSES":
                return evaluacion.listarSesiones(params);
            case "MODSES":
                return evaluacion.modificarSesion(params);
            case "ADDASIS":
                return evaluacion.registrarAsistencia(params);
            case "LISASIS":
                return evaluacion.listarAsistencia(params);
            case "MODASIS":
                return evaluacion.modificarAsistencia(params);
            // CU-05 Flujo C: Calificaciones
            case "ADDNOTA":
                return evaluacion.registrarNotas(params);
            case "LISNOTA":
                return evaluacion.listarNotas(params);
            case "MODNOTA":
                return evaluacion.modificarNota(params);
            // CU-05 Flujo D: Actas
            case "VERACTA":
                return actas.verActa(params);
            case "LISACTA":
                return actas.listarActas(params);
            case "CIEACTA":
                return actas.cerrarActa(params);
            // CU-05 Flujo E: Examen recuperatorio
            case "HABREC":
                return actas.habilitarRecuperatorio(params);
            // CU-05 Flujo F: Enmienda de notas
            case "ENMIENDA":
                return actas.enmendarNota(params);
            case "LISENM":
                return actas.listarEnmiendas(params);

            // CU-06 Flujo A: Solicitud (público)
            case "SOLCERT":
                return certificados.solicitar(params, remitente);
            // CU-06 Flujo B: Habilitados para título
            case "LISELEG":
                return certificados.listarHabilitados(params);
            // CU-06 Flujo C: Emisión, listado y anulación
            case "GENCERT":
                return certificados.emitir(params);
            case "LISCERT":
                return certificados.listar(params[0]);
            case "ANUCERT":
                return certificados.anular(params);
            // CU-06 Flujo D: Verificación pública
            case "VERCERT":
                return certificados.verificar(params[0]);

            // CU-07 Flujo A: Gestión del expediente
            case "ADDDOC":
                return documentos.registrar(params, adjuntos);
            case "LISDOC": {
                // Con un documento indicado, el archivo va adjunto en la respuesta
                String r = documentos.listar(params);
                adjuntosRespuesta = documentos.getAdjuntosRespuesta();
                return r;
            }
            case "MODDOC":
                return documentos.reemplazar(params, adjuntos);
            case "DELDOC":
                return documentos.eliminar(params);
            // CU-07 Flujo B: Búsqueda de expediente
            case "BUSDOC": {
                String r = documentos.buscar(params);
                adjuntosRespuesta = documentos.getAdjuntosRespuesta();
                return r;
            }

            // CU-08: cada reporte trae adjuntos el PDF y el Excel
            case "REPING":
            case "REPACAD":
            case "REPDEM":
            case "REPNOTAS":
            case "REPCERT":
            case "REPLOG": {
                String r = switch (accion) {
                    // Flujo A: Reporte financiero
                    case "REPING" -> reportes.ingresos(params);
                    // Flujo B: Reportes académicos
                    case "REPACAD" -> reportes.academico(params);
                    case "REPDEM" -> reportes.demanda(params);
                    // Flujo C: Notas procesadas
                    case "REPNOTAS" -> reportes.notas(params);
                    // Flujo D: Títulos emitidos
                    case "REPCERT" -> reportes.certificados(params);
                    // Flujo E: Bitácora
                    default -> reportes.bitacora(params);
                };
                adjuntosRespuesta = reportes.getAdjuntosRespuesta();
                return r;
            }
            default:
                return GeneradorHTML
                        .mensajeError("El comando <b>" + accion + "</b> no existe.");
        }
    }

    // Asunto que se puede mostrar en la consola y en la respuesta: MODCLAVE lleva claves en texto plano
    public static String asuntoVisible(String asunto) {
        return asunto.trim().toUpperCase().startsWith("MODCLAVE") ? "MODCLAVE[***]" : asunto;
    }

    // Comando mal escrito: nombra los comandos que difieren en 1 o 2 letras (LISPERR -> LISPER). Solo los sugiere,
    // nunca los ejecuta: el usuario debe volver a enviar el correo con el comando correcto
    private static final int MAXIMA_DIFERENCIA = 2;
    private static final int MAXIMO_SUGERIDOS = 3;

    static String sugerencia(String accion, String parametros) {
        // INSxxx (insertar) se llama ADDxxx en este sistema: INSPER -> ADDPER
        if (accion.startsWith("INS") && COMANDOS.contains("ADD" + accion.substring(3))) {
            String comando = "ADD" + accion.substring(3);
            return " Los registros se agregan con ADD: ¿quiso decir <b>" + comando + "</b>?" + formas(comando);
        }
        List<String> candidatos = new ArrayList<>(new TreeSet<>(COMANDOS));
        candidatos.add(GeneradorHTML.COMANDO_AYUDA);
        List<String> parecidos = new ArrayList<>();
        int mejor = MAXIMA_DIFERENCIA;
        for (String c : candidatos) {
            int d = distancia(accion, c.toUpperCase());
            if (d > mejor) {
                continue;
            }
            if (d < mejor) {
                mejor = d;
                parecidos.clear();
            }
            parecidos.add(c);
        }
        if (parecidos.isEmpty()) {
            return "";
        }
        if (parecidos.size() == 1) {
            String comando = parecidos.get(0);
            return " ¿Quiso decir <b>" + comando + "</b>?" + (parametros.isEmpty() || comando.equals(GeneradorHTML.COMANDO_AYUDA) ? ""
                    : " Asunto corregido: <code>" + escapar(comando + parametros) + "</code>.");
        }
        return " ¿Quiso decir <b>" + String.join("</b> o <b>", parecidos.subList(0, Math.min(parecidos.size(), MAXIMO_SUGERIDOS)))
                + "</b>?";
    }

    // Distancia de Levenshtein: letras que hay que cambiar, agregar o quitar para pasar de a a b
    private static int distancia(String a, String b) {
        int[] previa = new int[b.length() + 1];
        int[] actual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previa[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            actual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cambio = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                actual[j] = Math.min(Math.min(actual[j - 1] + 1, previa[j] + 1), previa[j - 1] + cambio);
            }
            int[] t = previa;
            previa = actual;
            actual = t;
        }
        return previa[b.length()];
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // Comillas curvas de los celulares (“ ” ‘ ’) a comillas rectas; espacios especiales (espacio duro, de ancho cero,
    // marca BOM) a espacios normales o nada, para que no impidan reconocer el comando
    static String normalizar(String asunto) {
        if (asunto == null) {
            return "";
        }
        return asunto.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace("​", "").replace("﻿", "")
                .strip();
    }

    // Plantillas del comando para mostrarlas en un error de sintaxis o de cantidad de valores
    private static String formas(String comando) {
        List<String> f = GeneradorHTML.sintaxis(comando);
        if (f.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<br>Formato correcto: ");
        for (int i = 0; i < f.size(); i++) {
            sb.append(i > 0 ? " o " : "").append("<code>").append(escapar(f.get(i))).append("</code>");
        }
        return sb.append('.').toString();
    }

    // Error de sintaxis en los valores: se responde sin tocar la base
    static final class SintaxisInvalida extends Exception {
        SintaxisInvalida(String mensaje) {
            super(mensaje);
        }
    }

    // Separa los valores del comando. Cada uno va entre comillas dobles y se separan con comas; los espacios alrededor
    // no importan. Dentro de un valor, \" es una comilla y \\ una barra invertida:
    //   "Av. Busch, Nro 5", "SC"       -> [Av. Busch, Nro 5] [SC]
    //   "Taller \"Excel\" avanzado"    -> [Taller "Excel" avanzado]
    // Sin comillas, con una comilla sin cerrar o con texto entre un valor y la coma, es un error de sintaxis
    static String[] separarParametros(String contenido) throws SintaxisInvalida {
        List<String> valores = new ArrayList<>();
        int n = contenido.length();
        int i = saltarEspacios(contenido, 0);
        if (i == n) {
            // COMANDO[] -> sin valores
            return new String[] { "" };
        }
        while (true) {
            int numero = valores.size() + 1;
            if (contenido.charAt(i) != '"') {
                throw new SintaxisInvalida("el valor " + numero + " debe ir entre comillas dobles (recibido: <b>" + escapar(fragmento(contenido, i))
                        + "</b>).");
            }
            StringBuilder valor = new StringBuilder();
            boolean cerrado = false;
            i++;
            while (i < n) {
                char c = contenido.charAt(i);
                if (c == '\\' && i + 1 < n && (contenido.charAt(i + 1) == '"' || contenido.charAt(i + 1) == '\\')) {
                    valor.append(contenido.charAt(i + 1));
                    i += 2;
                } else if (c == '"') {
                    cerrado = true;
                    i++;
                    break;
                } else {
                    valor.append(c);
                    i++;
                }
            }
            if (!cerrado) {
                throw new SintaxisInvalida("falta cerrar las comillas del valor " + numero + " (<b>\"" + escapar(valor.toString()) + "</b>).");
            }
            valores.add(valor.toString().trim());
            i = saltarEspacios(contenido, i);
            if (i == n) {
                break;
            }
            if (contenido.charAt(i) != ',') {
                throw new SintaxisInvalida("después del valor " + numero + " (<b>\"" + escapar(valor.toString()) + "\"</b>) sigue <b>"
                        + escapar(fragmento(contenido, i)) + "</b>. Separe los valores con comas; si un valor lleva comillas, escríbalas así: "
                        + "<code>\\\"</code>.");
            }
            i = saltarEspacios(contenido, i + 1);
            if (i == n) {
                throw new SintaxisInvalida("sobra una coma después del valor " + numero + ".");
            }
        }
        return valores.toArray(new String[0]);
    }

    private static int saltarEspacios(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    // Lo que sigue a partir de i, recortado, para mostrarlo en el error
    private static String fragmento(String s, int i) {
        String resto = s.substring(i).trim();
        return resto.length() > 30 ? resto.substring(0, 30) + "…" : resto;
    }
}
