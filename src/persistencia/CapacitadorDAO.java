package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.Capacitador;
import src.utilidades.Tabla;

public class CapacitadorDAO {
    // Columnas del perfil académico que MODCAP puede modificar en CAPACITOR
    public static final Set<String> CAMPOS = Set.of("titulo_academico", "grado_academico", "experiencia_lab",
            "cv_documento");

    private final Connection con;

    public CapacitadorDAO(Connection con) {
        this.con = con;
    }

    // El registro en PERSONAL debe existir antes (c.getIdPersonal() ya asignado)
    public void insertar(Capacitador c) throws SQLException {
        String sql = "INSERT INTO CAPACITOR (id_personal, titulo_academico, grado_academico, experiencia_lab, cv_documento) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, c.getIdPersonal());
            ps.setString(2, c.getTituloAcademico());
            ps.setString(3, c.getGradoAcademico());
            ps.setString(4, c.getExperienciaLab());
            ps.setString(5, c.getCvDocumento());
            ps.executeUpdate();
        }
    }

    public Capacitador buscarPorCi(String ci) throws SQLException {
        String sql = "SELECT p.*, c.titulo_academico, c.grado_academico, c.experiencia_lab, c.cv_documento "
                + "FROM PERSONAL p JOIN CAPACITOR c ON c.id_personal = p.id_personal WHERE p.ci = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ci);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Capacitador c = new Capacitador(rs.getString("ci"), rs.getString("ci_expedicion"),
                        rs.getString("nombre"), rs.getString("apellidos"), rs.getString("direccion"),
                        rs.getString("telefono"), rs.getString("titulo_academico"), rs.getString("grado_academico"),
                        rs.getString("experiencia_lab"), rs.getString("cv_documento"));
                c.setIdPersonal(rs.getInt("id_personal"));
                c.setEstado(rs.getString("estado"));
                return c;
            }
        }
    }

    public void actualizarCampo(int idPersonal, String campo, String valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en CAPACITOR: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE CAPACITOR SET " + campo + " = ? WHERE id_personal = ?")) {
            ps.setString(1, valor);
            ps.setInt(2, idPersonal);
            ps.executeUpdate();
        }
    }

    // DELCAP se rechaza si el capacitador tiene una asignación con el acta ABIERTA
    public boolean tieneActaAbierta(int idPersonal) throws SQLException {
        String sql = "SELECT 1 FROM ASIGNACION_MODULO am "
                + "JOIN ACTA_CALIFICACION ac ON ac.id_asignacion_modulo = am.id_asignacion_modulo "
                + "WHERE am.id_personal = ? AND ac.estado_acta = 'ABIERTA' LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idPersonal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Tabla listar(String filtro) throws SQLException {
        String sql = "SELECT p.ci, p.nombre || ' ' || p.apellidos AS profesor, c.titulo_academico, "
                + "c.grado_academico, c.experiencia_lab, "
                // cv_documento guarda la ruta del archivo; un texto anterior a este cambio no es un archivo
                + "CASE WHEN c.cv_documento IS NULL THEN 'Falta' WHEN c.cv_documento LIKE 'documentos/%' THEN 'Cargado' "
                + "ELSE 'Sin archivo' END AS cv, p.telefono, u.email, p.estado "
                + "FROM CAPACITOR c "
                + "JOIN PERSONAL p ON p.id_personal = c.id_personal "
                + "LEFT JOIN USUARIO u ON u.id_personal = p.id_personal "
                + "WHERE ? = '*' OR p.ci = ? OR p.nombre ILIKE '%' || ? || '%' OR p.apellidos ILIKE '%' || ? || '%' "
                + "ORDER BY p.id_personal";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, filtro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
