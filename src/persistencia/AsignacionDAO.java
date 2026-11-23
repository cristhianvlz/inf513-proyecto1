package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import src.modelo.AsignacionModulo;
import src.utilidades.Tabla;

// ASIGNACION_MODULO y su ACTA_CALIFICACION
public class AsignacionDAO {
    // Columnas que MODASIG puede modificar
    public static final Set<String> CAMPOS = Set.of("fecha_inicio", "fecha_fin", "programa_analitico",
            "propuesta_tecnica", "id_personal");

    private final Connection con;

    public AsignacionDAO(Connection con) {
        this.con = con;
    }

    public int insertar(AsignacionModulo a) throws SQLException {
        String sql = "INSERT INTO ASIGNACION_MODULO (fecha_inicio, fecha_fin, programa_analitico, propuesta_tecnica, "
                + "id_oferta, id_modulo, id_personal) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id_asignacion_modulo";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Sql.asignar(ps, 1, a.getFechaInicio());
            Sql.asignar(ps, 2, a.getFechaFin());
            ps.setString(3, a.getProgramaAnalitico());
            ps.setString(4, a.getPropuestaTecnica());
            ps.setInt(5, a.getIdOferta());
            ps.setInt(6, a.getIdModulo());
            ps.setInt(7, a.getIdPersonal());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                a.setIdAsignacion(rs.getInt(1));
                return a.getIdAsignacion();
            }
        }
    }

    // Al asignar el módulo se crea su acta en estado ABIERTA
    public int crearActa(int idAsignacion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO ACTA_CALIFICACION (id_asignacion_modulo) VALUES (?) RETURNING id_acta")) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // Un módulo se asigna una sola vez por oferta: en los comandos se identifica por la oferta y el código del módulo
    public AsignacionModulo buscar(int idOferta, int idModulo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM ASIGNACION_MODULO WHERE id_oferta = ? AND id_modulo = ?")) {
            ps.setInt(1, idOferta);
            ps.setInt(2, idModulo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public List<AsignacionModulo> listarModelos(int idOferta) throws SQLException {
        List<AsignacionModulo> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM ASIGNACION_MODULO WHERE id_oferta = ?")) {
            ps.setInt(1, idOferta);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    public void actualizarCampo(int idAsignacion, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en ASIGNACION_MODULO: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ASIGNACION_MODULO SET " + campo + " = ? WHERE id_asignacion_modulo = ?")) {
            Sql.asignar(ps, 1, valor);
            ps.setInt(2, idAsignacion);
            ps.executeUpdate();
        }
    }

    // Un módulo se puede cursar o abandonar mientras su acta siga ABIERTA
    public boolean actaAbierta(int idAsignacion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM ACTA_CALIFICACION "
                + "WHERE id_asignacion_modulo = ? AND estado_acta = 'ABIERTA' LIMIT 1")) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // DELASIG solo procede si ningún estudiante está inscrito en ese módulo de la oferta
    public boolean tieneEstudiantes(int idAsignacion) throws SQLException {
        String sql = "SELECT 1 FROM ASIGNACION_MODULO am "
                + "JOIN INSCRIPCION i ON i.id_oferta = am.id_oferta "
                + "JOIN INSCRIPCION_MODULO im ON im.id_inscripcion = i.id_inscripcion AND im.id_modulo = am.id_modulo "
                + "WHERE am.id_asignacion_modulo = ? LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Horarios, sesiones y acta se borran en cascada
    public void eliminar(int idAsignacion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM ASIGNACION_MODULO WHERE id_asignacion_modulo = ?")) {
            ps.setInt(1, idAsignacion);
            ps.executeUpdate();
        }
    }

    // LISASIG[programa, gestion, grupo]
    public Tabla listarPorOferta(int idOferta) throws SQLException {
        String sql = "SELECT m.codigo AS modulo, m.nombre, "
                + "p.ci AS ci_profesor, p.nombre || ' ' || p.apellidos AS profesor, "
                + "am.fecha_inicio, am.fecha_fin, am.programa_analitico, am.propuesta_tecnica, "
                + "(SELECT ac.estado_acta FROM ACTA_CALIFICACION ac WHERE ac.id_asignacion_modulo = am.id_asignacion_modulo "
                + "  ORDER BY ac.id_acta DESC LIMIT 1) AS acta "
                + "FROM ASIGNACION_MODULO am "
                + "JOIN MODULO m ON m.id_modulo = am.id_modulo "
                + "JOIN PERSONAL p ON p.id_personal = am.id_personal "
                + "WHERE am.id_oferta = ? ORDER BY am.fecha_inicio, m.codigo";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private AsignacionModulo mapear(ResultSet rs) throws SQLException {
        AsignacionModulo a = new AsignacionModulo();
        a.setIdAsignacion(rs.getInt("id_asignacion_modulo"));
        a.setFechaInicio(Sql.fecha(rs.getDate("fecha_inicio")));
        a.setFechaFin(Sql.fecha(rs.getDate("fecha_fin")));
        a.setProgramaAnalitico(rs.getString("programa_analitico"));
        a.setPropuestaTecnica(rs.getString("propuesta_tecnica"));
        a.setIdOferta(rs.getInt("id_oferta"));
        a.setIdModulo(rs.getInt("id_modulo"));
        a.setIdPersonal(rs.getInt("id_personal"));
        return a;
    }
}
