package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import src.utilidades.Tabla;

// HISTORIAL_ESTADO: trazabilidad de cada cambio de estado, con el usuario que lo realizó.
// Los cambios de un módulo también se guardan aquí: el módulo se indica en el motivo
public class HistorialDAO {
    private static final int LARGO_MOTIVO = 255;

    private final Connection con;

    public HistorialDAO(Connection con) {
        this.con = con;
    }

    public void registrar(String estadoAnterior, String estadoNuevo, String motivo, int idEstudiante, int idInscripcion,
            int idUsuario) throws SQLException {
        String sql = "INSERT INTO HISTORIAL_ESTADO (estado_anterior, estado_nuevo, motivo, id_estudiante, id_inscripcion, id_usuario) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, estadoAnterior);
            ps.setString(2, estadoNuevo);
            ps.setString(3, motivo != null && motivo.length() > LARGO_MOTIVO ? motivo.substring(0, LARGO_MOTIVO) : motivo);
            ps.setInt(4, idEstudiante);
            ps.setInt(5, idInscripcion);
            ps.setInt(6, idUsuario);
            ps.executeUpdate();
        }
    }

    // Estado que tenía la inscripción antes de su última baja: PREINSCRITO (ANUPRE) o INSCRITO (ESTINS).
    // Filtrar por estos estados deja fuera los cambios de módulo, que pasan de EN_CURSO a ABANDONO
    public String estadoAntesDeLaBaja(int idInscripcion) throws SQLException {
        String sql = "SELECT estado_anterior FROM HISTORIAL_ESTADO WHERE id_inscripcion = ? "
                + "AND estado_anterior IN ('PREINSCRITO','INSCRITO') AND estado_nuevo IN ('RETIRADO','ABANDONO') "
                + "ORDER BY id_historial DESC LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // KARDEX: historial de cambios de estado del estudiante
    public Tabla listarPorEstudiante(int idEstudiante) throws SQLException {
        String sql = "SELECT to_char(h.fecha_cambio, 'YYYY-MM-DD HH24:MI') AS fecha, "
                + "p.codigo || ' ' || o.gestion || ' grupo ' || o.grupo AS oferta, "
                + "COALESCE(h.estado_anterior, '-') AS estado_anterior, h.estado_nuevo, h.motivo, u.usuario AS registrado_por "
                + "FROM HISTORIAL_ESTADO h "
                + "JOIN USUARIO u ON u.id_usuario = h.id_usuario "
                + "LEFT JOIN INSCRIPCION i ON i.id_inscripcion = h.id_inscripcion "
                + "LEFT JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "LEFT JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
                + "LEFT JOIN PROGRAMA p ON p.id_programa = v.id_programa "
                + "WHERE h.id_estudiante = ? ORDER BY h.id_historial";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
