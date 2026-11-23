package src.negocio;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import src.comunicacion.ClienteSMTP;
import src.modelo.Rol;
import src.persistencia.ConexionBD;
import src.persistencia.OfertaDAO;
import src.persistencia.RolDAO;
import src.persistencia.UsuarioDAO;
import src.utilidades.ArchivoLog;
import src.utilidades.GeneradorHTML;
import src.utilidades.RegistroLocal;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// CU-02 Flujo D paso 20: si a 3 días de la fecha de inicio los preinscritos no alcanzan el cupo mínimo,
// avisa al Director para postergar la oferta de 1 a 2 semanas o cancelarla.
// MainICAP lo revisa al iniciar, cada hora y después de procesar comandos; avisa una sola vez por oferta y fecha de inicio
public class AvisoCupoMinimo {

    private final RegistroLocal enviados = new RegistroLocal("avisos_cupo_minimo.txt");

    public void revisar() {
        List<String[]> correos = new ArrayList<>(); // [destinatario, asunto, html]
        List<String> claves = new ArrayList<>();

        try {
            Connection con = ConexionBD.conectar();
            Tabla ofertas = new OfertaDAO(con).ofertasSinCupoMinimo();
            if (ofertas.estaVacia()) {
                return;
            }
            List<String> destinatarios = correosDirector(con);
            if (destinatarios.isEmpty()) {
                System.out.println(">>> [AVISO]: Hay ofertas sin cupo mínimo pero ninguna cuenta activa de Director.");
                return;
            }
            Set<String> yaEnviados = enviados.cargar();
            // Columnas: id_oferta, codigo_programa, programa, gestion, grupo, fecha_inicio, cupo_min, preinscritos
            for (String[] o : ofertas.getFilas()) {
                // Si la oferta se posterga cambia su fecha de inicio y se vuelve a avisar con la nueva fecha
                String clave = o[0] + ":" + o[5];
                if (yaEnviados.contains(clave)) {
                    continue;
                }
                String oferta = o[1] + " " + o[3] + " grupo " + o[4];
                String ref = "\"" + o[1] + "\", \"" + o[3] + "\", \"" + o[4] + "\"";
                String html = GeneradorHTML.mensajeAviso("Oferta sin cupo mínimo",
                        "La oferta <b>" + oferta + "</b> de <b>" + o[2] + "</b> inicia el <b>" + o[5] + "</b> y tiene <b>"
                                + o[7] + "</b> preinscrito(s) de un cupo mínimo de <b>" + o[6] + "</b>.<br>"
                                + "Según el reglamento, puede postergarla de 1 a 2 semanas "
                                + "(<code>MODOFER[" + ref + ", \"estado\", \"POSTERGADA\"]</code> y luego las nuevas fechas) "
                                + "o cancelarla (<code>DELOFER[" + ref + "]</code>).");
                for (String d : destinatarios) {
                    correos.add(new String[] { d, "Oferta " + oferta + " sin cupo minimo", html });
                }
                claves.add(clave);
            }
        } catch (SQLException | IOException e) {
            System.out.println(">>> [AVISO]: No se pudo revisar el cupo mínimo de las ofertas: " + e.getMessage());
            ArchivoLog.error("Aviso de cupo mínimo", e);
            return;
        }

        if (correos.isEmpty()) {
            return;
        }
        ClienteSMTP smtp = new ClienteSMTP();
        for (String[] c : correos) {
            System.out.println(">>> [AVISO]: Oferta sin cupo mínimo, notificando a " + c[0]);
            smtp.enviarCorreo(c[0], c[1], c[2]);
        }
        enviados.agregar(claves);
    }

    private List<String> correosDirector(Connection con) throws SQLException {
        for (Rol r : new RolDAO(con).listarRoles()) {
            if (Validador.normalizar(r.getNombre()).equals("DIRECTOR")) {
                return new UsuarioDAO(con).correosActivosPorRol(r.getIdRol());
            }
        }
        return new ArrayList<>();
    }
}
