package src.negocio;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import src.modelo.Adjunto;
import src.modelo.Usuario;
import src.persistencia.ConexionBD;
import src.persistencia.LogDAO;
import src.persistencia.UsuarioDAO;
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
            "ADDPAGO", "ESTCTA", "LISPAGO", "MODPAGO", "ANUPAGO", "RESPAGO", "CONCPAGO", "OBSPAGO",
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

    public String ejecutar(String remitente, String asuntoBruto, List<Adjunto> adjuntos) {
        adjuntosRespuesta = List.of();
        if (asuntoBruto == null || asuntoBruto.trim().isEmpty()) {
            return GeneradorHTML.mensajeError("El asunto (Subject) del correo está vacío.");
        }

        // Normalizar comillas curvas de celulares (“ ”) a comillas estándar (")
        String asunto = asuntoBruto.trim()
                .replace("“", "\"")
                .replace("”", "\"")
                .replace("‘", "'")
                .replace("’", "'");

        if (asunto.equalsIgnoreCase("help!me") || asunto.equalsIgnoreCase("HELP")) {
            return GeneradorHTML.manualAyudaHTML();
        }

        int idxAbre = asunto.indexOf('[');
        int idxCierra = asunto.lastIndexOf(']');

        String accion;
        String[] params;
        if (idxAbre == -1 && idxCierra == -1) {
            // Comando sin parámetros, por ejemplo: CATALOGO
            accion = asunto.toUpperCase();
            params = new String[] { "" };
        } else if (idxAbre == -1 || idxCierra == -1 || idxCierra < idxAbre) {
            return GeneradorHTML.mensajeError("Sintaxis inválida en comando: <b>" + asunto
                    + "</b>. Use el formato COMANDO[\"param1\", \"param2\"].");
        } else {
            accion = asunto.substring(0, idxAbre).trim().toUpperCase();
            params = separarParametros(asunto.substring(idxAbre + 1, idxCierra));
        }

        if (!COMANDOS.contains(accion)) {
            return GeneradorHTML.mensajeError("El comando <b>" + escapar(accion) + "</b> no existe."
                    + sugerencia(accion, idxAbre == -1 ? "" : asunto.substring(idxAbre))
                    + " Envíe <b>help!me</b> para ayuda.");
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
            } else if (u == null || !u.estaActivo() || !usuarioDAO.tienePermiso(u.getIdUsuario(), accion)) {
                if (u != null) {
                    // LOG no tiene columna de detalle: en un rechazo se guarda el comando intentado
                    new LogDAO(con).registrar(u.getIdUsuario(), accion, "RECHAZADO");
                }
                return GeneradorHTML.mensajeError("Acceso denegado: El remitente <b>"
                        + (remitente != null ? remitente : "desconocido")
                        + "</b> no tiene permisos para ejecutar el comando <b>" + accion + "</b>.");
            } else {
                idRemitente = u.getIdUsuario();
            }
        } catch (SQLException e) {
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
                return usuarios.registrarCapacitador(params);
            case "LISCAP":
                return usuarios.listarCapacitadores(params[0]);
            case "MODCAP":
                return usuarios.modificarCapacitador(params);
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
                return ofertas.registrarAsignacion(params);
            case "LISASIG":
                return ofertas.listarAsignaciones(params);
            case "MODASIG":
                return ofertas.modificarAsignacion(params);
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
                        .mensajeError("El comando <b>" + accion + "</b> no existe. Envíe <b>help!me</b> para ayuda.");
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
        List<String> candidatos = new ArrayList<>(new TreeSet<>(COMANDOS));
        candidatos.add("help!me");
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
            return " ¿Quiso decir <b>" + comando + "</b>?" + (parametros.isEmpty() || comando.equals("help!me") ? ""
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

    // Separa por comas respetando las comillas: "Calle 1, Nro 5", "SC" -> [Calle 1, Nro 5] [SC]
    static String[] separarParametros(String contenido) {
        List<String> params = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean enComillas = false;
        for (char c : contenido.toCharArray()) {
            if (c == '"') {
                enComillas = !enComillas;
            } else if (c == ',' && !enComillas) {
                params.add(actual.toString().trim());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        params.add(actual.toString().trim());
        return params.toArray(new String[0]);
    }
}
