package src.negocio;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import src.comunicacion.ClienteSMTP;
import src.comunicacion.PasarelaPago;
import src.modelo.Pago;
import src.persistencia.ConexionBD;
import src.persistencia.PagoDAO;
import src.utilidades.ArchivoLog;

// CU-04 Flujo E: confirma los pagos hechos en la pasarela. MainICAP lo ejecuta cada pasarela.revision_segundos (30 s por
// defecto): pregunta a la pasarela por cada orden sin pagar. Si se pagó, el pago pasa a CONCILIADO (u OBSERVADO si la
// pasarela cobró otro monto) y avisa igual que CONCPAGO; si venció, pasa a ANULADO y avisa al estudiante.
// Nunca se toma como pago un correo del estudiante: solo lo que responde la pasarela.
// No escribe en LOG: lo hace el sistema, no un usuario (queda en PAGO y en la consola)
public class VerificadorPagos {

    private static final int LARGO_NOMBRE = 150;

    // Una sola línea en error.log por corte de la pasarela, y otra cuando se recupera
    private boolean pasarelaCaida;
    private boolean configuracionInvalida;

    public void revisar() {
        PasarelaPago pasarela;
        try {
            pasarela = PasarelaPago.configurada();
        } catch (IllegalStateException e) {
            if (!configuracionInvalida) {
                System.out.println(">>> [PAGOS]: " + e.getMessage());
                ArchivoLog.error("Pasarela de pago", e.getMessage());
                configuracionInvalida = true;
            }
            return;
        }
        configuracionInvalida = false;

        List<String[]> correos = new ArrayList<>();
        try {
            Connection con = ConexionBD.conectar();
            for (Pago orden : new PagoDAO(con).ordenesSinPagar()) {
                PasarelaPago.Consulta c;
                try {
                    c = pasarela.consultar(orden.getNumeroTransaccion());
                } catch (IOException e) {
                    if (!pasarelaCaida) {
                        System.out.println(">>> [PAGOS]: La pasarela no responde (" + e.getMessage() + "). Se reintenta en la próxima revisión.");
                        ArchivoLog.error("Pasarela de pago: no se pudo consultar la orden " + orden.getNumeroTransaccion(), e.getMessage());
                        pasarelaCaida = true;
                    }
                    break;
                }
                if (pasarelaCaida) {
                    System.out.println(">>> [PAGOS]: La pasarela volvió a responder.");
                    pasarelaCaida = false;
                }
                // Una orden con problemas no impide confirmar las demás
                try {
                    switch (c.estado()) {
                        case PAGADA -> correos.addAll(confirmar(con, orden, c));
                        case EXPIRADA -> correos.addAll(anular(con, orden, "Orden vencida sin pago", true));
                        case ANULADA -> correos.addAll(anular(con, orden, "Orden anulada en la pasarela", false));
                        default -> {
                        }
                    }
                } catch (SQLException e) {
                    System.out.println(">>> [PAGOS]: No se pudo actualizar la orden " + orden.getNumeroTransaccion() + ": " + e.getMessage());
                    ArchivoLog.error("Confirmación de pagos: orden " + orden.getNumeroTransaccion(), e);
                }
            }
        } catch (SQLException e) {
            System.out.println(">>> [PAGOS]: No se pudo revisar las órdenes de la pasarela: " + e.getMessage());
            ArchivoLog.error("Confirmación de pagos", e);
            return;
        }

        if (!correos.isEmpty()) {
            ClienteSMTP smtp = new ClienteSMTP();
            for (String[] c : correos) {
                System.out.println(">>> [PAGOS]: Notificando a " + c[0]);
                smtp.enviarCorreo(c[0], c[1], c[2]);
            }
        }
    }

    private List<String[]> confirmar(Connection con, Pago orden, PasarelaPago.Consulta c) throws SQLException {
        con.setAutoCommit(false);
        try {
            PagoDAO dao = new PagoDAO(con);
            BigDecimal pagado = c.montoPagado() == null ? orden.getMonto() : c.montoPagado().setScale(2, RoundingMode.HALF_UP);
            dao.actualizarCampo(orden.getIdPago(), "fecha_pago", c.fechaPago() == null ? LocalDate.now() : c.fechaPago().toLocalDate());
            if (c.pagador() != null && !c.pagador().isBlank()) {
                String nombre = c.pagador().trim();
                dao.actualizarCampo(orden.getIdPago(), "nombre_del_depositante", nombre.length() > LARGO_NOMBRE ? nombre.substring(0, LARGO_NOMBRE) : nombre);
            }
            List<String[]> avisos;
            if (pagado.compareTo(orden.getMonto()) == 0) {
                orden.setEstadoConciliacion("CONCILIADO");
                orden.setMotivo(null);
                dao.guardarEstado(orden);
                avisos = CtrlPagos.avisosDeConciliacion(con, orden, "fue confirmado por la pasarela de pago");
            } else {
                String motivo = "La pasarela cobró Bs " + pagado + " y la orden era por Bs " + orden.getMonto();
                dao.actualizarCampo(orden.getIdPago(), "monto", pagado);
                orden.setMonto(pagado);
                orden.setEstadoConciliacion("OBSERVADO");
                orden.setMotivo(motivo);
                dao.guardarEstado(orden);
                avisos = CtrlPagos.avisosDeObservacion(con, orden);
            }
            con.commit();
            System.out.println(">>> [PAGOS]: La orden " + orden.getNumeroTransaccion() + " fue pagada: quedó " + orden.getEstadoConciliacion() + ".");
            return avisos;
        } finally {
            // La conexión se reutiliza: lo que no se confirmó se deshace antes de volver a autocommit
            con.rollback();
            con.setAutoCommit(true);
        }
    }

    // Una orden que venció avisa al estudiante; una que se anuló en la pasarela (por ejemplo, desde su panel) no
    private List<String[]> anular(Connection con, Pago orden, String motivo, boolean avisar) throws SQLException {
        con.setAutoCommit(false);
        try {
            orden.setEstadoConciliacion("ANULADO");
            orden.setMotivo(motivo);
            new PagoDAO(con).guardarEstado(orden);
            List<String[]> avisos = avisar ? CtrlPagos.avisosDeVencimiento(con, orden) : List.of();
            con.commit();
            System.out.println(">>> [PAGOS]: La orden " + orden.getNumeroTransaccion() + " quedó ANULADO: " + motivo + ".");
            return avisos;
        } finally {
            con.rollback();
            con.setAutoCommit(true);
        }
    }
}
