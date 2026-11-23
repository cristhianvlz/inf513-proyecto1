package src.utilidades;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Registro técnico en archivos de texto, para la auditoría durante la defensa (no usa la base de datos):
//   logs/access.log -> una línea por correo procesado: fecha, remitente, comando, resultado, envío de la respuesta y duración
//   logs/error.log  -> las excepciones capturadas: errores SQL, fallas de POP3 y SMTP, errores internos
// La tabla LOG sigue registrando qué hizo cada usuario en cada tabla; estos archivos guardan también lo que la tabla no
// puede: remitentes sin cuenta, comandos que fallan (su transacción se deshace) y caídas de la propia base
public final class ArchivoLog {
    private static final Path CARPETA = Paths.get("logs");
    private static final Path ACCESO = CARPETA.resolve("access.log");
    private static final Path ERRORES = CARPETA.resolve("error.log");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Correo que se está atendiendo: se anota en cada error para saber qué comando lo produjo
    private static String contexto = "";

    private ArchivoLog() {
    }

    public static synchronized void contexto(String remitente, String asunto) {
        contexto = "de " + (remitente == null ? "desconocido" : remitente) + ", asunto " + recortar(asunto, 300);
    }

    public static synchronized void limpiarContexto() {
        contexto = "";
    }

    public static synchronized void acceso(String remitente, String asunto, String resultado, String envio, long milisegundos) {
        escribir(ACCESO, ahora() + " | de: " + remitente + " | asunto: " + recortar(asunto, 300) + " | resultado: " + recortar(resultado, 300)
                + " | respuesta: " + envio + " | " + milisegundos + " ms");
    }

    public static synchronized void error(String origen, String detalle) {
        escribir(ERRORES, ahora() + " | " + origen + " | " + detalle + (contexto.isEmpty() ? "" : " | correo: " + contexto));
    }

    // Con la traza completa, para ubicar el error en el código
    public static synchronized void error(String origen, Throwable t) {
        StringWriter traza = new StringWriter();
        t.printStackTrace(new PrintWriter(traza));
        escribir(ERRORES, ahora() + " | " + origen + " | " + t + (contexto.isEmpty() ? "" : " | correo: " + contexto)
                + System.lineSeparator() + traza.toString().stripTrailing());
    }

    private static void escribir(Path archivo, String linea) {
        try {
            Files.createDirectories(CARPETA);
            Files.writeString(archivo, linea.replace("\r", "") + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Sin disco no hay log: al menos queda en la consola
            System.out.println(">>> [LOG]: No se pudo escribir en " + archivo + ": " + e.getMessage() + " | " + linea);
        }
    }

    private static String ahora() {
        return LocalDateTime.now().format(FECHA);
    }

    private static String recortar(String s, int maximo) {
        if (s == null) {
            return "";
        }
        String una = s.replaceAll("[\\r\\n]+", " ");
        return una.length() > maximo ? una.substring(0, maximo) + "…" : una;
    }
}
