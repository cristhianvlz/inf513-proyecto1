package src.comunicacion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import src.modelo.Adjunto;
import src.utilidades.ArchivoLog;

// Extrae los archivos adjuntos de un correo MIME. Gmail los envía en partes anidadas:
// multipart/mixed -> multipart/alternative (el texto) + una parte por archivo, normalmente en base64
public class LectorMime {
    private static final int NIVEL_MAXIMO = 10;

    public static List<Adjunto> adjuntos(String correo) {
        List<Adjunto> lista = new ArrayList<>();
        leerParte(correo.replaceFirst("^\n", ""), lista, 0);
        return lista;
    }

    private static void leerParte(String parte, List<Adjunto> lista, int nivel) {
        int corte = parte.startsWith("\n") ? 0 : parte.indexOf("\n\n");
        if (corte < 0) {
            return;
        }
        Map<String, String> encabezados = encabezados(parte.substring(0, corte));
        String cuerpo = parte.substring(corte + (corte == 0 ? 1 : 2));

        String tipoCompleto = encabezados.getOrDefault("content-type", "text/plain");
        String tipo = tipoCompleto.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (tipo.startsWith("multipart/")) {
            String limite = parametro(tipoCompleto, "boundary");
            if (limite != null && nivel < NIVEL_MAXIMO) {
                for (String subparte : subpartes(cuerpo, limite)) {
                    leerParte(subparte, lista, nivel + 1);
                }
            }
            return;
        }

        String nombre = parametro(encabezados.getOrDefault("content-disposition", ""), "filename");
        if (nombre == null) {
            nombre = parametro(tipoCompleto, "name");
        }
        // El texto del correo no es un adjunto; un PDF o una imagen sí, aunque venga sin nombre
        if (nombre == null && !tipo.equals("application/pdf") && !tipo.startsWith("image/")) {
            return;
        }
        try {
            lista.add(new Adjunto(nombre, tipo, decodificar(cuerpo, encabezados.get("content-transfer-encoding"))));
        } catch (IllegalArgumentException e) {
            System.out.println(">>> [POP3]: Se ignoró un adjunto mal codificado: " + e.getMessage());
            ArchivoLog.error("POP3: se ignoró el adjunto " + nombre + " por estar mal codificado", e.getMessage());
        }
    }

    // Nombres en minúsculas; los encabezados largos continúan en líneas que empiezan con espacio
    private static Map<String, String> encabezados(String bloque) {
        Map<String, String> mapa = new HashMap<>();
        String actual = null;
        for (String linea : bloque.split("\n")) {
            if ((linea.startsWith(" ") || linea.startsWith("\t")) && actual != null) {
                mapa.put(actual, mapa.get(actual) + " " + linea.trim());
            } else {
                int dosPuntos = linea.indexOf(':');
                if (dosPuntos > 0) {
                    actual = linea.substring(0, dosPuntos).trim().toLowerCase(Locale.ROOT);
                    mapa.put(actual, linea.substring(dosPuntos + 1).trim());
                }
            }
        }
        return mapa;
    }

    // Valor de un parámetro del encabezado: filename="x.pdf", filename=x.pdf o filename*=UTF-8''x.pdf
    private static String parametro(String encabezado, String nombre) {
        Matcher m = Pattern.compile(";\\s*" + nombre + "(?:\\*\\d*)?\\*?\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]*))",
                Pattern.CASE_INSENSITIVE).matcher(encabezado);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static List<String> subpartes(String cuerpo, String limite) {
        String inicio = "--" + limite;
        List<String> lista = new ArrayList<>();
        StringBuilder actual = null;
        for (String linea : cuerpo.split("\n", -1)) {
            String limpia = linea.trim();
            if (limpia.equals(inicio) || limpia.equals(inicio + "--")) {
                if (actual != null) {
                    lista.add(actual.toString());
                }
                actual = limpia.equals(inicio) ? new StringBuilder() : null;
                if (limpia.equals(inicio + "--")) {
                    break;
                }
            } else if (actual != null) {
                actual.append(linea).append('\n');
            }
        }
        if (actual != null) {
            lista.add(actual.toString());
        }
        return lista;
    }

    private static byte[] decodificar(String cuerpo, String codificacion) {
        String tipo = codificacion == null ? "" : codificacion.trim().toLowerCase(Locale.ROOT);
        if (tipo.equals("base64")) {
            return Base64.getMimeDecoder().decode(cuerpo.getBytes(StandardCharsets.ISO_8859_1));
        }
        if (tipo.equals("quoted-printable")) {
            return decodificarQuotedPrintable(cuerpo);
        }
        return cuerpo.getBytes(StandardCharsets.UTF_8);
    }

    // "=XX" es un byte en hexadecimal y "=" al final de la línea une dos líneas
    private static byte[] decodificarQuotedPrintable(String texto) {
        byte[] entrada = texto.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (int i = 0; i < entrada.length; i++) {
            if (entrada[i] != '=') {
                salida.write(entrada[i]);
            } else if (i + 1 < entrada.length && entrada[i + 1] == '\n') {
                i++;
            } else if (i + 2 < entrada.length) {
                salida.write(Integer.parseInt(new String(entrada, i + 1, 2, StandardCharsets.US_ASCII), 16));
                i += 2;
            }
        }
        return salida.toByteArray();
    }
}
