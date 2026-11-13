package src.modelo;

import java.time.LocalDate;

// Tabla DOCUMENTO_DIGITAL: archivo del expediente del estudiante guardado en el servidor (documentos/<ci>/...).
// Una boleta de pago queda asociada a su PAGO
public class DocumentoDigital {
    private int idDocumento;
    private String tipoDocumento;
    private String urlDoc;
    private LocalDate fechaPresentacion;
    private int idEstudiante;
    private Integer idPago;

    // No son columnas de la tabla: el pago que respalda la boleta
    private String numeroTransaccion;
    private String estadoPago;

    public int getIdDocumento() { return idDocumento; }
    public void setIdDocumento(int idDocumento) { this.idDocumento = idDocumento; }
    public String getTipoDocumento() { return tipoDocumento; }
    public void setTipoDocumento(String tipoDocumento) { this.tipoDocumento = tipoDocumento; }
    public String getUrlDoc() { return urlDoc; }
    public void setUrlDoc(String urlDoc) { this.urlDoc = urlDoc; }
    public LocalDate getFechaPresentacion() { return fechaPresentacion; }
    public void setFechaPresentacion(LocalDate fechaPresentacion) { this.fechaPresentacion = fechaPresentacion; }
    public int getIdEstudiante() { return idEstudiante; }
    public void setIdEstudiante(int idEstudiante) { this.idEstudiante = idEstudiante; }
    public Integer getIdPago() { return idPago; }
    public void setIdPago(Integer idPago) { this.idPago = idPago; }
    public String getNumeroTransaccion() { return numeroTransaccion; }
    public void setNumeroTransaccion(String numeroTransaccion) { this.numeroTransaccion = numeroTransaccion; }
    public String getEstadoPago() { return estadoPago; }
    public void setEstadoPago(String estadoPago) { this.estadoPago = estadoPago; }

    public boolean esBoleta() {
        return "BOLETA_PAGO".equals(tipoDocumento);
    }

    // La boleta de un pago conciliado es la evidencia del depósito: no se reemplaza ni se elimina
    public boolean respaldaPagoConciliado() {
        return esBoleta() && "CONCILIADO".equals(estadoPago);
    }
}
