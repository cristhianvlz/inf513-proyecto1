package src.negocio;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import src.comunicacion.ClienteSMTP;
import src.modelo.Adjunto;
import src.modelo.Estudiante;
import src.modelo.Inscripcion;
import src.modelo.Modulo;
import src.modelo.OfertaAcademica;
import src.persistencia.ConexionBD;
import src.persistencia.EstudianteDAO;
import src.persistencia.InscripcionDAO;
import src.persistencia.LogDAO;
import src.persistencia.ModuloDAO;
import src.persistencia.OfertaDAO;
import src.utilidades.AlmacenDocumentos;
import src.utilidades.ArchivoLog;
import src.utilidades.GeneradorHTML;
import src.utilidades.Validador;

// Base de los controladores: transacción por comando, bitácora (LOG), notificaciones y conversión de parámetros
public abstract class CtrlBase {

    // Parámetros que identifican una oferta en los comandos
    protected static final String OFERTA = "\"programa\", \"gestion\", \"grupo\"";

    private static final int EDAD_MINIMA = 14;

    private static final int TAMANO_MAXIMO_ADJUNTO = 10 * 1024 * 1024;

    // Usuario que envió el comando; 0 en comandos públicos enviados por alguien sin cuenta
    protected final int idRemitente;

    // Correos que se envían solo si la transacción se confirma
    private final List<Notificacion> notificaciones = new ArrayList<>();

    private record Notificacion(String destinatario, String asunto, String html, List<Adjunto> adjuntos) {
    }

    // Avisos de comandos ya confirmados que esperan a que salga primero la respuesta al remitente: cada envío tarda
    // varios segundos y quien mandó el comando no tiene por qué esperar los avisos a otras personas
    private static final List<Notificacion> PENDIENTES = new ArrayList<>();

    // Actor Estudiante: correo del estudiante que envió el comando (null si lo envió personal del ICAP o alguien sin cuenta).
    // No tiene USUARIO; ProcesadorComandos lo reconoce por el correo de su expediente y lo fija mientras dura el comando.
    // Con él, cualquier búsqueda de estudiante solo admite al propio remitente: consulta sus datos, nunca los de otro
    private static String correoEstudianteRemitente;

    static void estudianteRemitente(String correo) {
        correoEstudianteRemitente = correo;
    }

    // Archivos que viajan con la respuesta al remitente (por ejemplo, un documento del expediente pedido con LISDOC)
    private final List<Adjunto> adjuntosRespuesta = new ArrayList<>();

    protected CtrlBase(int idRemitente) {
        this.idRemitente = idRemitente;
    }

    protected interface Operacion {
        String ejecutar(Connection con) throws SQLException;
    }

