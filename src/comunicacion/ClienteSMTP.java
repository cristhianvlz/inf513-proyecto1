package src.comunicacion;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import src.modelo.Adjunto;
import src.utilidades.ArchivoLog;
import src.utilidades.Configuracion;

// Envío de las respuestas por SMTP.
// Conectarse es lo más lento del envío: medido desde una conexión doméstica, el servidor tarda unos 11 s en saludar
// (todo indica que intenta averiguar el nombre de la IP que se conecta y esa IP no tiene). Por eso la conexión se abre
// en segundo plano apenas llega un correo, mientras se ejecuta el comando, y se reutiliza para los avisos y respuestas
// que salen enseguida (notificaciones de un comando, aviso de cupo mínimo, cierre de actas)
public class ClienteSMTP {
    // Buzón del sistema: recibe los comandos y envía las respuestas (también es el destino del QR de los certificados)
    public static final String CORREO = Configuracion.get("correo.direccion");

    // Datos del servidor en icap.properties (no en el código)
    private static final String SERVIDOR = Configuracion.get("correo.servidor");
    private static final String USUARIO = Configuracion.get("correo.usuario");
    private static final String CLAVE = Configuracion.get("correo.clave");
    private static final int PUERTO = Configuracion.entero("correo.smtp.puerto", 25);

    private static final int TIEMPO_ESPERA_MS = 30000;
    // En una conexión reutilizada el primer comando debe responder pronto: si no, se la da por cortada y se abre otra
    private static final int ESPERA_REUTILIZADA_MS = 8000;
    // Una conexión recién abierta se usa directamente; la que lleva más que esto sin uso se comprueba
    private static final long ESPERA_SIN_COMPROBAR_MS = 5000;
    // El servidor del curso corta una conexión sin uso entre los 20 y los 30 s (medido): se cierra antes, con QUIT
    private static final long INACTIVIDAD_MAXIMA_MS = 15_000;
    private static final byte[] FIN_DE_LINEA = "\r\n".getBytes(StandardCharsets.US_ASCII);
    // Una palabra codificada (RFC 2047) no puede pasar de 75 caracteres: 45 bytes de texto ocupan 60 en base64
    private static final int BYTES_POR_PALABRA = 45;

    // Si el servidor rechaza la autenticación, no se vuelve a intentar hasta reiniciar MainICAP: muchos intentos fallidos
    // seguidos pueden hacer que el servidor bloquee la IP (y con ella también POP3 y la base, que están en el mismo equipo)
    private static volatile boolean autenticacionRechazada = false;

