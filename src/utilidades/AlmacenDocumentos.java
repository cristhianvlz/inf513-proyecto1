package src.utilidades;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import src.modelo.Adjunto;

// Guarda los archivos del expediente en el servidor: documentos/<ci>/<tipo>_<fecha>.<extensión>.
// El nombre original del adjunto no se usa porque lo elige quien envía el correo.
// Ningún archivo se destruye: el que se reemplaza (MODDOC) o se elimina (DELDOC) pasa a documentos/<ci>/historico/
public class AlmacenDocumentos {
    private static final String CARPETA = "documentos";
    private static final String HISTORICO = "historico";
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    // Devuelve la ruta que se registra en DOCUMENTO_DIGITAL.url_doc
    public static String guardar(String ci, String tipoDocumento, Adjunto adjunto) throws IOException {
        // Un CI registrado en ventanilla (ADDEST) no pasó por la validación de PREINS
        String subcarpeta = ci.replaceAll("[^0-9A-Za-z-]", "_");
        Path carpeta = Paths.get(CARPETA, subcarpeta);
        Files.createDirectories(carpeta);
        String base = tipoDocumento + "_" + LocalDateTime.now().format(MARCA);
        String archivo = base + "." + adjunto.getExtension();
        // Dos archivos del mismo tipo en el mismo segundo no se pisan
        for (int n = 2; Files.exists(carpeta.resolve(archivo)); n++) {
            archivo = base + "_" + n + "." + adjunto.getExtension();
        }
        Files.write(carpeta.resolve(archivo), adjunto.getContenido());
        return CARPETA + "/" + subcarpeta + "/" + archivo;
    }

    // Copia de archivo de un documento emitido por el ICAP (CU-06): documentos/<ci>/<codigo>.pdf
    public static String guardarEmitido(String ci, String codigo, byte[] pdf) throws IOException {
        String subcarpeta = ci.replaceAll("[^0-9A-Za-z-]", "_");
        Path carpeta = Paths.get(CARPETA, subcarpeta);
        Files.createDirectories(carpeta);
        String archivo = codigo.replaceAll("[^0-9A-Za-z-]", "_") + ".pdf";
        Files.write(carpeta.resolve(archivo), pdf);
        return CARPETA + "/" + subcarpeta + "/" + archivo;
    }

    // Contenido del archivo guardado; null si ya no está en el servidor
    public static byte[] leer(String ruta) {
        try {
            Path archivo = Paths.get(ruta);
            return Files.exists(archivo) ? Files.readAllBytes(archivo) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // Mueve el archivo a la carpeta historico de su expediente y devuelve su nueva ruta; null si el archivo ya no estaba
    public static String archivar(String ruta) throws IOException {
        Path origen = Paths.get(ruta);
        if (!Files.exists(origen)) {
            return null;
        }
        Path carpeta = origen.getParent().resolve(HISTORICO);
        Files.createDirectories(carpeta);
        Path destino = carpeta.resolve(origen.getFileName());
        Files.move(origen, destino);
        return ruta.substring(0, ruta.lastIndexOf('/') + 1) + HISTORICO + "/" + origen.getFileName();
    }
}
