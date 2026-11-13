package src.utilidades;

import java.util.ArrayList;
import java.util.List;

// Gráfico de un reporte: categorías (una barra cada una) con el valor de una o varias series.
// BARRAS son horizontales (categorías con nombre largo); COLUMNAS, verticales (evolución en el tiempo).
// Con varias series las barras se apilan (partes de un total) o se agrupan (valores que se comparan).
// El mismo gráfico se dibuja en el cuerpo del correo (HTML) y en el PDF
public final class Grafico {

    public enum Tipo { BARRAS, COLUMNAS }

    // Paleta categórica en orden fijo (nunca se repite un color en el mismo gráfico)
    public static final String AZUL = "#2a78d6";
    public static final String NARANJA = "#eb6834";
    public static final String AGUAMARINA = "#1baf7a";
    public static final String AMARILLO = "#eda100";
    public static final String MAGENTA = "#e87ba4";
    public static final String[] CATEGORICOS = { AZUL, NARANJA, AGUAMARINA, AMARILLO, MAGENTA, "#008300", "#6250d6", "#e34948" };

    // Colores de estado: solo para estados (aprobado, reprobado...) y siempre con su leyenda
    public static final String BIEN = "#0ca30c";
    public static final String ALERTA = "#fab219";
    public static final String SERIO = "#ec835a";
    public static final String CRITICO = "#d03b3b";
    public static final String NEUTRO = "#a9a8a2";

    private final String titulo;
    private final Tipo tipo;
    private final String unidad;
    private final List<String> series = new ArrayList<>();
    private final List<String> colores = new ArrayList<>();
    private final List<String> categorias = new ArrayList<>();
    private final List<double[]> valores = new ArrayList<>();
    private boolean apilado = true;
    private boolean porcentaje;
    private boolean leyenda;
    private Double referencia;
    private String etiquetaReferencia;

    // unidad: "Bs" en los montos, vacío en las cantidades
    public Grafico(String titulo, Tipo tipo, String unidad) {
        this.titulo = titulo;
        this.tipo = tipo;
        this.unidad = unidad == null ? "" : unidad;
    }

    public Grafico serie(String nombre, String color) {
        series.add(nombre);
        colores.add(color);
        return this;
    }

    public Grafico categoria(String nombre, double... valoresDeLasSeries) {
        categorias.add(nombre);
        valores.add(valoresDeLasSeries);
        return this;
    }

    // Las series se comparan lado a lado en vez de apilarse
    public Grafico agrupado() {
        apilado = false;
        return this;
    }

    // Con una sola serie, cada barra muestra además su porcentaje del total
    public Grafico conPorcentaje() {
        porcentaje = true;
        return this;
    }

    // Muestra la leyenda aunque quede una sola serie (por ejemplo, el único estado con datos de un gráfico apilado)
    public Grafico conLeyenda() {
        leyenda = true;
        return this;
    }

    // Línea de referencia (por ejemplo, la nota mínima de aprobación)
    public Grafico referencia(double valor, String etiqueta) {
        referencia = valor;
        etiquetaReferencia = etiqueta;
        return this;
    }

    public String getTitulo() { return titulo; }
    public Tipo getTipo() { return tipo; }
    public String getUnidad() { return unidad; }
    public List<String> getSeries() { return series; }
    public List<String> getColores() { return colores; }
    public List<String> getCategorias() { return categorias; }
    public List<double[]> getValores() { return valores; }
    public boolean esApilado() { return apilado && series.size() > 1; }
    public boolean muestraLeyenda() { return series.size() > 1 || leyenda; }
    public boolean conPorcentajes() { return porcentaje && series.size() == 1; }
    public Double getReferencia() { return referencia; }
    public String getEtiquetaReferencia() { return etiquetaReferencia; }
    public boolean estaVacio() { return categorias.isEmpty() || maximo() <= 0; }

    // Valor de la serie s en la categoría c (0 si falta)
    public double valor(int c, int s) {
        double[] v = valores.get(c);
        return s < v.length ? v[s] : 0;
    }

    // Total de la categoría (suma de sus series)
    public double total(int c) {
        double t = 0;
        for (int s = 0; s < series.size(); s++) {
            t += valor(c, s);
        }
        return t;
    }

    public double totalGeneral() {
        double t = 0;
        for (int c = 0; c < categorias.size(); c++) {
            t += total(c);
        }
        return t;
    }

    // Mayor largo de barra: el total si se apilan, el mayor valor si se agrupan
    public double maximo() {
        double m = referencia != null ? referencia : 0;
        for (int c = 0; c < categorias.size(); c++) {
            if (esApilado() || series.size() == 1) {
                m = Math.max(m, total(c));
            } else {
                for (int s = 0; s < series.size(); s++) {
                    m = Math.max(m, valor(c, s));
                }
            }
        }
        return m;
    }

    // Tope del eje redondeado (1, 2, 2.5 o 5 por potencia de diez) y su paso, para marcas limpias
    public double[] escala() {
        double max = maximo();
        if (max <= 0) {
            return new double[] { 1, 0.25 };
        }
        double bruto = max / 4;
        double potencia = Math.pow(10, Math.floor(Math.log10(bruto)));
        double paso = potencia * 10;
        for (double f : new double[] { 1, 2, 2.5, 5, 10 }) {
            if (f * potencia >= bruto) {
                paso = f * potencia;
                break;
            }
        }
        if (unidad.isEmpty() && paso < 1) {
            paso = 1;
        }
        return new double[] { Math.ceil(max / paso - 1e-9) * paso, paso };
    }

    // Número con dos decimales en montos y sin decimales en cantidades
    public String formato(double v) {
        if (!unidad.isEmpty() || v != Math.rint(v)) {
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
        return String.valueOf((long) v);
    }

    // Marca del eje: entero si lo es, para no escribir 250.00 debajo del eje
    public String marca(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    public String porcentajeDe(int c) {
        double total = totalGeneral();
        return total <= 0 ? "" : String.format(java.util.Locale.ROOT, "%.1f %%", 100 * total(c) / total);
    }
}
