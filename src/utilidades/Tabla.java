package src.utilidades;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Resultado tabular de un listado, desacoplado del ResultSet para poder cerrar la conexión antes de armar el HTML.
// Recuerda qué columnas son numéricas (cantidades y montos) para alinearlas a la derecha en el PDF y guardarlas
// como números en el Excel; un CI o un código no se marcan numéricos aunque tengan solo dígitos
public class Tabla {
    private final List<String> cabeceras = new ArrayList<>();
    private final List<String[]> filas = new ArrayList<>();
    private final Set<Integer> numericas = new HashSet<>();

    public static Tabla desde(ResultSet rs) throws SQLException {
        Tabla t = new Tabla();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            t.cabeceras.add(meta.getColumnLabel(i));
            switch (meta.getColumnType(i)) {
                case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.NUMERIC, Types.DECIMAL, Types.DOUBLE, Types.REAL, Types.FLOAT ->
                    t.numericas.add(i - 1);
                default -> {
                }
            }
        }
        while (rs.next()) {
            String[] fila = new String[cols];
            for (int i = 1; i <= cols; i++) {
                fila[i - 1] = rs.getString(i);
            }
            t.filas.add(fila);
        }
        return t;
    }

    // Tabla armada a mano: en las planillas cada actividad o sesión es una columna
    public static Tabla de(List<String> cabeceras, List<String[]> filas) {
        Tabla t = new Tabla();
        t.cabeceras.addAll(cabeceras);
        t.filas.addAll(filas);
        return t;
    }

    // Marca columnas como numéricas en una tabla armada a mano
    public Tabla numericas(int... columnas) {
        for (int c : columnas) {
            numericas.add(c);
        }
        return this;
    }

    public boolean esNumerica(int columna) {
        return numericas.contains(columna);
    }

    public List<String> getCabeceras() { return cabeceras; }
    public List<String[]> getFilas() { return filas; }
    public boolean estaVacia() { return filas.isEmpty(); }
}
