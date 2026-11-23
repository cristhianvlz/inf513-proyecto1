package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.Arancel;
import src.utilidades.Tabla;

public class ArancelDAO {
    // Columnas que MODARAN puede modificar
    public static final Set<String> CAMPOS = Set.of("monto", "numero_resolucion", "fecha_resolucion");

    private final Connection con;

    public ArancelDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Arancel a) throws SQLException {
        String sql = "INSERT INTO ARANCEL (concepto_arancel, monto, gestion, numero_resolucion, fecha_resolucion) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id_arancel";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, a.getConcepto());
            ps.setBigDecimal(2, a.getMonto());
            ps.setString(3, a.getGestion());
            ps.setString(4, a.getNumeroResolucion());
            Sql.asignar(ps, 5, a.getFechaResolucion());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                a.setIdArancel(rs.getInt(1));
                return a.getIdArancel();
            }
        }
    }

    // En los comandos el arancel se identifica por concepto y gestión: solo uno de ellos puede estar VIGENTE
    public Arancel buscarVigente(String concepto, String gestion) throws SQLException {
        return buscar(concepto, gestion, "VIGENTE");
    }

    // El último arancel que pasó a HISTORICO (RESARAN)
    public Arancel buscarUltimoHistorico(String concepto, String gestion) throws SQLException {
        return buscar(concepto, gestion, "HISTORICO");
    }

    private Arancel buscar(String concepto, String gestion, String estado) throws SQLException {
        String sql = "SELECT * FROM ARANCEL WHERE concepto_arancel = ? AND UPPER(gestion) = UPPER(?) AND estado = ? "
                + "ORDER BY id_arancel DESC LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, concepto);
            ps.setString(2, gestion.trim());
            ps.setString(3, estado);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Arancel buscarPorId(int idArancel) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM ARANCEL WHERE id_arancel = ?")) {
            ps.setInt(1, idArancel);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public boolean tienePagos(int idArancel) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM PAGO WHERE id_arancel = ? LIMIT 1")) {
            ps.setInt(1, idArancel);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void actualizarCampo(int idArancel, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en ARANCEL: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE ARANCEL SET " + campo + " = ? WHERE id_arancel = ?")) {
            Sql.asignar(ps, 1, valor);
            ps.setInt(2, idArancel);
            ps.executeUpdate();
        }
    }

    public void cambiarEstado(int idArancel, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE ARANCEL SET estado = ? WHERE id_arancel = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idArancel);
            ps.executeUpdate();
        }
    }

    // LISARAN[gestion, estado]: "*" en cualquiera de los dos no filtra por ese dato
    public Tabla listar(String gestion, String estado) throws SQLException {
        String sql = "SELECT a.concepto_arancel AS concepto, a.monto, a.gestion, a.numero_resolucion AS resolucion, "
                + "a.fecha_resolucion, a.estado, (SELECT COUNT(*) FROM PAGO p WHERE p.id_arancel = a.id_arancel) AS pagos "
                + "FROM ARANCEL a WHERE (? = '*' OR UPPER(a.gestion) = UPPER(?)) AND (? = '*' OR a.estado = ?) "
                + "ORDER BY a.gestion, a.concepto_arancel, a.id_arancel";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, gestion);
            ps.setString(2, gestion);
            ps.setString(3, estado);
            ps.setString(4, estado);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Arancel mapear(ResultSet rs) throws SQLException {
        Arancel a = new Arancel();
        a.setIdArancel(rs.getInt("id_arancel"));
        a.setConcepto(rs.getString("concepto_arancel"));
        a.setMonto(rs.getBigDecimal("monto"));
        a.setGestion(rs.getString("gestion"));
        a.setNumeroResolucion(rs.getString("numero_resolucion"));
        a.setFechaResolucion(Sql.fecha(rs.getDate("fecha_resolucion")));
        a.setEstado(rs.getString("estado"));
        return a;
    }
}
