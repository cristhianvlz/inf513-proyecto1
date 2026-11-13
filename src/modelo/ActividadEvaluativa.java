package src.modelo;

import java.time.LocalDate;

// Tabla ACTIVIDAD_EVALUATIVA: parte del plan de evaluación de una asignación
public class ActividadEvaluativa {
    private int idActividad;
    private String titulo;
    private LocalDate fechaLimite;
    private int ponderacion;
    private int idAsignacion;
    private int idTipo;
    private String tipo;

    public int getIdActividad() { return idActividad; }
    public void setIdActividad(int idActividad) { this.idActividad = idActividad; }
    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public LocalDate getFechaLimite() { return fechaLimite; }
    public void setFechaLimite(LocalDate fechaLimite) { this.fechaLimite = fechaLimite; }
    public int getPonderacion() { return ponderacion; }
    public void setPonderacion(int ponderacion) { this.ponderacion = ponderacion; }
    public int getIdAsignacion() { return idAsignacion; }
    public void setIdAsignacion(int idAsignacion) { this.idAsignacion = idAsignacion; }
    public int getIdTipo() { return idTipo; }
    public void setIdTipo(int idTipo) { this.idTipo = idTipo; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public boolean esRecuperatorio() {
        return TipoEvaluacion.RECUPERATORIO.equals(tipo);
    }
}
