package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

// Bitácora de acciones (tabla LOG), consultada en CU-08 con REPLOG
public class LogDAO {
    private final Connection con;

    public LogDAO(Connection con) {
        this.con = con;
    }

    // accion: INSERT, UPDATE, DELETE, SELECT o RECHAZADO
    public void registrar(int idUsuario, String tablaAfectada, String accion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO LOG (tabla_afectada, accion, id_usuario) VALUES (?, ?, ?)")) {
            ps.setString(1, tablaAfectada);
            ps.setString(2, accion);
            ps.setInt(3, idUsuario);
            ps.executeUpdate();
        }
    }
}
