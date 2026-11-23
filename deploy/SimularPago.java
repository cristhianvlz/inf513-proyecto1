package deploy;

import java.math.BigDecimal;
import java.util.List;
import src.comunicacion.PasarelaSimulada;

// Hace de banco para la pasarela simulada (pasarela.proveedor=simulada): marca una orden de GENPAGO como pagada o vencida.
// MainICAP la confirma en su próxima revisión de pagos (pasarela.revision_segundos) y avisa por correo.
// Se ejecuta desde la carpeta del proyecto, con las clases compiladas en el classpath (bin con run.bat, o la raíz):
//   java -cp bin deploy/SimularPago.java                          lista las órdenes
//   java -cp bin deploy/SimularPago.java SIM-...                  paga exactamente el monto de la orden
//   java -cp bin deploy/SimularPago.java SIM-... 90 "Juan Perez"  paga otro monto (el pago quedará OBSERVADO)
//   java -cp bin deploy/SimularPago.java SIM-... vencer           la orden vence sin pago (quedará ANULADO)
public class SimularPago {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            List<String> ordenes = PasarelaSimulada.listar();
            System.out.println(ordenes.isEmpty() ? "No hay órdenes en la pasarela simulada." : String.join(System.lineSeparator(), ordenes));
            System.out.println("Uso: java -cp bin deploy/SimularPago.java <orden> [monto | vencer] [pagador]");
            return;
        }
        try {
            if (args.length >= 2 && args[1].equalsIgnoreCase("vencer")) {
                System.out.println(PasarelaSimulada.simularVencimiento(args[0]));
                return;
            }
            BigDecimal monto = args.length >= 2 ? new BigDecimal(args[1].replace(',', '.')) : null;
            String pagador = args.length >= 3 ? args[2] : null;
            System.out.println(PasarelaSimulada.simularPago(args[0], monto, pagador));
        } catch (java.io.IOException | NumberFormatException e) {
            System.out.println("No se pudo: " + e.getMessage());
        }
    }
}
