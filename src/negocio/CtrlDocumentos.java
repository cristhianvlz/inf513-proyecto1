package src.negocio;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import src.modelo.Adjunto;
import src.modelo.DocumentoDigital;
import src.modelo.Estudiante;
import src.modelo.Inscripcion;
import src.modelo.OfertaAcademica;
import src.modelo.Pago;
import src.persistencia.CertificadoDAO;
import src.persistencia.DocumentoDAO;
import src.persistencia.EstudianteDAO;
import src.persistencia.ExpedienteDAO;
import src.persistencia.InscripcionDAO;
import src.persistencia.OfertaDAO;
import src.persistencia.PagoDAO;
import src.persistencia.ProgramaDAO;
import src.utilidades.AlmacenDocumentos;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-07: REPOSITORIO DOCUMENTAL HISTÓRICO
// El estudiante presenta sus originales en ventanilla y el Encargado de Registro carga la copia escaneada como
// adjunto del correo: el sistema la guarda en documentos/<ci>/ y registra la ruta en DOCUMENTO_DIGITAL.
// Sin IDs: un documento es el estudiante (CI o código) más su tipo; una boleta, además, el número de transacción.
// Ningún archivo se destruye: el reemplazado o eliminado queda en documentos/<ci>/historico/
// =========================================================================
public class CtrlDocumentos extends CtrlBase {

    private static final String BOLETA = "BOLETA_PAGO";
    // Lo que admite un correo de respuesta (Gmail acepta 25 MB, que en base64 son unos 18 MB de archivos)
    private static final long TAMANO_MAXIMO_RESPUESTA = 15L * 1024 * 1024;

    // Tipos del expediente (las boletas llegan con ADDPAGO), con su nombre para los mensajes
    private static final Map<String, String> NOMBRES = new LinkedHashMap<>();
    static {
        NOMBRES.put("CARNET_IDENTIDAD", "carnet de identidad");
        NOMBRES.put("FORMULARIO_INSCRIPCION", "formulario de inscripción");
        NOMBRES.put("FOTOGRAFIA", "fotografía");
        NOMBRES.put("TITULO_BACHILLER", "título de bachiller");
        NOMBRES.put("TITULO_TECNICO_AUXILIAR", "título de Técnico Auxiliar");
        NOMBRES.put("FORMULARIO_EXPERIENCIA", "formulario de experiencia");
        NOMBRES.put("LIBRETA_ESCOLAR", "libreta escolar");
        NOMBRES.put("CERTIFICADO_NACIMIENTO", "certificado de nacimiento");
        NOMBRES.put(BOLETA, "boleta de pago");
    }

