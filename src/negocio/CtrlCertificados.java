package src.negocio;

import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import src.comunicacion.ClienteSMTP;
import src.modelo.Adjunto;
import src.modelo.Certificado;
import src.modelo.Estudiante;
import src.modelo.Inscripcion;
import src.modelo.InscripcionModulo;
import src.modelo.Modulo;
import src.modelo.OfertaAcademica;
import src.modelo.Pago;
import src.modelo.Personal;
import src.modelo.Usuario;
import src.persistencia.CertificadoDAO;
import src.persistencia.EstudianteDAO;
import src.persistencia.HistorialDAO;
import src.persistencia.InscripcionDAO;
import src.persistencia.InscripcionModuloDAO;
import src.persistencia.OfertaDAO;
import src.persistencia.PagoDAO;
import src.persistencia.PersonalDAO;
import src.persistencia.ProgramaDAO;
import src.persistencia.UsuarioDAO;
import src.utilidades.AlmacenDocumentos;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-06: VALIDAR REQUISITOS Y EMITIR CERTIFICADOS DIGITALES CON QR Y CÓDIGO ÚNICO
// El estudiante no tiene cuenta: solicita con SOLCERT (público) y cualquiera verifica con VERCERT (público).
// Coordinación Académica informa los habilitados para título (LISELEG) y el Director emite (GENCERT) y anula (ANUCERT).
// Sin IDs: el estudiante es su CI o código, el tipo es su nombre (o un alias corto) y el documento es su código único
// =========================================================================
public class CtrlCertificados extends CtrlBase {

    private static final String ROL_COORDINACION = "Coordinación Académica";
    private static final String TIPO_ANALITICO = "CONSTANCIA_PROGRAMA_ANALITICO";
    private static final String TIPO_TITULO = "TITULO";
    private static final SecureRandom AZAR = new SecureRandom();

