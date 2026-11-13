package src.modelo;

import java.math.BigDecimal;
import java.time.LocalDate;

// Tabla CONVENIO: beca institucional. VIGENTE -> VENCIDO (por fecha) o RESCINDIDO (DELCONV)
public class Convenio {
    private int idConvenio;
    private String institucion;
    private String tipoConvenio;
    private BigDecimal porcentajeDescuento;
    private LocalDate fechaInicio;
    private LocalDate fechaFin;
    private String estado = "VIGENTE";

    public int getIdConvenio() { return idConvenio; }
    public void setIdConvenio(int idConvenio) { this.idConvenio = idConvenio; }
    public String getInstitucion() { return institucion; }
    public void setInstitucion(String institucion) { this.institucion = institucion; }
    public String getTipoConvenio() { return tipoConvenio; }
    public void setTipoConvenio(String tipoConvenio) { this.tipoConvenio = tipoConvenio; }
    public BigDecimal getPorcentajeDescuento() { return porcentajeDescuento; }
    public void setPorcentajeDescuento(BigDecimal porcentajeDescuento) { this.porcentajeDescuento = porcentajeDescuento; }
    public LocalDate getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(LocalDate fechaInicio) { this.fechaInicio = fechaInicio; }
    public LocalDate getFechaFin() { return fechaFin; }
    public void setFechaFin(LocalDate fechaFin) { this.fechaFin = fechaFin; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    // Estado que le corresponde por sus fechas, si no fue rescindido
    public String estadoPorFechas() {
        return fechaFin != null && fechaFin.isBefore(LocalDate.now()) ? "VENCIDO" : "VIGENTE";
    }
}
