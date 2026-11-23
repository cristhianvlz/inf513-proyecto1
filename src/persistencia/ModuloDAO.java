package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.Modulo;
import src.utilidades.Tabla;

public class ModuloDAO {
    // Columnas que MODMOD puede modificar
    public static final Set<String> CAMPOS = Set.of("codigo", "nombre");

    private final Connection con;

    public ModuloDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Modulo m) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO MODULO (codigo, nombre) VALUES (?, ?) RETURNING id_modulo")) {
            ps.setString(1, m.getCodigo());
            ps.setString(2, m.getNombre());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                m.setIdModulo(rs.getInt(1));
                return m.getIdModulo();
            }
        }
    }

    public Modulo buscarPorCodigo(String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM MODULO WHERE UPPER(codigo) = UPPER(?)")) {
            ps.setString(1, codigo.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Modulo m = new Modulo(rs.getString("codigo"), rs.getString("nombre"));
                m.setIdModulo(rs.getInt("id_modulo"));
                return m;
            }
        }
    }

    public void actualizarCampo(int idModulo, String campo, String valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en MODULO: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE MODULO SET " + campo + " = ? WHERE id_modulo = ?")) {
            ps.setString(1, valor);
            ps.setInt(2, idModulo);
            ps.executeUpdate();
        }
    }

    // DELMOD solo procede si el módulo no pertenece a ninguna malla ni tiene asignaciones
    public boolean estaEnUso(int idModulo) throws SQLException {
        String sql = "SELECT 1 FROM MODULO_VERSION WHERE id_modulo = ? "
                + "UNION ALL SELECT 1 FROM ASIGNACION_MODULO WHERE id_modulo = ? LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idModulo);
            ps.setInt(2, idModulo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void eliminar(int idModulo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM MODULO WHERE id_modulo = ?")) {
            ps.setInt(1, idModulo);
            ps.executeUpdate();
        }
    }

    // LISMOD["*" | codigo | nombre]: indica en qué versiones participa cada módulo
    public Tabla listar(String filtro) throws SQLException {
        String sql = "SELECT m.codigo, m.nombre, "
                + "COALESCE(string_agg(p.codigo || ' ' || v.numero_version || ' [' || v.estado || ']', ', ' "
                + "  ORDER BY p.codigo, v.numero_version), '-') AS versiones "
                + "FROM MODULO m "
                + "LEFT JOIN MODULO_VERSION mv ON mv.id_modulo = m.id_modulo "
                + "LEFT JOIN VERSION_PROGRAMA v ON v.id_version = mv.id_version "
                + "LEFT JOIN PROGRAMA p ON p.id_programa = v.id_programa "
                + "WHERE ? = '*' OR m.codigo ILIKE ? OR m.nombre ILIKE '%' || ? || '%' "
                + "GROUP BY m.id_modulo ORDER BY m.codigo";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 3; i++) {
                ps.setString(i, filtro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
