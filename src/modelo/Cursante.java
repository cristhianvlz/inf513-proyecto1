package src.modelo;

import java.math.BigDecimal;

// Un intento de un estudiante en el módulo de una asignación, con los datos que muestran las planillas y el acta
public class Cursante {
    private int idInscripcionModulo;
    private int numeroIntento;
    private String condicion;
    private String estado;
    private BigDecimal notaFinal;
    private BigDecimal porcentajeAsistencia;
    private int idInscripcion;
    private String ci;
    private String codigo;
    private String nombre;

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
    public String getCi() { return ci; }
    public void setCi(String ci) { this.ci = ci; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public boolean esRecuperatorio() {
        return "RECUPERATORIO".equals(condicion);
    }

    public boolean corresponde(String valor) {
        String v = valor.trim();
        return v.equalsIgnoreCase(ci) || v.equalsIgnoreCase(codigo);
    }
}