    // Nombres cortos aceptados en los comandos
    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("CARNET", "CARNET_IDENTIDAD"), Map.entry("CI", "CARNET_IDENTIDAD"), Map.entry("CEDULA", "CARNET_IDENTIDAD"),
            Map.entry("CEDULA_IDENTIDAD", "CARNET_IDENTIDAD"), Map.entry("BACHILLER", "TITULO_BACHILLER"),
            Map.entry("TECNICO_AUXILIAR", "TITULO_TECNICO_AUXILIAR"), Map.entry("LIBRETA", "LIBRETA_ESCOLAR"),
            Map.entry("NACIMIENTO", "CERTIFICADO_NACIMIENTO"), Map.entry("FOTO", "FOTOGRAFIA"),
            Map.entry("FORMULARIO", "FORMULARIO_INSCRIPCION"), Map.entry("INSCRIPCION", "FORMULARIO_INSCRIPCION"),
            Map.entry("EXPERIENCIA", "FORMULARIO_EXPERIENCIA"), Map.entry("BOLETA", BOLETA));

    // Documentos que se piden para todo programa: el CI (preinscripción y título), el formulario que el estudiante
    // llena al inscribirse y la fotografía que va en el título
    private static final List<String> EXIGIDOS_SIEMPRE = List.of("CARNET_IDENTIDAD", "FORMULARIO_INSCRIPCION", "FOTOGRAFIA");
    // Y los que agrega cada nivel: Técnico Medio exige bachillerato y Técnico Auxiliar (CU-03 y CU-06)
    private static final Map<String, List<String>> EXIGIDOS_POR_NIVEL = Map.of(
            "TECNICO_MEDIO", List.of("TITULO_BACHILLER", "TITULO_TECNICO_AUXILIAR"),
            "CERTIFICACION_COMPETENCIA_LABORAL", List.of("FORMULARIO_EXPERIENCIA"));

    private static final String TIPOS_VALIDOS = "CARNET_IDENTIDAD, FORMULARIO_INSCRIPCION, FOTOGRAFIA, TITULO_BACHILLER, "
            + "TITULO_TECNICO_AUXILIAR, FORMULARIO_EXPERIENCIA, LIBRETA_ESCOLAR, CERTIFICADO_NACIMIENTO o BOLETA_PAGO "
            + "(también CARNET, FOTO, BACHILLER, FORMULARIO, EXPERIENCIA, LIBRETA, NACIMIENTO, BOLETA)";

    public CtrlDocumentos(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Gestión del expediente (Encargado de Registro)
    // ---------------------------------------------------------------------

    // ADDDOC[estudiante, tipo_documento], con el archivo escaneado adjunto.
    // Una boleta lleva además el número de transacción del pago que no tiene boleta
    public String registrar(String[] p, List<Adjunto> adjuntos) {
        if (p.length < 2) {
            return formato("ADDDOC[\"codigo_estudiante o ci\", \"tipo_documento\"], con el archivo escaneado adjunto. Tipos: " + TIPOS_VALIDOS);
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            String tipo = tipo(p[1]);
            DocumentoDAO dao = new DocumentoDAO(con);
            String ruta;
            if (BOLETA.equals(tipo)) {
                Pago pago = pagoDelEstudiante(con, e, param(p, 2));
                if (!dao.boletas(e.getIdEstudiante(), pago.getNumeroTransaccion()).isEmpty()) {
                    throw new ReglaNegocioException("El pago <b>" + pago.getNumeroTransaccion() + "</b> ya tiene su boleta. Para cambiar el archivo use "
                            + "<code>MODDOC[\"" + e.getCi() + "\", \"BOLETA_PAGO\", \"" + pago.getNumeroTransaccion() + "\"]</code>.");
                }
                ruta = guardarDocumento(e.getCi(), tipo, archivo(adjuntos, tipo));
                dao.insertarBoleta(ruta, e.getIdEstudiante(), pago.getIdPago());
            } else {
                DocumentoDigital previo = dao.ultimo(e.getIdEstudiante(), tipo);
                if (previo != null) {
                    throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene su " + NOMBRES.get(tipo) + " en el expediente, "
                            + "presentado el " + previo.getFechaPresentacion() + ". Para cambiar el archivo use <code>MODDOC[\"" + e.getCi() + "\", \""
                            + tipo + "\"]</code> con el nuevo adjunto.");
                }
                ruta = guardarDocumento(e.getCi(), tipo, archivo(adjuntos, tipo));
                dao.insertar(tipo, ruta, e.getIdEstudiante());
            }
            log(con, "DOCUMENTO_DIGITAL", "INSERT");
            return GeneradorHTML.mensajeExito("Carga de Documento",
                    "Se cargó " + descripcion(tipo, param(p, 2).trim().toUpperCase()) + " al expediente de <b>" + e.getNombreCompleto() + "</b> (" + identificacion(e) + "). Archivo: <code>" + ruta + "</code>.<br>"
                            + resumenFaltantes(con, e));
        });
    }

    // LISDOC[estudiante]: sus documentos y los que le faltan según el nivel de sus programas.
    // LISDOC[estudiante, documento]: además devuelve el archivo adjunto ("TODOS" adjunta el expediente completo)
    public String listar(String[] p) {
        if (Validador.vacio(param(p, 0))) {
            return formato("LISDOC[\"codigo_estudiante o ci\", \"documento (opcional: tipo, BOLETA_PAGO con su transacción, o TODOS)\"]");
        }
        if (!Validador.vacio(param(p, 1))) {
            return enTransaccion(con -> enviarArchivos(con, buscarEstudiante(con, p[0]), p[1], param(p, 2), "LISDOC"));
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            Tabla documentos = new DocumentoDAO(con).listarPorEstudiante(e.getIdEstudiante());
            List<String[]> exigidos = exigidos(con, e);
            long faltan = exigidos.stream().filter(f -> "FALTA".equals(f[2])).count();
            log(con, "DOCUMENTO_DIGITAL", "SELECT");
            return GeneradorHTML.tablas(new String[] {
                    "el Expediente de " + e.getNombreCompleto() + " (" + identificacion(e) + "): documentos presentados",
                    "los Documentos que exige el nivel de su programa (" + (faltan == 0 ? "expediente completo" : faltan + " faltante(s)") + ")" },
                    documentos, Tabla.de(List.of("documento", "exigido para", "estado", "detalle"), exigidos));
        });
    }

    // MODDOC[estudiante, tipo_documento, numero_transaccion (solo boletas)], con el nuevo adjunto
    public String reemplazar(String[] p, List<Adjunto> adjuntos) {
        if (p.length < 2) {
            return formato("MODDOC[\"codigo_estudiante o ci\", \"tipo_documento\", \"numero_transaccion (solo BOLETA_PAGO)\"], con el nuevo archivo adjunto");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            String tipo = tipo(p[1]);
            DocumentoDigital d = documento(con, e, tipo, param(p, 2), "MODDOC");
            if (d.respaldaPagoConciliado()) {
                throw new ReglaNegocioException("La boleta del pago <b>" + d.getNumeroTransaccion() + "</b> respalda un pago CONCILIADO: es la evidencia "
                        + "del depósito ya contrastado con el extracto y no se reemplaza.");
            }
            String nueva = guardarDocumento(e.getCi(), tipo, archivo(adjuntos, tipo));
            new DocumentoDAO(con).reemplazar(d.getIdDocumento(), nueva);
            String anterior = archivar(d.getUrlDoc());
            log(con, "DOCUMENTO_DIGITAL", "UPDATE");
            return GeneradorHTML.mensajeExito("Reemplazo de Documento",
                    "Se reemplazó " + descripcion(tipo, d.getNumeroTransaccion()) + " de <b>" + e.getNombreCompleto()
                            + "</b> (presentación anterior: " + d.getFechaPresentacion() + "). Nuevo archivo: <code>" + nueva
                            + "</code> (presentado hoy).<br>" + (anterior != null ? "El archivo anterior se conserva en <code>" + anterior + "</code>."
                                    : "El archivo anterior ya no estaba en el servidor."));
        });
    }

    // DELDOC[estudiante, tipo_documento, numero_transaccion (solo boletas)]: documento cargado por error
    public String eliminar(String[] p) {
        if (p.length < 2) {
            return formato("DELDOC[\"codigo_estudiante o ci\", \"tipo_documento\", \"numero_transaccion (solo BOLETA_PAGO)\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            String tipo = tipo(p[1]);
            DocumentoDigital d = documento(con, e, tipo, param(p, 2), "DELDOC");
            if (d.respaldaPagoConciliado()) {
                throw new ReglaNegocioException("No se elimina: la boleta respalda el pago <b>" + d.getNumeroTransaccion() + "</b>, que está CONCILIADO.");
            }
            new DocumentoDAO(con).eliminar(d.getIdDocumento());
            String archivado = archivar(d.getUrlDoc());
            log(con, "DOCUMENTO_DIGITAL", "DELETE");
            String volver = "<code>ADDDOC[\"" + e.getCi() + "\", \"" + tipo + "\"" + (d.esBoleta() ? ", \"" + d.getNumeroTransaccion() + "\"" : "")
                    + "]</code> con el archivo correcto";
            return GeneradorHTML.mensajeExito("Eliminación de Documento",
                    "Se eliminó " + descripcion(tipo, d.getNumeroTransaccion()) + (d.esBoleta() ? " (" + d.getEstadoPago() + ")" : "")
                            + " del expediente de <b>" + e.getNombreCompleto() + "</b>."
                            + (archivado != null ? " El archivo no se destruye: queda en <code>" + archivado + "</code>." : "")
                            + "<br>Si se eliminó por error, vuelva a cargarlo con " + volver + ".<br>" + resumenFaltantes(con, e));
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Búsqueda de expediente (Coordinación Académica, Director)
    // ---------------------------------------------------------------------

    // BUSDOC[ci | apellido | codigo_estudiante | gestion]: documentos, actas y certificados en una sola tabla.
    // BUSDOC[ci o codigo, documento]: el archivo adjunto, para entregarlo ante el requerimiento
    public String buscar(String[] p) {
        String criterio = param(p, 0);
        if (Validador.vacio(criterio)) {
            return formato("BUSDOC[\"ci, apellido, codigo_estudiante o gestion (por ejemplo 2-2026)\", \"documento (opcional, con CI o código)\"]");
        }
        if (!Validador.vacio(param(p, 1))) {
            return enTransaccion(con -> {
                Estudiante e = new EstudianteDAO(con).buscarPorCiOCodigo(criterio);
                if (e == null) {
                    throw new ReglaNegocioException("Para pedir un archivo indique el CI o el código del estudiante (recibido: <b>"
                            + criterio.trim().replace("<", "&lt;") + "</b>).");
                }
                return enviarArchivos(con, e, p[1], param(p, 2), "BUSDOC");
            });
        }
        return enTransaccion(con -> {
            String v = criterio.trim();
            if (Validador.contieneHtml(v)) {
                throw new ReglaNegocioException("El criterio no puede contener los caracteres &lt; ni &gt;.");
            }
            ExpedienteDAO dao = new ExpedienteDAO(con);
            Tabla t;
            String titulo;
            Estudiante e = new EstudianteDAO(con).buscarPorCiOCodigo(v);
            if (v.matches("\\d+-\\d{4}")) {
                t = dao.buscar(0, "", v);
                titulo = "los Expedientes de la gestión " + v.toUpperCase();
            } else if (e != null) {
                t = dao.buscar(e.getIdEstudiante(), "", "*");
                titulo = "el Expediente de " + e.getNombreCompleto() + " (" + identificacion(e) + ")";
            } else {
                String apellido = Validador.normalizar(v).toLowerCase(Locale.ROOT);
                if (apellido.length() < 3) {
                    throw new ReglaNegocioException("Para buscar por apellido escriba al menos 3 letras.");
                }
                t = dao.buscar(0, apellido, "*");
                titulo = "los Expedientes con el apellido \"" + v + "\"";
            }
            log(con, "DOCUMENTO_DIGITAL", "SELECT");
            if (t.estaVacia()) {
                return GeneradorHTML.mensajeAviso("Búsqueda de expediente", "No hay registros para el criterio <b>" + v + "</b>: no coincide con "
                        + "el CI, el código, el apellido ni la gestión de ningún estudiante con documentos, actas o certificados.");
            }
            int documentos = 0;
            int actas = 0;
            int certificados = 0;
            for (String[] f : t.getFilas()) {
                documentos += "DOCUMENTO".equals(f[3]) ? 1 : 0;
                actas += "ACTA".equals(f[3]) ? 1 : 0;
                certificados += "CERTIFICADO".equals(f[3]) ? 1 : 0;
            }
            return GeneradorHTML.tabla(titulo + ": " + documentos + " documento(s), " + actas + " acta(s) y " + certificados + " certificado(s)", t);
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    // Adjunta a la respuesta el archivo de un documento (o todos los del expediente) tal como está guardado en el servidor
    private String enviarArchivos(Connection con, Estudiante e, String documento, String transaccion, String comando) throws SQLException {
        List<DocumentoDigital> pedidos;
        if (Validador.normalizar(documento).equals("TODOS")) {
            pedidos = new DocumentoDAO(con).todos(e.getIdEstudiante());
            if (pedidos.isEmpty()) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene documentos en su expediente.");
            }
        } else {
            pedidos = List.of(documento(con, e, tipo(documento), transaccion, comando));
        }
        List<String> enviados = new ArrayList<>();
        List<String> ausentes = new ArrayList<>();
        long total = 0;
        for (DocumentoDigital d : pedidos) {
            byte[] contenido = AlmacenDocumentos.leer(d.getUrlDoc());
            String nombre = d.esBoleta() ? "boleta del pago " + d.getNumeroTransaccion() : NOMBRES.getOrDefault(d.getTipoDocumento(), d.getTipoDocumento());
            if (contenido == null) {
                ausentes.add(nombre);
                continue;
            }
            total += contenido.length;
            if (total > TAMANO_MAXIMO_RESPUESTA) {
                throw new ReglaNegocioException("Los archivos pedidos superan los 15 MB que admite un correo: pídalos de a uno, por ejemplo "
                        + "<code>" + comando + "[\"" + e.getCi() + "\", \"" + d.getTipoDocumento() + "\"]</code>.");
            }
            String extension = d.getUrlDoc().substring(d.getUrlDoc().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String archivo = e.getCi() + "_" + d.getTipoDocumento() + (d.esBoleta() ? "_" + d.getNumeroTransaccion() : "") + "." + extension;
            adjuntarALaRespuesta(new Adjunto(archivo, extension.equals("pdf") ? "application/pdf" : "image/" + (extension.equals("png") ? "png" : "jpeg"),
                    contenido));
            enviados.add(nombre + " (presentación: " + d.getFechaPresentacion() + "): <b>" + archivo + "</b>");
        }
        if (enviados.isEmpty()) {
            throw new ReglaNegocioException("El archivo de " + String.join(", ", ausentes) + " no está en el servidor (" + pedidos.get(0).getUrlDoc()
                    + "). Vuelva a cargarlo con MODDOC.");
        }
        log(con, "DOCUMENTO_DIGITAL", "SELECT");
        return GeneradorHTML.mensajeExito("Documentos del Expediente",
                "Va" + (enviados.size() > 1 ? "n" : "") + " adjunto" + (enviados.size() > 1 ? "s" : "") + " " + enviados.size()
                        + " archivo(s) del expediente de <b>" + e.getNombreCompleto() + "</b> (" + identificacion(e) + "):<ul><li>"
                        + String.join("</li><li>", enviados) + "</li></ul>"
                        + (ausentes.isEmpty() ? "" : "No se encontró en el servidor el archivo de: " + String.join(", ", ausentes) + "."));
    }

    private static String tipo(String valor) {
        requerido(valor, "tipo_documento");
        String n = Validador.normalizar(valor).replace(' ', '_').replace("_DEL_", "_").replace("_DE_", "_");
        String t = NOMBRES.containsKey(n) ? n : ALIAS.get(n);
        if (t == null) {
            throw new ReglaNegocioException("<b>tipo_documento</b> no es válido (recibido: <b>" + valor.trim() + "</b>). Tipos: " + TIPOS_VALIDOS + ".");
        }
        return t;
    }

    // "la <b>fotografía</b>", "el <b>carnet de identidad</b>", "la <b>boleta</b> del pago <b>CERT-0001</b>"
    private static String descripcion(String tipo, String transaccion) {
        if (BOLETA.equals(tipo)) {
            return "la <b>boleta</b> del pago <b>" + transaccion + "</b>";
        }
        return ("FOTOGRAFIA".equals(tipo) || "LIBRETA_ESCOLAR".equals(tipo) ? "la" : "el") + " <b>" + NOMBRES.get(tipo) + "</b>";
    }

    private static String identificacion(Estudiante e) {
        return "CI " + e.getCi() + ", registro " + e.getCodigoEstudiante();
    }

    // El adjunto escaneado (PDF, JPG o PNG). La fotografía va impresa en el título: tiene que ser una imagen
    private static Adjunto archivo(List<Adjunto> adjuntos, String tipo) {
        if (!"FOTOGRAFIA".equals(tipo)) {
            return elegirArchivo(adjuntos, BOLETA.equals(tipo) ? "boleta de pago" : "copia escaneada del documento");
        }
        List<Adjunto> imagenes = new ArrayList<>();
        for (Adjunto a : adjuntos) {
            if (a.getExtension().equals("jpg") || a.getExtension().equals("png")) {
                imagenes.add(a);
            }
        }
        if (imagenes.isEmpty()) {
            throw new ReglaNegocioException("Falta la fotografía: adjúntela como imagen JPG o PNG (va impresa en el título).");
        }
        return elegirArchivo(imagenes, "fotografía");
    }

    // El documento al que se refiere MODDOC o DELDOC. Una boleta se elige por el número de transacción de su pago
    private DocumentoDigital documento(Connection con, Estudiante e, String tipo, String transaccion, String comando) throws SQLException {
        DocumentoDAO dao = new DocumentoDAO(con);
        if (!BOLETA.equals(tipo)) {
            DocumentoDigital d = dao.ultimo(e.getIdEstudiante(), tipo);
            if (d == null) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene " + NOMBRES.get(tipo) + " en su expediente. "
                        + "Cárguelo con <code>ADDDOC[\"" + e.getCi() + "\", \"" + tipo + "\"]</code>.");
            }
            return d;
        }
        List<DocumentoDigital> boletas = dao.boletas(e.getIdEstudiante(), transaccion == null ? "" : transaccion.trim());
        if (boletas.isEmpty()) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene " + (Validador.vacio(transaccion) ? "boletas de pago"
                    : "la boleta del pago <b>" + transaccion.trim() + "</b>") + " en su expediente. Consulte <code>LISDOC[\"" + e.getCi() + "\"]</code>.");
        }
        if (boletas.size() > 1) {
            List<String> numeros = new ArrayList<>();
            for (DocumentoDigital b : boletas) {
                numeros.add(b.getNumeroTransaccion());
            }
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> tiene varias boletas (" + String.join(", ", numeros)
                    + "): indique el número de transacción, por ejemplo <code>" + comando + "[\"" + e.getCi() + "\", \"BOLETA_PAGO\", \"" + numeros.get(0)
                    + "\"]</code>.");
        }
        return boletas.get(0);
    }

    // Pago del estudiante al que se le carga la boleta (ADDDOC de BOLETA_PAGO)
    private Pago pagoDelEstudiante(Connection con, Estudiante e, String transaccion) throws SQLException {
        if (Validador.vacio(transaccion)) {
            throw new ReglaNegocioException("La boleta es de un pago: indique su número de transacción, por ejemplo <code>ADDDOC[\"" + e.getCi()
                    + "\", \"BOLETA_PAGO\", \"numero_transaccion\"]</code>. Los pagos nuevos se registran con su boleta en ADDPAGO.");
        }
        Pago pago = new PagoDAO(con).buscarPorTransaccion(transaccion);
        if (pago == null || new InscripcionDAO(con).buscarPorId(pago.getIdInscripcion()).getIdEstudiante() != e.getIdEstudiante()) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene un pago con la transacción <b>" + transaccion.trim()
                    + "</b>. Consulte <code>ESTCTA[\"" + e.getCi() + "\"]</code>.");
        }
        return pago;
    }

    private static String archivar(String ruta) {
        try {
            return AlmacenDocumentos.archivar(ruta);
        } catch (IOException ex) {
            throw new ReglaNegocioException("No se pudo mover el archivo anterior a la carpeta histórica. Intente nuevamente.");
        }
    }

    // Filas {documento, exigido para, estado, detalle} según el nivel de cada programa en el que está inscrito
    private List<String[]> exigidos(Connection con, Estudiante e) throws SQLException {
        Map<String, List<String>> porTipo = new LinkedHashMap<>();
        for (String t : NOMBRES.keySet()) {
            porTipo.put(t, new ArrayList<>());
        }
        OfertaDAO ofertas = new OfertaDAO(con);
        ProgramaDAO programas = new ProgramaDAO(con);
        boolean conPrograma = false;
        for (Inscripcion i : new InscripcionDAO(con).deEstudiante(e.getIdEstudiante())) {
            if (!List.of("PREINSCRITO", "INSCRITO", "CONCLUIDO").contains(i.getEstado())) {
                continue;
            }
            OfertaAcademica o = ofertas.buscarPorId(i.getIdOferta());
            String nivel = programas.buscarPorCodigo(o.getCodigoPrograma()).getNivelTitulacion();
            String programa = o.getCodigoPrograma() + " (" + ContenidoCertificado.nivel(nivel) + ")";
            List<String> tipos = new ArrayList<>(EXIGIDOS_SIEMPRE);
            tipos.addAll(EXIGIDOS_POR_NIVEL.getOrDefault(nivel, List.of()));
            for (String t : tipos) {
                if (!porTipo.get(t).contains(programa)) {
                    porTipo.get(t).add(programa);
                }
            }
            conPrograma = true;
        }
        if (!conPrograma) {
            for (String t : EXIGIDOS_SIEMPRE) {
                porTipo.get(t).add("todo programa");
            }
        }
        DocumentoDAO docs = new DocumentoDAO(con);
        List<String[]> filas = new ArrayList<>();
        for (Map.Entry<String, List<String>> t : porTipo.entrySet()) {
            if (t.getValue().isEmpty()) {
                continue;
            }
            DocumentoDigital d = docs.ultimo(e.getIdEstudiante(), t.getKey());
            String estado;
            String detalle;
            if (d != null) {
                estado = "PRESENTADO";
                detalle = "el " + d.getFechaPresentacion();
            } else if ("TITULO_TECNICO_AUXILIAR".equals(t.getKey()) && new CertificadoDAO(con).tieneVigente(e.getIdEstudiante(), t.getKey())) {
                estado = "CUMPLIDO";
                detalle = "título emitido por el ICAP";
            } else {
                estado = "FALTA";
                detalle = "cárguelo con ADDDOC[\"" + e.getCi() + "\", \"" + t.getKey() + "\"]";
            }
            filas.add(new String[] { NOMBRES.get(t.getKey()), String.join(", ", t.getValue()), estado, detalle });
        }
        return filas;
    }

    private String resumenFaltantes(Connection con, Estudiante e) throws SQLException {
        List<String> faltan = new ArrayList<>();
        for (String[] f : exigidos(con, e)) {
            if ("FALTA".equals(f[2])) {
                faltan.add(f[0]);
            }
        }
        return faltan.isEmpty() ? "El expediente está completo para el nivel de su programa."
                : "Todavía le falta: <b>" + String.join(", ", faltan) + "</b>.";
    }
}
