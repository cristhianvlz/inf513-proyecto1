package src.persistencia;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class CalificacionDAO {
    private final Connection con;

    public CalificacionDAO(Connection con) {
        this.con = con;
    }

    public void insertar(int idActividad, int idInscripcionModulo, BigDecimal nota) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO CALIFICACION (nota_obtenida, id_actividad_evaluativa, id_inscripcion_modulo) VALUES (?, ?, ?)")) {
            ps.setBigDecimal(1, nota);
            ps.setInt(2, idActividad);
            ps.setInt(3, idInscripcionModulo);
            ps.executeUpdate();
        }
    }

    public void actualizar(int idActividad, int idInscripcionModulo, BigDecimal nota) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE CALIFICACION SET nota_obtenida = ?, fecha = CURRENT_TIMESTAMP "
                + "WHERE id_actividad_evaluativa = ? AND id_inscripcion_modulo = ?")) {
            ps.setBigDecimal(1, nota);
            ps.setInt(2, idActividad);
            ps.setInt(3, idInscripcionModulo);
            ps.executeUpdate();
        }
    }

    // id_inscripcion_modulo -> (id_actividad -> nota) de toda la asignación
    public Map<Integer, Map<Integer, BigDecimal>> deLaAsignacion(int idAsignacion) throws SQLException {
        String sql = "SELECT c.id_inscripcion_modulo, c.id_actividad_evaluativa, c.nota_obtenida FROM CALIFICACION c "
                + "JOIN ACTIVIDAD_EVALUATIVA a ON a.id_actividad_evaluativa = c.id_actividad_evaluativa WHERE a.id_asignacion_modulo = ?";
        Map<Integer, Map<Integer, BigDecimal>> mapa = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mapa.computeIfAbsent(rs.getInt(1), k -> new HashMap<>()).put(rs.getInt(2), rs.getBigDecimal(3));
                }
            }
        }
        return mapa;
    }
}
