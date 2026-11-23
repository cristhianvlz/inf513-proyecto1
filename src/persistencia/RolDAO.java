package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import src.modelo.Rol;
import src.utilidades.Tabla;

public class RolDAO {
    private final Connection con;

    public RolDAO(Connection con) {
        this.con = con;
    }

    public List<Rol> listarRoles() throws SQLException {
        List<Rol> roles = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT id_rol, nombre, descripcion FROM ROL ORDER BY id_rol");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                roles.add(new Rol(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        }
        return roles;
    }

    // Permisos de un rol que no se asigna en ROL_USUARIO (el rol Estudiante: el estudiante no tiene USUARIO)
    public boolean rolTienePermiso(String nombreRol, String permiso) throws SQLException {
        String sql = "SELECT 1 FROM ROL r JOIN ROL_PERMISO rp ON rp.id_rol = r.id_rol JOIN PERMISO p ON p.id_permiso = rp.id_permiso "
                + "WHERE UPPER(r.nombre) = UPPER(?) AND UPPER(p.nombre) = UPPER(?) LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nombreRol);
            ps.setString(2, permiso);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void asignar(int idRol, int idUsuario) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO ROL_USUARIO (id_rol, id_usuario) VALUES (?, ?)")) {
            ps.setInt(1, idRol);
            ps.setInt(2, idUsuario);
            ps.executeUpdate();
        }
    }

    public void quitar(int idRol, int idUsuario) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM ROL_USUARIO WHERE id_rol = ? AND id_usuario = ?")) {
            ps.setInt(1, idRol);
            ps.setInt(2, idUsuario);
            ps.executeUpdate();
        }
    }

    public boolean tieneRol(int idUsuario, int idRol) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM ROL_USUARIO WHERE id_usuario = ? AND id_rol = ?")) {
            ps.setInt(1, idUsuario);
            ps.setInt(2, idRol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int contarRoles(int idUsuario) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM ROL_USUARIO WHERE id_usuario = ?")) {
            ps.setInt(1, idUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // LISROL[email]
    public Tabla rolesDeUsuario(int idUsuario) throws SQLException {
        String sql = "SELECT r.nombre AS rol, r.descripcion, ru.fecha_asignacion "
                + "FROM ROL_USUARIO ru JOIN ROL r ON r.id_rol = ru.id_rol "
                + "WHERE ru.id_usuario = ? ORDER BY ru.fecha_asignacion";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // LISROL["*"]
    public Tabla rolesConPermisos() throws SQLException {
        String sql = "SELECT r.nombre AS rol, r.descripcion, "
                + "COALESCE(string_agg(p.nombre, ', ' ORDER BY p.nombre), '-') AS permisos "
                + "FROM ROL r "
                + "LEFT JOIN ROL_PERMISO rp ON rp.id_rol = r.id_rol "
                + "LEFT JOIN PERMISO p ON p.id_permiso = rp.id_permiso "
                + "GROUP BY r.id_rol ORDER BY r.id_rol";
        try (PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return Tabla.desde(rs);
        }
    }
}
