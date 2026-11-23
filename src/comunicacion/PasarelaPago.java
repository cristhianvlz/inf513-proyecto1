package src.comunicacion;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import src.utilidades.Configuracion;

// CU-04 Flujo E: pago en línea. El sistema no depende de un proveedor: cuando la Universidad habilite una pasarela
// autorizada (banco o QR interbancario), se agrega una implementación de esta interfaz y se elige en icap.properties
// con pasarela.proveedor. Mientras tanto, PasarelaSimulada permite probar el flujo completo.
// El sistema sigue siendo por correo: la pasarela solo cobra; la confirmación se obtiene consultándola (VerificadorPagos)
public interface PasarelaPago {

    enum Estado {
        PENDIENTE, PAGADA, EXPIRADA, ANULADA
    }

    // Orden de cobro: el estudiante paga con el enlace o escaneando el QR (PNG). Un proveedor de solo QR puede no dar enlace
    record Orden(String id, String enlace, byte[] qrPng, LocalDateTime vence) {
    }

    // Estado de la orden según la pasarela; monto, pagador y fecha solo vienen cuando está PAGADA
    record Consulta(Estado estado, BigDecimal montoPagado, String pagador, LocalDateTime fechaPago) {
    }

    // Nombre que se muestra en las respuestas
    String nombre();

    // true si no cobra dinero real: las respuestas lo advierten para no confundir al estudiante
    boolean esDePrueba();

    Orden crearOrden(String referencia, BigDecimal monto, String descripcion, String correoPagador) throws IOException;

    Consulta consultar(String idOrden) throws IOException;

    // Anula una orden sin pagar. Si ya está pagada, lanza IOException: el pago no se pierde, lo confirma VerificadorPagos
    void anular(String idOrden) throws IOException;

    // Proveedor elegido en icap.properties (pasarela.proveedor); por defecto, la pasarela simulada
    static PasarelaPago configurada() {
        String proveedor = Configuracion.get("pasarela.proveedor", "simulada").toLowerCase(Locale.ROOT);
        if (proveedor.equals("simulada")) {
            return new PasarelaSimulada();
        }
        throw new IllegalStateException("La pasarela '" + proveedor + "' de " + Configuracion.ARCHIVO
                + " no está implementada. Use pasarela.proveedor=simulada o agregue la clase del proveedor.");
    }
}
