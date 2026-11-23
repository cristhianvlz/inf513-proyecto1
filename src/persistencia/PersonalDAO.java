package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.Personal;
import src.utilidades.Tabla;

public class PersonalDAO {
    // Columnas que MODPER / MODCAP pueden modificar directamente en PERSONAL
    public static final Set<String> CAMPOS = Set.of("nombre", "apellidos", "ci_expedicion", "direccion", "telefono");

    private final Connection con;

    public PersonalDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Personal p) throws SQLException {
        String sql = "INSERT INTO PERSONAL (nombre, apellidos, ci, ci_expedicion, direccion, telefono) "
                + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id_personal";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, p.getNombre());
            ps.setString(2, p.getApellidos());
            ps.setString(3, p.getCi());
            ps.setString(4, p.getCiExpedicion());
            ps.setString(5, p.getDireccion());
            ps.setString(6, p.getTelefono());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                p.setIdPersonal(rs.getInt(1));
                return p.getIdPersonal();
            }
        }
    }

    public Personal buscarPorCi(String ci) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM PERSONAL WHERE ci = ?")) {
            ps.setString(1, ci);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Personal buscarPorId(int idPersonal) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM PERSONAL WHERE id_personal = ?")) {
            ps.setInt(1, idPersonal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // Personal de una cuenta ACTIVA con el rol, que no sea el de la cuenta excluida; el más antiguo. null si no hay.
    // El título de Técnico Auxiliar lo firman dos personas con rol Director: quien lo emite y otra (Coordinador(a) General)
    public Personal conRol(String nombreRol, int idUsuarioExcluido) throws SQLException {
        String sql = "SELECT p.* FROM PERSONAL p JOIN USUARIO u ON u.id_personal = p.id_personal "
                + "JOIN ROL_USUARIO ru ON ru.id_usuario = u.id_usuario JOIN ROL r ON r.id_rol = ru.id_rol "
                + "WHERE UPPER(r.nombre) = UPPER(?) AND u.estado = 'ACTIVO' AND p.estado = 'ACTIVO' AND u.id_usuario <> ? "
                + "ORDER BY u.id_usuario LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nombreRol);
            ps.setInt(2, idUsuarioExcluido);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public boolean esCapacitador(int idPersonal) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM CAPACITOR WHERE id_personal = ?")) {
            ps.setInt(1, idPersonal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void actualizarCampo(int idPersonal, String campo, String valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en PERSONAL: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE PERSONAL SET " + campo + " = ? WHERE id_personal = ?")) {
            ps.setString(1, valor);
            ps.setInt(2, idPersonal);
            ps.executeUpdate();
        }
    }

    public void cambiarEstado(int idPersonal, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE PERSONAL SET estado = ? WHERE id_personal = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idPersonal);
            ps.executeUpdate();
        }
    }

    // LISPER: personal administrativo (sin capacitadores, que se listan con LISCAP)
    public Tabla listar(String filtro) throws SQLException {
        String sql = "SELECT p.ci, p.ci_expedicion AS exp, p.nombre || ' ' || p.apellidos AS nombre_completo, "
                + "p.direccion, p.telefono, u.usuario, u.email, "
                + "COALESCE(string_agg(r.nombre, ', ' ORDER BY r.nombre), '-') AS roles, p.estado "
                + "FROM PERSONAL p "
                + "LEFT JOIN USUARIO u ON u.id_personal = p.id_personal "
                + "LEFT JOIN ROL_USUARIO ru ON ru.id_usuario = u.id_usuario "
                + "LEFT JOIN ROL r ON r.id_rol = ru.id_rol "
                + "WHERE NOT EXISTS (SELECT 1 FROM CAPACITOR c WHERE c.id_personal = p.id_personal) "
                + "AND (? = '*' OR p.ci = ? OR p.nombre ILIKE '%' || ? || '%' OR p.apellidos ILIKE '%' || ? || '%') "
                + "GROUP BY p.id_personal, u.id_usuario ORDER BY p.id_personal";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, filtro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    static Personal mapear(ResultSet rs) throws SQLException {
        Personal p = new Personal(rs.getString("ci"), rs.getString("ci_expedicion"), rs.getString("nombre"),
                rs.getString("apellidos"), rs.getString("direccion"), rs.getString("telefono"));
        p.setIdPersonal(rs.getInt("id_personal"));
        p.setEstado(rs.getString("estado"));
        return p;
    }
}
