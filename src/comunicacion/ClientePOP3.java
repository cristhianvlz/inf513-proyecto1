package src.comunicacion;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import src.modelo.CorreoRecibido;
import src.utilidades.ArchivoLog;
import src.utilidades.Configuracion;
import src.utilidades.RegistroLocal;

public class ClientePOP3 {
    // Datos del buzón en icap.properties (no en el código)
    private final String servidor = Configuracion.get("correo.servidor");
    private final String user = Configuracion.get("correo.usuario");
    private final String pass = Configuracion.get("correo.clave");
    private final String direccion = Configuracion.get("correo.direccion");
    private final int puerto = Configuracion.entero("correo.pop3.puerto", 110);

    // El buzón se revisa cada pocos segundos y el servidor a veces corta una revisión (Connection reset) o no saluda.
    // error.log anota una sola línea por corte: si la revisión siguiente ya funciona, "corte breve"; si no, la caída y,
    // al volver, cuánto duró. Son errores de red: la traza de Java no aporta nada
    private int fallosSeguidos = 0;
    private long caidaDesde;
    private String primerFallo;

    // Registro local de los correos ya procesados (un UIDL por línea).
    // Los correos nunca se borran del servidor: no se usa DELE
    private final RegistroLocal registro = new RegistroLocal("correos_procesados.txt");

    // Devuelve los correos que llegaron desde la última revisión, del más antiguo al más nuevo
    public List<CorreoRecibido> leerCorreosNuevos() {
        List<CorreoRecibido> nuevos = new ArrayList<>();
        // La conversación con el servidor se muestra solo si llegaron correos o hubo algún problema,
        // así las revisiones sin novedades no llenan la consola
        StringBuilder traza = new StringBuilder();
        boolean mostrar = false;

        try (Socket socket = conectar();
                BufferedReader entrada = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                DataOutputStream salida = new DataOutputStream(socket.getOutputStream())) {

            // writeBytes envía byte a byte: sin esto TCP retiene el resto del comando y cada uno tarda el doble
            socket.setTcpNoDelay(true);
            traza.append("S : ").append(entrada.readLine()).append("\n");

            enviar("USER " + user, salida, entrada, traza);
            enviar("PASS " + pass, salida, entrada, traza);

            // UIDL: identificador único y permanente de cada correo (no cambia aunque cambie su número)
            String respUidl = enviar("UIDL", salida, entrada, traza);

            if (respUidl != null && respUidl.startsWith("+OK")) {
                boolean primeraEjecucion = !registro.existe();
                Set<String> procesados = registro.cargar();
                List<String> existentes = new ArrayList<>();

                for (String linea : getMultiline(entrada).trim().split("\n")) {
                    String[] partes = linea.trim().split(" ");
                    if (partes.length < 2 || procesados.contains(partes[1])) {
                        continue;
                    }
                    int num = Integer.parseInt(partes[0]);
                    String uid = partes[1];

                    // En la primera ejecución los correos que ya estaban en el buzón no se ejecutan
                    if (primeraEjecucion) {
                        existentes.add(uid);
                        continue;
                    }

                    traza.append("C : RETR ").append(num).append("\n");
                    salida.writeBytes("RETR " + num + "\r\n");
                    String correo = getMultiline(entrada);

                    String remitente = "";
                    String asunto = "";
                    for (String h : encabezados(correo)) {
                        if (h.toUpperCase().startsWith("FROM:")) {
                            remitente = h.substring(5).trim();
                        } else if (h.toUpperCase().startsWith("SUBJECT:")) {
                            asunto = h.substring(8).trim();
                        }
                    }
                    remitente = extraerCorreoLimpio(remitente);
                    // El asunto se repite en el encabezado de la respuesta: no puede traer saltos de línea
                    asunto = decodificarMimeSubject(asunto).replaceAll("[\\r\\n]+", " ");

                    // Si es una respuesta generada por el propio sistema o enviada por el buzón local, se ignora
                    if (remitente.equalsIgnoreCase(direccion)
                            || asunto.toUpperCase().startsWith("RESPUESTA ICAP:")) {
                        traza.append(">>> [POP3]: Ignorando correo #").append(num)
                                .append(" por ser autorrespuesta interna.\n");
                        marcarProcesado(uid);
                        continue;
                    }

                    nuevos.add(new CorreoRecibido(remitente, asunto, num, uid, LectorMime.adjuntos(correo)));
                }

                if (primeraEjecucion) {
                    registro.agregar(existentes);
                    traza.append(">>> [POP3]: Primera ejecución: ").append(existentes.size())
                            .append(" correos existentes quedan registrados como ya procesados (no se ejecutan ni se borran).\n");
                    mostrar = true;
                }
            } else {
                traza.append(">>> [POP3]: El servidor no respondió al comando UIDL.\n");
                ArchivoLog.error("POP3", "El servidor no aceptó el comando UIDL (respuesta: " + respUidl + "). Revise usuario y clave en "
                        + Configuracion.ARCHIVO + ".");
                mostrar = true;
            }

            enviar("QUIT", salida, entrada, traza);
            if (fallosSeguidos > 0) {
                long segundos = (System.currentTimeMillis() - caidaDesde) / 1000;
                ArchivoLog.error("POP3", fallosSeguidos == 1
                        ? "Corte breve al leer el buzón (" + primerFallo + "); la revisión siguiente funcionó, " + segundos + " s después."
                        : "Se recuperó la conexión con " + servidor + ":" + puerto + " tras " + segundos + " s y " + fallosSeguidos
                                + " revisiones fallidas.");
                fallosSeguidos = 0;
            }

        } catch (IOException e) {
            traza.append(">>> [POP3]: No se pudo leer el buzón (").append(e)
                    .append("). Se vuelve a intentar en la siguiente revisión.\n");
            mostrar = true;
            if (fallosSeguidos == 0) {
                caidaDesde = System.currentTimeMillis();
                primerFallo = e.toString();
            }
            fallosSeguidos++;
            if (fallosSeguidos == 2) {
                ArchivoLog.error("POP3", "No se puede leer el buzón " + servidor + ":" + puerto + " (" + primerFallo + "; luego " + e
                        + "). Se reintenta en cada revisión.");
            }
        }

        if (mostrar || !nuevos.isEmpty()) {
            System.out.print(traza);
        }
        return nuevos;
    }