    // Ejecuta la operación en una transacción: cualquier regla incumplida o error SQL deshace todo
    protected String enTransaccion(Operacion op) {
        notificaciones.clear();
        adjuntosRespuesta.clear();
        String respuesta;
        try {
            Connection con = ConexionBD.conectar();
            con.setAutoCommit(false);
            try {
                respuesta = op.ejecutar(con);
                con.commit();
            } catch (ReglaNegocioException ex) {
                adjuntosRespuesta.clear();
                return GeneradorHTML.mensajeError(ex.getMessage());
            } catch (SQLException ex) {
                adjuntosRespuesta.clear();
                ArchivoLog.error("SQL " + ex.getSQLState() + " en " + getClass().getSimpleName(), ex.getMessage());
                return GeneradorHTML.mensajeError(GeneradorHTML.formatearErrorSQL(ex));
            } finally {
                // La conexión se reutiliza: lo que no se confirmó se deshace antes de volver a autocommit
                con.rollback();
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            ArchivoLog.error("Base de datos: error de conexión en " + getClass().getSimpleName(), e);
            return GeneradorHTML.mensajeError("Error de conexión a la Base de Datos: " + e.getMessage());
        }
        // La transacción se confirmó: sus avisos se envían después de la respuesta (enviarNotificacionesPendientes)
        PENDIENTES.addAll(notificaciones);
        notificaciones.clear();
        return respuesta;
    }

    protected void notificar(String destinatario, String asunto, String html) {
        notificar(destinatario, asunto, html, List.of());
    }

    // Solo se envían si el comando termina bien
    protected void adjuntarALaRespuesta(Adjunto a) {
        adjuntosRespuesta.add(a);
    }

    public List<Adjunto> getAdjuntosRespuesta() {
        return new ArrayList<>(adjuntosRespuesta);
    }

    protected void notificar(String destinatario, String asunto, String html, List<Adjunto> adjuntos) {
        notificaciones.add(new Notificacion(destinatario, asunto, html, adjuntos));
    }

    // MainICAP la llama después de responder al remitente (ver ProcesadorComandos.enviarNotificaciones)
    static void enviarNotificacionesPendientes() {
        if (PENDIENTES.isEmpty()) {
            return;
        }
        List<Notificacion> lista = new ArrayList<>(PENDIENTES);
        PENDIENTES.clear();
        ClienteSMTP smtp = new ClienteSMTP();
        for (Notificacion n : lista) {
            System.out.println(">>> Enviando notificación a: " + n.destinatario());
            smtp.enviarCorreo(n.destinatario(), n.asunto(), n.html(), n.adjuntos());
        }
    }

    // LOG exige un usuario: los comandos públicos de remitentes sin cuenta no se registran
    protected void log(Connection con, String tabla, String accion) throws SQLException {
        if (idRemitente > 0) {
            new LogDAO(con).registrar(idRemitente, tabla, accion);
        }
    }

    protected String formato(String sintaxis) {
        return GeneradorHTML.mensajeError("Faltan parámetros. Formato correcto: <code>" + sintaxis + "</code>");
    }

    protected static String param(String[] p, int i) {
        return i < p.length ? p[i] : "";
    }

    // La oferta se identifica con los parámetros programa, gestión y grupo a partir de la posición i
    protected OfertaAcademica buscarOferta(Connection con, String[] p, int i) throws SQLException {
        requerido(param(p, i), "programa");
        requerido(param(p, i + 1), "gestion");
        requerido(param(p, i + 2), "grupo");
        OfertaAcademica o = new OfertaDAO(con).buscar(p[i], p[i + 1], p[i + 2]);
        if (o == null) {
            throw new ReglaNegocioException("No existe la oferta <b>" + p[i].trim().toUpperCase() + " " + p[i + 1].trim().toUpperCase()
                    + " grupo " + p[i + 2].trim().toUpperCase() + "</b>. Consulte <code>CATALOGO</code> o <code>LISOFER[\""
                    + p[i].trim().toUpperCase() + "\"]</code>.");
        }
        return o;
    }

    protected Modulo buscarModulo(Connection con, String codigo) throws SQLException {
        requerido(codigo, "modulo");
        Modulo m = new ModuloDAO(con).buscarPorCodigo(codigo);
        if (m == null) {
            throw new ReglaNegocioException("No existe un módulo con el código <b>" + codigo
                    + "</b>. Consulte LISMOD[\"*\"] o regístrelo con ADDMOD.");
        }
        return m;
    }

    // Los comandos del CU-03 y CU-04 aceptan el CI o el código del estudiante
    protected Estudiante buscarEstudiante(Connection con, String valor) throws SQLException {
        requerido(valor, "estudiante");
        Estudiante e = new EstudianteDAO(con).buscarPorCiOCodigo(valor);
        if (e == null) {
            throw new ReglaNegocioException("No existe un estudiante con el CI o código <b>" + valor + "</b>. Consulte LISEST.");
        }
        if (correoEstudianteRemitente != null && !correoEstudianteRemitente.equalsIgnoreCase(String.valueOf(e.getEmail()).trim())) {
            throw new ReglaNegocioException("El CI o código <b>" + valor + "</b> no corresponde a su expediente: "
                    + "como estudiante solo puede consultar sus propios datos.");
        }
        return e;
    }

    protected Inscripcion buscarInscripcion(Connection con, Estudiante e, OfertaAcademica o) throws SQLException {
        Inscripcion i = new InscripcionDAO(con).buscar(e.getIdEstudiante(), o.getIdOferta());
        if (i == null) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene inscripción en la " + o.getDescripcion() + ".");
        }
        return i;
    }

