package src.utilidades;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

// Datos de conexión (base de datos y correo) leídos de icap.properties, que no se sube a GitHub: así las claves no
// quedan en el código. Cada clave puede reemplazarse con una variable de entorno: db.clave -> ICAP_DB_CLAVE.
// Modelo del archivo: icap.properties.ejemplo
public final class Configuracion {
    public static final String ARCHIVO = "icap.properties";

    // Claves sin las que MainICAP no puede trabajar
    private static final String[] OBLIGATORIAS = { "db.url", "db.usuario", "db.clave", "correo.servidor", "correo.usuario",
            "correo.clave", "correo.direccion" };

    private static final Properties VALORES = cargar();

    private Configuracion() {
    }

    private static Properties cargar() {
        Properties p = new Properties();
        Path archivo = Paths.get(ARCHIVO);
        if (Files.exists(archivo)) {
            try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.out.println(">>> [CONFIG]: No se pudo leer " + ARCHIVO + ": " + e.getMessage());
            }
        }
        return p;
    }

    public static String get(String clave) {
        String v = get(clave, null);
        if (v == null) {
            throw new IllegalStateException("Falta la clave '" + clave + "' en " + ARCHIVO + " (o la variable de entorno "
                    + variable(clave) + "). Copie icap.properties.ejemplo como icap.properties y complételo.");
        }
        return v;
    }

    public static String get(String clave, String porDefecto) {
        String entorno = System.getenv(variable(clave));
        if (entorno != null && !entorno.isBlank()) {
            return entorno.trim();
        }
        String v = VALORES.getProperty(clave);
        return v == null || v.isBlank() ? porDefecto : v.trim();
    }

    public static int entero(String clave, int porDefecto) {
        try {
            return Integer.parseInt(get(clave, String.valueOf(porDefecto)));
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    // Claves obligatorias que faltan; MainICAP no arranca hasta que estén todas
    public static List<String> faltantes() {
        List<String> lista = new ArrayList<>();
        for (String c : OBLIGATORIAS) {
            if (get(c, null) == null) {
                lista.add(c);
            }
        }
        return lista;
    }

    // db.clave -> ICAP_DB_CLAVE
    private static String variable(String clave) {
        return "ICAP_" + clave.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}
