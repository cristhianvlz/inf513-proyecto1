package src.persistencia;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import src.modelo.Cursante;
import src.modelo.InscripcionModulo;
import src.modelo.ModuloCursado;
import src.utilidades.Tabla;

public class InscripcionModuloDAO {
    private final Connection con;

    public InscripcionModuloDAO(Connection con) {
        this.con = con;
    }

    public int insertar(InscripcionModulo im) throws SQLException {
        String sql = "INSERT INTO INSCRIPCION_MODULO (numero_intento, condicion, porcentaje_asistencia, id_inscripcion, id_modulo) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id_inscripcion_modulo";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, im.getNumeroIntento());
            ps.setString(2, im.getCondicion());
            Sql.asignar(ps, 3, im.getPorcentajeAsistencia());
            ps.setInt(4, im.getIdInscripcion());
            ps.setInt(5, im.getIdModulo());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                im.setIdInscripcionModulo(rs.getInt(1));
                return im.getIdInscripcionModulo();
            }
        }
    }

    // Todos los intentos del estudiante en el módulo, en cualquiera de sus inscripciones, del más antiguo al más nuevo
    public List<InscripcionModulo> intentos(int idEstudiante, int idModulo) throws SQLException {
        String sql = "SELECT im.* FROM INSCRIPCION_MODULO im JOIN INSCRIPCION i ON i.id_inscripcion = im.id_inscripcion "
                + "WHERE i.id_estudiante = ? AND im.id_modulo = ? ORDER BY im.id_inscripcion_modulo";
        List<InscripcionModulo> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            ps.setInt(2, idModulo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    InscripcionModulo im = new InscripcionModulo();
                    im.setIdInscripcionModulo(rs.getInt("id_inscripcion_modulo"));
                    im.setNumeroIntento(rs.getInt("numero_intento"));
                    im.setCondicion(rs.getString("condicion"));
                    im.setEstado(rs.getString("estado"));
                    im.setNotaFinal(rs.getBigDecimal("nota_final"));
                    im.setPorcentajeAsistencia(rs.getBigDecimal("porcentaje_asistencia"));
                    im.setIdInscripcion(rs.getInt("id_inscripcion"));
                    im.setIdModulo(rs.getInt("id_modulo"));
                    lista.add(im);
                }
            }
        }
        return lista;
    }

    public void cambiarEstado(int idInscripcionModulo, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE INSCRIPCION_MODULO SET estado = ? WHERE id_inscripcion_modulo = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idInscripcionModulo);
            ps.executeUpdate();
        }
    }

    public void guardarResultado(int idInscripcionModulo, BigDecimal notaFinal, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE INSCRIPCION_MODULO SET nota_final = ?, estado = ? WHERE id_inscripcion_modulo = ?")) {
            Sql.asignar(ps, 1, notaFinal);
            ps.setString(2, estado);
            ps.setInt(3, idInscripcionModulo);
            ps.executeUpdate();
        }
    }

    // Los intentos del módulo de una asignación, salvo los abandonados, por estudiante y número de intento
    public List<Cursante> cursantes(int idAsignacion) throws SQLException {
        String sql = "SELECT im.*, e.ci, e.codigo_estudiante, e.nombre || ' ' || e.paterno || COALESCE(' ' || e.materno, '') AS estudiante "
                + "FROM ASIGNACION_MODULO a "
                + "JOIN INSCRIPCION i ON i.id_oferta = a.id_oferta "
                + "JOIN INSCRIPCION_MODULO im ON im.id_inscripcion = i.id_inscripcion AND im.id_modulo = a.id_modulo "
                + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "WHERE a.id_asignacion_modulo = ? AND im.estado <> 'ABANDONO' "
                + "ORDER BY e.paterno, e.nombre, im.numero_intento";
        List<Cursante> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Cursante c = new Cursante();
                    c.setIdInscripcionModulo(rs.getInt("id_inscripcion_modulo"));
                    c.setNumeroIntento(rs.getInt("numero_intento"));
                    c.setCondicion(rs.getString("condicion"));
                    c.setEstado(rs.getString("estado"));
                    c.setNotaFinal(rs.getBigDecimal("nota_final"));
                    c.setPorcentajeAsistencia(rs.getBigDecimal("porcentaje_asistencia"));
                    c.setIdInscripcion(rs.getInt("id_inscripcion"));
                    c.setCi(rs.getString("ci"));
                    c.setCodigo(rs.getString("codigo_estudiante"));
                    c.setNombre(rs.getString("estudiante"));
                    lista.add(c);
                }
            }
        }
        return lista;
    }

    // Módulos EN_CURSO de una inscripción que se da de baja: pasan a ABANDONO. Devuelve sus códigos
    public List<String> abandonarEnCurso(int idInscripcion) throws SQLException {
        String sql = "UPDATE INSCRIPCION_MODULO im SET estado = 'ABANDONO' FROM MODULO m "
                + "WHERE m.id_modulo = im.id_modulo AND im.id_inscripcion = ? AND im.estado = 'EN_CURSO' RETURNING m.codigo";
        List<String> codigos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codigos.add(rs.getString(1));
                }
            }
        }
        return codigos;
    }

    public int contarEnCurso(int idEstudiante) throws SQLException {
        String sql = "SELECT COUNT(*) FROM INSCRIPCION_MODULO im JOIN INSCRIPCION i ON i.id_inscripcion = im.id_inscripcion "
                + "WHERE i.id_estudiante = ? AND im.estado = 'EN_CURSO'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // Prerrequisitos del módulo en esa malla que el estudiante no aprobó con nota >= 51
    public List<String> prerrequisitosPendientes(int idEstudiante, int idModuloVersion) throws SQLException {
        String sql = "SELECT mp.codigo FROM MODULO_VERSION_PRERREQUISITO pr "
                + "JOIN MODULO_VERSION mvp ON mvp.id_modulo_version = pr.id_modulo_version_prerrequisito "
                + "JOIN MODULO mp ON mp.id_modulo = mvp.id_modulo "
                + "WHERE pr.id_modulo_version = ? AND NOT EXISTS (SELECT 1 FROM INSCRIPCION_MODULO im "
                + "  JOIN INSCRIPCION i ON i.id_inscripcion = im.id_inscripcion "
                + "  WHERE i.id_estudiante = ? AND im.id_modulo = mvp.id_modulo AND im.estado = 'APROBADO' AND im.nota_final >= 51) "
                + "ORDER BY mvp.orden_modulo";
        List<String> codigos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idModuloVersion);
            ps.setInt(2, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codigos.add(rs.getString(1));
                }
            }
        }
        return codigos;
    }

    // Módulo EN_CURSO del estudiante que se cruza con el horario de la asignación en día, franja y fechas.
    // Devuelve una descripción del choque o null si no hay
    public String choqueHorario(int idEstudiante, int idAsignacion) throws SQLException {
        String sql = "SELECT m.codigo, h2.dia_semana, to_char(h2.hora_inicio, 'HH24:MI'), to_char(h2.hora_fin, 'HH24:MI') "
                + "FROM ASIGNACION_MODULO a1 "
                + "JOIN HORARIO h1 ON h1.id_asignacion_modulo = a1.id_asignacion_modulo "
                + "JOIN INSCRIPCION i ON i.id_estudiante = ? "
                + "JOIN INSCRIPCION_MODULO im ON im.id_inscripcion = i.id_inscripcion AND im.estado = 'EN_CURSO' "
                + "JOIN ASIGNACION_MODULO a2 ON a2.id_oferta = i.id_oferta AND a2.id_modulo = im.id_modulo "
                + "JOIN HORARIO h2 ON h2.id_asignacion_modulo = a2.id_asignacion_modulo "
                + "JOIN MODULO m ON m.id_modulo = im.id_modulo "
                + "WHERE a1.id_asignacion_modulo = ? AND h1.dia_semana = h2.dia_semana "
                + "AND h1.hora_inicio < h2.hora_fin AND h2.hora_inicio < h1.hora_fin "
                + "AND a1.fecha_inicio <= a2.fecha_fin AND a2.fecha_inicio <= a1.fecha_fin LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            ps.setInt(2, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return "<b>" + rs.getString(1) + "</b> el " + rs.getString(2) + " de " + rs.getString(3) + " a " + rs.getString(4);
            }
        }
    }

    // CU-06: cada módulo de la malla con el intento aprobado de la inscripción o, si no aprobó, el último,
    // y si el acta vigente de su asignación está cerrada (la nota ya es oficial)
    public List<ModuloCursado> malla(int idInscripcion) throws SQLException {
        String sql = "SELECT m.codigo, m.nombre, mv.carga_horaria, im.numero_intento, im.condicion, im.nota_final, "
                + "im.porcentaje_asistencia, im.estado, COALESCE(ac.estado_acta = 'CERRADA', FALSE) AS acta_cerrada, "
                + "COALESCE(a.id_asignacion_modulo, 0) AS id_asignacion "
                + "FROM INSCRIPCION i "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN MODULO_VERSION mv ON mv.id_version = o.id_version "
                + "JOIN MODULO m ON m.id_modulo = mv.id_modulo "
                + "LEFT JOIN LATERAL (SELECT x.* FROM INSCRIPCION_MODULO x WHERE x.id_inscripcion = i.id_inscripcion "
                + "  AND x.id_modulo = mv.id_modulo ORDER BY (x.estado = 'APROBADO') DESC, x.numero_intento DESC LIMIT 1) im ON TRUE "
                + "LEFT JOIN ASIGNACION_MODULO a ON a.id_oferta = i.id_oferta AND a.id_modulo = mv.id_modulo "
                + "LEFT JOIN LATERAL (SELECT y.estado_acta FROM ACTA_CALIFICACION y WHERE y.id_asignacion_modulo = a.id_asignacion_modulo "
                + "  AND y.estado_acta <> 'ANULADA' ORDER BY y.id_acta DESC LIMIT 1) ac ON TRUE "
                + "WHERE i.id_inscripcion = ? ORDER BY mv.orden_modulo";
        List<ModuloCursado> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ModuloCursado mc = new ModuloCursado();
                    mc.setCodigo(rs.getString("codigo"));
                    mc.setNombre(rs.getString("nombre"));
                    mc.setCargaHoraria(rs.getInt("carga_horaria"));
                    int intento = rs.getInt("numero_intento");
                    mc.setIntento(rs.wasNull() ? null : intento);
                    mc.setCondicion(rs.getString("condicion"));
                    mc.setNotaFinal(rs.getBigDecimal("nota_final"));
                    mc.setAsistencia(rs.getBigDecimal("porcentaje_asistencia"));
                    mc.setEstado(rs.getString("estado"));
                    mc.setActaCerrada(rs.getBoolean("acta_cerrada"));
                    mc.setIdAsignacion(rs.getInt("id_asignacion"));
                    lista.add(mc);
                }
            }
        }
        return lista;
    }

    // KARDEX: módulos, intentos, notas, asistencia y estados
    public Tabla kardex(int idEstudiante) throws SQLException {
        String sql = "SELECT p.codigo AS programa, o.gestion, o.grupo, m.codigo AS modulo, m.nombre, "
                + "im.numero_intento AS intento, im.condicion, im.nota_final, "
                + "im.porcentaje_asistencia AS asistencia, im.estado "
                + "FROM INSCRIPCION_MODULO im "
                + "JOIN INSCRIPCION i ON i.id_inscripcion = im.id_inscripcion "
                + "JOIN MODULO m ON m.id_modulo = im.id_modulo "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
                + "JOIN PROGRAMA p ON p.id_programa = v.id_programa "
                + "WHERE i.id_estudiante = ? ORDER BY i.fecha_inscripcion, m.codigo, im.numero_intento";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
