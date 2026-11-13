package src.comunicacion;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import src.modelo.Adjunto;

public class ClienteSMTP {
    // Buzón del sistema: recibe los comandos y envía las respuestas (también es el destino del QR de los certificados)
    public static final String CORREO = "grupo15sc@tecnoweb.org.bo";

    private final String servidor = "mail.tecnoweb.org.bo";
    private final String user_emisor = CORREO;
    private final String user = "grupo15sc";
    private final String pass = "grup015grup015*";
    private final int puerto = 25;

    public void enviarCorreo(String user_receptor, String asunto, String contenidoHtml) {
        enviarCorreo(user_receptor, asunto, contenidoHtml, List.of());
    }

    // Con adjuntos (por ejemplo, el PDF de un certificado) el mensaje va como multipart/mixed
    public void enviarCorreo(String user_receptor, String asunto, String contenidoHtml, List<Adjunto> adjuntos) {
        // Guardamos vista previa en el proyecto para ver las tablas HTML al instante
        guardarVistaPreviaLocal(contenidoHtml);

        String comando = "";
        try {
            Socket socket = new Socket(servidor, puerto);
            // Si el servidor deja de responder, se corta en vez de dejar colgado a MainICAP
            socket.setSoTimeout(30000);
            // writeBytes envía byte a byte: sin esto TCP retiene el resto del comando y cada uno tarda el doble
            socket.setTcpNoDelay(true);
            BufferedReader entrada = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            DataOutputStream salida = new DataOutputStream(socket.getOutputStream());

            if (socket != null && entrada != null && salida != null) {
                System.out.println("S : " + entrada.readLine());

                // 1. EHLO para iniciar sesión extendida
                comando = "EHLO " + servidor + "\r\n";
                System.out.print("C : " + comando);
                salida.writeBytes(comando);
                leerMultilineaSMTP(entrada);

                // 2. Autenticación manual con usuario grupo15sc
                comando = "AUTH LOGIN\r\n";
                System.out.print("C : " + comando);
                salida.writeBytes(comando);
                String respAuth = entrada.readLine();
                System.out.println("S : " + respAuth);

                if (respAuth != null && respAuth.startsWith("334")) {
                    String userB64 = Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)) + "\r\n";
                    System.out.print("C : [ Base64 USER: " + user + " ]\r\n");
                    salida.writeBytes(userB64);
                    System.out.println("S : " + entrada.readLine());

                    String passB64 = Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)) + "\r\n";
                    System.out.print("C : [ Base64 PASS: ******** ]\r\n");
                    salida.writeBytes(passB64);
                    System.out.println("S : " + entrada.readLine());
                }

                // 3. MAIL FROM
                comando = "MAIL FROM: <" + user_emisor + ">\r\n";
                System.out.print("C : " + comando);
                salida.writeBytes(comando);
                System.out.println("S : " + entrada.readLine());

                // 4. RCPT TO
                comando = "RCPT TO: <" + user_receptor + ">\r\n";
                System.out.print("C : " + comando);
                salida.writeBytes(comando);
                String respRcpt = entrada.readLine();
                System.out.println("S : " + respRcpt);

                // Si el servidor aún no tiene tu IP habilitada para Gmail (550), enviamos al buzón local
                // para que aparezca en el Webmail Roundcube (POP3 ya ignora de forma segura las autorrespuestas)
                if (respRcpt != null && !respRcpt.startsWith("250")) {
                    System.out.println(
                            ">>> [AVISO]: IP externa (" + user_receptor + ") no autorizada en relay (550).");
                    System.out.println(
                            ">>> [AVISO]: Enviando respuesta al buzón local para Roundcube: " + user_emisor);
                    comando = "RCPT TO: <" + user_emisor + ">\r\n";
                    System.out.print("C : " + comando);
                    salida.writeBytes(comando);
                    respRcpt = entrada.readLine();
                    System.out.println("S : " + respRcpt);
                }

                // 5. DATA (Solo se envía si el destinatario fue aceptado con 250)
                if (respRcpt != null && respRcpt.startsWith("250")) {
                    comando = "DATA\r\n";
                    System.out.print("C : " + comando);
                    salida.writeBytes(comando);
                    String respData = entrada.readLine();
                    System.out.println("S : " + respData);

                    if (respData != null && respData.startsWith("354")) {
                        String htmlLimpio = contenidoHtml.replace("\r\n", "\n").replace("\n", "\r\n");

                        String cuerpo = adjuntos.isEmpty()
                                ? "Content-Type: text/html; charset=UTF-8\r\n\r\n" + htmlLimpio
                                : multipart(htmlLimpio, adjuntos);
                        // Una línea que empieza con punto se duplica: un punto solo cerraría el mensaje (RFC 5321)
                        cuerpo = cuerpo.replace("\r\n.", "\r\n..");

                        String mensajeCompleto = "From: Sistema ICAP <" + user_emisor + ">\r\n"
                                + "To: <" + user_receptor + ">\r\n"
                                + "Subject: Respuesta ICAP: " + asunto + "\r\n"
                                + "MIME-Version: 1.0\r\n"
                                + cuerpo + "\r\n"
                                + ".\r\n";

                        System.out.println("C : SUBJECT: Respuesta ICAP: " + asunto);
                        System.out.println("C : [Cuerpo HTML enviado" + (adjuntos.isEmpty() ? "" : " con " + adjuntos.size() + " adjunto(s)") + "]");
                        System.out.println("C : .");
                        salida.write(mensajeCompleto.getBytes(StandardCharsets.UTF_8));
                        salida.flush();
                        System.out.println("S : " + entrada.readLine());
                    }
                }

                // 6. QUIT
                comando = "QUIT\r\n";
                System.out.print("C : " + comando);
                salida.writeBytes(comando);
                System.out.println("S : " + entrada.readLine());
            }

            salida.close();
            entrada.close();
            socket.close();

        } catch (UnknownHostException e) {
            System.out.println("S : No se pudo conectar con el servidor indicado");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Cuerpo HTML más los archivos en base64, en líneas de 76 caracteres
    private static String multipart(String html, List<Adjunto> adjuntos) {
        String limite = "ICAP-" + UUID.randomUUID();
        StringBuilder sb = new StringBuilder("Content-Type: multipart/mixed; boundary=\"" + limite + "\"\r\n\r\n");
        sb.append("--").append(limite).append("\r\n")
                .append("Content-Type: text/html; charset=UTF-8\r\n\r\n")
                .append(html).append("\r\n");
        for (Adjunto a : adjuntos) {
            String nombre = a.getNombre().replaceAll("[^0-9A-Za-z._-]", "_");
            sb.append("--").append(limite).append("\r\n")
                    .append("Content-Type: ").append(a.getTipoMime()).append("; name=\"").append(nombre).append("\"\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n")
                    .append("Content-Disposition: attachment; filename=\"").append(nombre).append("\"\r\n\r\n")
                    .append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(a.getContenido()))
                    .append("\r\n");
        }
        return sb.append("--").append(limite).append("--").toString();
    }

    private void leerMultilineaSMTP(BufferedReader entrada) throws IOException {
        String line;
        while ((line = entrada.readLine()) != null) {
            System.out.println("S : " + line);
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
        }
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
}