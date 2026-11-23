package src.utilidades;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

// Conjunto de claves guardado en un archivo de texto local (una por línea).
// Se usa para recordar entre ejecuciones qué correos ya se procesaron y qué avisos ya se enviaron
public class RegistroLocal {
    private final Path archivo;

    public RegistroLocal(String nombreArchivo) {
        this.archivo = Paths.get(nombreArchivo);
    }

    public boolean existe() {
        return Files.exists(archivo);
    }

    public Set<String> cargar() throws IOException {
        if (!existe()) {
            return new HashSet<>();
        }
        return new HashSet<>(Files.readAllLines(archivo, StandardCharsets.UTF_8));
    }

    public void agregar(Collection<String> claves) {
        try {
            Files.write(archivo, claves, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("Error al guardar el registro " + archivo + ": " + e.getMessage());
        }
    }
}
