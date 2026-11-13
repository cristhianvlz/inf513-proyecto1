package src.modelo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Tabla OFERTA_ACADEMICA: apertura de un programa (versión VIGENTE) en una gestión y grupo
public class OfertaAcademica {
    private int idOferta;
    private String gestion;
    private String grupo;
    private String modalidad;
    private String lugar;
    private LocalDate fechaInicio;
    private LocalDate fechaFin;
    private int cupoMin;
    private Integer cupoMax;
    private BigDecimal costo;
    private String estado = "PLANIFICADA";
    private int idVersion;
    // No es columna de la tabla: se carga con la oferta porque la identifica junto con la gestión y el grupo
    private String codigoPrograma;

    public int getIdOferta() { return idOferta; }
    public void setIdOferta(int idOferta) { this.idOferta = idOferta; }
    public String getGestion() { return gestion; }
    public void setGestion(String gestion) { this.gestion = gestion; }
    public String getGrupo() { return grupo; }
    public void setGrupo(String grupo) { this.grupo = grupo; }
    public String getModalidad() { return modalidad; }
    public void setModalidad(String modalidad) { this.modalidad = modalidad; }
    public String getLugar() { return lugar; }
    public void setLugar(String lugar) { this.lugar = lugar; }
    public LocalDate getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(LocalDate fechaInicio) { this.fechaInicio = fechaInicio; }
    public LocalDate getFechaFin() { return fechaFin; }
    public void setFechaFin(LocalDate fechaFin) { this.fechaFin = fechaFin; }
    public int getCupoMin() { return cupoMin; }
    public void setCupoMin(int cupoMin) { this.cupoMin = cupoMin; }
    public Integer getCupoMax() { return cupoMax; }
    public void setCupoMax(Integer cupoMax) { this.cupoMax = cupoMax; }
    public BigDecimal getCosto() { return costo; }
    public void setCosto(BigDecimal costo) { this.costo = costo; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public int getIdVersion() { return idVersion; }
    public void setIdVersion(int idVersion) { this.idVersion = idVersion; }
    public String getCodigoPrograma() { return codigoPrograma; }
    public void setCodigoPrograma(String codigoPrograma) { this.codigoPrograma = codigoPrograma; }

    // "oferta TAC 2-2026 grupo A"
    public String getDescripcion() {
        return "oferta <b>" + codigoPrograma + " " + gestion + " grupo " + grupo + "</b>";
    }

    // Parámetros que identifican la oferta en los comandos: "TAC", "2-2026", "A"
    public String getReferencia() {
        return "\"" + codigoPrograma + "\", \"" + gestion + "\", \"" + grupo + "\"";
    }

    // Año de la gestión: "2-2026" -> "2026". Si la gestión no lo indica, se usa el de la fecha de inicio
    public String getAnioGestion() {
        Matcher anio = Pattern.compile("\\d{4}").matcher(gestion);
        return anio.find() ? anio.group() : String.valueOf(fechaInicio.getYear());
    }

    // CANCELADA y FINALIZADA son estados terminales: la oferta ya no se modifica
    public boolean estaCerrada() {
        return "CANCELADA".equals(estado) || "FINALIZADA".equals(estado);
    }
}
