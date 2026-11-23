package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import src.modelo.Usuario;
import src.utilidades.Tabla;

public class UsuarioDAO {
    private final Connection con;

    public UsuarioDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Usuario u) throws SQLException {
        String sql = "INSERT INTO USUARIO (usuario, clave, email, estado, id_personal) "
                + "VALUES (?, ?, ?, 'ACTIVO', ?) RETURNING id_usuario";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, u.getUsuario());
            ps.setString(2, u.getClave());
            ps.setString(3, u.getEmail());
            ps.setInt(4, u.getIdPersonal());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                u.setIdUsuario(rs.getInt(1));
                return u.getIdUsuario();
            }
        }
    }

    public Usuario buscarPorEmail(String email) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM USUARIO WHERE LOWER(email) = LOWER(?)")) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Usuario buscarPorId(int idUsuario) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM USUARIO WHERE id_usuario = ?")) {
            ps.setInt(1, idUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Usuario buscarPorUsuario(String usuario) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM USUARIO WHERE LOWER(usuario) = LOWER(?)")) {
            ps.setString(1, usuario.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Usuario buscarPorIdPersonal(int idPersonal) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM USUARIO WHERE id_personal = ?")) {
            ps.setInt(1, idPersonal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // Nombre de usuario a partir del email (jperez@gmail.com -> jperez, jperez2, ...)
    public String generarNombreUsuario(String email) throws SQLException {
        String base = email.substring(0, email.indexOf('@')).toLowerCase().replaceAll("[^a-z0-9._-]", "");
        if (base.isEmpty()) {
            base = "usuario";
        }
        if (base.length() > 45) {
            base = base.substring(0, 45);
        }
        String candidato = base;
        int sufijo = 2;
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM USUARIO WHERE usuario = ?")) {
            while (true) {
                ps.setString(1, candidato);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return candidato;
                    }
                }
                candidato = base + sufijo++;
            }
        }
    }

    public void actualizarEmail(int idUsuario, String email) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE USUARIO SET email = ? WHERE id_usuario = ?")) {
            ps.setString(1, email);
            ps.setInt(2, idUsuario);
            ps.executeUpdate();
        }
    }

    public void actualizarUsuario(int idUsuario, String usuario) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE USUARIO SET usuario = ? WHERE id_usuario = ?")) {
            ps.setString(1, usuario);
            ps.setInt(2, idUsuario);
            ps.executeUpdate();
        }
    }

    // Recibe la clave ya encriptada en MD5
    public void actualizarClave(int idUsuario, String claveMD5) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE USUARIO SET clave = ? WHERE id_usuario = ?")) {
            ps.setString(1, claveMD5);
            ps.setInt(2, idUsuario);
            ps.executeUpdate();
        }
    }

    public void cambiarEstado(int idUsuario, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE USUARIO SET estado = ? WHERE id_usuario = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idUsuario);
            ps.executeUpdate();
        }
    }

    // RBAC: el usuario tiene algún rol con el permiso (nombre del comando)
    public boolean tienePermiso(int idUsuario, String permiso) throws SQLException {
        String sql = "SELECT 1 FROM ROL_USUARIO ru "
                + "JOIN ROL_PERMISO rp ON rp.id_rol = ru.id_rol "
                + "JOIN PERMISO p ON p.id_permiso = rp.id_permiso "
                + "WHERE ru.id_usuario = ? AND UPPER(p.nombre) = UPPER(?) LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idUsuario);
            ps.setString(2, permiso);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Correos de las cuentas ACTIVAS que tienen el rol (destinatarios de avisos automáticos)
    public List<String> correosActivosPorRol(int idRol) throws SQLException {
        String sql = "SELECT u.email FROM USUARIO u JOIN ROL_USUARIO ru ON ru.id_usuario = u.id_usuario "
                + "WHERE ru.id_rol = ? AND u.estado = 'ACTIVO'";
        List<String> correos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idRol);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    correos.add(rs.getString(1));
                }
            }
        }
        return correos;
    }

    public List<String> correosActivosPorRol(String nombreRol) throws SQLException {
        String sql = "SELECT u.email FROM USUARIO u JOIN ROL_USUARIO ru ON ru.id_usuario = u.id_usuario "
                + "JOIN ROL r ON r.id_rol = ru.id_rol WHERE UPPER(r.nombre) = UPPER(?) AND u.estado = 'ACTIVO'";
        List<String> correos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nombreRol);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    correos.add(rs.getString(1));
                }
            }
        }
        return correos;
    }

    // LISUSR: todas las cuentas con su titular, roles y estado
    public Tabla listar(String filtro) throws SQLException {
        String sql = "SELECT u.usuario, u.email, p.ci, p.nombre || ' ' || p.apellidos AS titular, "
                + "COALESCE(string_agg(r.nombre, ', ' ORDER BY r.nombre), '-') AS roles, u.estado "
                + "FROM USUARIO u "
                + "JOIN PERSONAL p ON p.id_personal = u.id_personal "
                + "LEFT JOIN ROL_USUARIO ru ON ru.id_usuario = u.id_usuario "
                + "LEFT JOIN ROL r ON r.id_rol = ru.id_rol "
                + "WHERE ? = '*' OR u.email ILIKE '%' || ? || '%' OR u.usuario ILIKE '%' || ? || '%' OR p.ci = ? "
                + "GROUP BY u.id_usuario, p.id_personal ORDER BY u.id_usuario";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, filtro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Usuario mapear(ResultSet rs) throws SQLException {
        Usuario u = new Usuario(rs.getString("usuario"), rs.getString("clave"), rs.getString("email"),
                rs.getInt("id_personal"));
        u.setIdUsuario(rs.getInt("id_usuario"));
        u.setEstado(rs.getString("estado"));
        return u;
    }
}
