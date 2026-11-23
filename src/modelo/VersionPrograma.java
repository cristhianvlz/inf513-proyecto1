package src.modelo;

import java.time.LocalDate;

// Tabla VERSION_PROGRAMA: malla curricular versionada. EN_ELABORACION -> VIGENTE (APRVER) -> CERRADA
public class VersionPrograma {
    private int idVersion;
    private String numeroVersion;
    private int totalHoras;
    private String numeroResolucion;
    private String tipoResolucion;
    private LocalDate fechaAprobacion;
    private LocalDate fechaInicioVigencia;
    private String estado = "EN_ELABORACION";
    private int idPrograma;

    public VersionPrograma() {
    }

    public VersionPrograma(int idPrograma, String numeroVersion, int totalHoras) {
        this.idPrograma = idPrograma;
        this.numeroVersion = numeroVersion;
        this.totalHoras = totalHoras;
    }

    public int getIdVersion() { return idVersion; }
    public void setIdVersion(int idVersion) { this.idVersion = idVersion; }
    public String getNumeroVersion() { return numeroVersion; }
    public void setNumeroVersion(String numeroVersion) { this.numeroVersion = numeroVersion; }
    public int getTotalHoras() { return totalHoras; }
    public void setTotalHoras(int totalHoras) { this.totalHoras = totalHoras; }
    public String getNumeroResolucion() { return numeroResolucion; }
    public void setNumeroResolucion(String numeroResolucion) { this.numeroResolucion = numeroResolucion; }
    public String getTipoResolucion() { return tipoResolucion; }
    public void setTipoResolucion(String tipoResolucion) { this.tipoResolucion = tipoResolucion; }
    public LocalDate getFechaAprobacion() { return fechaAprobacion; }
    public void setFechaAprobacion(LocalDate fechaAprobacion) { this.fechaAprobacion = fechaAprobacion; }
    public LocalDate getFechaInicioVigencia() { return fechaInicioVigencia; }
    public void setFechaInicioVigencia(LocalDate fechaInicioVigencia) { this.fechaInicioVigencia = fechaInicioVigencia; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public int getIdPrograma() { return idPrograma; }
    public void setIdPrograma(int idPrograma) { this.idPrograma = idPrograma; }

    public boolean enElaboracion() {
        return "EN_ELABORACION".equals(estado);
    }
}
