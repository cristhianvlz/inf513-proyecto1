package src.modelo;

import java.time.LocalDate;

// Tabla ASIGNACION_MODULO: un módulo de la oferta dictado por un capacitador entre dos fechas
public class AsignacionModulo {
    private int idAsignacion;
    private LocalDate fechaInicio;
    private LocalDate fechaFin;
    private String programaAnalitico;
    private String propuestaTecnica;
    private int idOferta;
    private int idModulo;
    private int idPersonal;

    public int getIdAsignacion() { return idAsignacion; }
    public void setIdAsignacion(int idAsignacion) { this.idAsignacion = idAsignacion; }
    public LocalDate getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(LocalDate fechaInicio) { this.fechaInicio = fechaInicio; }
    public LocalDate getFechaFin() { return fechaFin; }
    public void setFechaFin(LocalDate fechaFin) { this.fechaFin = fechaFin; }
    public String getProgramaAnalitico() { return programaAnalitico; }
    public void setProgramaAnalitico(String programaAnalitico) { this.programaAnalitico = programaAnalitico; }
    public String getPropuestaTecnica() { return propuestaTecnica; }
    public void setPropuestaTecnica(String propuestaTecnica) { this.propuestaTecnica = propuestaTecnica; }
    public int getIdOferta() { return idOferta; }
    public void setIdOferta(int idOferta) { this.idOferta = idOferta; }
    public int getIdModulo() { return idModulo; }
    public void setIdModulo(int idModulo) { this.idModulo = idModulo; }
    public int getIdPersonal() { return idPersonal; }
    public void setIdPersonal(int idPersonal) { this.idPersonal = idPersonal; }
}
