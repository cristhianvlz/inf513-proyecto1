package src.modelo;

import java.math.BigDecimal;
import java.time.LocalDate;

// Tabla PAGO: comprobante de depósito. PENDIENTE -> CONCILIADO | OBSERVADO; cualquiera -> ANULADO.
// PAGO no tiene columnas para la cuenta de origen ni para el motivo de una observación o anulación:
// ambos se guardan en concepto_pago, con el formato "MATRICULA | Cuenta: 123 | Motivo: ..."
public class Pago {
    public static final int LARGO_DETALLE = 150;
    // Un pago por la pasarela (CU-04 Flujo E) se marca en relacion_depositante; su numero_transaccion es el código de la orden.
    // PENDIENTE = orden emitida sin pagar; la pasarela lo confirma (CONCILIADO), o vence (ANULADO)
    public static final String PASARELA = "PASARELA";

    private static final String CUENTA = " | Cuenta: ";
    private static final String MOTIVO = " | Motivo: ";

    private int idPago;
    private LocalDate fechaPago;
    private BigDecimal monto;
    private String numeroTransaccion;
    private String nombreDepositante;
    private String relacionDepositante;
    private String estadoConciliacion = "PENDIENTE";
    private int idInscripcion;
    private int idArancel;
    private Integer idInscripcionModulo;
    private Integer idCertificado;
    private String concepto;
    private String cuentaOrigen;
    private String motivo;

    public int getIdPago() { return idPago; }
    public void setIdPago(int idPago) { this.idPago = idPago; }
    public LocalDate getFechaPago() { return fechaPago; }
    public void setFechaPago(LocalDate fechaPago) { this.fechaPago = fechaPago; }
    public BigDecimal getMonto() { return monto; }
    public void setMonto(BigDecimal monto) { this.monto = monto; }
    public String getNumeroTransaccion() { return numeroTransaccion; }
    public void setNumeroTransaccion(String numeroTransaccion) { this.numeroTransaccion = numeroTransaccion; }
    public String getNombreDepositante() { return nombreDepositante; }
    public void setNombreDepositante(String nombreDepositante) { this.nombreDepositante = nombreDepositante; }
    public String getRelacionDepositante() { return relacionDepositante; }
    public void setRelacionDepositante(String relacionDepositante) { this.relacionDepositante = relacionDepositante; }
    public String getEstadoConciliacion() { return estadoConciliacion; }
    public void setEstadoConciliacion(String estadoConciliacion) { this.estadoConciliacion = estadoConciliacion; }
    public int getIdInscripcion() { return idInscripcion; }
    public void setIdInscripcion(int idInscripcion) { this.idInscripcion = idInscripcion; }
    public int getIdArancel() { return idArancel; }
    public void setIdArancel(int idArancel) { this.idArancel = idArancel; }
    public Integer getIdInscripcionModulo() { return idInscripcionModulo; }
    public void setIdInscripcionModulo(Integer idInscripcionModulo) { this.idInscripcionModulo = idInscripcionModulo; }
    public Integer getIdCertificado() { return idCertificado; }
    public void setIdCertificado(Integer idCertificado) { this.idCertificado = idCertificado; }
    public String getConcepto() { return concepto; }
    public void setConcepto(String concepto) { this.concepto = concepto; }
    public String getCuentaOrigen() { return cuentaOrigen; }
    public void setCuentaOrigen(String cuentaOrigen) { this.cuentaOrigen = cuentaOrigen; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    // Valor de la columna concepto_pago
    public String getConceptoPago() {
        return concepto + (cuentaOrigen != null ? CUENTA + cuentaOrigen : "") + (motivo != null ? MOTIVO + motivo : "");
    }

    // Recupera la cuenta y el motivo desde concepto_pago
    public void leerConceptoPago(String texto) {
        if (texto == null) {
            return;
        }
        int posMotivo = texto.indexOf(MOTIVO);
        if (posMotivo >= 0) {
            motivo = texto.substring(posMotivo + MOTIVO.length());
            texto = texto.substring(0, posMotivo);
        }
        int posCuenta = texto.indexOf(CUENTA);
        if (posCuenta >= 0) {
            cuentaOrigen = texto.substring(posCuenta + CUENTA.length());
        }
    }

    public boolean esMensualidad() {
        return concepto != null && concepto.startsWith("MODULO_MENSUAL");
    }

    public boolean esDePasarela() {
        return PASARELA.equals(relacionDepositante);
    }

    // Orden de la pasarela que todavía no se pagó: solo la pasarela la confirma, nadie la concilia a mano
    public boolean esOrdenSinPagar() {
        return esDePasarela() && "PENDIENTE".equals(estadoConciliacion);
    }
}
