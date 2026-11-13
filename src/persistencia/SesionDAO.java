package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import src.modelo.SesionClase;
import src.utilidades.Tabla;

// SESION_CLASE: las clases de una asignación (las genera ADDHOR; ADDSES agrega las de recuperación)
public class SesionDAO {
    private final Connection con;

    public SesionDAO(Connection con) {
        this.con = con;
    }

    public int insertarRecuperacion(SesionClase s) throws SQLException {
        String sql = "INSERT INTO SESION_CLASE (fecha, hora_inicio, hora_fin, estado, id_asignacion_modulo) "
                + "VALUES (?, ?, ?, 'RECUPERADA', ?) RETURNING id_sesion";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Sql.asignar(ps, 1, s.getFecha());
            Sql.asignar(ps, 2, s.getHoraInicio());
            Sql.asignar(ps, 3, s.getHoraFin());
            ps.setInt(4, s.getIdAsignacion());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                s.setIdSesion(rs.getInt(1));
                return s.getIdSesion();
            }
        }
    }

    // En los comandos la sesión se identifica por su fecha; la hora solo hace falta si ese día hubo dos clases
    public List<SesionClase> buscar(int idAsignacion, LocalDate fecha, LocalTime hora) throws SQLException {
        String sql = "SELECT * FROM SESION_CLASE WHERE id_asignacion_modulo = ? AND fecha = ?"
                + (hora != null ? " AND hora_inicio = ?" : "") + " ORDER BY hora_inicio";
        List<SesionClase> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            Sql.asignar(ps, 2, fecha);
            if (hora != null) {
                Sql.asignar(ps, 3, hora);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    public List<SesionClase> listarModelos(int idAsignacion) throws SQLException {
        List<SesionClase> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM SESION_CLASE WHERE id_asignacion_modulo = ? ORDER BY fecha, hora_inicio")) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    public void cambiarEstado(int idSesion, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE SESION_CLASE SET estado = ? WHERE id_sesion = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idSesion);
            ps.executeUpdate();
        }
    }

    // LISSES
    public Tabla listar(int idAsignacion) throws SQLException {
        String sql = "SELECT s.fecha, to_char(s.hora_inicio, 'HH24:MI') AS inicio, to_char(s.hora_fin, 'HH24:MI') AS fin, s.estado, "
                + "(SELECT COUNT(*) FROM ASISTENCIA a WHERE a.id_sesion = s.id_sesion) AS asistencias_registradas "
                + "FROM SESION_CLASE s WHERE s.id_asignacion_modulo = ? ORDER BY s.fecha, s.hora_inicio";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private SesionClase mapear(ResultSet rs) throws SQLException {
        SesionClase s = new SesionClase();
        s.setIdSesion(rs.getInt("id_sesion"));
        s.setFecha(Sql.fecha(rs.getDate("fecha")));
        s.setHoraInicio(rs.getTime("hora_inicio").toLocalTime());
        s.setHoraFin(rs.getTime("hora_fin").toLocalTime());
        s.setEstado(rs.getString("estado"));
        s.setIdAsignacion(rs.getInt("id_asignacion_modulo"));
        return s;
    }
}
