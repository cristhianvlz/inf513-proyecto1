package src.modelo;

// Tabla PROGRAMA: catálogo de programas técnicos del ICAP
public class Programa {
    private int idPrograma;
    private String codigo;
    private String nombre;
    private String nivelTitulacion;
    private String estado = "ACTIVO";

    public Programa() {
    }

    public Programa(String codigo, String nombre, String nivelTitulacion) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.nivelTitulacion = nivelTitulacion;
    }

    public int getIdPrograma() { return idPrograma; }
    public void setIdPrograma(int idPrograma) { this.idPrograma = idPrograma; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getNivelTitulacion() { return nivelTitulacion; }
    public void setNivelTitulacion(String nivelTitulacion) { this.nivelTitulacion = nivelTitulacion; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
