package src.modelo;

import java.util.Locale;

// Archivo adjunto de un correo (por ejemplo, la fotocopia del CI en PREINS)
public class Adjunto {
    private final String nombre;
    private final String tipoMime;
    private final byte[] contenido;

    public Adjunto(String nombre, String tipoMime, byte[] contenido) {
        this.nombre = nombre;
        this.tipoMime = tipoMime;
        this.contenido = contenido;
    }

    public String getNombre() { return nombre; }
    public String getTipoMime() { return tipoMime; }
    public byte[] getContenido() { return contenido; }

    // pdf, jpg o png según el tipo MIME o, si no lo indica, según el nombre. Vacío si es otro tipo de archivo
    public String getExtension() {
        String tipo = tipoMime == null ? "" : tipoMime.toLowerCase(Locale.ROOT);
        String archivo = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT).trim();
        if (tipo.equals("application/pdf") || archivo.endsWith(".pdf")) {
            return "pdf";
        }
        if (tipo.equals("image/jpeg") || tipo.equals("image/jpg") || archivo.endsWith(".jpg") || archivo.endsWith(".jpeg")) {
            return "jpg";
        }
        if (tipo.equals("image/png") || archivo.endsWith(".png")) {
            return "png";
        }
        return "";
    }
}