    // Conexión abierta (saludo, EHLO y autenticación hechos) esperando el próximo envío, y la que se está abriendo
    // en segundo plano. Todos los envíos salen del hilo principal; el otro hilo solo abre y cierra conexiones
    private static Sesion guardada;
    private static Future<Sesion> enApertura;
    private static final ExecutorService SEGUNDO_PLANO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smtp-conexion");
        t.setDaemon(true);
        return t;
    });

    // MainICAP la llama apenas llega un correo: el saludo del servidor se espera mientras se ejecuta el comando
    public static synchronized void prepararConexion() {
        if (guardada == null && enApertura == null) {
            enApertura = SEGUNDO_PLANO.submit(() -> abrir(false));
        }
    }

    // MainICAP la llama en cada revisión del buzón: una conexión sin uso por 15 s se cierra con QUIT
    public static synchronized void cerrarSiInactiva() {
        if (enApertura != null && enApertura.isDone()) {
            Sesion s = esperar(enApertura);
            enApertura = null;
            if (s != null && guardada == null) {
                guardada = s;
            } else if (s != null) {
                s.cerrarEnSegundoPlano();
            }
        }
        if (guardada != null && guardada.inactiva() > INACTIVIDAD_MAXIMA_MS) {
            guardada.cerrarEnSegundoPlano();
            guardada = null;
        }
    }

    public String enviarCorreo(String user_receptor, String asunto, String contenidoHtml) {
        return enviarCorreo(user_receptor, asunto, contenidoHtml, List.of());
    }

    // Con adjuntos (por ejemplo, el PDF de un certificado) el mensaje va como multipart/mixed.
    // Devuelve en pocas palabras qué pasó con el envío; MainICAP lo anota en access.log
    public String enviarCorreo(String user_receptor, String asunto, String contenidoHtml, List<Adjunto> adjuntos) {
        // Guardamos vista previa en el proyecto para ver las tablas HTML al instante
        guardarVistaPreviaLocal(contenidoHtml);

        Sesion s = null;
        try {
            s = tomarSesion();
            String resultado;
            try {
                resultado = transaccion(s, user_receptor, asunto, contenidoHtml, adjuntos);
            } catch (ConexionCortada e) {
                // La conexión guardada ya no respondía y todavía no se había enviado nada: se repite con una nueva
                System.out.println(">>> [SMTP]: La conexión guardada ya no responde (" + e.getMessage() + "); se abre una nueva.");
                s.descartar();
                s = abrir(true);
                resultado = transaccion(s, user_receptor, asunto, contenidoHtml, adjuntos);
            }
            if (resultado.startsWith("no enviada")) {
                s.cerrar();
            } else {
                guardar(s);
            }
            return resultado;

        } catch (IOException e) {
            if (s != null) {
                // La conexión falló a mitad de camino: no tiene sentido esperar la respuesta a un QUIT
                s.descartar();
            }
            System.out.println("S : No se pudo enviar el correo a " + user_receptor + ": " + e.getMessage());
            ArchivoLog.error("SMTP: no se pudo enviar el correo a " + user_receptor + " por " + SERVIDOR + ":" + PUERTO, e);
            return "no enviada (" + e.getMessage() + ")";
        }
    }

    // MAIL, RCPT y DATA de un mensaje. Al terminar, la conexión queda lista para el siguiente
    private String transaccion(Sesion s, String user_receptor, String asunto, String contenidoHtml, List<Adjunto> adjuntos)
            throws IOException {
        // 3. MAIL FROM (1. saludo y 2. EHLO + autenticación ya se hicieron al abrir la conexión).
        // Si la conexión estuvo esperando, pudo haberse cortado: se comprueba con una espera corta
        boolean comprobar = s.reutilizada || s.inactiva() > ESPERA_SIN_COMPROBAR_MS;
        String r = comprobar ? s.primerComando("MAIL FROM:<" + CORREO + ">") : ultima(s.comando("MAIL FROM:<" + CORREO + ">", null));
        if (!r.startsWith("250")) {
            return fallo(user_receptor, "el servidor rechazó el remitente: " + r);
        }

        // 4. RCPT TO. Si el servidor no reenvía a ese dominio (550 Relaying denied), la respuesta queda en el buzón
        // del grupo para verla en el Webmail (POP3 ignora las autorrespuestas) y el rechazo queda en error.log
        String resultado = "entregada al servidor para " + user_receptor;
        String rcpt = ultima(s.comando("RCPT TO:<" + user_receptor + ">", null));
        if (!rcpt.startsWith("250")) {
            System.out.println(">>> [AVISO]: El servidor no aceptó el destinatario " + user_receptor + ": " + rcpt);
            System.out.println(">>> [AVISO]: La respuesta se deja en el buzón " + CORREO + " (Webmail).");
            ArchivoLog.error("SMTP", "El servidor no aceptó el destinatario " + user_receptor + " (" + rcpt + "). La respuesta se dejó en el buzón "
                    + CORREO + ".");
            resultado = "NO llegó a " + user_receptor + " (" + codigo(rcpt) + "); se dejó en el buzón " + CORREO;
            rcpt = ultima(s.comando("RCPT TO:<" + CORREO + ">", null));
            if (!rcpt.startsWith("250")) {
                return fallo(user_receptor, "el servidor tampoco aceptó el buzón del grupo: " + rcpt);
            }
        }

        // 5. DATA
        String data = ultima(s.comando("DATA", null));
        if (!data.startsWith("354")) {
            return fallo(user_receptor, "el servidor no aceptó DATA: " + data);
        }
        System.out.println("C : SUBJECT: Respuesta ICAP: " + asunto);
        System.out.println("C : [Cuerpo HTML enviado" + (adjuntos.isEmpty() ? "" : " con " + adjuntos.size() + " adjunto(s)") + "]");
        System.out.println("C : .");
        s.escribir(mensaje(user_receptor, asunto, contenidoHtml, adjuntos).getBytes(StandardCharsets.UTF_8));
        String fin = ultima(s.respuesta());
        if (!fin.startsWith("250")) {
            return fallo(user_receptor, "el servidor no aceptó el mensaje: " + fin);
        }
        // 6. Sin QUIT: la conexión queda abierta para el siguiente envío y se cierra tras 15 s sin uso
        return resultado;
    }

    // La conexión guardada, la que se abrió en segundo plano o, si no hay ninguna, una nueva
    private static synchronized Sesion tomarSesion() throws IOException {
        Sesion s = guardada;
        guardada = null;
        String origen = null;
        if (s != null) {
            origen = "Se reutiliza la conexión abierta hace " + s.edad() + " s (no hace falta esperar el saludo del servidor).";
        }
        if (enApertura != null) {
            Sesion nueva = esperar(enApertura);
            enApertura = null;
            if (s == null && nueva != null) {
                s = nueva;
                origen = "Conexión abierta mientras se procesaba el comando:";
            } else if (nueva != null) {
                nueva.cerrarEnSegundoPlano();
            }
        }
        if (s != null && s.inactiva() > INACTIVIDAD_MAXIMA_MS) {
            s.cerrarEnSegundoPlano();
            s = null;
        }
        if (s == null) {
            return abrir(true);
        }
        System.out.println(">>> [SMTP]: " + origen);
        s.mostrarTraza();
        return s;
    }

    private static synchronized void guardar(Sesion s) {
        s.ultimoUso = System.currentTimeMillis();
        s.reutilizada = true;
        if (guardada != null) {
            guardada.cerrarEnSegundoPlano();
        }
        guardada = s;
    }

    // Resultado de una apertura en segundo plano; si falló, se devuelve null y el envío abre otra conexión
    private static Sesion esperar(Future<Sesion> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            System.out.println(">>> [SMTP]: No se pudo abrir la conexión en segundo plano (" + e.getCause() + "); se intenta de nuevo al enviar.");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // 1. Conexión y saludo 220, 2. EHLO y autenticación
    private static Sesion abrir(boolean enVivo) throws IOException {
        Sesion s = Sesion.conectar(enVivo);
        try {
            String saludo = s.respuesta().get(0);
            if (!saludo.startsWith("220")) {
                throw new IOException("el servidor no aceptó la conexión: " + saludo);
            }
            // EHLO: el servidor dice qué admite, entre otras cosas los mecanismos de autenticación
            List<String> ehlo = s.comando("EHLO " + SERVIDOR, null);
            // Autenticación, solo con un mecanismo que el servidor ofrezca
            autenticar(s, ehlo);
            s.ultimoUso = System.currentTimeMillis();
            return s;
        } catch (IOException | RuntimeException e) {
            s.descartar();
            throw e;
        }
    }

    // Prefiere CRAM-MD5, que no manda la clave por la red; LOGIN y PLAIN la mandan en base64 (el puerto 25 no está cifrado)
    private static void autenticar(Sesion s, List<String> ehlo) throws IOException {
        if (autenticacionRechazada) {
            return;
        }
        List<String> mecanismos = new ArrayList<>();
        for (String linea : ehlo) {
            String t = linea.length() > 4 ? linea.substring(4).trim().toUpperCase(Locale.ROOT) : "";
            if (t.startsWith("AUTH ") || t.startsWith("AUTH=")) {
                mecanismos.addAll(Arrays.asList(t.substring(5).trim().split("\\s+")));
            }
        }
        String r;
        if (mecanismos.contains("CRAM-MD5")) {
            String reto = ultima(s.comando("AUTH CRAM-MD5", null));
            if (!reto.startsWith("334")) {
                r = reto;
            } else {
                String desafio = new String(Base64.getDecoder().decode(reto.substring(4).trim()), StandardCharsets.UTF_8);
                r = ultima(s.comando(base64(USUARIO + " " + hmacMd5(desafio)), "[ respuesta CRAM-MD5 ]"));
            }
        } else if (mecanismos.contains("LOGIN")) {
            r = ultima(s.comando("AUTH LOGIN", null));
            if (r.startsWith("334")) {
                r = ultima(s.comando(base64(USUARIO), "[ Base64 USER: " + USUARIO + " ]"));
            }
            if (r.startsWith("334")) {
                r = ultima(s.comando(base64(CLAVE), "[ Base64 PASS: ******** ]"));
            }
        } else if (mecanismos.contains("PLAIN")) {
            r = ultima(s.comando("AUTH PLAIN " + base64("\0" + USUARIO + "\0" + CLAVE), "AUTH PLAIN ********"));
        } else {
            s.anotar(">>> [SMTP]: El servidor no ofrece un mecanismo de autenticación conocido; se envía sin autenticar.");
            return;
        }
        if (!r.startsWith("235")) {
            autenticacionRechazada = true;
            ArchivoLog.error("SMTP", "El servidor rechazó la autenticación (" + r + "; mecanismos ofrecidos: " + mecanismos
                    + "). Hasta reiniciar MainICAP los correos se envían sin autenticar.");
        }
    }

    private static String hmacMd5(String desafio) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(CLAVE.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(desafio.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacMD5 no disponible", e);
        }
    }

    // Encabezados y cuerpo. El HTML va en base64: así viaja en líneas cortas (SMTP corta las líneas de más de 998
    // caracteres y el manual de ayuda es una sola línea de más de 70 000) y los acentos llegan intactos
    private String mensaje(String receptor, String asunto, String html, List<Adjunto> adjuntos) {
        String dominio = CORREO.substring(CORREO.indexOf('@') + 1);
        StringBuilder m = new StringBuilder()
                .append("From: Sistema ICAP <").append(CORREO).append(">\r\n")
                .append("To: <").append(receptor).append(">\r\n")
                .append("Subject: ").append(encabezado("Respuesta ICAP: " + asunto)).append("\r\n")
                .append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n")
                .append("Message-ID: <").append(UUID.randomUUID()).append('@').append(dominio).append(">\r\n")
                .append("MIME-Version: 1.0\r\n");
        if (adjuntos.isEmpty()) {
            m.append("Content-Type: text/html; charset=UTF-8\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(base64Mime(html.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        } else {
            m.append(multipart(html, adjuntos)).append("\r\n");
        }
        // Una línea que empieza con punto se duplica: un punto solo cerraría el mensaje (RFC 5321)
        return m.toString().replace("\r\n.", "\r\n..") + ".\r\n";
    }

    // Cuerpo HTML más los archivos, todo en base64 en líneas de 76 caracteres
    private static String multipart(String html, List<Adjunto> adjuntos) {
        String limite = "ICAP-" + UUID.randomUUID();
        StringBuilder sb = new StringBuilder("Content-Type: multipart/mixed; boundary=\"" + limite + "\"\r\n\r\n");
        sb.append("--").append(limite).append("\r\n")
                .append("Content-Type: text/html; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(base64Mime(html.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        for (Adjunto a : adjuntos) {
            String nombre = a.getNombre().replaceAll("[^0-9A-Za-z._-]", "_");
            sb.append("--").append(limite).append("\r\n")
                    .append("Content-Type: ").append(a.getTipoMime()).append("; name=\"").append(nombre).append("\"\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n")
                    .append("Content-Disposition: attachment; filename=\"").append(nombre).append("\"\r\n\r\n")
                    .append(base64Mime(a.getContenido()))
                    .append("\r\n");
        }
        return sb.append("--").append(limite).append("--").toString();
    }

    // Un encabezado con tildes o ñ va como palabras codificadas =?UTF-8?B?...?= (RFC 2047), sin partir un carácter
    static String encabezado(String texto) {
        if (texto.chars().allMatch(c -> c >= 32 && c < 127)) {
            return texto;
        }
        List<String> palabras = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < texto.length();) {
            int cp = texto.codePointAt(i);
            String c = new String(Character.toChars(cp));
            int largo = c.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + largo > BYTES_POR_PALABRA) {
                palabras.add(palabraCodificada(actual.toString()));
                actual.setLength(0);
                bytes = 0;
            }
            actual.append(c);
            bytes += largo;
            i += Character.charCount(cp);
        }
        if (actual.length() > 0) {
            palabras.add(palabraCodificada(actual.toString()));
        }
        return String.join("\r\n ", palabras);
    }

    private static String palabraCodificada(String s) {
        return "=?UTF-8?B?" + base64(s) + "?=";
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Mime(byte[] datos) {
        return Base64.getMimeEncoder(76, FIN_DE_LINEA).encodeToString(datos);
    }

    private static String ultima(List<String> lineas) {
        return lineas.get(lineas.size() - 1);
    }

    private static String codigo(String respuesta) {
        return respuesta.length() >= 3 ? respuesta.substring(0, 3) : respuesta;
    }

    private static String fallo(String receptor, String motivo) {
        System.out.println(">>> [SMTP]: No se envió el correo a " + receptor + ": " + motivo);
        ArchivoLog.error("SMTP", "No se envió el correo a " + receptor + ": " + motivo);
        return "no enviada (" + motivo + ")";
    }

    private void guardarVistaPreviaLocal(String html) {
        try (FileOutputStream fos = new FileOutputStream("respuesta_icap.html")) {
            fos.write(html.getBytes(StandardCharsets.UTF_8));
            System.out.println(
                    ">>> [ ARCHIVO GENERADO ]: Abre 'respuesta_icap.html' en tu navegador para ver la respuesta.");
        } catch (IOException e) {
            System.out.println("Error guardando vista previa: " + e.getMessage());
        }
    }

    // La conexión guardada se cortó antes de enviar nada (el servidor o la red la cerraron por inactividad)
    private static final class ConexionCortada extends IOException {
        ConexionCortada(String motivo) {
            super(motivo);
        }
    }

    // Una conexión con el servidor SMTP. La conversación se muestra en la consola; la que ocurre en segundo plano se
    // guarda y se muestra cuando la conexión se usa, para no mezclarse con lo que imprime el hilo principal
    private static final class Sesion {
        private final Socket socket;
        private final BufferedReader entrada;
        private final OutputStream salida;
        private final long abierta = System.currentTimeMillis();
        private long ultimoUso = abierta;
        private boolean reutilizada;
        private boolean enVivo;
        private final StringBuilder traza = new StringBuilder();

        private Sesion(Socket socket, boolean enVivo) throws IOException {
            this.socket = socket;
            this.enVivo = enVivo;
            this.entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.salida = socket.getOutputStream();
        }

        static Sesion conectar(boolean enVivo) throws IOException {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(SERVIDOR, PUERTO), TIEMPO_ESPERA_MS);
                // Si el servidor deja de responder, se corta en vez de dejar colgado a MainICAP
                socket.setSoTimeout(TIEMPO_ESPERA_MS);
                // Sin esto TCP retiene el resto del comando y cada uno tarda el doble
                socket.setTcpNoDelay(true);
                return new Sesion(socket, enVivo);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        void anotar(String linea) {
            if (enVivo) {
                System.out.println(linea);
            } else {
                traza.append(linea).append(System.lineSeparator());
            }
        }

        void mostrarTraza() {
            if (!enVivo) {
                System.out.print(traza);
                traza.setLength(0);
                enVivo = true;
            }
        }

        // Envía una línea, muestra la conversación (la clave nunca) y devuelve las líneas de la respuesta
        List<String> comando(String linea, String visible) throws IOException {
            anotar("C : " + (visible != null ? visible : linea));
            escribir((linea + "\r\n").getBytes(StandardCharsets.UTF_8));
            return respuesta();
        }

        // Primer comando en una conexión reutilizada: si no hay respuesta pronto (o el servidor avisa 421 que cierra),
        // la conexión ya estaba cortada y no se alcanzó a enviar nada, así que se puede repetir en otra sin duplicar el correo
        String primerComando(String linea) throws IOException {
            try {
                socket.setSoTimeout(ESPERA_REUTILIZADA_MS);
                String r = ultima(comando(linea, null));
                socket.setSoTimeout(TIEMPO_ESPERA_MS);
                if (r.startsWith("421")) {
                    throw new ConexionCortada(r);
                }
                return r;
            } catch (ConexionCortada e) {
                throw e;
            } catch (IOException e) {
                throw new ConexionCortada(e.getMessage());
            }
        }

        void escribir(byte[] datos) throws IOException {
            salida.write(datos);
            salida.flush();
        }

        // Una respuesta SMTP puede ocupar varias líneas ("250-..."); la última lleva un espacio después del código
        List<String> respuesta() throws IOException {
            List<String> lineas = new ArrayList<>();
            String line;
            while ((line = entrada.readLine()) != null) {
                anotar("S : " + line);
                lineas.add(line);
                if (line.length() < 4 || line.charAt(3) != '-') {
                    break;
                }
            }
            if (lineas.isEmpty()) {
                throw new IOException("el servidor cerró la conexión");
            }
            return lineas;
        }

        long inactiva() {
            return System.currentTimeMillis() - ultimoUso;
        }

        long edad() {
            return (System.currentTimeMillis() - abierta) / 1000;
        }

        // QUIT y cierre; si el servidor ya cortó la conexión, solo se cierra el socket
        void cerrar() {
            try {
                if (!socket.isClosed()) {
                    socket.setSoTimeout(5000);
                    comando("QUIT", null);
                }
            } catch (IOException e) {
                // la conexión ya estaba cortada
            } finally {
                descartar();
            }
        }

        // Cierre sin QUIT, para una conexión que ya no responde
        void descartar() {
            try {
                socket.close();
            } catch (IOException e) {
                // nada más que hacer
            }
        }

        // Sin esperar la respuesta del QUIT en el hilo principal, y sin mostrar esa conversación en la consola
        void cerrarEnSegundoPlano() {
            enVivo = false;
            traza.setLength(0);
            SEGUNDO_PLANO.submit(this::cerrar);
        }
    }
}
