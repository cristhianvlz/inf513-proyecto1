package src.modelo;

// Tabla MODULO: asignatura reutilizable en distintas mallas
public class Modulo {
    private int idModulo;
    private String codigo;
    private String nombre;

    public Modulo() {
    }

    public Modulo(String codigo, String nombre) {
        this.codigo = codigo;
        this.nombre = nombre;
    }

    public int getIdModulo() { return idModulo; }
    public void setIdModulo(int idModulo) { this.idModulo = idModulo; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
}
