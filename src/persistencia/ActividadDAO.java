package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import src.modelo.ActividadEvaluativa;
import src.modelo.TipoEvaluacion;
import src.utilidades.Tabla;

// ACTIVIDAD_EVALUATIVA y TIPO_EVALUACION: el plan de evaluación de una asignación
public class ActividadDAO {
    // Columnas que MODACT puede modificar
    public static final Set<String> CAMPOS = Set.of("titulo", "ponderacion_designada", "fecha_limite");

    private static final String SELECCION = "SELECT a.*, t.nombre AS tipo FROM ACTIVIDAD_EVALUATIVA a "
            + "JOIN TIPO_EVALUACION t ON t.id_tipo_evaluacion = a.id_tipo_evaluacion ";

    // El examen recuperatorio no forma parte del plan que debe sumar 100
    private static final String ES_DEL_PLAN = "(SELECT nombre FROM TIPO_EVALUACION x WHERE x.id_tipo_evaluacion = a.id_tipo_evaluacion) <> '"
            + TipoEvaluacion.RECUPERATORIO + "'";

    private final Connection con;

    public ActividadDAO(Connection con) {
        this.con = con;
    }

    public List<TipoEvaluacion> listarTipos() throws SQLException {
        List<TipoEvaluacion> tipos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM TIPO_EVALUACION ORDER BY id_tipo_evaluacion");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tipos.add(new TipoEvaluacion(rs.getInt("id_tipo_evaluacion"), rs.getString("nombre"), rs.getInt("ponderacion_max")));
            }
        }
        return tipos;
    }

    public int insertar(ActividadEvaluativa a) throws SQLException {
        String sql = "INSERT INTO ACTIVIDAD_EVALUATIVA (titulo, fecha_limite, ponderacion_designada, id_asignacion_modulo, id_tipo_evaluacion) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id_actividad_evaluativa";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, a.getTitulo());
            Sql.asignar(ps, 2, a.getFechaLimite());
            ps.setInt(3, a.getPonderacion());
            ps.setInt(4, a.getIdAsignacion());
            ps.setInt(5, a.getIdTipo());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                a.setIdActividad(rs.getInt(1));
                return a.getIdActividad();
            }
        }
    }

    // En los comandos la actividad se identifica por su título dentro de la asignación
    public ActividadEvaluativa buscar(int idAsignacion, String titulo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECCION
                + "WHERE a.id_asignacion_modulo = ? AND UPPER(a.titulo) = UPPER(?)")) {
            ps.setInt(1, idAsignacion);
            ps.setString(2, titulo.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public ActividadEvaluativa buscarRecuperatorio(int idAsignacion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECCION + "WHERE a.id_asignacion_modulo = ? AND t.nombre = ?")) {
            ps.setInt(1, idAsignacion);
            ps.setString(2, TipoEvaluacion.RECUPERATORIO);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public List<ActividadEvaluativa> listarModelos(int idAsignacion) throws SQLException {
        List<ActividadEvaluativa> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(SELECCION
                + "WHERE a.id_asignacion_modulo = ? ORDER BY a.id_tipo_evaluacion, a.id_actividad_evaluativa")) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    public void actualizarCampo(int idActividad, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en ACTIVIDAD_EVALUATIVA: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ACTIVIDAD_EVALUATIVA SET " + campo + " = ? WHERE id_actividad_evaluativa = ?")) {
            Sql.asignar(ps, 1, valor);
            ps.setInt(2, idActividad);
            ps.executeUpdate();
        }
    }

    public void eliminar(int idActividad) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM ACTIVIDAD_EVALUATIVA WHERE id_actividad_evaluativa = ?")) {
            ps.setInt(1, idActividad);
            ps.executeUpdate();
        }
    }

    public boolean tieneNotas(int idActividad) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM CALIFICACION WHERE id_actividad_evaluativa = ? LIMIT 1")) {
            ps.setInt(1, idActividad);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Suma de las ponderaciones de un tipo, sin contar la actividad indicada (0 = ninguna)
    public int sumaPorTipo(int idAsignacion, int idTipo, int idActividadExcluida) throws SQLException {
        return entero("SELECT COALESCE(SUM(ponderacion_designada), 0) FROM ACTIVIDAD_EVALUATIVA "
                + "WHERE id_asignacion_modulo = ? AND id_tipo_evaluacion = ? AND id_actividad_evaluativa <> ?",
                idAsignacion, idTipo, idActividadExcluida);
    }

    public int contarPorTipo(int idAsignacion, int idTipo) throws SQLException {
        return entero("SELECT COUNT(*) FROM ACTIVIDAD_EVALUATIVA WHERE id_asignacion_modulo = ? AND id_tipo_evaluacion = ? "
                + "AND id_actividad_evaluativa <> ?", idAsignacion, idTipo, 0);
    }

    // Suma del plan de evaluación: debe llegar a 100 antes de cargar notas
    public int sumaPlan(int idAsignacion) throws SQLException {
        return entero("SELECT COALESCE(SUM(a.ponderacion_designada), 0) FROM ACTIVIDAD_EVALUATIVA a "
                + "WHERE a.id_asignacion_modulo = ? AND " + ES_DEL_PLAN, idAsignacion);
    }

    // LISACT: el plan con las notas ya cargadas en cada actividad
    public Tabla listar(int idAsignacion) throws SQLException {
        String sql = "SELECT t.nombre AS tipo, a.titulo, a.ponderacion_designada AS ponderacion, t.ponderacion_max AS maximo_del_tipo, "
                + "a.fecha_limite, (SELECT COUNT(*) FROM CALIFICACION c WHERE c.id_actividad_evaluativa = a.id_actividad_evaluativa) AS notas_cargadas "
                + "FROM ACTIVIDAD_EVALUATIVA a JOIN TIPO_EVALUACION t ON t.id_tipo_evaluacion = a.id_tipo_evaluacion "
                + "WHERE a.id_asignacion_modulo = ? ORDER BY a.id_tipo_evaluacion, a.id_actividad_evaluativa";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private int entero(String sql, int... parametros) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setInt(i + 1, parametros[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private ActividadEvaluativa mapear(ResultSet rs) throws SQLException {
        ActividadEvaluativa a = new ActividadEvaluativa();
        a.setIdActividad(rs.getInt("id_actividad_evaluativa"));
        a.setTitulo(rs.getString("titulo"));
        a.setFechaLimite(Sql.fecha(rs.getDate("fecha_limite")));
        a.setPonderacion(rs.getInt("ponderacion_designada"));
        a.setIdAsignacion(rs.getInt("id_asignacion_modulo"));
        a.setIdTipo(rs.getInt("id_tipo_evaluacion"));
        a.setTipo(rs.getString("tipo"));
        return a;
    }
}
