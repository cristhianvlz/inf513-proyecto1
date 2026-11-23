package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import src.modelo.Inscripcion;
import src.utilidades.Tabla;

public class InscripcionDAO {
    private final Connection con;

    public InscripcionDAO(Connection con) {
        this.con = con;
    }

    // La inscripción nace en estado PREINSCRITO
    public int insertar(Inscripcion i) throws SQLException {
        String sql = "INSERT INTO INSCRIPCION (id_estudiante, id_oferta) VALUES (?, ?) RETURNING id_inscripcion";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, i.getIdEstudiante());
            ps.setInt(2, i.getIdOferta());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                i.setIdInscripcion(rs.getInt(1));
                return i.getIdInscripcion();
            }
        }
    }

    // Un estudiante tiene a lo sumo una inscripción por oferta
    public Inscripcion buscar(int idEstudiante, int idOferta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM INSCRIPCION WHERE id_estudiante = ? AND id_oferta = ?")) {
            ps.setInt(1, idEstudiante);
            ps.setInt(2, idOferta);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Inscripcion buscarPorId(int idInscripcion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM INSCRIPCION WHERE id_inscripcion = ?")) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public void cambiarEstado(int idInscripcion, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE INSCRIPCION SET estado = ? WHERE id_inscripcion = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idInscripcion);
            ps.executeUpdate();
        }
    }

    public boolean tienePreinscripcion(int idEstudiante) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM INSCRIPCION WHERE id_estudiante = ? AND estado = 'PREINSCRITO' LIMIT 1")) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Inscripciones INSCRITO del estudiante cuya malla incluye el módulo (INSMOD elige la oferta con esto)
    public List<Inscripcion> inscritasConModulo(int idEstudiante, int idModulo) throws SQLException {
        String sql = "SELECT i.* FROM INSCRIPCION i "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN MODULO_VERSION mv ON mv.id_version = o.id_version AND mv.id_modulo = ? "
                + "WHERE i.id_estudiante = ? AND i.estado = 'INSCRITO' ORDER BY i.id_inscripcion";
        List<Inscripcion> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idModulo);
            ps.setInt(2, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    // Inscripciones a las que se puede cargar un pago. RN-06: la matrícula y la mensualidad solo
    // se pagan con la oferta CONFIRMADA o EN_CURSO y con la inscripción vigente
    public List<Inscripcion> paraPago(int idEstudiante, boolean soloOfertaConfirmada) throws SQLException {
        String sql = "SELECT i.* FROM INSCRIPCION i JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "WHERE i.id_estudiante = ? "
                + (soloOfertaConfirmada ? "AND i.estado IN ('PREINSCRITO','INSCRITO') AND o.estado IN ('CONFIRMADA','EN_CURSO') " : "")
                + "ORDER BY i.id_inscripcion";
        List<Inscripcion> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    // CU-06: todas las inscripciones del estudiante, de la más reciente a la más antigua
    public List<Inscripcion> deEstudiante(int idEstudiante) throws SQLException {
        return lista("SELECT * FROM INSCRIPCION WHERE id_estudiante = ? ORDER BY fecha_inscripcion DESC, id_inscripcion DESC", idEstudiante);
    }

    // LISELEG: inscripciones de la oferta que pueden haber culminado el programa
    public List<Inscripcion> vigentesOConcluidas(int idOferta) throws SQLException {
        return lista("SELECT i.* FROM INSCRIPCION i JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "WHERE i.id_oferta = ? AND i.estado IN ('INSCRITO','CONCLUIDO') ORDER BY e.paterno, e.nombre", idOferta);
    }

    private List<Inscripcion> lista(String sql, int id) throws SQLException {
        List<Inscripcion> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    // ¿Aprobó todos los módulos de la malla de su versión?
    public boolean aproboTodaLaMalla(int idInscripcion) throws SQLException {
        String sql = "SELECT COUNT(*) > 0 AND COUNT(*) = COUNT(aprobado.id_modulo) FROM INSCRIPCION i "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN MODULO_VERSION mv ON mv.id_version = o.id_version "
                + "LEFT JOIN LATERAL (SELECT im.id_modulo FROM INSCRIPCION_MODULO im WHERE im.id_inscripcion = i.id_inscripcion "
                + "  AND im.id_modulo = mv.id_modulo AND im.estado = 'APROBADO' LIMIT 1) aprobado ON TRUE "
                + "WHERE i.id_inscripcion = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    // ASIGCONV y QUICONV (null retira el convenio)
    public void asignarConvenio(int idInscripcion, Integer idConvenio) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE INSCRIPCION SET id_convenio = ? WHERE id_inscripcion = ?")) {
            Sql.asignar(ps, 1, idConvenio);
            ps.setInt(2, idInscripcion);
            ps.executeUpdate();
        }
    }

    // Programas exigidos por el de la oferta que el estudiante no cumple. Se cumple habiendo concluido ese programa
    // en el ICAP o, si es un Técnico Auxiliar, con su título cargado en el expediente. idEstudiante 0 = aún sin expediente
    public List<String> prerrequisitosPendientes(int idEstudiante, int idOferta) throws SQLException {
        String sql = "SELECT pr.codigo || ' - ' || pr.nombre "
                + "FROM OFERTA_ACADEMICA o "
                + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
                + "JOIN PROGRAMA_PRERREQUISITO pp ON pp.id_programa = v.id_programa "
                + "JOIN PROGRAMA pr ON pr.id_programa = pp.id_programa_prerrequisito "
                + "WHERE o.id_oferta = ? "
                + "AND NOT EXISTS (SELECT 1 FROM INSCRIPCION i "
                + "  JOIN OFERTA_ACADEMICA o2 ON o2.id_oferta = i.id_oferta "
                + "  JOIN VERSION_PROGRAMA v2 ON v2.id_version = o2.id_version "
                + "  WHERE i.id_estudiante = ? AND i.estado = 'CONCLUIDO' AND v2.id_programa = pr.id_programa) "
                + "AND NOT (pr.nivel_titulacion = 'TECNICO_AUXILIAR' AND EXISTS (SELECT 1 FROM DOCUMENTO_DIGITAL d "
                + "  WHERE d.id_estudiante = ? AND d.tipo_documento = 'TITULO_TECNICO_AUXILIAR'))";
        List<String> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            ps.setInt(2, idEstudiante);
            ps.setInt(3, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(rs.getString(1));
                }
            }
        }
        return lista;
    }

    // LISINS[programa, gestion, grupo, estado]: con lo que Secretaría necesita para formalizar
    public Tabla listarPorOferta(int idOferta, String estado) throws SQLException {
        String sql = "SELECT e.ci, e.codigo_estudiante AS codigo, "
                + "e.nombre || ' ' || e.paterno || COALESCE(' ' || e.materno, '') AS estudiante, "
                + "e.telefono, e.email, e.ciudad, i.estado, to_char(i.fecha_inscripcion, 'YYYY-MM-DD HH24:MI') AS fecha, "
                + "CASE WHEN EXISTS (SELECT 1 FROM DOCUMENTO_DIGITAL d WHERE d.id_estudiante = e.id_estudiante "
                + "  AND d.tipo_documento = 'CARNET_IDENTIDAD') THEN 'SI' ELSE 'NO' END AS copia_ci, "
                + "CASE WHEN EXISTS (SELECT 1 FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "  WHERE p.id_inscripcion = i.id_inscripcion AND a.concepto_arancel = 'MATRICULA' "
                + "  AND p.estado_conciliacion = 'CONCILIADO') THEN 'CONCILIADA' ELSE 'PENDIENTE' END AS matricula "
                + "FROM INSCRIPCION i JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "WHERE i.id_oferta = ? AND (? = '*' OR i.estado = ?) "
                + "ORDER BY e.paterno, e.nombre";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            ps.setString(2, estado);
            ps.setString(3, estado);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // KARDEX: inscripciones del estudiante
    public Tabla listarPorEstudiante(int idEstudiante) throws SQLException {
        String sql = "SELECT p.codigo AS programa, p.nombre, o.gestion, o.grupo, i.estado, "
                + "to_char(i.fecha_inscripcion, 'YYYY-MM-DD HH24:MI') AS fecha "
                + "FROM INSCRIPCION i "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
                + "JOIN PROGRAMA p ON p.id_programa = v.id_programa "
                + "WHERE i.id_estudiante = ? ORDER BY i.fecha_inscripcion";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Inscripcion mapear(ResultSet rs) throws SQLException {
        Inscripcion i = new Inscripcion(rs.getInt("id_estudiante"), rs.getInt("id_oferta"));
        i.setIdInscripcion(rs.getInt("id_inscripcion"));
        i.setEstado(rs.getString("estado"));
        int convenio = rs.getInt("id_convenio");
        i.setIdConvenio(rs.wasNull() ? null : convenio);
        return i;
    }
}
