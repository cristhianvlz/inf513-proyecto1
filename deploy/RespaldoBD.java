package deploy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Respaldo de la base del ICAP en scripts SQL, sin pg_dump. Solo lee: abre la sesión en modo READ ONLY.
 *
 * Uso (desde la carpeta del proyecto):
 *   java -cp lib/postgresql-42.7.14.jar deploy/RespaldoBD.java [correo_a_conservar ...]
 * Sin correos, conserva la cuenta 1711167cvr@gmail.com (Director).
 *
 * Genera respaldo/AAAA-MM-DD_HHMM/ con:
 *   00_borrar_todo.sql      borra las 33 tablas y sus secuencias (lo que hará el ingeniero)
 *   01_esquema.sql          crea de nuevo las tablas, claves, restricciones e índices, idénticos a los actuales
 *   02_datos_base.sql       roles, permisos, rol_permiso, tipos de evaluación y las cuentas conservadas
 *   03_datos_completos.sql  todas las filas de todas las tablas (para volver exactamente al estado de hoy)
 */
public class RespaldoBD {

    // Tablas que el software necesita y que ningún comando carga
    private static final List<String> BASE = List.of("rol", "permiso", "rol_permiso", "tipo_evaluacion");

    public static void main(String[] args) throws Exception {
        Properties cfg = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream("icap.properties"), StandardCharsets.UTF_8)) {
            cfg.load(r);
        }
        List<String> conservar = new ArrayList<>();
        for (String a : args) conservar.add(a.trim().toLowerCase());
        if (conservar.isEmpty()) conservar.add("1711167cvr@gmail.com");

        Path dir = Paths.get("respaldo", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")));
        Files.createDirectories(dir);

        try (Connection c = DriverManager.getConnection(cfg.getProperty("db.url"), cfg.getProperty("db.usuario"), cfg.getProperty("db.clave"))) {
            c.setReadOnly(true);
            try (Statement st = c.createStatement()) {
                st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                st.execute("SET search_path = public");
            }
            List<String> tablas = ordenPorDependencias(c);
            System.out.println("Base: " + cfg.getProperty("db.url") + " (" + tablas.size() + " tablas)");

            escribir(dir.resolve("00_borrar_todo.sql"), borrarTodo(c, tablas));
            escribir(dir.resolve("01_esquema.sql"), esquema(c, tablas));
            escribir(dir.resolve("02_datos_base.sql"), datosBase(c, conservar));
            escribir(dir.resolve("03_datos_completos.sql"), datosCompletos(c, tablas));
        }
        System.out.println("\nListo: " + dir.toAbsolutePath());
        System.out.println("Cuentas conservadas en 02_datos_base.sql: " + conservar);
    }

    // ------------------------------------------------------------------ orden de las tablas (padres primero)
    static List<String> ordenPorDependencias(Connection c) throws SQLException {
        Map<String, Set<String>> padres = new TreeMap<>();
        for (String t : lista(c, "SELECT relname FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='r' ORDER BY 1")) {
            padres.put(t, new TreeSet<>());
        }
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT conrelid::regclass::text, confrelid::regclass::text FROM pg_constraint "
                        + "WHERE contype='f' AND connamespace='public'::regnamespace")) {
            while (rs.next()) {
                if (!rs.getString(1).equals(rs.getString(2))) padres.get(rs.getString(1)).add(rs.getString(2));
            }
        }
        List<String> orden = new ArrayList<>();
        while (orden.size() < padres.size()) {
            boolean avance = false;
            for (var e : padres.entrySet()) {
                if (!orden.contains(e.getKey()) && orden.containsAll(e.getValue())) {
                    orden.add(e.getKey());
                    avance = true;
                }
            }
            if (!avance) throw new IllegalStateException("Dependencias circulares entre tablas");
        }
        return orden;
    }

    // ------------------------------------------------------------------ 00: borrar
    static String borrarTodo(Connection c, List<String> tablas) throws SQLException {
        StringBuilder sb = cabecera("Borra TODAS las tablas del sistema ICAP y sus secuencias. NO tiene vuelta atrás:\n"
                + "-- ejecútelo solo si ya tiene este respaldo (01, 02 y 03).");
        sb.append("BEGIN;\n");
        List<String> inversa = new ArrayList<>(tablas);
        Collections.reverse(inversa);
        for (String t : inversa) sb.append("DROP TABLE IF EXISTS ").append(t).append(" CASCADE;\n");
        for (String s : lista(c, "SELECT relname FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='S' ORDER BY 1")) {
            sb.append("DROP SEQUENCE IF EXISTS ").append(s).append(" CASCADE;\n");
        }
        return sb.append("COMMIT;\n").toString();
    }

    // ------------------------------------------------------------------ 01: esquema
    static String esquema(Connection c, List<String> tablas) throws SQLException {
        StringBuilder sb = cabecera("Crea las " + tablas.size() + " tablas del sistema ICAP, idénticas a la base respaldada.\n"
                + "-- Se ejecuta sobre una base vacía (después de 00_borrar_todo.sql). Luego: 02_datos_base.sql o 03_datos_completos.sql.");
        sb.append("BEGIN;\n\n-- Secuencias de las claves primarias (SERIAL)\n");
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT c.relname, format_type(s.seqtypid, NULL) FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid "
                        + "WHERE c.relnamespace='public'::regnamespace ORDER BY 1")) {
            while (rs.next()) sb.append("CREATE SEQUENCE ").append(rs.getString(1)).append(" AS ").append(rs.getString(2)).append(";\n");
        }
        for (String t : tablas) {
            sb.append("\nCREATE TABLE ").append(t).append(" (\n");
            List<String> partes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, pg_get_expr(d.adbin, d.adrelid) "
                            + "FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum "
                            + "WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum")) {
                ps.setString(1, t);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String col = "    " + rs.getString(1) + " " + rs.getString(2);
                        if (rs.getString(4) != null) col += " DEFAULT " + rs.getString(4);
                        if (rs.getBoolean(3)) col += " NOT NULL";
                        partes.add(col);
                    }
                }
            }
            // PK primero, luego UNIQUE y CHECK
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass AND contype IN ('p','u','c') "
                            + "ORDER BY CASE contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 ELSE 2 END, conname")) {
                ps.setString(1, t);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) partes.add("    CONSTRAINT " + rs.getString(1) + " " + rs.getString(2));
                }
            }
            sb.append(String.join(",\n", partes)).append("\n);\n");
        }
        sb.append("\n-- Cada secuencia pertenece a su columna (como un SERIAL)\n");
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT s.relname, t.relname, a.attname FROM pg_class s JOIN pg_depend d ON d.objid=s.oid AND d.deptype IN ('a','i') "
                        + "JOIN pg_class t ON t.oid=d.refobjid JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid "
                        + "WHERE s.relkind='S' AND s.relnamespace='public'::regnamespace ORDER BY 1")) {
            while (rs.next()) {
                sb.append("ALTER SEQUENCE ").append(rs.getString(1)).append(" OWNED BY ").append(rs.getString(2)).append(".")
                        .append(rs.getString(3)).append(";\n");
            }
        }
        sb.append("\n-- Claves foráneas\n");
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE contype='f' AND connamespace='public'::regnamespace ORDER BY 1, 2")) {
            while (rs.next()) {
                sb.append("ALTER TABLE ").append(rs.getString(1)).append(" ADD CONSTRAINT ").append(rs.getString(2)).append(" ")
                        .append(rs.getString(3)).append(";\n");
            }
        }
        sb.append("\n-- Índices (los de las claves primarias y UNIQUE ya se crearon con sus restricciones)\n");
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid "
                        + "WHERE t.relnamespace='public'::regnamespace AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid=i.indexrelid) "
                        + "ORDER BY 1")) {
            while (rs.next()) sb.append(rs.getString(1).replace(" ON public.", " ON ")).append(";\n");
        }
        return sb.append("\nCOMMIT;\n").toString();
    }

    // ------------------------------------------------------------------ 02: datos base
    static String datosBase(Connection c, List<String> conservar) throws SQLException {
        StringBuilder sb = cabecera("Datos mínimos para que el sistema funcione: roles, permisos, permisos por rol, tipos de evaluación\n"
                + "-- y las cuentas conservadas (" + String.join(", ", conservar) + ") con su personal y sus roles.\n"
                + "-- Se ejecuta sobre las tablas vacías (después de 01_esquema.sql).");
        sb.append("BEGIN;\n");
        for (String t : BASE) volcar(c, sb, t, null);

        String lista = "'" + String.join("','", conservar.stream().map(e -> e.replace("'", "''")).toList()) + "'";
        int halladas = Integer.parseInt(lista(c, "SELECT count(*) FROM usuario WHERE lower(email) IN (" + lista + ")").get(0));
        if (halladas != conservar.size()) {
            throw new IllegalStateException("No se encontraron todas las cuentas a conservar (" + halladas + " de " + conservar.size()
                    + "). Revise los correos: " + conservar);
        }
        volcar(c, sb, "personal", "id_personal IN (SELECT id_personal FROM usuario WHERE lower(email) IN (" + lista + "))");
        volcar(c, sb, "usuario", "lower(email) IN (" + lista + ")");
        volcar(c, sb, "rol_usuario", "id_usuario IN (SELECT id_usuario FROM usuario WHERE lower(email) IN (" + lista + "))");
        sb.append(secuencias(c));
        return sb.append("COMMIT;\n").toString();
    }

    // ------------------------------------------------------------------ 03: todo
    static String datosCompletos(Connection c, List<String> tablas) throws SQLException {
        StringBuilder sb = cabecera("Todas las filas de las " + tablas.size() + " tablas. Se ejecuta sobre las tablas vacías\n"
                + "-- (después de 01_esquema.sql) para volver exactamente al estado del respaldo.");
        sb.append("BEGIN;\n");
        for (String t : tablas) volcar(c, sb, t, null);
        sb.append(secuencias(c));
        return sb.append("COMMIT;\n").toString();
    }

    static void volcar(Connection c, StringBuilder sb, String tabla, String filtro) throws SQLException {
        String pk = lista(c, "SELECT string_agg(a.attname, ', ' ORDER BY array_position(k.conkey, a.attnum)) FROM pg_constraint k "
                + "JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum = ANY(k.conkey) WHERE k.conrelid='" + tabla + "'::regclass AND k.contype='p'").get(0);
        String sql = "SELECT * FROM " + tabla + (filtro != null ? " WHERE " + filtro : "") + (pk != null ? " ORDER BY " + pk : "");
        int n = 0;
        sb.append("\n-- ").append(tabla.toUpperCase()).append("\n");
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnName(i));
            String inicio = "INSERT INTO " + tabla + " (" + String.join(", ", cols) + ") VALUES (";
            while (rs.next()) {
                List<String> v = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) v.add(literal(rs, i, md.getColumnType(i)));
                sb.append(inicio).append(String.join(", ", v)).append(");\n");
                n++;
            }
        }
        System.out.printf("  %-30s %6d filas%n", tabla, n);
    }

    static String literal(ResultSet rs, int i, int tipo) throws SQLException {
        String s = rs.getString(i);
        if (s == null) return "NULL";
        switch (tipo) {
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.NUMERIC, Types.DECIMAL, Types.DOUBLE, Types.REAL, Types.FLOAT:
                return s;
            case Types.BIT, Types.BOOLEAN:
                return rs.getBoolean(i) ? "TRUE" : "FALSE";
            default:
                return "'" + s.replace("'", "''") + "'";
        }
    }

    // Cada secuencia continúa después del mayor id cargado
    static String secuencias(Connection c) throws SQLException {
        StringBuilder sb = new StringBuilder("\n-- Las secuencias siguen después del mayor id cargado\n");
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT s.relname, t.relname, a.attname FROM pg_class s JOIN pg_depend d ON d.objid=s.oid AND d.deptype IN ('a','i') "
                        + "JOIN pg_class t ON t.oid=d.refobjid JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid "
                        + "WHERE s.relkind='S' AND s.relnamespace='public'::regnamespace ORDER BY 1")) {
            while (rs.next()) {
                String seq = rs.getString(1), t = rs.getString(2), col = rs.getString(3);
                sb.append("SELECT setval('").append(seq).append("', COALESCE((SELECT max(").append(col).append(") FROM ").append(t)
                        .append("), 1), (SELECT max(").append(col).append(") FROM ").append(t).append(") IS NOT NULL);\n");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ utilidades
    static StringBuilder cabecera(String texto) {
        return new StringBuilder("-- Sistema ICAP (Grupo 15 SC) · generado por deploy/RespaldoBD.java el "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n-- " + texto + "\n\n");
    }

    static List<String> lista(Connection c, String sql) throws SQLException {
        List<String> r = new ArrayList<>();
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) r.add(rs.getString(1));
        }
        return r;
    }

    static void escribir(Path p, String contenido) throws IOException {
        Files.writeString(p, contenido, StandardCharsets.UTF_8);
        System.out.println("Escrito " + p);
    }
}
