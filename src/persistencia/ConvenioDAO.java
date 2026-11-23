package src.persistencia;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Set;
import src.modelo.Convenio;
import src.utilidades.Tabla;

public class ConvenioDAO {
    // Columnas que MODCONV puede modificar
    public static final Set<String> CAMPOS = Set.of("institucion", "tipo_convenio", "porcentaje_descuento", "fecha_inicio",
            "fecha_fin");

    private final Connection con;

    public ConvenioDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Convenio c) throws SQLException {
        String sql = "INSERT INTO CONVENIO (institucion, tipo_convenio, porcentaje_descuento, fecha_inicio, fecha_fin, estado) "
                + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id_convenio";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, c.getInstitucion());
            ps.setString(2, c.getTipoConvenio());
            ps.setBigDecimal(3, c.getPorcentajeDescuento());
            Sql.asignar(ps, 4, c.getFechaInicio());
            Sql.asignar(ps, 5, c.getFechaFin());
            ps.setString(6, c.getEstado());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                c.setIdConvenio(rs.getInt(1));
                return c.getIdConvenio();
            }
        }
    }

    // En los comandos el convenio se identifica por su institución: tiene a lo sumo uno vigente.
    // Si ya no lo tiene, devuelve el último registrado
    public Convenio buscarPorInstitucion(String institucion) throws SQLException {
        String sql = "SELECT * FROM CONVENIO WHERE UPPER(institucion) = UPPER(?) "
                + "ORDER BY (estado = 'VIGENTE') DESC, id_convenio DESC LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, institucion.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public void actualizarCampo(int idConvenio, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en CONVENIO: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE CONVENIO SET " + campo + " = ? WHERE id_convenio = ?")) {
            Sql.asignar(ps, 1, valor);
            ps.setInt(2, idConvenio);
            ps.executeUpdate();
        }
    }

    public void cambiarEstado(int idConvenio, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE CONVENIO SET estado = ? WHERE id_convenio = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idConvenio);
            ps.executeUpdate();
        }
    }

    // Los convenios cuya fecha de fin ya pasó quedan VENCIDO
    public void marcarVencidos() throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE CONVENIO SET estado = 'VENCIDO' WHERE estado = 'VIGENTE' AND fecha_fin < CURRENT_DATE")) {
            ps.executeUpdate();
        }
    }

    public int contarBeneficiarios(int idConvenio) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM INSCRIPCION WHERE id_convenio = ?")) {
            ps.setInt(1, idConvenio);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // Descuento del convenio de la inscripción en la fecha del pago; 0 si no tiene o no estaba en vigor.
    // Un convenio vencido sigue valiendo para los depósitos hechos mientras estaba vigente, uno rescindido ya no
    public BigDecimal descuentoEn(int idInscripcion, LocalDate fecha) throws SQLException {
        String sql = "SELECT c.porcentaje_descuento FROM INSCRIPCION i JOIN CONVENIO c ON c.id_convenio = i.id_convenio "
                + "WHERE i.id_inscripcion = ? AND c.estado <> 'RESCINDIDO' AND c.fecha_inicio <= ? "
                + "AND (c.fecha_fin IS NULL OR c.fecha_fin >= ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            Sql.asignar(ps, 2, fecha);
            Sql.asignar(ps, 3, fecha);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    // LISCONV["*" | estado]: convenios con sus beneficiarios
    public Tabla listar(String estado) throws SQLException {
        String sql = "SELECT c.institucion, c.tipo_convenio AS tipo, c.porcentaje_descuento AS descuento, c.fecha_inicio, "
                + "c.fecha_fin, c.estado, COUNT(i.id_inscripcion) AS cantidad, "
                + "COALESCE(string_agg(e.nombre || ' ' || e.paterno || ' (' || e.ci || ')', ', ' ORDER BY e.paterno), '-') AS beneficiarios "
                + "FROM CONVENIO c "
                + "LEFT JOIN INSCRIPCION i ON i.id_convenio = c.id_convenio "
                + "LEFT JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "WHERE ? = '*' OR c.estado = ? GROUP BY c.id_convenio ORDER BY c.institucion, c.id_convenio";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, estado);
            ps.setString(2, estado);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Convenio mapear(ResultSet rs) throws SQLException {
        Convenio c = new Convenio();
        c.setIdConvenio(rs.getInt("id_convenio"));
        c.setInstitucion(rs.getString("institucion"));
        c.setTipoConvenio(rs.getString("tipo_convenio"));
        c.setPorcentajeDescuento(rs.getBigDecimal("porcentaje_descuento"));
        c.setFechaInicio(Sql.fecha(rs.getDate("fecha_inicio")));
        c.setFechaFin(Sql.fecha(rs.getDate("fecha_fin")));
        c.setEstado(rs.getString("estado"));
        return c;
    }
}
