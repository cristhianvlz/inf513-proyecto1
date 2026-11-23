package src.modelo;

import java.math.BigDecimal;
import java.time.LocalDate;

// Tabla ARANCEL: monto de un concepto en una gestión. A lo sumo uno VIGENTE por concepto y gestión
public class Arancel {
    private int idArancel;
    private String concepto;
    private BigDecimal monto;
    private String gestion;
    private String numeroResolucion;
    private LocalDate fechaResolucion;
    private String estado = "VIGENTE";

    public int getIdArancel() { return idArancel; }
    public void setIdArancel(int idArancel) { this.idArancel = idArancel; }
    public String getConcepto() { return concepto; }
    public void setConcepto(String concepto) { this.concepto = concepto; }
    public BigDecimal getMonto() { return monto; }
    public void setMonto(BigDecimal monto) { this.monto = monto; }
    public String getGestion() { return gestion; }
    public void setGestion(String gestion) { this.gestion = gestion; }
    public String getNumeroResolucion() { return numeroResolucion; }
    public void setNumeroResolucion(String numeroResolucion) { this.numeroResolucion = numeroResolucion; }
    public LocalDate getFechaResolucion() { return fechaResolucion; }
    public void setFechaResolucion(LocalDate fechaResolucion) { this.fechaResolucion = fechaResolucion; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    // "MATRICULA de la gestión 2-2026"
    public String getDescripcion() {
        return "<b>" + concepto + "</b> de la gestión <b>" + gestion + "</b>";
    }
}
