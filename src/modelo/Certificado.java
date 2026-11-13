package src.modelo;

import java.time.LocalDate;
import java.util.List;

// Tabla CERTIFICADO: título, certificado o constancia emitido con código único y hash para el código QR.
// Nace VIGENTE; ANUCERT lo pasa a ANULADO y nunca se borra. El motivo de la anulación queda en HISTORIAL_ESTADO
public class Certificado {

    // Títulos y certificados finales del programa: requieren la triple condición y el arancel de TITULO
    public static final List<String> FINALES = List.of("TITULO_TECNICO_MEDIO", "TITULO_TECNICO_AUXILIAR",
            "CERTIFICADO_COMPETENCIA_LABORAL", "CERTIFICADO_PARTICIPACION");
    // Certificaciones y constancias que emite el Director con el arancel de CERTIFICADO
    public static final List<String> CERTIFICACIONES = List.of("CERTIFICADO_NOTAS", "CERTIFICADO_ESTUDIANTE",
            "CERTIFICADO_CONCLUSION", "CERTIFICADO_VENCIMIENTO", "CONSTANCIA_HORARIOS", "CONSTANCIA_ASISTENCIA",
            "CONSTANCIA_ADMISION", "CONSTANCIA_PROGRAMA_ANALITICO");

    private int idCertificado;
    private String tipoCertificado;
    private String gradoOtorgado;
    private String numeroTitulo;
    private String codigoAlfanumerico;
    private String codigoQrHash;
    private LocalDate fechaEmision;
    private String estado = "VIGENTE";
    private int idInscripcion;

    // No son columnas de la tabla: datos públicos para VERCERT y la anulación registrada en HISTORIAL_ESTADO
    private String titular;
    private String programa;
    private String fechaAnulacion;
    private String motivoAnulacion;

    public int getIdCertificado() { return idCertificado; }
    public void setIdCertificado(int idCertificado) { this.idCertificado = idCertificado; }
    public String getTipoCertificado() { return tipoCertificado; }
    public void setTipoCertificado(String tipoCertificado) { this.tipoCertificado = tipoCertificado; }
    public String getGradoOtorgado() { return gradoOtorgado; }
    public void setGradoOtorgado(String gradoOtorgado) { this.gradoOtorgado = gradoOtorgado; }
    public String getNumeroTitulo() { return numeroTitulo; }
    public void setNumeroTitulo(String numeroTitulo) { this.numeroTitulo = numeroTitulo; }
    public String getCodigoAlfanumerico() { return codigoAlfanumerico; }
    public void setCodigoAlfanumerico(String codigoAlfanumerico) { this.codigoAlfanumerico = codigoAlfanumerico; }
    public String getCodigoQrHash() { return codigoQrHash; }
    public void setCodigoQrHash(String codigoQrHash) { this.codigoQrHash = codigoQrHash; }
    public LocalDate getFechaEmision() { return fechaEmision; }
    public void setFechaEmision(LocalDate fechaEmision) { this.fechaEmision = fechaEmision; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public int getIdInscripcion() { return idInscripcion; }
    public void setIdInscripcion(int idInscripcion) { this.idInscripcion = idInscripcion; }
    public String getTitular() { return titular; }
    public void setTitular(String titular) { this.titular = titular; }
    public String getPrograma() { return programa; }
    public void setPrograma(String programa) { this.programa = programa; }
    public String getFechaAnulacion() { return fechaAnulacion; }
    public void setFechaAnulacion(String fechaAnulacion) { this.fechaAnulacion = fechaAnulacion; }
    public String getMotivoAnulacion() { return motivoAnulacion; }
    public void setMotivoAnulacion(String motivoAnulacion) { this.motivoAnulacion = motivoAnulacion; }

    public static boolean esFinal(String tipo) {
        return FINALES.contains(tipo);
    }

    public boolean estaAnulado() {
        return "ANULADO".equals(estado);
    }

    // Comienzo del motivo en HISTORIAL_ESTADO: identifica el documento ("ICAP-2026-000001 (Certificado de Notas): ...")
    public static String referenciaHistorial(String codigo, String nombreTipo) {
        return codigo + " (" + nombreTipo + "): ";
    }
}
