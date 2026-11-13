package src.persistencia;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// MainICAP atiende un comando a la vez, así que se reutiliza una sola conexión: abrir una contra el servidor
// remoto tarda unos 2 segundos. Quien la usa no la cierra; si estuvo inactiva un rato, se comprueba que siga viva
public class ConexionBD {
    private static final String URL = "jdbc:postgresql://mail.tecnoweb.org.bo:5432/db_grupo15sc";
    private static final String USER = "grupo15sc";
    private static final String PASS = "grup015grup015*";

    private static final long VERIFICAR_TRAS_MS = 30_000;

    private static Connection conexion;
    private static long ultimoUso;

    public static Connection conectar() throws SQLException {
        long ahora = System.currentTimeMillis();
        if (conexion != null && !conexion.isClosed()
                && (ahora - ultimoUso < VERIFICAR_TRAS_MS || conexion.isValid(5))) {
            ultimoUso = ahora;
            return conexion;
        }
        cerrar(conexion);
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No se encontro el driver de PostgreSQL", e);
        }
        conexion = DriverManager.getConnection(URL, USER, PASS);
        ultimoUso = System.currentTimeMillis();
        return conexion;
    }

    public static void cerrar(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                System.err.println("Error al cerrar la conexion: " + e.getMessage());
            }
        }
    }
}
