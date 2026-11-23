package src.modelo;

// Tabla TIPO_EVALUACION: componente de la nota final con su ponderación máxima
public class TipoEvaluacion {
    public static final String RECUPERATORIO = "Examen recuperatorio";
    public static final String TRABAJOS_PRACTICOS = "Trabajos practicos";

    private final int idTipo;
    private final String nombre;
    private final int ponderacionMaxima;

    public TipoEvaluacion(int idTipo, String nombre, int ponderacionMaxima) {
        this.idTipo = idTipo;
        this.nombre = nombre;
        this.ponderacionMaxima = ponderacionMaxima;
    }

    public int getIdTipo() { return idTipo; }
    public String getNombre() { return nombre; }
    public int getPonderacionMaxima() { return ponderacionMaxima; }
}
