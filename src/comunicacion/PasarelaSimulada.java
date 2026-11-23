package src.comunicacion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;
import src.utilidades.CodigoQR;
import src.utilidades.Configuracion;

// Pasarela de prueba: no cobra dinero real. Guarda sus órdenes en un archivo de texto (pasarela_simulada.txt) y
// deploy/SimularPago.java las marca como pagadas o vencidas, como lo haría el banco. Así se prueba el flujo completo
// (GENPAGO -> pago -> confirmación automática) sin la API del proveedor
public class PasarelaSimulada implements PasarelaPago {

    private static final String ENLACE = "https://pasarela-simulada.icap.local/pagar/";
    private static final DateTimeFormatter CODIGO = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final int ESCALA_QR = 8;
    private static final int MARGEN_QR = 4;

    // Una orden por renglón, campos separados por tabulador
    private record Registro(String id, PasarelaPago.Estado estado, BigDecimal monto, LocalDateTime vence, String correo,
            String referencia, String descripcion, BigDecimal montoPagado, String pagador, LocalDateTime fechaPago) {

        String linea() {
            return String.join("\t", id, estado.name(), monto.toPlainString(), vence.toString(), limpio(correo), limpio(referencia),
                    limpio(descripcion), montoPagado == null ? "" : montoPagado.toPlainString(), limpio(pagador),
                    fechaPago == null ? "" : fechaPago.toString());
        }

        static Registro de(String linea) {
            String[] c = linea.split("\t", -1);
            return new Registro(c[0], PasarelaPago.Estado.valueOf(c[1]), new BigDecimal(c[2]), LocalDateTime.parse(c[3]), c[4], c[5],
                    c[6], c[7].isEmpty() ? null : new BigDecimal(c[7]), c[8], c[9].isEmpty() ? null : LocalDateTime.parse(c[9]));
        }

        Registro con(PasarelaPago.Estado nuevo, BigDecimal pagado, String quien, LocalDateTime cuando) {
            return new Registro(id, nuevo, monto, vence, correo, referencia, descripcion, pagado, quien, cuando);
        }
    }

    @Override
    public String nombre() {
        return "Pasarela simulada (pruebas)";
    }

    @Override
    public boolean esDePrueba() {
        return true;
    }

    @Override
    public Orden crearOrden(String referencia, BigDecimal monto, String descripcion, String correoPagador) throws IOException {
        synchronized (PasarelaSimulada.class) {
            Map<String, Registro> ordenes = leer();
            String id;
            do {
                id = "SIM-" + LocalDateTime.now().format(CODIGO) + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
            } while (ordenes.containsKey(id));
            LocalDateTime vence = LocalDateTime.now().plusHours(Configuracion.entero("pasarela.vigencia_horas", 24)).withNano(0);
            ordenes.put(id, new Registro(id, PasarelaPago.Estado.PENDIENTE, monto.setScale(2, RoundingMode.HALF_UP), vence,
                    correoPagador == null ? "" : correoPagador, referencia, descripcion, null, "", null));
            escribir(ordenes);
            String enlace = ENLACE + id;
            return new Orden(id, enlace, qrPng(enlace), vence);
        }
    }

    @Override
    public Consulta consultar(String idOrden) throws IOException {
        synchronized (PasarelaSimulada.class) {
            Map<String, Registro> ordenes = leer();
            Registro r = buscar(ordenes, idOrden);
            if (r.estado() == PasarelaPago.Estado.PENDIENTE && LocalDateTime.now().isAfter(r.vence())) {
                r = r.con(PasarelaPago.Estado.EXPIRADA, null, "", null);
                ordenes.put(r.id(), r);
                escribir(ordenes);
            }
            return new Consulta(r.estado(), r.montoPagado(), r.pagador().isEmpty() ? null : r.pagador(), r.fechaPago());
        }
    }

    @Override
    public void anular(String idOrden) throws IOException {
        synchronized (PasarelaSimulada.class) {
            Map<String, Registro> ordenes = leer();
            Registro r = buscar(ordenes, idOrden);
            if (r.estado() == PasarelaPago.Estado.PAGADA) {
                throw new IOException("La orden " + idOrden + " ya fue pagada en la pasarela.");
            }
            if (r.estado() == PasarelaPago.Estado.PENDIENTE) {
                ordenes.put(r.id(), r.con(PasarelaPago.Estado.ANULADA, null, "", null));
                escribir(ordenes);
            }
        }
    }

    // ------------------------------------------------------------------ lo que haría el banco (deploy/SimularPago.java)

