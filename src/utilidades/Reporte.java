package src.utilidades;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// Un reporte del CU-08 armado una sola vez y entregado de tres formas: el cuerpo del correo (HTML con gráficos),
// un PDF para imprimir o archivar y un Excel para seguir trabajando los datos
public final class Reporte {

    // Cifra destacada del reporte (total recaudado, cantidad de pagos...)
    public record Indicador(String etiqueta, String valor, String detalle) {
    }

    // Tabla del reporte con su título y, si hace falta, una aclaración debajo
    public record Seccion(String titulo, Tabla tabla, String nota) {
    }

    private final String comando;
    private final String titulo;
    private final String alcance;
    private final String generado = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    private String generadoPor = "";
    private final List<Indicador> indicadores = new ArrayList<>();
    private final List<Grafico> graficos = new ArrayList<>();
    private final List<Seccion> secciones = new ArrayList<>();
    private final List<String> notas = new ArrayList<>();
    private String archivo;

    // comando: REPING; titulo: "Reporte de ingresos"; alcance: los filtros aplicados ("Gestión 2-2026 · Programa TAC")
    public Reporte(String comando, String titulo, String alcance) {
        this.comando = comando;
        this.titulo = titulo;
        this.alcance = alcance;
    }

    public Reporte generadoPor(String quien) {
        generadoPor = quien == null ? "" : quien;
        return this;
    }

    public Reporte indicador(String etiqueta, String valor, String detalle) {
        indicadores.add(new Indicador(etiqueta, valor, detalle));
        return this;
    }

    // Un gráfico sin datos (todo en cero) no se agrega
    public Reporte grafico(Grafico g) {
        if (g != null && !g.estaVacio()) {
            graficos.add(g);
        }
        return this;
    }

    public Reporte seccion(String titulo, Tabla tabla) {
        return seccion(titulo, tabla, null);
    }

    public Reporte seccion(String titulo, Tabla tabla, String nota) {
        secciones.add(new Seccion(titulo, tabla, nota));
        return this;
    }

    public Reporte nota(String texto) {
        notas.add(texto);
        return this;
    }

    public String getComando() { return comando; }
    public String getTitulo() { return titulo; }
    public String getAlcance() { return alcance; }
    public String getGenerado() { return generado; }
    public String getGeneradoPor() { return generadoPor; }
    public List<Indicador> getIndicadores() { return indicadores; }
    public List<Grafico> getGraficos() { return graficos; }
    public List<Seccion> getSecciones() { return secciones; }
    public List<String> getNotas() { return notas; }

    // Nombre de los archivos adjuntos sin la extension: REPING_TAC_2-2026 (sin espacios ni caracteres raros)
    public Reporte archivo(String... partes) {
        StringBuilder sb = new StringBuilder(comando);
        for (String p : partes) {
            if (p != null && !p.isBlank()) {
                sb.append('_').append(p.trim());
            }
        }
        archivo = sb.toString().replaceAll("[^A-Za-z0-9-]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        return this;
    }

    public String nombreArchivo() {
        String base = archivo != null ? archivo : comando;
        return base.length() > 60 ? base.substring(0, 60) : base;
    }
}