    // El servidor saluda en menos de 2 s; cuando se traba, no responde nunca. Esperar 10 s (antes 30) hace que la
    // revisión siguiente llegue mucho antes. Es la espera por cada lectura: un correo grande que sigue llegando no se corta
    private static final int TIEMPO_ESPERA_MS = 10000;

    private Socket conectar() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(servidor, puerto), TIEMPO_ESPERA_MS);
            // Si el servidor deja de responder a mitad de la sesión, se corta en vez de quedar colgado
            socket.setSoTimeout(TIEMPO_ESPERA_MS);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    // Envía un comando de una línea, anota la conversación y devuelve la respuesta del servidor
    private String enviar(String comando, DataOutputStream salida, BufferedReader entrada, StringBuilder traza)
            throws IOException {
        traza.append("C : ").append(comando).append("\n");
        salida.writeBytes(comando + "\r\n");
        String respuesta = entrada.readLine();
        traza.append("S : ").append(respuesta).append("\n");
        return respuesta;
    }

    // Registra el correo como procesado para que no se vuelva a ejecutar en el siguiente ciclo
    public void marcarProcesado(String uid) {
        registro.agregar(List.of(uid));
    }

    // Encabezados del correo hasta la primera línea vacía, uniendo los que vienen partidos en
    // varias líneas (un asunto o remitente largo continúa en líneas que empiezan con espacio)
    private List<String> encabezados(String correo) {
        List<String> lista = new ArrayList<>();
        for (String linea : correo.replaceFirst("^\n", "").split("\n")) {
            if (linea.isEmpty()) {
                break;
            }
            if ((linea.startsWith(" ") || linea.startsWith("\t")) && !lista.isEmpty()) {
                String anterior = lista.get(lista.size() - 1);
                String cont = linea.trim();
                lista.set(lista.size() - 1, anterior + (anterior.endsWith(" ") || cont.isEmpty() ? "" : " ") + cont);
            } else {
                lista.add(linea);
            }
        }
        return lista;
    }

    static protected String getMultiline(BufferedReader in) throws IOException {
        // Un correo con adjuntos tiene miles de líneas: concatenar con + se volvería muy lento
        StringBuilder lines = new StringBuilder();
        while (true) {
            String line = in.readLine();
            if (line == null) {
                throw new IOException(" S : Server unawares closed the connection.");
            }
            if (line.equals(".")) {
                break;
            }
            if ((line.length() > 0) && (line.charAt(0) == '.')) {
                line = line.substring(1);
            }
            lines.append('\n').append(line);
        }
        return lines.toString();
    }

    private String extraerCorreoLimpio(String from) {
        if (from.contains("<") && from.contains(">")) {
            return from.substring(from.indexOf('<') + 1, from.indexOf('>')).trim();
        }
        return from.trim();
    }

    // Decodifica asuntos MIME (RFC 2047) de Gmail y celulares: =?UTF-8?B?...?= y =?UTF-8?Q?...?=.
    // Un asunto largo con tildes llega partido en varias palabras codificadas que se unen en una sola
    private String decodificarMimeSubject(String subject) {
        Matcher m = PALABRA_MIME.matcher(subject);
        StringBuilder sb = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String charset = "UTF-8";
        int fin = 0;
        while (m.find()) {
            String entre = subject.substring(fin, m.start());
            // El espacio entre dos palabras codificadas seguidas no forma parte del asunto
            if (bytes.size() == 0 || !entre.trim().isEmpty()) {
                sb.append(volcar(bytes, charset)).append(entre);
            }
            try {
                byte[] b = m.group(2).equalsIgnoreCase("B")
                        ? Base64.getMimeDecoder().decode(m.group(3))
                        : decodificarQ(m.group(3));
                charset = m.group(1).split("\\*")[0];
                bytes.write(b, 0, b.length);
            } catch (IllegalArgumentException e) {
                sb.append(volcar(bytes, charset)).append(m.group());
            }
            fin = m.end();
        }
        sb.append(volcar(bytes, charset)).append(subject.substring(fin));
        return sb.toString();
    }

    private static final Pattern PALABRA_MIME = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");

    // Codificación Q: "_" es espacio y "=XX" es un byte en hexadecimal
    private byte[] decodificarQ(String texto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=' && i + 2 < texto.length()) {
                out.write(Integer.parseInt(texto.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    private String volcar(ByteArrayOutputStream bytes, String charset) {
        if (bytes.size() == 0) {
            return "";
        }
        String texto;
        try {
            texto = new String(bytes.toByteArray(), Charset.forName(charset));
        } catch (IllegalArgumentException e) {
            texto = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        bytes.reset();
        return texto;
    }
}