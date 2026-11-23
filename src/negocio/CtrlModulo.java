package src.negocio;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import src.modelo.AsignacionModulo;
import src.modelo.Cursante;
import src.modelo.Modulo;
import src.modelo.OfertaAcademica;
import src.modelo.Usuario;
import src.persistencia.ActaDAO;
import src.persistencia.AsignacionDAO;
import src.persistencia.UsuarioDAO;

// Base de los controladores del CU-05: todo gira alrededor de la asignación de un módulo,
// que en los comandos se identifica por la oferta y el módulo ("TAC", "2-2026", "A", "CONT-1")
abstract class CtrlModulo extends CtrlBase {

    protected static final String ASIGNACION = OFERTA + ", \"modulo\"";

    // Solo el Director tiene este permiso: puede consultar cualquier asignación
    private static final String PERMISO_DE_COORDINACION = "LISACTA";

    protected CtrlModulo(int idRemitente) {
        super(idRemitente);
    }

    protected static final class Asignacion {
        final OfertaAcademica oferta;
        final Modulo modulo;
        final AsignacionModulo datos;

        Asignacion(OfertaAcademica oferta, Modulo modulo, AsignacionModulo datos) {
            this.oferta = oferta;
            this.modulo = modulo;
            this.datos = datos;
        }

        int id() {
            return datos.getIdAsignacion();
        }

        // "CONT-1 de la oferta TAC 2-2026 grupo A"
        String descripcion() {
            return "<b>" + modulo.getCodigo() + "</b> de la " + oferta.getDescripcion();
        }

        // Parámetros que identifican la asignación en los comandos
        String referencia() {
            return oferta.getReferencia() + ", \"" + modulo.getCodigo() + "\"";
        }
    }

    // La asignación se identifica con los parámetros programa, gestión, grupo y módulo a partir de la posición i
    protected Asignacion buscarAsignacion(Connection con, String[] p, int i) throws SQLException {
        OfertaAcademica o = buscarOferta(con, p, i);
        return asignacionDe(con, o, buscarModulo(con, param(p, i + 3)));
    }

    protected Asignacion asignacionDe(Connection con, OfertaAcademica o, Modulo m) throws SQLException {
        AsignacionModulo a = new AsignacionDAO(con).buscar(o.getIdOferta(), m.getIdModulo());
        if (a == null) {
            throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> no está asignado en la " + o.getDescripcion()
                    + ". Consulte <code>LISASIG[" + o.getReferencia() + "]</code>.");
        }
        return new Asignacion(o, m, a);
    }

    // El capacitador opera solo sobre sus propias asignaciones
    protected void validarCapacitador(Connection con, Asignacion a) throws SQLException {
        if (!esSuCapacitador(con, a)) {
            throw new ReglaNegocioException("La asignación de " + a.descripcion() + " no es suya: solo puede operar sobre sus propias asignaciones.");
        }
    }

    // Consultar: su profesor o el Director
    protected void validarConsulta(Connection con, Asignacion a) throws SQLException {
        if (!esSuCapacitador(con, a) && !new UsuarioDAO(con).tienePermiso(idRemitente, PERMISO_DE_COORDINACION)) {
            throw new ReglaNegocioException("La asignación de " + a.descripcion() + " no es suya: solo puede consultar sus propias asignaciones.");
        }
    }

    private boolean esSuCapacitador(Connection con, Asignacion a) throws SQLException {
        Usuario u = new UsuarioDAO(con).buscarPorId(idRemitente);
        return u != null && u.getIdPersonal() == a.datos.getIdPersonal();
    }

    protected void validarActaAbierta(Connection con, Asignacion a) throws SQLException {
        String[] acta = new ActaDAO(con).vigente(a.id());
        if (acta == null || !"ABIERTA".equals(acta[1])) {
            throw new ReglaNegocioException("El acta de " + a.descripcion() + " está cerrada: la corrección requiere una enmienda con resolución.");
        }
    }

    // Último intento del estudiante, por su CI o código. Los recuperatorios se buscan aparte de los demás intentos
    protected static Cursante elegir(List<Cursante> cursantes, String valor, boolean recuperatorio) {
        Cursante elegido = null;
        for (Cursante c : cursantes) {
            if (c.corresponde(valor) && c.esRecuperatorio() == recuperatorio) {
                elegido = c;
            }
        }
        return elegido;
    }
}
