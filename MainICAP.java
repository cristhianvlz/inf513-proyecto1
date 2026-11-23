//package src;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TimeZone;
import src.comunicacion.ClientePOP3;
import src.comunicacion.ClienteSMTP;
import src.modelo.CorreoRecibido;
import src.negocio.AvisoCupoMinimo;
import src.negocio.CierreActas;
import src.negocio.ProcesadorComandos;
import src.negocio.VerificadorPagos;
import src.utilidades.ArchivoLog;
import src.utilidades.Configuracion;
import src.utilidades.GeneradorHTML;

// Servicio que queda escuchando el buzón: cada pocos segundos lee los correos nuevos, ejecuta sus comandos
// y responde. POP3 no avisa cuando llega un correo, por eso hay que preguntarle al servidor periódicamente.
// Uso: java -cp "bin;lib\postgresql-42.7.14.jar" MainICAP [segundos entre revisiones]   (Ctrl+C para detener)
// Registro técnico: logs/access.log (un renglón por correo) y logs/error.log (excepciones)
public class MainICAP {

    // La revisión del buzón ya tarda unos 3 s (el servidor está lejos), así que no conviene esperar mucho más entre una y otra
    private static final int INTERVALO_POR_DEFECTO = 3;
    // El aviso de cupo mínimo y el cierre de actas dependen de la fecha: basta revisarlos cada hora y después de procesar comandos
    private static final long REVISION_AVISOS_MS = 60 * 60 * 1000;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Se mantiene en un campo para que el candado dure mientras el programa esté abierto
    private static FileLock candado;

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("====================================================");
        System.out.println("   SISTEMA DE GESTIÓN ACADÉMICA ICAP - GRUPO 15 SC  ");
        System.out.println("====================================================\n");

        // Sin las claves de icap.properties no se puede leer el buzón ni usar la base
        List<String> faltan = Configuracion.faltantes();
        if (!faltan.isEmpty()) {
            System.out.println("Faltan claves en " + Configuracion.ARCHIVO + ": " + String.join(", ", faltan) + ".");
            System.out.println("Copie icap.properties.ejemplo como icap.properties y complételo.");
            return;
        }

