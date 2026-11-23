package deploy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Ejecuta un script .sql completo contra la base de icap.properties (para cuando no hay pgAdmin ni psql a mano).
 * Pide confirmación escribiendo SI. Muestra lo que devuelven los SELECT del script.
 *
 * Uso (desde la carpeta del proyecto):
 *   java -cp lib/postgresql-42.7.14.jar deploy/EjecutarSQL.java sql/vaciar_datos.sql
 *   java -cp lib/postgresql-42.7.14.jar deploy/EjecutarSQL.java respaldo/AAAA-MM-DD_HHMM/01_esquema.sql
 */
public class EjecutarSQL {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Uso: java -cp lib/postgresql-42.7.14.jar deploy/EjecutarSQL.java <archivo.sql>");
            return;
        }
        Path archivo = Paths.get(args[0]);
        String script = Files.readString(archivo, StandardCharsets.UTF_8);
        Properties cfg = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream("icap.properties"), StandardCharsets.UTF_8)) {
            cfg.load(r);
        }
        System.out.println("Archivo: " + archivo.toAbsolutePath() + " (" + script.lines().count() + " líneas)");
        System.out.println("Base:    " + cfg.getProperty("db.url") + " (usuario " + cfg.getProperty("db.usuario") + ")");
        System.out.print("Escriba SI para ejecutarlo: ");
        String resp = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (resp == null || !resp.trim().equals("SI")) {
            System.out.println("Cancelado: no se ejecutó nada.");
            return;
        }
        try (Connection c = DriverManager.getConnection(cfg.getProperty("db.url"), cfg.getProperty("db.usuario"), cfg.getProperty("db.clave"));
             Statement st = c.createStatement()) {
            boolean hay = st.execute(script);
            while (true) {
                if (hay) {
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            StringBuilder sb = new StringBuilder("  ");
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                sb.append(i > 1 ? " | " : "").append(md.getColumnName(i)).append("=").append(rs.getString(i));
                            }
                            System.out.println(sb);
                        }
                    }
                } else if (st.getUpdateCount() == -1) {
                    break;
                }
                hay = st.getMoreResults();
            }
            System.out.println("Listo: el script se ejecutó completo.");
        } catch (SQLException e) {
            System.out.println("ERROR: " + e.getMessage());
            System.out.println("El script se detuvo. Lo que estaba entre BEGIN y COMMIT no se guardó.");
        }
    }
}
