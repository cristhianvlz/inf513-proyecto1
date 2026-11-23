package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import src.modelo.Horario;
import src.utilidades.Tabla;

// HORARIO semanal y las SESION_CLASE que se generan a partir de él
public class HorarioDAO {
    // Posición ISO (1 = lunes) del día guardado en HORARIO.dia_semana
    private static final String DIA_ISO = "array_position(ARRAY['LUNES','MARTES','MIERCOLES','JUEVES','VIERNES',"
            + "'SABADO','DOMINGO'], h.dia_semana::text)";

    private final Connection con;

    public HorarioDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Horario h) throws SQLException {
        String sql = "INSERT INTO HORARIO (dia_semana, hora_inicio, hora_fin, aula, id_asignacion_modulo) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id_horario";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, h.getDiaSemana());
            Sql.asignar(ps, 2, h.getHoraInicio());
            Sql.asignar(ps, 3, h.getHoraFin());
            ps.setString(4, h.getAula());
            ps.setInt(5, h.getIdAsignacion());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                h.setIdHorario(rs.getInt(1));
                return h.getIdHorario();
            }
        }
    }

    // Un módulo tiene a lo sumo un horario por día: en los comandos se identifica por la asignación y el día
    public Horario buscar(int idAsignacion, String diaSemana) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM HORARIO WHERE id_asignacion_modulo = ? AND dia_semana = ?")) {
            ps.setInt(1, idAsignacion);
            ps.setString(2, diaSemana);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public List<Horario> listarPorAsignacion(int idAsignacion) throws SQLException {
        List<Horario> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM HORARIO WHERE id_asignacion_modulo = ?")) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    public void actualizar(Horario h) throws SQLException {
        String sql = "UPDATE HORARIO SET dia_semana = ?, hora_inicio = ?, hora_fin = ?, aula = ? WHERE id_horario = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, h.getDiaSemana());
            Sql.asignar(ps, 2, h.getHoraInicio());
            Sql.asignar(ps, 3, h.getHoraFin());
            ps.setString(4, h.getAula());
            ps.setInt(5, h.getIdHorario());
            ps.executeUpdate();
        }
    }

    public void eliminar(int idHorario) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM HORARIO WHERE id_horario = ?")) {
            ps.setInt(1, idHorario);
            ps.executeUpdate();
        }
    }

    // Otro horario del mismo capacitador que se cruza en día, franja y fechas de asignación.
    // Devuelve una descripción del choque o null si no hay
    public String choqueCapacitador(Horario h, int idPersonal, LocalDate desde, LocalDate hasta) throws SQLException {
        return buscarChoque("am.id_personal = ?", idPersonal, h, desde, hasta);
    }

    // Otro horario en la misma aula y lugar que se cruza en día, franja y fechas
    public String choqueAula(Horario h, String lugar, LocalDate desde, LocalDate hasta) throws SQLException {
        if (h.getAula() == null) {
            return null;
        }
        return buscarChoque("UPPER(h.aula) = UPPER(?) AND UPPER(o.lugar) = UPPER(?)", new String[] { h.getAula(), lugar },
                h, desde, hasta);
    }

    private String buscarChoque(String condicion, Object valor, Horario h, LocalDate desde, LocalDate hasta)
            throws SQLException {
        String sql = "SELECT m.codigo, p.codigo || ' ' || o.gestion || ' grupo ' || o.grupo, h.dia_semana, "
                + "to_char(h.hora_inicio, 'HH24:MI'), to_char(h.hora_fin, 'HH24:MI'), h.aula "
                + "FROM HORARIO h "
                + "JOIN ASIGNACION_MODULO am ON am.id_asignacion_modulo = h.id_asignacion_modulo "
                + "JOIN MODULO m ON m.id_modulo = am.id_modulo "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = am.id_oferta "
                + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
                + "JOIN PROGRAMA p ON p.id_programa = v.id_programa "
                + "WHERE h.id_horario <> ? AND o.estado <> 'CANCELADA' AND h.dia_semana = ? "
                + "AND h.hora_inicio < ? AND h.hora_fin > ? "
                + "AND am.fecha_inicio <= ? AND am.fecha_fin >= ? "
                + "AND " + condicion + " LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, h.getIdHorario());
            ps.setString(i++, h.getDiaSemana());
            Sql.asignar(ps, i++, h.getHoraFin());
            Sql.asignar(ps, i++, h.getHoraInicio());
            Sql.asignar(ps, i++, hasta);
            Sql.asignar(ps, i++, desde);
            if (valor instanceof Object[]) {
                for (Object v : (Object[]) valor) {
                    Sql.asignar(ps, i++, v);
                }
            } else {
                Sql.asignar(ps, i, valor);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return "módulo <b>" + rs.getString(1) + "</b> de la oferta <b>" + rs.getString(2) + "</b> el "
                        + rs.getString(3) + " de " + rs.getString(4) + " a " + rs.getString(5)
                        + (rs.getString(6) != null ? " en " + rs.getString(6) : "");
            }
        }
    }

    // Crea una SESION_CLASE PROGRAMADA por cada fecha del día del horario entre 'desde' y 'hasta'
    public int generarSesiones(Horario h, LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "INSERT INTO SESION_CLASE (fecha, hora_inicio, hora_fin, id_asignacion_modulo) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
        int creadas = 0;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (LocalDate d = desde.with(TemporalAdjusters.nextOrSame(h.getDiaDeLaSemana())); !d.isAfter(hasta); d = d
                    .plusWeeks(1)) {
                Sql.asignar(ps, 1, d);
                Sql.asignar(ps, 2, h.getHoraInicio());
                Sql.asignar(ps, 3, h.getHoraFin());
                ps.setInt(4, h.getIdAsignacion());
                ps.addBatch();
            }
            for (int n : ps.executeBatch()) {
                creadas += Math.max(n, 0);
            }
        }
        return creadas;
    }

    // Borra las sesiones de ese horario aún no dictadas (desde hoy y en estado PROGRAMADA).
    // SESION_CLASE no guarda el horario: se identifica por asignación, día de la semana y hora de inicio
    public int eliminarSesionesFuturas(Horario h) throws SQLException {
        String sql = "DELETE FROM SESION_CLASE WHERE id_asignacion_modulo = ? AND EXTRACT(ISODOW FROM fecha) = ? "
                + "AND hora_inicio = ? AND fecha >= CURRENT_DATE AND estado = 'PROGRAMADA'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, h.getIdAsignacion());
            ps.setInt(2, h.getDiaDeLaSemana().getValue());
            Sql.asignar(ps, 3, h.getHoraInicio());
            return ps.executeUpdate();
        }
    }

    // LISHOR[programa, gestion, grupo]: horario semanal de la oferta con sus sesiones generadas
    public Tabla listarPorOferta(int idOferta) throws SQLException {
        String sql = "SELECT m.codigo AS modulo, "
                + "p.nombre || ' ' || p.apellidos AS profesor, h.dia_semana AS dia, "
                + "to_char(h.hora_inicio, 'HH24:MI') AS inicio, to_char(h.hora_fin, 'HH24:MI') AS fin, h.aula, "
                + "(SELECT COUNT(*) FROM SESION_CLASE s WHERE s.id_asignacion_modulo = am.id_asignacion_modulo "
                + "  AND EXTRACT(ISODOW FROM s.fecha) = " + DIA_ISO + " AND s.hora_inicio = h.hora_inicio) AS sesiones "
                + "FROM HORARIO h "
                + "JOIN ASIGNACION_MODULO am ON am.id_asignacion_modulo = h.id_asignacion_modulo "
                + "JOIN MODULO m ON m.id_modulo = am.id_modulo "
                + "JOIN PERSONAL p ON p.id_personal = am.id_personal "
                + "WHERE am.id_oferta = ? ORDER BY " + DIA_ISO + ", h.hora_inicio";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Horario mapear(ResultSet rs) throws SQLException {
        Horario h = new Horario();
        h.setIdHorario(rs.getInt("id_horario"));
        h.setDiaSemana(rs.getString("dia_semana"));
        h.setHoraInicio(rs.getTime("hora_inicio").toLocalTime());
        h.setHoraFin(rs.getTime("hora_fin").toLocalTime());
        h.setAula(rs.getString("aula"));
        h.setIdAsignacion(rs.getInt("id_asignacion_modulo"));
        return h;
    }
}