        // Las fechas del sistema (sesiones dictadas, inicio de ofertas, cierre de actas a las 72 h) son las de Bolivia,
        // aunque el reloj del servidor esté en otra zona: en un VPS suele estar en UTC, cuatro horas adelante
        String zona = Configuracion.get("zona.horaria", "America/La_Paz");
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zona)));
        } catch (DateTimeException e) {
            System.out.println(">>> Zona horaria no válida en " + Configuracion.ARCHIVO + ": " + zona + ". Se usa la del sistema.");
        }

        // Dos MainICAP abiertos a la vez ejecutarían dos veces cada comando
        candado = FileChannel.open(Paths.get("mainicap.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                .tryLock();
        if (candado == null) {
            System.out.println("Ya hay otro MainICAP en ejecución. Deténgalo (Ctrl+C) antes de iniciar uno nuevo.");
            return;
        }

        int intervalo = leerIntervalo(args);
        ClientePOP3 pop3 = new ClientePOP3();
        ProcesadorComandos parser = new ProcesadorComandos();
        ClienteSMTP smtp = new ClienteSMTP();
        AvisoCupoMinimo aviso = new AvisoCupoMinimo();
        CierreActas cierreActas = new CierreActas();
        // Pasarela de pago (CU-04): la confirmación se obtiene preguntándole, como el buzón por POP3
        VerificadorPagos verificadorPagos = new VerificadorPagos();
        long revisionPagosMs = Configuracion.entero("pasarela.revision_segundos", 30) * 1000L;
        long ultimaRevisionAvisos = 0;
        long ultimaRevisionPagos = 0;

        System.out.println(">>> Zona horaria: " + TimeZone.getDefault().getID() + ". Registro técnico en la carpeta logs/.");
        System.out.println(">>> Escuchando el buzón cada " + intervalo + " s. Presione Ctrl+C para detener.");

        while (true) {
            try {
                int procesados = procesarCorreosNuevos(pop3, parser, smtp);

                // Aviso automático (CU-02): ofertas a 3 días del inicio sin cupo mínimo
                if (procesados > 0 || System.currentTimeMillis() - ultimaRevisionAvisos >= REVISION_AVISOS_MS) {
                    aviso.revisar();
                    // Cierre automático (CU-05): actas de módulos que terminaron hace 72 horas o más
                    cierreActas.revisar();
                    ultimaRevisionAvisos = System.currentTimeMillis();
                }
                // Órdenes de la pasarela sin pagar: si se pagaron quedan CONCILIADAS; si vencieron, ANULADAS
                if (System.currentTimeMillis() - ultimaRevisionPagos >= revisionPagosMs) {
                    verificadorPagos.revisar();
                    ultimaRevisionPagos = System.currentTimeMillis();
                }
                // La conexión SMTP queda abierta para los envíos que siguen; tras 15 s sin uso se cierra
                ClienteSMTP.cerrarSiInactiva();
            } catch (RuntimeException e) {
                // Un error inesperado no detiene el servicio: la siguiente revisión vuelve a intentarlo
                System.out.println(">>> [ERROR]: " + e);
                ArchivoLog.error("MainICAP: error en la revisión del buzón", e);
            }
            Thread.sleep(intervalo * 1000L);
        }
    }

    // Ejecuta los comandos de los correos que llegaron desde la última revisión (no se borra ninguno)
    private static int procesarCorreosNuevos(ClientePOP3 pop3, ProcesadorComandos parser, ClienteSMTP smtp) {
        List<CorreoRecibido> correos = pop3.leerCorreosNuevos();
        if (!correos.isEmpty()) {
            // El servidor SMTP tarda en saludar: la conexión se abre ya, mientras se ejecuta el comando
            ClienteSMTP.prepararConexion();
        }

        for (CorreoRecibido correo : correos) {
            String remitente = correo.getRemitente();
            String comandoAsunto = correo.getAsunto();
            String asuntoVisible = ProcesadorComandos.asuntoVisible(comandoAsunto);

            // Se registra antes de ejecutar: si el programa se interrumpe, el comando no se repite
            pop3.marcarProcesado(correo.getUid());
            long inicio = System.currentTimeMillis();
            String resultado = "sin procesar";
            String envio = "no enviada";

            // Cada correo se atiende por separado: un problema con uno no impide responder a los siguientes
            try {
                System.out.println("\n>>> [" + LocalTime.now().format(HORA) + "] Correo #" + correo.getNumero() + " extraído:");
                System.out.println(">>> Remitente : " + remitente);
                System.out.println(">>> Subject   : [" + asuntoVisible + "]");
                System.out.println(">>> Adjuntos  : " + correo.getAdjuntos().size() + "\n");

                // Procesar el comando en la Base de Datos PostgreSQL (validando permisos del remitente)
                String respuestaHTML = parser.ejecutar(remitente, comandoAsunto, correo.getAdjuntos());
                resultado = GeneradorHTML.resumenResultado(respuestaHTML);

                // Despachar la respuesta por SMTP al remitente
                System.out.println(">>> Enviando respuesta por SMTP a: " + remitente);
                // ClienteSMTP ya antepone "Respuesta ICAP: " al asunto
                // Si el comando pidió un archivo (por ejemplo LISDOC de un documento), va adjunto en la misma respuesta
                envio = smtp.enviarCorreo(remitente, asuntoVisible, respuestaHTML, parser.getAdjuntosRespuesta());
            } catch (RuntimeException e) {
                System.out.println(">>> [ERROR]: " + e + " (detalle en logs/error.log)");
                ArchivoLog.contexto(remitente, asuntoVisible);
                ArchivoLog.error("MainICAP: error al atender el correo", e);
                ArchivoLog.limpiarContexto();
                resultado = "ERROR interno: " + e;
            } finally {
                ArchivoLog.acceso(remitente, asuntoVisible, resultado, envio, System.currentTimeMillis() - inicio);
            }

            // Los avisos a otras personas salen después de la respuesta: el remitente no los espera
            ArchivoLog.contexto(remitente, asuntoVisible);
            try {
                ProcesadorComandos.enviarNotificaciones();
            } catch (RuntimeException e) {
                System.out.println(">>> [ERROR]: " + e + " (detalle en logs/error.log)");
                ArchivoLog.error("MainICAP: error al enviar las notificaciones", e);
            } finally {
                ArchivoLog.limpiarContexto();
            }
        }

        if (!correos.isEmpty()) {
            System.out.println("\n>>> Esperando nuevos correos... (Ctrl+C para detener)");
        }
        return correos.size();
    }

    // java MainICAP 30 -> revisa el buzón cada 30 segundos
    private static int leerIntervalo(String[] args) {
        if (args.length == 0) {
            return INTERVALO_POR_DEFECTO;
        }
        try {
            int segundos = Integer.parseInt(args[0].trim());
            if (segundos > 0) {
                return segundos;
            }
        } catch (NumberFormatException e) {
            // se usa el intervalo por defecto
        }
        System.out.println(">>> Intervalo no válido: " + args[0] + ". Se usan " + INTERVALO_POR_DEFECTO + " s.");
        return INTERVALO_POR_DEFECTO;
    }
}
