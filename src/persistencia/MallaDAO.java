package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.ModuloVersion;
import src.utilidades.Tabla;

// Malla curricular de una versión (MODULO_VERSION) y sus prerrequisitos (MODULO_VERSION_PRERREQUISITO)
public class MallaDAO {
    // Columnas que MODMALLA puede modificar
    public static final Set<String> CAMPOS = Set.of("orden_modulo", "carga_horaria");

    private final Connection con;

    public MallaDAO(Connection con) {
        this.con = con;
    }

    public int insertar(ModuloVersion mv) throws SQLException {
        String sql = "INSERT INTO MODULO_VERSION (orden_modulo, carga_horaria, id_modulo, id_version) "
                + "VALUES (?, ?, ?, ?) RETURNING id_modulo_version";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, mv.getOrdenModulo());
            ps.setInt(2, mv.getCargaHoraria());
            ps.setInt(3, mv.getIdModulo());
            ps.setInt(4, mv.getIdVersion());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                mv.setIdModuloVersion(rs.getInt(1));
                return mv.getIdModuloVersion();
            }
        }
    }

    public ModuloVersion buscar(int idVersion, int idModulo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM MODULO_VERSION WHERE id_version = ? AND id_modulo = ?")) {
            ps.setInt(1, idVersion);
            ps.setInt(2, idModulo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ModuloVersion mv = new ModuloVersion(rs.getInt("id_version"), rs.getInt("id_modulo"),
                        rs.getInt("orden_modulo"), rs.getInt("carga_horaria"));
                mv.setIdModuloVersion(rs.getInt("id_modulo_version"));
                return mv;
            }
        }
    }

    public boolean ordenOcupado(int idVersion, int orden, int idModuloVersionExcluido) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM MODULO_VERSION WHERE id_version = ? AND orden_modulo = ? AND id_modulo_version <> ?")) {
            ps.setInt(1, idVersion);
            ps.setInt(2, orden);
            ps.setInt(3, idModuloVersionExcluido);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void actualizarCampo(int idModuloVersion, String campo, int valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en MODULO_VERSION: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE MODULO_VERSION SET " + campo + " = ? WHERE id_modulo_version = ?")) {
            ps.setInt(1, valor);
            ps.setInt(2, idModuloVersion);
            ps.executeUpdate();
        }
    }

    // Sus prerrequisitos se borran en cascada
    public void eliminar(int idModuloVersion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM MODULO_VERSION WHERE id_modulo_version = ?")) {
            ps.setInt(1, idModuloVersion);
            ps.executeUpdate();
        }
    }

    public int sumaCargaHoraria(int idVersion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(carga_horaria), 0) FROM MODULO_VERSION WHERE id_version = ?")) {
            ps.setInt(1, idVersion);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public int contarModulos(int idVersion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM MODULO_VERSION WHERE id_version = ?")) {
            ps.setInt(1, idVersion);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void agregarPrerrequisito(int idModuloVersion, int idPrerrequisito) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO MODULO_VERSION_PRERREQUISITO "
                + "(id_modulo_version, id_modulo_version_prerrequisito) VALUES (?, ?)")) {
            ps.setInt(1, idModuloVersion);
            ps.setInt(2, idPrerrequisito);
            ps.executeUpdate();
        }
    }

    public boolean existePrerrequisito(int idModuloVersion, int idPrerrequisito) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM MODULO_VERSION_PRERREQUISITO "
                + "WHERE id_modulo_version = ? AND id_modulo_version_prerrequisito = ?")) {
            ps.setInt(1, idModuloVersion);
            ps.setInt(2, idPrerrequisito);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void quitarPrerrequisito(int idModuloVersion, int idPrerrequisito) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM MODULO_VERSION_PRERREQUISITO "
                + "WHERE id_modulo_version = ? AND id_modulo_version_prerrequisito = ?")) {
            ps.setInt(1, idModuloVersion);
            ps.setInt(2, idPrerrequisito);
            ps.executeUpdate();
        }
    }

    // Mayor orden entre los prerrequisitos del módulo (null si no tiene)
    public Integer maxOrdenPrerrequisitos(int idModuloVersion) throws SQLException {
        return entero("SELECT MAX(mvp.orden_modulo) FROM MODULO_VERSION_PRERREQUISITO p "
                + "JOIN MODULO_VERSION mvp ON mvp.id_modulo_version = p.id_modulo_version_prerrequisito "
                + "WHERE p.id_modulo_version = ?", idModuloVersion);
    }

    // Menor orden entre los módulos que exigen a este como prerrequisito (null si ninguno)
    public Integer minOrdenDependientes(int idModuloVersion) throws SQLException {
        return entero("SELECT MIN(mv.orden_modulo) FROM MODULO_VERSION_PRERREQUISITO p "
                + "JOIN MODULO_VERSION mv ON mv.id_modulo_version = p.id_modulo_version "
                + "WHERE p.id_modulo_version_prerrequisito = ?", idModuloVersion);
    }

    // LISMALLA[id_version]
    public Tabla listarMalla(int idVersion) throws SQLException {
        String sql = "SELECT mv.orden_modulo AS orden, m.codigo, m.nombre AS modulo, mv.carga_horaria, "
                + "COALESCE((SELECT string_agg(mp.codigo, ', ' ORDER BY mvp.orden_modulo) FROM MODULO_VERSION_PRERREQUISITO p "
                + "  JOIN MODULO_VERSION mvp ON mvp.id_modulo_version = p.id_modulo_version_prerrequisito "
                + "  JOIN MODULO mp ON mp.id_modulo = mvp.id_modulo "
                + "  WHERE p.id_modulo_version = mv.id_modulo_version), '-') AS prerrequisitos "
                + "FROM MODULO_VERSION mv JOIN MODULO m ON m.id_modulo = mv.id_modulo "
                + "WHERE mv.id_version = ? ORDER BY mv.orden_modulo";
        return tabla(sql, idVersion);
    }

    // LISPRE[id_version]
    public Tabla listarPrerrequisitos(int idVersion) throws SQLException {
        String sql = "SELECT mv.orden_modulo AS orden, m.codigo AS modulo, m.nombre, "
                + "mp.codigo AS requiere, mp.nombre AS nombre_requisito "
                + "FROM MODULO_VERSION_PRERREQUISITO p "
                + "JOIN MODULO_VERSION mv ON mv.id_modulo_version = p.id_modulo_version "
                + "JOIN MODULO m ON m.id_modulo = mv.id_modulo "
                + "JOIN MODULO_VERSION mvp ON mvp.id_modulo_version = p.id_modulo_version_prerrequisito "
                + "JOIN MODULO mp ON mp.id_modulo = mvp.id_modulo "
                + "WHERE mv.id_version = ? ORDER BY mv.orden_modulo, mvp.orden_modulo";
        return tabla(sql, idVersion);
    }

    private Tabla tabla(String sql, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Integer entero(String sql, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int valor = rs.getInt(1);
                return rs.wasNull() ? null : valor;
            }
        }
    }
}
