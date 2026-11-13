package src.persistencia;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;

// Asigna un parámetro según su tipo Java (los MOD* reciben valores de distintos tipos en la misma columna dinámica)
final class Sql {
    private Sql() {
    }

    static void asignar(PreparedStatement ps, int i, Object valor) throws SQLException {
        if (valor == null) {
            ps.setNull(i, Types.NULL);
        } else if (valor instanceof LocalDate) {
            ps.setDate(i, java.sql.Date.valueOf((LocalDate) valor));
        } else if (valor instanceof LocalTime) {
            ps.setTime(i, java.sql.Time.valueOf((LocalTime) valor));
        } else if (valor instanceof Integer) {
            ps.setInt(i, (Integer) valor);
        } else if (valor instanceof BigDecimal) {
            ps.setBigDecimal(i, (BigDecimal) valor);
        } else {
            ps.setString(i, valor.toString());
        }
    }

    static LocalDate fecha(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