    // Nombres cortos aceptados en los comandos ("NOTAS" = CERTIFICADO_NOTAS). TITULO es el documento final del programa
    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("TITULO", TIPO_TITULO),
            Map.entry("TECNICO_MEDIO", "TITULO_TECNICO_MEDIO"),
            Map.entry("TECNICO_AUXILIAR", "TITULO_TECNICO_AUXILIAR"),
            Map.entry("COMPETENCIA_LABORAL", "CERTIFICADO_COMPETENCIA_LABORAL"),
            Map.entry("PARTICIPACION", "CERTIFICADO_PARTICIPACION"),
            Map.entry("NOTAS", "CERTIFICADO_NOTAS"),
            Map.entry("ESTUDIANTE", "CERTIFICADO_ESTUDIANTE"),
            Map.entry("CONCLUSION", "CERTIFICADO_CONCLUSION"),
            Map.entry("VENCIMIENTO", "CERTIFICADO_VENCIMIENTO"),
            Map.entry("HORARIOS", "CONSTANCIA_HORARIOS"),
            Map.entry("HORARIO", "CONSTANCIA_HORARIOS"),
            Map.entry("ASISTENCIA", "CONSTANCIA_ASISTENCIA"),
            Map.entry("ADMISION", "CONSTANCIA_ADMISION"),
            Map.entry("PROGRAMA_ANALITICO", TIPO_ANALITICO),
            Map.entry("ANALITICO", TIPO_ANALITICO));

    private static final String TIPOS_VALIDOS = "TITULO, NOTAS, ESTUDIANTE, CONCLUSION, VENCIMIENTO, HORARIOS, ASISTENCIA, ADMISION "
            + "o PROGRAMA_ANALITICO (también el nombre completo, por ejemplo CERTIFICADO_NOTAS)";

    public CtrlCertificados(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Solicitud (público)
    // ---------------------------------------------------------------------

    // SOLCERT[ci, codigo_estudiante, tipo_documento, programa o modulo]: el resultado va al correo registrado del estudiante
    public String solicitar(String[] p, String remitente) {
        if (p.length < 3) {
            return formato("SOLCERT[\"ci\", \"codigo_estudiante\", \"tipo_documento\", \"programa (opcional) o modulo (para PROGRAMA_ANALITICO)\"]. "
                    + "Tipos: " + TIPOS_VALIDOS);
        }
        return enTransaccion(con -> {
            rechazarHtml(p);
            requerido(p[0], "ci");
            requerido(p[1], "codigo_estudiante");
            // El CI y el código deben ser del mismo expediente; si no, no se revela nada
            Estudiante e = new EstudianteDAO(con).buscarPorCi(p[0].trim());
            if (e == null || !e.getCodigoEstudiante().equalsIgnoreCase(p[1].trim())) {
                throw new ReglaNegocioException("El CI y el código de estudiante no corresponden a un mismo expediente del ICAP. "
                        + "Verifique los datos de su formulario de inscripción.");
            }
            validarRegistro(e);
            if (e.getEmail() == null) {
                throw new ReglaNegocioException("El expediente no tiene un correo registrado para enviarle el resultado. Actualícelo en "
                        + "ventanilla del ICAP.");
            }
            String solicitado = tipoSolicitado(p[2]);
            RequisitosCertificado.Expediente x = expediente(con, e, solicitado, param(p, 3),
                    "SOLCERT[\"" + e.getCi() + "\", \"" + e.getCodigoEstudiante() + "\", \"" + p[2].trim() + "\", \"programa\"]");
            String tipo = tipoDefinitivo(solicitado, x);
            List<RequisitosCertificado.Requisito> requisitos = new RequisitosCertificado(con).evaluar(x, tipo);
            if (Certificado.esFinal(tipo)) {
                String vigente = new CertificadoDAO(con).vigenteEnPrograma(e.getIdEstudiante(), x.programa.getIdPrograma(), tipo);
                requisitos.add(0, new RequisitosCertificado.Requisito(null, "No tener ya este documento vigente en el programa", vigente == null,
                        vigente == null ? "no lo tiene" : "ya le fue emitido con el N° " + vigente + "; puede verificarlo con VERCERT"));
            }
            boolean completo = RequisitosCertificado.cumpleTodos(requisitos);
            log(con, "CERTIFICADO", "SELECT");

            String nombre = ContenidoCertificado.nombre(tipo);
            String resultado = GeneradorHTML.requisitos(completo ? "Cumple los requisitos " + ContenidoCertificado.del(tipo) : "Requisitos " + ContenidoCertificado.del(tipo),
                    "Estimado(a) <b>" + e.getNombreCompleto() + "</b> (Registro " + e.getCodigoEstudiante() + "): este es el resultado de su "
                            + "solicitud " + ContenidoCertificado.del(tipo).replaceFirst(" ", " <b>") + "</b> en el programa <b>" + x.programa.getCodigo() + " - " + x.programa.getNombre()
                            + "</b> (" + x.oferta.getGestion() + ", grupo " + x.oferta.getGrupo() + ").",
                    filas(requisitos), completo,
                    completo ? "Su solicitud fue derivada a Coordinación Académica para su trámite. Recibirá el documento en PDF, con su código "
                            + "único y su código QR, en este correo."
                            : "Cuando cumpla lo que falta, vuelva a enviar la misma solicitud.");
            if (completo) {
                avisarCoordinacion(con, x, tipo);
            }
            if (remitente != null && remitente.trim().equalsIgnoreCase(e.getEmail())) {
                return resultado;
            }
            notificar(e.getEmail(), "Solicitud de " + nombre, resultado);
            return GeneradorHTML.mensajeExito("Solicitud de Documento",
                    "Se recibió la solicitud. Por seguridad, el resultado se envió al correo registrado del estudiante (<b>"
                            + ocultar(e.getEmail()) + "</b>).");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Habilitados para título (Coordinación Académica)
    // ---------------------------------------------------------------------

    // LISELEG[programa, gestion, grupo]: los que culminaron la malla, con la triple condición; es el informe al Director
    public String listarHabilitados(String[] p) {
        if (p.length < 3) {
            return formato("LISELEG[" + OFERTA + "]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOferta(con, p, 0);
            RequisitosCertificado req = new RequisitosCertificado(con);
            EstudianteDAO estudiantes = new EstudianteDAO(con);
            CertificadoDAO certificados = new CertificadoDAO(con);
            List<String[]> filas = new ArrayList<>();
            int habilitados = 0;
            for (Inscripcion i : new InscripcionDAO(con).vigentesOConcluidas(o.getIdOferta())) {
                Estudiante e = estudiantes.buscarPorId(i.getIdEstudiante());
                RequisitosCertificado.Expediente x = req.expediente(e, i, null);
                if (!x.culminoLaMalla()) {
                    continue;
                }
                String tipo = x.tipoFinal();
                List<RequisitosCertificado.Requisito> r = req.tripleCondicion(x, tipo);
                String emitido = certificados.vigenteEnPrograma(e.getIdEstudiante(), x.programa.getIdPrograma(), tipo);
                boolean habilitado = emitido == null && RequisitosCertificado.cumpleTodos(r);
                if (habilitado) {
                    habilitados++;
                }
                filas.add(new String[] { e.getCodigoEstudiante(), e.getCi(), e.getNombreCompleto(),
                        condicion(r, RequisitosCertificado.ACADEMICA), condicion(r, RequisitosCertificado.FINANCIERA),
                        condicion(r, RequisitosCertificado.DOCUMENTAL),
                        emitido != null ? "YA EMITIDO: " + emitido : habilitado ? "SI" : "NO",
                        habilitado ? "GENCERT[\"" + e.getCodigoEstudiante() + "\", \"" + TIPO_TITULO + "\", \"" + o.getCodigoPrograma() + "\"]" : "-" });
            }
            log(con, "INSCRIPCION", "SELECT");
            Tabla t = Tabla.de(List.of("registro", "ci", "estudiante", "academica", "financiera", "documental", "habilitado",
                    "comando para el director"), filas);
            return GeneradorHTML.tabla("los Estudiantes que culminaron la oferta " + o.getCodigoPrograma() + " " + o.getGestion() + " grupo "
                    + o.getGrupo() + ": triple condición para " + ContenidoCertificado.el(RequisitosCertificado.tipoFinal(nivelDe(con, o)))
                    + " (" + habilitados + " habilitado(s) de " + filas.size() + ")", t);
        });
    }

    // ---------------------------------------------------------------------
    // Flujo C - Emisión, listado y anulación (Director)
    // ---------------------------------------------------------------------

    // GENCERT[codigo_estudiante o ci, tipo_documento, programa o modulo]: vuelve a validar, emite y envía el PDF
    public String emitir(String[] p) {
        if (p.length < 2) {
            return formato("GENCERT[\"codigo_estudiante o ci\", \"tipo_documento\", \"programa (opcional) o modulo (para PROGRAMA_ANALITICO)\"]. "
                    + "Tipos: " + TIPOS_VALIDOS);
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            validarRegistro(e);
            String solicitado = tipoSolicitado(p[1]);
            RequisitosCertificado.Expediente x = expediente(con, e, solicitado, param(p, 2),
                    "GENCERT[\"" + e.getCodigoEstudiante() + "\", \"" + p[1].trim() + "\", \"programa\"]");
            String tipo = tipoDefinitivo(solicitado, x);
            String nombre = ContenidoCertificado.nombre(tipo);
            CertificadoDAO dao = new CertificadoDAO(con);
            if (Certificado.esFinal(tipo)) {
                String vigente = dao.vigenteEnPrograma(e.getIdEstudiante(), x.programa.getIdPrograma(), tipo);
                if (vigente != null) {
                    throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene vigente " + ContenidoCertificado.el(tipo) + " N° <b>" + vigente
                            + "</b> en el programa " + x.programa.getCodigo() + ". Si tiene un error, anúlelo con <code>ANUCERT[\"" + vigente
                            + "\", \"motivo\"]</code> y vuelva a emitirlo.");
                }
            }
            List<String> faltan = RequisitosCertificado.faltantes(new RequisitosCertificado(con).evaluar(x, tipo));
            if (!faltan.isEmpty()) {
                throw new ReglaNegocioException("No se emite " + ContenidoCertificado.el(tipo) + " de <b>" + e.getNombreCompleto() + "</b>: no cumple estos requisitos."
                        + "<ul><li>" + String.join("</li><li>", faltan) + "</li></ul>");
            }
            Pago pago = new PagoDAO(con).documentoDisponible(x.inscripcion.getIdInscripcion(), RequisitosCertificado.concepto(tipo), tipo);

            LocalDate hoy = LocalDate.now();
            String anio = String.valueOf(hoy.getYear());
            Certificado c = new Certificado();
            c.setTipoCertificado(tipo);
            c.setFechaEmision(hoy);
            c.setIdInscripcion(x.inscripcion.getIdInscripcion());
            c.setCodigoAlfanumerico(String.format("ICAP-%s-%06d", anio, dao.siguienteCorrelativo("codigo_alfanumerico_unico", "ICAP-" + anio + "-")));
            if (tipo.startsWith("TITULO_")) {
                // Numeración del título (como la del título oficial: 0030969), única en todos los años
                c.setNumeroTitulo(String.format("%07d", dao.siguienteCorrelativo("numero_titulo", "")));
            }
            c.setGradoOtorgado(TIPO_ANALITICO.equals(tipo) ? x.modulo.getCodigo() + " - " + x.modulo.getNombre()
                    : Certificado.esFinal(tipo) ? x.programa.getNombre() : null);
            c.setCodigoQrHash(hash(c, e));
            c.setEstado("VIGENTE");
            dao.insertar(c);
            new PagoDAO(con).asociarCertificado(pago.getIdPago(), c.getIdCertificado());
            // Como los cambios de un módulo, la emisión y la anulación quedan en el historial del estudiante (KARDEX)
            new HistorialDAO(con).registrar(null, "VIGENTE", Certificado.referenciaHistorial(c.getCodigoAlfanumerico(), nombre) + "emitido",
                    e.getIdEstudiante(), x.inscripcion.getIdInscripcion(), idRemitente);
            log(con, "CERTIFICADO", "INSERT");
            log(con, "PAGO", "UPDATE");
            log(con, "HISTORIAL_ESTADO", "INSERT");

            Personal coordinador = Certificado.esFinal(tipo) ? new PersonalDAO(con).conRol(ROL_COORDINACION, idRemitente) : null;
            byte[] pdf = new ContenidoCertificado(con).pdf(x, c, nombreDelDirector(con),
                    coordinador == null ? null : coordinador.getNombreCompleto());
            String archivo;
            try {
                archivo = AlmacenDocumentos.guardarEmitido(e.getCi(), c.getCodigoAlfanumerico(), pdf);
            } catch (IOException ex) {
                throw new ReglaNegocioException("No se pudo guardar la copia de archivo del documento. Intente nuevamente.");
            }
            String verificacion = "<code>VERCERT[\"" + c.getCodigoAlfanumerico() + "\"]</code>";
            if (e.getEmail() != null) {
                notificar(e.getEmail(), nombre + " " + c.getCodigoAlfanumerico(), GeneradorHTML.mensajeExito("Documento Emitido",
                        "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: el ICAP le emitió su <b>" + nombre + "</b> N° <b>"
                                + c.getCodigoAlfanumerico() + "</b>, que va adjunto en PDF.<br>Cualquier persona puede verificar su "
                                + "autenticidad escaneando su código QR o enviando un correo a " + ClienteSMTP.CORREO + " con el asunto "
                                + verificacion + "."),
                        List.of(new Adjunto(c.getCodigoAlfanumerico() + ".pdf", "application/pdf", pdf)));
            }
            return GeneradorHTML.mensajeExito("Emisión de Documento",
                    "Se emitió " + ContenidoCertificado.el(tipo).replaceFirst(" ", " <b>") + "</b> N° <b>" + c.getCodigoAlfanumerico() + "</b>"
                            + (c.getNumeroTitulo() != null ? " (Título N° <b>" + c.getNumeroTitulo() + "</b>)" : "") + " a nombre de <b>"
                            + e.getNombreCompleto() + "</b> (Registro " + e.getCodigoEstudiante() + "), en la " + x.oferta.getDescripcion()
                            + ". Estado: <b>VIGENTE</b>. Se aplicó el pago <b>" + pago.getNumeroTransaccion() + "</b>.<br>"
                            + (e.getEmail() != null ? "El PDF con el código QR se envió a <b>" + e.getEmail() + "</b>."
                                    : "El estudiante no tiene correo registrado: entréguele el PDF impreso.")
                            + " Copia de archivo: <code>" + archivo + "</code>.<br>Hash del QR: <code>" + c.getCodigoQrHash() + "</code><br>"
                            + "Verificación pública: " + verificacion + ".");
        });
    }

    // LISCERT[gestion | tipo_documento | codigo_estudiante o ci | *]
    public String listar(String filtro) {
        String f = filtroListado(filtro);
        return enTransaccion(con -> {
            CertificadoDAO dao = new CertificadoDAO(con);
            Tabla t;
            String titulo;
            String tipo = f.equals("*") ? null : tipoConocido(f);
            if (f.equals("*")) {
                t = dao.listar("*", "*", 0);
                titulo = "todos los Documentos emitidos";
            } else if (f.matches("\\d+-\\d{4}")) {
                t = dao.listar(f, "*", 0);
                titulo = "los Documentos emitidos en la gestión " + f;
            } else if (tipo != null) {
                t = dao.listar("*", TIPO_TITULO.equals(tipo) ? "TITULO\\_%" : tipo, 0);
                titulo = "los Documentos de tipo " + (TIPO_TITULO.equals(tipo) ? "título" : tipo);
            } else {
                Estudiante e = new EstudianteDAO(con).buscarPorCiOCodigo(f);
                if (e == null) {
                    throw new ReglaNegocioException("<b>" + f + "</b> no es una gestión (por ejemplo 2-2026), un tipo de documento (" + TIPOS_VALIDOS
                            + ") ni el CI o código de un estudiante.");
                }
                t = dao.listar("*", "*", e.getIdEstudiante());
                titulo = "los Documentos emitidos a " + e.getNombreCompleto() + " (Registro " + e.getCodigoEstudiante() + ")";
            }
            log(con, "CERTIFICADO", "SELECT");
            return GeneradorHTML.tabla(titulo, t);
        });
    }

    // ANUCERT[codigo_alfanumerico, motivo]: el documento queda ANULADO con su motivo; nunca se borra
    public String anular(String[] p) {
        if (p.length < 2) {
            return formato("ANUCERT[\"codigo_alfanumerico\", \"motivo\"]");
        }
        return enTransaccion(con -> {
            requerido(p[0], "codigo_alfanumerico");
            requerido(p[1], "motivo");
            CertificadoDAO dao = new CertificadoDAO(con);
            Certificado c = dao.buscarPorCodigo(p[0]);
            if (c == null) {
                throw new ReglaNegocioException("No existe un documento con el código <b>" + p[0].trim() + "</b>. Consulte LISCERT[\"*\"].");
            }
            if (c.estaAnulado()) {
                throw new ReglaNegocioException("El documento <b>" + c.getCodigoAlfanumerico() + "</b> ya está ANULADO desde el "
                        + c.getFechaAnulacion() + ".");
            }
            String motivo = p[1].trim();
            String nombre = ContenidoCertificado.nombre(c.getTipoCertificado());
            // El motivo queda en HISTORIAL_ESTADO (255 caracteres), precedido del código y el tipo del documento
            String referencia = Certificado.referenciaHistorial(c.getCodigoAlfanumerico(), nombre);
            int maximo = 255 - referencia.length();
            if (motivo.length() > maximo) {
                throw new ReglaNegocioException("El <b>motivo</b> no puede superar los " + maximo + " caracteres.");
            }
            Inscripcion i = new InscripcionDAO(con).buscarPorId(c.getIdInscripcion());
            Estudiante e = new EstudianteDAO(con).buscarPorId(i.getIdEstudiante());
            dao.anular(c.getIdCertificado());
            new HistorialDAO(con).registrar("VIGENTE", "ANULADO", referencia + motivo, e.getIdEstudiante(), i.getIdInscripcion(), idRemitente);
            c.setEstado("ANULADO");
            c.setMotivoAnulacion(motivo);
            log(con, "CERTIFICADO", "UPDATE");
            log(con, "HISTORIAL_ESTADO", "INSERT");
            if (e.getEmail() != null) {
                notificar(e.getEmail(), "Documento anulado " + c.getCodigoAlfanumerico(), GeneradorHTML.mensajeAviso("Documento anulado",
                        "Estimado(a) <b>" + e.getNombreCompleto() + "</b>: su <b>" + nombre + "</b> N° <b>" + c.getCodigoAlfanumerico()
                                + "</b> fue anulad" + (c.getTipoCertificado().startsWith("CONSTANCIA") ? "a" : "o") + ". Motivo: <b>"
                                + c.getMotivoAnulacion() + "</b>. Desde ahora su verificación pública indica que está ANULADO. "
                                + "Si corresponde, el ICAP le emitirá el documento corregido sin volver a cobrarle el arancel."));
            }
            OfertaAcademica o = new OfertaDAO(con).buscarPorId(i.getIdOferta());
            String programaOModulo = TIPO_ANALITICO.equals(c.getTipoCertificado()) ? c.getGradoOtorgado().split(" - ")[0] : o.getCodigoPrograma();
            return GeneradorHTML.mensajeExito("Anulación de Documento",
                    "Se anuló " + ContenidoCertificado.el(c.getTipoCertificado()) + " N° <b>" + c.getCodigoAlfanumerico() + "</b> de <b>"
                            + e.getNombreCompleto() + "</b>: queda en estado <b>ANULADO</b>. Motivo: " + motivo
                            + ". Su registro se conserva, el motivo queda en su historial (KARDEX) y VERCERT lo informa como anulado.<br>"
                            + "Para emitir el documento corregido: <code>GENCERT[\"" + e.getCodigoEstudiante() + "\", \"" + c.getTipoCertificado()
                            + "\", \"" + programaOModulo + "\"]</code>; el pago de este documento se reutiliza para su reposición.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo D - Verificación pública
    // ---------------------------------------------------------------------

    // VERCERT[codigo_alfanumerico | hash_qr]: solo los datos públicos de acreditación
    public String verificar(String valor) {
        if (Validador.vacio(valor)) {
            return formato("VERCERT[\"codigo_alfanumerico o hash_qr\"]");
        }
        return enTransaccion(con -> {
            rechazarHtml(new String[] { valor });
            Certificado c = new CertificadoDAO(con).buscarPorCodigoOHash(valor);
            log(con, "CERTIFICADO", "SELECT");
            if (c == null) {
                throw new ReglaNegocioException("El código <b>" + valor.trim() + "</b> no corresponde a un documento emitido por el ICAP.");
            }
            String[][] datos = { { "Titular", c.getTitular() }, { "Tipo de documento", ContenidoCertificado.nombre(c.getTipoCertificado()) },
                    { "Programa", c.getPrograma() }, { "Fecha de emisión", String.valueOf(c.getFechaEmision()) },
                    { "Código único", c.getCodigoAlfanumerico() }, { "Estado", c.getEstado() } };
            return c.estaAnulado()
                    ? GeneradorHTML.verificacion(false, "Documento ANULADO", datos,
                            "Este documento fue anulado por el ICAP el " + c.getFechaAnulacion() + " y no tiene validez.")
                    : GeneradorHTML.verificacion(true, "Documento auténtico y vigente", datos,
                            "Documento emitido por el Instituto de Capacitación Popular (ICAP) de la Facultad Politécnica - UAGRM.");
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    // Tipo pedido: uno de los 12 de la tabla, un alias corto, o TITULO (el documento final del programa)
    private static String tipoSolicitado(String valor) {
        requerido(valor, "tipo_documento");
        String tipo = tipoConocido(valor);
        if (tipo == null) {
            throw new ReglaNegocioException("<b>tipo_documento</b> no es válido (recibido: <b>" + valor.trim() + "</b>). Tipos: " + TIPOS_VALIDOS + ".");
        }
        return tipo;
    }

    private static String tipoConocido(String valor) {
        String n = Validador.normalizar(valor).replace(' ', '_');
        if (Certificado.FINALES.contains(n) || Certificado.CERTIFICACIONES.contains(n)) {
            return n;
        }
        String corto = n.replaceFirst("^(CERTIFICADO|CONSTANCIA|TITULO)_(DEL?_)?", "").replaceFirst("^CONTENIDO_(DEL?_)?", "");
        return ALIAS.get(corto);
    }

    // TITULO se resuelve según el nivel del programa; un título de otro nivel no corresponde
    private static String tipoDefinitivo(String solicitado, RequisitosCertificado.Expediente x) {
        String fin = x.tipoFinal();
        if (TIPO_TITULO.equals(solicitado)) {
            return fin;
        }
        if (Certificado.esFinal(solicitado) && !solicitado.equals(fin)) {
            throw new ReglaNegocioException("El programa <b>" + x.programa.getCodigo() + "</b> es de nivel "
                    + ContenidoCertificado.nivel(x.programa.getNivelTitulacion()) + " y otorga " + ContenidoCertificado.el(fin).replaceFirst(" ", " <b>")
                    + "</b>, no " + ContenidoCertificado.el(solicitado) + ". Solicítelo como TITULO.");
        }
        return solicitado;
    }

    // El documento corresponde a una inscripción: la del programa indicado o, si solo tiene uno, la más reciente.
    // La constancia de programa analítico se pide por módulo, y se toma la inscripción en la que lo cursó
    private RequisitosCertificado.Expediente expediente(Connection con, Estudiante e, String tipo, String referencia, String ejemplo)
            throws SQLException {
        RequisitosCertificado req = new RequisitosCertificado(con);
        InscripcionDAO dao = new InscripcionDAO(con);
        if (TIPO_ANALITICO.equals(tipo)) {
            if (Validador.vacio(referencia)) {
                throw new ReglaNegocioException("La constancia de programa analítico es de un módulo: indíquelo al final del comando, "
                        + "por ejemplo <code>" + ejemplo.replace("\"programa\"", "\"CONT-1\"") + "</code>.");
            }
            Modulo m = buscarModulo(con, referencia);
            List<InscripcionModulo> intentos = new InscripcionModuloDAO(con).intentos(e.getIdEstudiante(), m.getIdModulo());
            if (intentos.isEmpty()) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no cursó el módulo <b>" + m.getCodigo() + "</b>.");
            }
            return req.expediente(e, dao.buscarPorId(intentos.get(intentos.size() - 1).getIdInscripcion()), m);
        }
        List<Inscripcion> todas = dao.deEstudiante(e.getIdEstudiante());
        if (todas.isEmpty()) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene inscripciones en el ICAP.");
        }
        OfertaDAO ofertas = new OfertaDAO(con);
        Set<String> programas = new LinkedHashSet<>();
        Inscripcion elegida = null;
        for (Inscripcion i : todas) {
            String programa = ofertas.buscarPorId(i.getIdOferta()).getCodigoPrograma();
            programas.add(programa);
            if (elegida == null && (Validador.vacio(referencia) || programa.equalsIgnoreCase(referencia.trim()))) {
                elegida = i;
            }
        }
        if (elegida == null) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene inscripción en el programa <b>" + referencia.trim()
                    + "</b>. Sus programas: " + String.join(", ", programas) + ".");
        }
        if (Validador.vacio(referencia) && programas.size() > 1) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> tiene inscripciones en varios programas (" + String.join(", ", programas)
                    + "): indique el programa al final del comando, por ejemplo <code>" + ejemplo + "</code>.");
        }
        return req.expediente(e, elegida, null);
    }

    // Precondición: el expediente tiene registro asignado (el código definitivo se asigna al formalizar la inscripción)
    private static void validarRegistro(Estudiante e) {
        if (!e.tieneCodigoDefinitivo()) {
            throw new ReglaNegocioException("El expediente de <b>" + e.getNombreCompleto() + "</b> todavía no tiene registro asignado: "
                    + "los documentos se tramitan después de formalizar la inscripción.");
        }
    }

    // Los comandos públicos reciben texto de cualquiera y luego se muestra en correos HTML
    private static void rechazarHtml(String[] p) {
        for (String s : p) {
            if (Validador.contieneHtml(s)) {
                throw new ReglaNegocioException("Los datos no pueden contener los caracteres &lt; ni &gt;.");
            }
        }
    }

    // Aviso a Coordinación Académica de una solicitud que cumple todos los requisitos, con el comando listo para el Director
    private void avisarCoordinacion(Connection con, RequisitosCertificado.Expediente x, String tipo) throws SQLException {
        Estudiante e = x.estudiante;
        String referencia = TIPO_ANALITICO.equals(tipo) ? x.modulo.getCodigo() : x.programa.getCodigo();
        String nombre = ContenidoCertificado.nombre(tipo);
        String detalle = "<b>" + e.getNombreCompleto() + "</b> (Registro " + e.getCodigoEstudiante() + ", CI " + e.getCi() + ") solicitó su <b>"
                + nombre + "</b> en la " + x.oferta.getDescripcion() + " y cumple todos los requisitos."
                + (Certificado.esFinal(tipo) ? " Verifique el informe de habilitados con <code>LISELEG[" + x.oferta.getReferencia() + "]</code>." : "")
                + "<br>Para su emisión, el Director envía <code>GENCERT[\"" + e.getCodigoEstudiante() + "\", \"" + tipo + "\", \"" + referencia
                + "\"]</code>.";
        for (String correo : new UsuarioDAO(con).correosActivosPorRol(ROL_COORDINACION)) {
            notificar(correo, "Solicitud de " + nombre + ": " + e.getCodigoEstudiante(), GeneradorHTML.mensajeAviso("Solicitud de documento", detalle));
        }
    }

    // Columna de LISELEG: CUMPLE o lo que falta de esa condición
    private static String condicion(List<RequisitosCertificado.Requisito> requisitos, String condicion) {
        List<String> faltan = new ArrayList<>();
        for (RequisitosCertificado.Requisito r : requisitos) {
            if (condicion.equals(r.condicion) && !r.cumple) {
                faltan.add(r.descripcion + ": " + r.detalle);
            }
        }
        return faltan.isEmpty() ? "CUMPLE" : "FALTA: " + String.join("; ", faltan);
    }

    private static List<String[]> filas(List<RequisitosCertificado.Requisito> requisitos) {
        List<String[]> filas = new ArrayList<>();
        for (RequisitosCertificado.Requisito r : requisitos) {
            filas.add(new String[] { (r.condicion != null ? r.condicion + ": " : "") + r.descripcion, r.cumple ? "SI" : "NO", r.detalle });
        }
        return filas;
    }

    private static String nivelDe(Connection con, OfertaAcademica o) throws SQLException {
        return new ProgramaDAO(con).buscarPorCodigo(o.getCodigoPrograma()).getNivelTitulacion();
    }

    // Quien emite firma como Director
    private String nombreDelDirector(Connection con) throws SQLException {
        Usuario u = new UsuarioDAO(con).buscarPorId(idRemitente);
        Personal director = u == null ? null : new PersonalDAO(con).buscarPorId(u.getIdPersonal());
        return director == null ? "Director del ICAP" : director.getNombreCompleto();
    }

    // Hash del QR: SHA-256 del código, el titular, el tipo, la fecha y un valor al azar, para que no se pueda adivinar.
    // Se usan sus primeros 128 bits (32 caracteres): el QR queda más chico y se lee mejor al imprimirlo
    private static String hash(Certificado c, Estudiante e) {
        byte[] azar = new byte[16];
        AZAR.nextBytes(azar);
        return Validador.sha256(c.getCodigoAlfanumerico() + "|" + e.getCi() + "|" + c.getTipoCertificado() + "|" + c.getFechaEmision()
                + "|" + HexFormat.of().formatHex(azar)).substring(0, 32);
    }

    // "ma***@gmail.com"
    private static String ocultar(String email) {
        int arroba = email.indexOf('@');
        return arroba <= 2 ? "***" + email.substring(Math.max(arroba, 0)) : email.substring(0, 2) + "***" + email.substring(arroba);
    }
}
