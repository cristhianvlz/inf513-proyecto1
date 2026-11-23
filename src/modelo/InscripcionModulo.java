package src.modelo;

import java.math.BigDecimal;

// Tabla INSCRIPCION_MODULO: un intento del estudiante en un módulo de su oferta
public class InscripcionModulo {
    private int idInscripcionModulo;
    private int numeroIntento = 1;
    private String condicion = "REGULAR";
    private String estado = "EN_CURSO";
    private BigDecimal notaFinal;
    private BigDecimal porcentajeAsistencia;
    private int idInscripcion;
    private int idModulo;

    public int getIdInscripcionModulo() { return idInscripcionModulo; }
    public void setIdInscripcionModulo(int idInscripcionModulo) { this.idInscripcionModulo = idInscripcionModulo; }
    public int getNumeroIntento() { return numeroIntento; }
    public void setNumeroIntento(int numeroIntento) { this.numeroIntento = numeroIntento; }
    public String getCondicion() { return condicion; }
    public void setCondicion(String condicion) { this.condicion = condicion; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public BigDecimal getNotaFinal() { return notaFinal; }
    public void setNotaFinal(BigDecimal notaFinal) { this.notaFinal = notaFinal; }
    public BigDecimal getPorcentajeAsistencia() { return porcentajeAsistencia; }
    public void setPorcentajeAsistencia(BigDecimal porcentajeAsistencia) { this.porcentajeAsistencia = porcentajeAsistencia; }
    public int getIdInscripcion() { return idInscripcion; }
    public void setIdInscripcion(int idInscripcion) { this.idInscripcion = idInscripcion; }
    public int getIdModulo() { return idModulo; }
    public void setIdModulo(int idModulo) { this.idModulo = idModulo; }
}