    // El primer adjunto PDF o imagen se toma como el documento pedido
    protected static Adjunto elegirArchivo(List<Adjunto> adjuntos, String documento) {
        if (adjuntos.isEmpty()) {
            throw new ReglaNegocioException("Falta la " + documento + ": adjúntela al correo en formato PDF, JPG o PNG.");
        }
        for (Adjunto a : adjuntos) {
            if (!a.getExtension().isEmpty()) {
                if (a.getContenido().length > TAMANO_MAXIMO_ADJUNTO) {
                    throw new ReglaNegocioException("La " + documento + " supera los 10 MB permitidos.");
                }
                return a;
            }
        }
        throw new ReglaNegocioException("La " + documento + " debe estar en formato PDF, JPG o PNG.");
    }

    protected static String guardarDocumento(String ci, String tipoDocumento, Adjunto adjunto) {
        return guardarArchivo(() -> AlmacenDocumentos.guardar(ci, tipoDocumento, adjunto));
    }

    // Documentos que no son del expediente del estudiante: el CV del profesor, el programa analítico y la propuesta técnica
    protected interface Guardado {
        String guardar() throws IOException;
    }

    protected static String guardarArchivo(Guardado g) {
        try {
            return g.guardar();
        } catch (IOException e) {
            throw new ReglaNegocioException("No se pudo guardar el archivo adjunto. Intente nuevamente.");
        }
    }

    // Un adjunto elegido por su nombre (ADDASIG trae dos documentos en el mismo correo): PDF, JPG o PNG de hasta 10 MB
    protected static Adjunto archivoPorNombre(List<Adjunto> adjuntos, String nombre, String documento) {
        String buscado = Validador.normalizar(nombre).toLowerCase();
        List<String> recibidos = new ArrayList<>();
        for (Adjunto a : adjuntos) {
            String n = a.getNombre() == null ? "" : Validador.normalizar(a.getNombre()).toLowerCase();
            recibidos.add(a.getNombre() == null ? "(sin nombre)" : a.getNombre());
            // Se acepta con o sin la extensión: "programa_gas101.pdf" o "programa_gas101"
            if (n.equals(buscado) || (n.contains(".") && n.substring(0, n.lastIndexOf('.')).equals(buscado))) {
                if (a.getExtension().isEmpty()) {
                    throw new ReglaNegocioException("El archivo <b>" + nombre + "</b> (" + documento + ") debe estar en formato PDF, JPG o PNG.");
                }
                if (a.getContenido().length > TAMANO_MAXIMO_ADJUNTO) {
                    throw new ReglaNegocioException("El archivo <b>" + nombre + "</b> (" + documento + ") supera los 10 MB permitidos.");
                }
                return a;
            }
        }
        throw new ReglaNegocioException("No viene adjunto el archivo <b>" + nombre + "</b> (" + documento + "). "
                + (recibidos.isEmpty() ? "El correo no trae archivos adjuntos." : "Archivos recibidos: <b>" + String.join(", ", recibidos) + "</b>."));
    }

    // El archivo reemplazado pasa a la carpeta historico: ningún documento se destruye
    protected static void archivarAnterior(String ruta) {
        if (AlmacenDocumentos.existe(ruta)) {
            try {
                AlmacenDocumentos.archivar(ruta);
            } catch (IOException e) {
                throw new ReglaNegocioException("No se pudo mover al histórico el archivo anterior. Intente nuevamente.");
            }
        }
    }

    // Un documento guardado, listo para adjuntarlo a un correo; null si no hay archivo
    protected static Adjunto adjuntoGuardado(String ruta, String nombre) {
        byte[] contenido = AlmacenDocumentos.existe(ruta) ? AlmacenDocumentos.leer(ruta) : null;
        if (contenido == null) {
            return null;
        }
        String extension = ruta.substring(ruta.lastIndexOf('.'));
        return new Adjunto(nombre + extension, AlmacenDocumentos.tipoMime(ruta), contenido);
    }

