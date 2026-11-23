package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.Estudiante;
import src.utilidades.Tabla;

public class EstudianteDAO {
    // Columnas que MODEST puede modificar (fecha_nacimiento recibe un LocalDate)
    public static final Set<String> CAMPOS = Set.of("nombre", "paterno", "materno", "ci_expedicion",
            "fecha_nacimiento", "direccion", "telefono", "email", "nivel_escolaridad", "ciudad", "pais");

    private final Connection con;

    public EstudianteDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Estudiante e) throws SQLException {
        String sql = "INSERT INTO ESTUDIANTE (codigo_estudiante, nombre, paterno, materno, ci, ci_expedicion, "
                + "fecha_nacimiento, direccion, telefono, email, nivel_escolaridad, ciudad, pais) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id_estudiante";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, e.getCodigoEstudiante());
            ps.setString(2, e.getNombre());
            ps.setString(3, e.getPaterno());
            ps.setString(4, e.getMaterno());
            ps.setString(5, e.getCi());
            ps.setString(6, e.getCiExpedicion());
            ps.setDate(7, java.sql.Date.valueOf(e.getFechaNacimiento()));
            ps.setString(8, e.getDireccion());
            ps.setString(9, e.getTelefono());
            ps.setString(10, e.getEmail());
            ps.setString(11, e.getNivelEscolaridad());
            ps.setString(12, e.getCiudad());
            ps.setString(13, e.getPais());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                e.setIdEstudiante(rs.getInt(1));
                return e.getIdEstudiante();
            }
        }
    }

    public Estudiante buscarPorCi(String ci) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM ESTUDIANTE WHERE ci = ?")) {
            ps.setString(1, ci);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // El actor Estudiante se reconoce por el correo registrado en su expediente
    public boolean existeConEmail(String email) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM ESTUDIANTE WHERE LOWER(email) = LOWER(?) LIMIT 1")) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Estudiante buscarPorId(int idEstudiante) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM ESTUDIANTE WHERE id_estudiante = ?")) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // Los comandos del CU-03 aceptan el CI o el código del estudiante
    public Estudiante buscarPorCiOCodigo(String valor) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM ESTUDIANTE WHERE ci = ? OR UPPER(codigo_estudiante) = UPPER(?)")) {
            ps.setString(1, valor.trim());
            ps.setString(2, valor.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // PREINS de alguien que ya tenía expediente sin formalizar: actualiza los datos que envió
    public void actualizarDatosPreinscripcion(Estudiante e) throws SQLException {
        String sql = "UPDATE ESTUDIANTE SET nombre = ?, paterno = ?, materno = ?, fecha_nacimiento = ?, telefono = ?, "
                + "ciudad = ?, email = ? WHERE id_estudiante = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, e.getNombre());
            ps.setString(2, e.getPaterno());
            ps.setString(3, e.getMaterno());
            ps.setDate(4, java.sql.Date.valueOf(e.getFechaNacimiento()));
            ps.setString(5, e.getTelefono());
            ps.setString(6, e.getCiudad());
            ps.setString(7, e.getEmail());
            ps.setInt(8, e.getIdEstudiante());
            ps.executeUpdate();
        }
    }

    public void actualizarCiYCodigo(int idEstudiante, String ci, String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ESTUDIANTE SET ci = ?, codigo_estudiante = ? WHERE id_estudiante = ?")) {
            ps.setString(1, ci);
            ps.setString(2, codigo);
            ps.setInt(3, idEstudiante);
            ps.executeUpdate();
        }
    }

    public void actualizarCodigo(int idEstudiante, String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ESTUDIANTE SET codigo_estudiante = ? WHERE id_estudiante = ?")) {
            ps.setString(1, codigo);
            ps.setInt(2, idEstudiante);
            ps.executeUpdate();
        }
    }

    public void actualizarCampo(int idEstudiante, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en ESTUDIANTE: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ESTUDIANTE SET " + campo + " = ? WHERE id_estudiante = ?")) {
            if (valor instanceof java.time.LocalDate) {
                ps.setDate(1, java.sql.Date.valueOf((java.time.LocalDate) valor));
            } else {
                ps.setString(1, (String) valor);
            }
            ps.setInt(2, idEstudiante);
            ps.executeUpdate();
        }
    }

    public boolean tieneInscripciones(int idEstudiante) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM INSCRIPCION WHERE id_estudiante = ? LIMIT 1")) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void eliminar(int idEstudiante) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM ESTUDIANTE WHERE id_estudiante = ?")) {
            ps.setInt(1, idEstudiante);
            ps.executeUpdate();
        }
    }

    // LISEST["*" | ci | codigo_estudiante | nombre]
    public Tabla listar(String filtro) throws SQLException {
        String sql = "SELECT codigo_estudiante, ci, ci_expedicion AS exp, "
                + "nombre || ' ' || paterno || COALESCE(' ' || materno, '') AS estudiante, "
                + "fecha_nacimiento, telefono, email, nivel_escolaridad, ciudad, pais, estado "
                + "FROM ESTUDIANTE "
                + "WHERE ? = '*' OR ci = ? OR codigo_estudiante ILIKE ? "
                + "OR nombre ILIKE '%' || ? || '%' OR paterno ILIKE '%' || ? || '%' "
                + "ORDER BY id_estudiante";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) {
                ps.setString(i, filtro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Estudiante mapear(ResultSet rs) throws SQLException {
        Estudiante e = new Estudiante();
        e.setIdEstudiante(rs.getInt("id_estudiante"));
        e.setCodigoEstudiante(rs.getString("codigo_estudiante"));
        e.setNombre(rs.getString("nombre"));
        e.setPaterno(rs.getString("paterno"));
        e.setMaterno(rs.getString("materno"));
        e.setCi(rs.getString("ci"));
        e.setCiExpedicion(rs.getString("ci_expedicion"));
        e.setFechaNacimiento(rs.getDate("fecha_nacimiento").toLocalDate());
        e.setDireccion(rs.getString("direccion"));
        e.setTelefono(rs.getString("telefono"));
        e.setEmail(rs.getString("email"));
        e.setNivelEscolaridad(rs.getString("nivel_escolaridad"));
        e.setCiudad(rs.getString("ciudad"));
        e.setPais(rs.getString("pais"));
        e.setEstado(rs.getString("estado"));
        return e;
    }
}
