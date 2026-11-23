package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class AsistenciaDAO {
    private final Connection con;

    public AsistenciaDAO(Connection con) {
        this.con = con;
    }

    public void insertar(int idSesion, int idInscripcionModulo, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO ASISTENCIA (estado, id_sesion, id_inscripcion_modulo) VALUES (?, ?, ?)")) {
            ps.setString(1, estado);
            ps.setInt(2, idSesion);
            ps.setInt(3, idInscripcionModulo);
            ps.executeUpdate();
        }
    }

    public void actualizar(int idSesion, int idInscripcionModulo, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ASISTENCIA SET estado = ? WHERE id_sesion = ? AND id_inscripcion_modulo = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idSesion);
            ps.setInt(3, idInscripcionModulo);
            ps.executeUpdate();
        }
    }

    // id_inscripcion_modulo -> estado registrado en esa sesión
    public Map<Integer, String> deLaSesion(int idSesion) throws SQLException {
        Map<Integer, String> mapa = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT id_inscripcion_modulo, estado FROM ASISTENCIA WHERE id_sesion = ?")) {
            ps.setInt(1, idSesion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mapa.put(rs.getInt(1), rs.getString(2));
                }
            }
        }
        return mapa;
    }

    // id_inscripcion_modulo -> (id_sesion -> estado) de toda la asignación, para la planilla
    public Map<Integer, Map<Integer, String>> deLaAsignacion(int idAsignacion) throws SQLException {
        String sql = "SELECT a.id_inscripcion_modulo, a.id_sesion, a.estado FROM ASISTENCIA a "
                + "JOIN SESION_CLASE s ON s.id_sesion = a.id_sesion WHERE s.id_asignacion_modulo = ?";
        Map<Integer, Map<Integer, String>> mapa = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mapa.computeIfAbsent(rs.getInt(1), k -> new HashMap<>()).put(rs.getInt(2), rs.getString(3));
                }
            }
        }
        return mapa;
    }

    // Licencias del estudiante en la materia, sin contar la sesión indicada (0 = ninguna)
    public int contarLicencias(int idInscripcionModulo, int idSesionExcluida) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM ASISTENCIA "
                + "WHERE id_inscripcion_modulo = ? AND estado = 'LICENCIA' AND id_sesion <> ?")) {
            ps.setInt(1, idInscripcionModulo);
            ps.setInt(2, idSesionExcluida);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // Porcentaje sobre las clases que se llevaron a cabo. Una licencia o una falta justificada cuentan como asistencia;
    // sin registro cuenta como ausencia. El intento de recuperatorio conserva el porcentaje del módulo que cursó
    public void recalcularPorcentajes(int idAsignacion) throws SQLException {
        String dadas = "SELECT id_sesion FROM SESION_CLASE WHERE id_asignacion_modulo = ? "
                + "AND estado IN ('DICTADA','RECUPERADA') AND fecha <= CURRENT_DATE";
        String sql = "UPDATE INSCRIPCION_MODULO im SET porcentaje_asistencia = "
                + "CASE WHEN (SELECT COUNT(*) FROM (" + dadas + ") d) = 0 THEN NULL ELSE ROUND(100.0 * "
                + "(SELECT COUNT(*) FROM ASISTENCIA x WHERE x.id_inscripcion_modulo = im.id_inscripcion_modulo "
                + "  AND x.estado IN ('PRESENTE','LICENCIA','JUSTIFICADO') AND x.id_sesion IN (" + dadas + ")) "
                + "/ (SELECT COUNT(*) FROM (" + dadas + ") d), 2) END "
                + "FROM INSCRIPCION i, ASIGNACION_MODULO a "
                + "WHERE i.id_inscripcion = im.id_inscripcion AND a.id_oferta = i.id_oferta AND a.id_modulo = im.id_modulo "
                + "AND a.id_asignacion_modulo = ? AND im.condicion <> 'RECUPERATORIO'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setInt(i, idAsignacion);
            }
            ps.executeUpdate();
        }
    }
}