    // Marca la orden como pagada. Sin monto, se paga exactamente lo pedido
    public static String simularPago(String idOrden, BigDecimal monto, String pagador) throws IOException {
        synchronized (PasarelaSimulada.class) {
            Map<String, Registro> ordenes = leer();
            Registro r = buscar(ordenes, idOrden);
            if (r.estado() != PasarelaPago.Estado.PENDIENTE) {
                throw new IOException("La orden " + r.id() + " está " + r.estado() + ": solo se paga una orden PENDIENTE.");
            }
            if (LocalDateTime.now().isAfter(r.vence())) {
                throw new IOException("La orden " + r.id() + " venció el " + r.vence() + ".");
            }
            BigDecimal pagado = (monto == null ? r.monto() : monto).setScale(2, RoundingMode.HALF_UP);
            ordenes.put(r.id(), r.con(PasarelaPago.Estado.PAGADA, pagado, pagador == null ? "" : pagador,
                    LocalDateTime.now().withNano(0)));
            escribir(ordenes);
            return "Orden " + r.id() + " PAGADA: Bs " + pagado + (pagador == null ? "" : " por " + pagador)
                    + ". MainICAP la confirmará en la próxima revisión de pagos.";
        }
    }

    // Adelanta el vencimiento para probar una orden que nadie pagó
    public static String simularVencimiento(String idOrden) throws IOException {
        synchronized (PasarelaSimulada.class) {
            Map<String, Registro> ordenes = leer();
            Registro r = buscar(ordenes, idOrden);
            if (r.estado() != PasarelaPago.Estado.PENDIENTE) {
                throw new IOException("La orden " + r.id() + " está " + r.estado() + ".");
            }
            ordenes.put(r.id(), r.con(PasarelaPago.Estado.EXPIRADA, null, "", null));
            escribir(ordenes);
            return "Orden " + r.id() + " EXPIRADA. MainICAP la anulará en la próxima revisión de pagos.";
        }
    }

    // Renglones legibles de las órdenes, para la herramienta de prueba
    public static List<String> listar() throws IOException {
        List<String> lista = new ArrayList<>();
        for (Registro r : leer().values()) {
            lista.add(r.id() + "  " + r.estado() + "  Bs " + r.monto() + "  vence " + r.vence() + "  " + r.referencia() + "  " + r.correo());
        }
        return lista;
    }

    // ------------------------------------------------------------------ archivo y QR

    private static Path archivo() {
        return Paths.get(Configuracion.get("pasarela.simulada.archivo", "pasarela_simulada.txt"));
    }

    private static Map<String, Registro> leer() throws IOException {
        Map<String, Registro> ordenes = new LinkedHashMap<>();
        Path a = archivo();
        if (Files.exists(a)) {
            for (String linea : Files.readAllLines(a, StandardCharsets.UTF_8)) {
                if (!linea.isBlank()) {
                    Registro r = Registro.de(linea);
                    ordenes.put(r.id(), r);
                }
            }
        }
        return ordenes;
    }

    // Se escribe en un temporal y se reemplaza: MainICAP y la herramienta de prueba nunca ven el archivo a medias
    private static void escribir(Map<String, Registro> ordenes) throws IOException {
        Path a = archivo().toAbsolutePath();
        Path tmp = a.resolveSibling(a.getFileName() + ".tmp");
        List<String> lineas = new ArrayList<>();
        for (Registro r : ordenes.values()) {
            lineas.add(r.linea());
        }
        Files.write(tmp, lineas, StandardCharsets.UTF_8);
        Files.move(tmp, a, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Registro buscar(Map<String, Registro> ordenes, String idOrden) throws IOException {
        Registro r = idOrden == null ? null : ordenes.get(idOrden.trim().toUpperCase());
        if (r == null) {
            throw new IOException("La orden " + idOrden + " no existe en la pasarela simulada.");
        }
        return r;
    }

    private static String limpio(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // PNG del QR con su margen blanco, generado con el CodigoQR del propio proyecto
    static byte[] qrPng(String texto) throws IOException {
        boolean[][] m = CodigoQR.generar(texto);
        int lado = (m.length + 2 * MARGEN_QR) * ESCALA_QR;
        BufferedImage img = new BufferedImage(lado, lado, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < lado; y++) {
            for (int x = 0; x < lado; x++) {
                int f = y / ESCALA_QR - MARGEN_QR;
                int c = x / ESCALA_QR - MARGEN_QR;
                boolean oscuro = f >= 0 && c >= 0 && f < m.length && c < m.length && m[f][c];
                img.setRGB(x, y, oscuro ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