    protected static LocalDate validarFechaNacimiento(String texto) {
        LocalDate fecha = Validador.fecha(texto);
        if (fecha == null) {
            throw new ReglaNegocioException("La fecha de nacimiento <b>" + texto + "</b> no es válida. Use el formato AAAA-MM-DD.");
        }
        int edad = Validador.edad(fecha);
        if (edad < EDAD_MINIMA) {
            throw new ReglaNegocioException("El estudiante tiene " + edad + " años. La edad mínima es " + EDAD_MINIMA + " años.");
        }
        return fecha;
    }

    // Valor validado de un dato del estudiante, listo para EstudianteDAO.actualizarCampo (MODEST y MODPRE)
    protected static Object valorEstudiante(String campo, String texto) {
        if (campo.equals("fecha_nacimiento")) {
            return validarFechaNacimiento(texto);
        }
        if (campo.equals("email")) {
            String email = texto.toLowerCase();
            if (!Validador.vacio(email) && !Validador.esEmail(email)) {
                throw new ReglaNegocioException("El correo <b>" + email + "</b> no tiene un formato válido.");
            }
            return nulo(email);
        }
        if (campo.equals("nombre") || campo.equals("paterno") || campo.equals("pais")) {
            if (Validador.vacio(texto)) {
                throw new ReglaNegocioException("El campo <b>" + campo + "</b> no puede quedar vacío.");
            }
            return texto.trim();
        }
        return nulo(texto);
    }

    // Los campos opcionales vacíos se guardan como NULL
    protected static String nulo(String s) {
        return Validador.vacio(s) ? null : s.trim();
    }

    protected static String filtroListado(String filtro) {
        return Validador.vacio(filtro) ? "*" : filtro.trim();
    }

    // Nombre del campo en el comando -> columna: "Dirección" -> "direccion", y los alias de cada controlador
    protected static String columna(String campo, Map<String, String> alias) {
        String n = Validador.normalizar(campo).replace(' ', '_');
        return alias.getOrDefault(n, n.toLowerCase());
    }

    protected static void requerido(String valor, String nombre) {
        if (Validador.vacio(valor)) {
            throw new ReglaNegocioException("El campo <b>" + nombre + "</b> es obligatorio.");
        }
    }

    protected static int entero(String valor, String nombre) {
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new ReglaNegocioException("<b>" + nombre + "</b> debe ser un número entero (recibido: <b>" + valor + "</b>).");
        }
    }

    protected static BigDecimal decimal(String valor, String nombre) {
        try {
            return new BigDecimal(valor.trim().replace(',', '.'));
        } catch (NumberFormatException | NullPointerException e) {
            throw new ReglaNegocioException("<b>" + nombre + "</b> debe ser un número (recibido: <b>" + valor + "</b>).");
        }
    }

    protected static LocalDate fecha(String valor, String nombre) {
        LocalDate f = Validador.fecha(valor);
        if (f == null) {
            throw new ReglaNegocioException("<b>" + nombre + "</b> no es una fecha válida (recibido: <b>" + valor
                    + "</b>). Use el formato AAAA-MM-DD.");
        }
        return f;
    }

    protected static LocalTime hora(String valor, String nombre) {
        LocalTime h = Validador.hora(valor);
        if (h == null) {
            throw new ReglaNegocioException("<b>" + nombre + "</b> no es una hora válida (recibido: <b>" + valor
                    + "</b>). Use el formato HH:MM, por ejemplo 19:00.");
        }
        return h;
    }

    // Valor normalizado (sin tildes, en mayúsculas y con _) que debe pertenecer a la lista de permitidos
    protected static String opcion(String valor, List<String> permitidos, String nombre) {
        String n = Validador.normalizar(valor).replace(' ', '_');
        if (!permitidos.contains(n)) {
            throw new ReglaNegocioException("<b>" + nombre + "</b> no es válido (recibido: <b>" + valor
                    + "</b>). Valores permitidos: " + String.join(", ", permitidos) + ".");
        }
        return n;
    }
}
