package deploy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

// Diagnóstico de tiempos de red desde el equipo donde corre MainICAP (por ejemplo, el VPS).
// No envía ningún correo (no llega a DATA), no inicia sesión en POP3 ni en la base y no usa claves.
// Uso, desde la carpeta del proyecto:   java deploy/SondaCorreo.java tu_correo@gmail.com
// Responde: cuánto tarda cada paso y si el servidor acepta reenviar a ese destinatario externo (250) o no (550)
public class SondaCorreo {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].contains("@")) {
            System.out.println("Uso: java deploy/SondaCorreo.java correo_externo@gmail.com");
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Paths.get("icap.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        String servidor = p.getProperty("correo.servidor").trim();
        String grupo = p.getProperty("correo.direccion").trim();
        int pop3 = Integer.parseInt(p.getProperty("correo.pop3.puerto", "110").trim());
        int smtp = Integer.parseInt(p.getProperty("correo.smtp.puerto", "25").trim());
        URI bd = URI.create(p.getProperty("db.url").trim().substring("jdbc:".length()));

        System.out.println("== Ida y vuelta por la red (conexión TCP) ==");
        for (String[] destino : new String[][] { { bd.getHost(), String.valueOf(bd.getPort()), "base de datos" },
                { servidor, String.valueOf(pop3), "POP3" } }) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                long t = System.nanoTime();
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(destino[0], Integer.parseInt(destino[1])), 15000);
                    sb.append(ms(t)).append(" ms  ");
                }
            }
            System.out.println("  " + destino[2] + " (" + destino[0] + ":" + destino[1] + "): " + sb);
        }

        System.out.println("\n== POP3: saludo (sin iniciar sesión) ==");
        long t = System.nanoTime();
        try (Socket s = conectar(servidor, pop3)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            in.readLine();
            System.out.println("  saludo a los " + ms(t) + " ms");
            s.getOutputStream().write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        // Dos rondas: la demora del servidor varía de una conexión a otra
        long peorSaludo = 0;
        long peorRcpt = 0;
        String externo = "";
        for (int ronda = 1; ronda <= 2; ronda++) {
            System.out.println("\n== SMTP, ronda " + ronda + ": conversación sin DATA (no se envía nada) ==");
            t = System.nanoTime();
            try (Socket s = conectar(servidor, smtp)) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = s.getOutputStream();
                long[] m = { System.nanoTime() };
                String saludo = leer(in);
                long msSaludo = (System.nanoTime() - m[0]) / 1_000_000;
                m[0] = System.nanoTime();
                System.out.println("  saludo            " + msSaludo + " ms   " + saludo);
                paso(out, in, m, "EHLO " + servidor, "EHLO");
                paso(out, in, m, "MAIL FROM:<" + grupo + ">", "MAIL FROM");
                externo = paso(out, in, m, "RCPT TO:<" + args[0] + ">", "RCPT externo");
                long inicioRcpt = System.nanoTime();
                paso(out, in, m, "RCPT TO:<" + grupo + ">", "RCPT del grupo");
                peorRcpt = Math.max(peorRcpt, ms(inicioRcpt));
                paso(out, in, m, "RSET", "RSET");
                paso(out, in, m, "QUIT", "QUIT");
                System.out.println("  TOTAL             " + ms(t) + " ms");
                peorSaludo = Math.max(peorSaludo, msSaludo);
            }
        }

        System.out.println("\n== Conclusión ==");
        System.out.println(peorSaludo > 5000
                ? "  El servidor tardó hasta " + peorSaludo / 1000 + " s en saludar: busca el nombre de esta IP. Revise el DNS inverso (PTR) del equipo."
                : "  El saludo SMTP es rápido (" + peorSaludo + " ms como máximo).");
        if (peorRcpt > 3000) {
            System.out.println("  Aceptar el buzón del grupo como destinatario tardó hasta " + peorRcpt / 1000
                    + " s: es una demora del propio servidor de correo.");
        }
        System.out.println(externo.startsWith("250")
                ? "  El servidor ACEPTA reenviar a " + args[0] + ": las respuestas llegarán a Gmail."
                : "  El servidor NO reenvía a " + args[0] + " (" + externo + ").\n  Las respuestas seguirán quedando en el buzón del grupo: consulte al docente.");
    }

    static Socket conectar(String host, int puerto) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, puerto), 15000);
        s.setSoTimeout(60000);
        s.setTcpNoDelay(true);
        return s;
    }

    static String paso(OutputStream out, BufferedReader in, long[] m, String comando, String nombre) throws Exception {
        out.write((comando + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String r = leer(in);
        System.out.printf("  %-17s %d ms   %s%n", nombre, (System.nanoTime() - m[0]) / 1_000_000, r);
        m[0] = System.nanoTime();
        return r;
    }

    // Última línea de una respuesta SMTP (las intermedias llevan "-" después del código)
    static String leer(BufferedReader in) throws Exception {
        String l;
        while ((l = in.readLine()) != null) {
            if (l.length() < 4 || l.charAt(3) != '-') {
                return l;
            }
        }
        throw new Exception("el servidor cerró la conexión");
    }

    static long ms(long desde) {
        return (System.nanoTime() - desde) / 1_000_000;
    }
}
