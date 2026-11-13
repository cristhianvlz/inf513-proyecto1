package src.modelo;

import java.math.BigDecimal;

// Un módulo de la malla con el resultado del estudiante en su inscripción (no es una tabla):
// el intento aprobado si lo hay o, si no, el último. Sin intento, los campos del resultado quedan en null
public class ModuloCursado {
    private static final BigDecimal NOTA_DE_APROBACION = new BigDecimal(51);

    private String codigo;
    private String nombre;
    private int cargaHoraria;
    private Integer intento;
    private String condicion;
    private BigDecimal notaFinal;
    private BigDecimal asistencia;
    private String estado;
    private boolean actaCerrada;
    private int idAsignacion;

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public int getCargaHoraria() { return cargaHoraria; }
    public void setCargaHoraria(int cargaHoraria) { this.cargaHoraria = cargaHoraria; }
    public Integer getIntento() { return intento; }
    public void setIntento(Integer intento) { this.intento = intento; }
    public String getCondicion() { return condicion; }
    public void setCondicion(String condicion) { this.condicion = condicion; }
    public BigDecimal getNotaFinal() { return notaFinal; }
    public void setNotaFinal(BigDecimal notaFinal) { this.notaFinal = notaFinal; }
    public BigDecimal getAsistencia() { return asistencia; }
    public void setAsistencia(BigDecimal asistencia) { this.asistencia = asistencia; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public boolean isActaCerrada() { return actaCerrada; }
    public void setActaCerrada(boolean actaCerrada) { this.actaCerrada = actaCerrada; }
    public int getIdAsignacion() { return idAsignacion; }
    public void setIdAsignacion(int idAsignacion) { this.idAsignacion = idAsignacion; }

    public boolean fueCursado() {
        return intento != null;
    }

    // Nota oficial: un resultado definitivo en un acta cerrada, que ya solo cambia por enmienda
    public boolean tieneNotaOficial() {
        return fueCursado() && actaCerrada && notaFinal != null
                && ("APROBADO".equals(estado) || "REPROBADO".equals(estado) || "PERDIDA_POR_INASISTENCIA".equals(estado));
    }

    public boolean estaAprobado() {
        return tieneNotaOficial() && "APROBADO".equals(estado) && notaFinal.compareTo(NOTA_DE_APROBACION) >= 0;
    }
}
