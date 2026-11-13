package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import src.utilidades.Tabla;

// ACTA_CALIFICACION: una asignación tiene un acta vigente; una enmienda la anula y emite otra
public class ActaDAO {
    private final Connection con;

    public ActaDAO(Connection con) {
        this.con = con;
    }

    // [id_acta, estado_acta] del acta vigente (la última no anulada); null si no hay
    public String[] vigente(int idAsignacion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT id_acta, estado_acta FROM ACTA_CALIFICACION "
                + "WHERE id_asignacion_modulo = ? AND estado_acta <> 'ANULADA' ORDER BY id_acta DESC LIMIT 1")) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[] { rs.getString(1), rs.getString(2) } : null;
            }
        }
    }

    public void cerrar(int idActa) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ACTA_CALIFICACION SET estado_acta = 'CERRADA', fecha_cierre = CURRENT_DATE WHERE id_acta = ?")) {
            ps.setInt(1, idActa);
            ps.executeUpdate();
        }
    }

    public void anular(int idActa) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE ACTA_CALIFICACION SET estado_acta = 'ANULADA' WHERE id_acta = ?")) {
            ps.setInt(1, idActa);
            ps.executeUpdate();
        }
    }

    // El acta que reemplaza a una anulada por enmienda ya nace cerrada: solo falta la firma del capacitador
    public int emitirCerrada(int idAsignacion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO ACTA_CALIFICACION (estado_acta, fecha_cierre, id_asignacion_modulo) "
                + "VALUES ('CERRADA', CURRENT_DATE, ?) RETURNING id_acta")) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // Asignaciones con el acta ABIERTA cuyo módulo terminó hace 3 días o más (cierre automático a las 72 horas)
    public List<Integer> asignacionesPorCerrar() throws SQLException {
        String sql = "SELECT a.id_asignacion_modulo FROM ACTA_CALIFICACION ac "
                + "JOIN ASIGNACION_MODULO a ON a.id_asignacion_modulo = ac.id_asignacion_modulo "
                + "WHERE ac.estado_acta = 'ABIERTA' AND a.fecha_fin + 3 <= CURRENT_DATE";
        List<Integer> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(rs.getInt(1));
            }
        }
        return lista;
    }

    // LISACTA: también las anuladas
    public Tabla listar(int idAsignacion) throws SQLException {
        String sql = "SELECT id_acta AS numero_de_acta, estado_acta AS estado, fecha_cierre FROM ACTA_CALIFICACION "
                + "WHERE id_asignacion_modulo = ? ORDER BY id_acta";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
