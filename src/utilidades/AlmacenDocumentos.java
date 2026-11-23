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
        return guardarEn(limpio(ci), tipoDocumento, adjunto);
    }

    // CV del profesor (CAPACITOR.cv_documento): documentos/profesores/<ci>/CV_<fecha>.<extensión>.
    // Va aparte del expediente porque un profesor también puede ser estudiante con el mismo CI
    public static String guardarDeProfesor(String ci, String tipoDocumento, Adjunto adjunto) throws IOException {
        return guardarEn("profesores/" + limpio(ci), tipoDocumento, adjunto);
    }

    // Programa analítico y propuesta técnica de una asignación (ASIGNACION_MODULO):
    // documentos/asignaciones/<programa>_<gestion>_<grupo>_<modulo>/<TIPO>_<fecha>.<extensión>
    public static String guardarDeAsignacion(String asignacion, String tipoDocumento, Adjunto adjunto) throws IOException {
        return guardarEn("asignaciones/" + limpio(asignacion), tipoDocumento, adjunto);
    }

    // true si la ruta es un archivo guardado por el sistema y todavía está en el servidor
    // (los datos anteriores a este cambio tenían solo un nombre escrito a mano, sin archivo)
    public static boolean existe(String ruta) {
        try {
            return ruta != null && ruta.startsWith(CARPETA + "/") && Files.isRegularFile(Paths.get(ruta));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // Tipo MIME para devolver el archivo como adjunto, según su extensión
    public static String tipoMime(String ruta) {
        String r = ruta.toLowerCase();
        return r.endsWith(".pdf") ? "application/pdf" : r.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    private static String limpio(String nombre) {
        return nombre.replaceAll("[^0-9A-Za-z-]", "_");
    }

    private static String guardarEn(String subcarpeta, String tipoDocumento, Adjunto adjunto) throws IOException {
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
