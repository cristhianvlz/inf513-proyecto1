package src.negocio;

import java.sql.Connection;
import java.sql.SQLException;
import src.persistencia.ActaDAO;
import src.persistencia.ConexionBD;
import src.utilidades.ArchivoLog;

// CU-05: el acta se cierra automáticamente 72 horas después del fin del módulo si nadie la cerró antes.
// MainICAP lo revisa al iniciar, cada hora y después de procesar comandos. No escribe en LOG: lo hace el sistema, no un usuario
public class CierreActas {

    public void revisar() {
        try {
            Connection con = ConexionBD.conectar();
            for (int idAsignacion : new ActaDAO(con).asignacionesPorCerrar()) {
                // Un acta con problemas no impide cerrar las demás
                try {
                    cerrar(con, idAsignacion);
                } catch (SQLException e) {
                    System.out.println(">>> [ACTAS]: No se pudo cerrar el acta de la asignación " + idAsignacion + ": " + e.getMessage());
                    ArchivoLog.error("Cierre automático de actas: asignación " + idAsignacion, e);
                }
            }
        } catch (SQLException e) {
            System.out.println(">>> [ACTAS]: No se pudo revisar el cierre de actas: " + e.getMessage());
            ArchivoLog.error("Cierre automático de actas", e);
        }
    }

    private void cerrar(Connection con, int idAsignacion) throws SQLException {
        con.setAutoCommit(false);
        try {
            String[] acta = new ActaDAO(con).vigente(idAsignacion);
            new ResultadosModulo(con, 0).cerrarActa(idAsignacion, Integer.parseInt(acta[0]));
            con.commit();
            System.out.println(">>> [ACTAS]: Se cerró automáticamente el acta N° " + acta[0] + ".");
        } finally {
            // La conexión se reutiliza: lo que no se confirmó se deshace antes de volver a autocommit
            con.rollback();
            con.setAutoCommit(true);
        }
    }
}
