package src.persistencia;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import src.utilidades.Tabla;

// ENMIENDA_NOTA: auditoría de las correcciones de nota con resolución
public class EnmiendaDAO {
    private static final String LISTADO = "SELECT to_char(en.fecha_enmienda, 'YYYY-MM-DD HH24:MI') AS fecha, e.codigo_estudiante AS codigo, e.ci, "
            + "e.nombre || ' ' || e.paterno AS estudiante, pr.codigo || ' ' || o.gestion || ' ' || o.grupo AS oferta, m.codigo AS modulo, "
            + "en.nota_anterior, en.nota_nueva, en.numero_resolucion AS resolucion, u.usuario AS registrado_por "
            + "FROM ENMIENDA_NOTA en "
            + "JOIN USUARIO u ON u.id_usuario = en.id_usuario "
            + "JOIN INSCRIPCION_MODULO im ON im.id_inscripcion_modulo = en.id_inscripcion_modulo "
            + "JOIN MODULO m ON m.id_modulo = im.id_modulo "
            + "JOIN INSCRIPCION i ON i.id_inscripcion = im.id_inscripcion "
            + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
            + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
            + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
            + "JOIN PROGRAMA pr ON pr.id_programa = v.id_programa ";

    private final Connection con;

    public EnmiendaDAO(Connection con) {
        this.con = con;
    }

    public void insertar(BigDecimal notaAnterior, BigDecimal notaNueva, String resolucion, int idInscripcionModulo, int idUsuario)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO ENMIENDA_NOTA "
                + "(nota_anterior, nota_nueva, numero_resolucion, id_inscripcion_modulo, id_usuario) VALUES (?, ?, ?, ?, ?)")) {
            ps.setBigDecimal(1, notaAnterior);
            ps.setBigDecimal(2, notaNueva);
            ps.setString(3, resolucion);
            ps.setInt(4, idInscripcionModulo);
            ps.setInt(5, idUsuario);
            ps.executeUpdate();
        }
    }

    public Tabla listarPorEstudiante(int idEstudiante) throws SQLException {
        return listar("WHERE e.id_estudiante = ?", idEstudiante);
    }

    public Tabla listarPorAsignacion(int idAsignacion) throws SQLException {
        return listar("JOIN ASIGNACION_MODULO a ON a.id_oferta = i.id_oferta AND a.id_modulo = im.id_modulo WHERE a.id_asignacion_modulo = ?", idAsignacion);
    }

    private Tabla listar(String condicion, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(LISTADO + condicion + " ORDER BY en.id_enmienda")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
