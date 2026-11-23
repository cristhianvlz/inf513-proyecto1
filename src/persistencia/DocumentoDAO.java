package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import src.modelo.DocumentoDigital;
import src.utilidades.Tabla;

// DOCUMENTO_DIGITAL: archivos del expediente del estudiante (CU-07) y boletas de pago (CU-04)
public class DocumentoDAO {

    // Documento con el pago que respalda, si es una boleta
    private static final String CONSULTA = "SELECT d.*, p.numero_transaccion, p.estado_conciliacion FROM DOCUMENTO_DIGITAL d "
            + "LEFT JOIN PAGO p ON p.id_pago = d.id_pago ";

    private final Connection con;

    public DocumentoDAO(Connection con) {
        this.con = con;
    }

    public void insertar(String tipoDocumento, String ruta, int idEstudiante) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO DOCUMENTO_DIGITAL (tipo_documento, url_doc, id_estudiante) VALUES (?, ?, ?)")) {
            ps.setString(1, tipoDocumento);
            ps.setString(2, ruta);
            ps.setInt(3, idEstudiante);
            ps.executeUpdate();
        }
    }

    // La boleta de un pago queda asociada a él (DELDOC la protege si el pago está conciliado)
    public void insertarBoleta(String ruta, int idEstudiante, int idPago) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO DOCUMENTO_DIGITAL (tipo_documento, url_doc, id_estudiante, id_pago) VALUES ('BOLETA_PAGO', ?, ?, ?)")) {
            ps.setString(1, ruta);
            ps.setInt(2, idEstudiante);
            ps.setInt(3, idPago);
            ps.executeUpdate();
        }
    }

    // Ruta del último documento de ese tipo (por ejemplo la FOTOGRAFIA del título); null si no hay
    public String ultimaRuta(int idEstudiante, String tipoDocumento) throws SQLException {
        DocumentoDigital d = ultimo(idEstudiante, tipoDocumento);
        return d == null ? null : d.getUrlDoc();
    }

    public boolean tiene(int idEstudiante, String tipoDocumento) throws SQLException {
        return ultimo(idEstudiante, tipoDocumento) != null;
    }

    // El último documento de ese tipo en el expediente; null si no tiene
    public DocumentoDigital ultimo(int idEstudiante, String tipoDocumento) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(CONSULTA + "WHERE d.id_estudiante = ? AND d.tipo_documento = ? "
                + "ORDER BY d.fecha_presentacion DESC, d.id_documento DESC LIMIT 1")) {
            ps.setInt(1, idEstudiante);
            ps.setString(2, tipoDocumento);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // Todos los documentos del expediente (LISDOC y BUSDOC con "TODOS")
    public List<DocumentoDigital> todos(int idEstudiante) throws SQLException {
        List<DocumentoDigital> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(CONSULTA + "WHERE d.id_estudiante = ? ORDER BY d.fecha_presentacion, d.id_documento")) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    // Boletas del estudiante; con número de transacción, solo la de ese pago
    public List<DocumentoDigital> boletas(int idEstudiante, String numeroTransaccion) throws SQLException {
        String sql = CONSULTA + "WHERE d.id_estudiante = ? AND d.tipo_documento = 'BOLETA_PAGO' "
                + "AND (? = '' OR UPPER(p.numero_transaccion) = UPPER(?)) ORDER BY d.id_documento";
        List<DocumentoDigital> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            ps.setString(2, numeroTransaccion);
            ps.setString(3, numeroTransaccion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
            }
        }
        return lista;
    }

    // MODDOC: el nuevo archivo reemplaza al anterior y su fecha de presentación es la de hoy
    public void reemplazar(int idDocumento, String ruta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE DOCUMENTO_DIGITAL SET url_doc = ?, fecha_presentacion = CURRENT_DATE WHERE id_documento = ?")) {
            ps.setString(1, ruta);
            ps.setInt(2, idDocumento);
            ps.executeUpdate();
        }
    }

    public void eliminar(int idDocumento) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM DOCUMENTO_DIGITAL WHERE id_documento = ?")) {
            ps.setInt(1, idDocumento);
            ps.executeUpdate();
        }
    }

    // LISDOC: los documentos del expediente, sin IDs; las boletas con su pago
    public Tabla listarPorEstudiante(int idEstudiante) throws SQLException {
        String sql = "SELECT d.tipo_documento AS documento, d.fecha_presentacion AS presentado, d.url_doc AS archivo, "
                + "p.numero_transaccion AS pago, a.concepto_arancel AS concepto, p.estado_conciliacion AS estado_del_pago "
                + "FROM DOCUMENTO_DIGITAL d LEFT JOIN PAGO p ON p.id_pago = d.id_pago LEFT JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "WHERE d.id_estudiante = ? ORDER BY d.fecha_presentacion, d.id_documento";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private DocumentoDigital mapear(ResultSet rs) throws SQLException {
        DocumentoDigital d = new DocumentoDigital();
        d.setIdDocumento(rs.getInt("id_documento"));
        d.setTipoDocumento(rs.getString("tipo_documento"));
        d.setUrlDoc(rs.getString("url_doc"));
        d.setFechaPresentacion(Sql.fecha(rs.getDate("fecha_presentacion")));
        d.setIdEstudiante(rs.getInt("id_estudiante"));
        int pago = rs.getInt("id_pago");
        d.setIdPago(rs.wasNull() ? null : pago);
        d.setNumeroTransaccion(rs.getString("numero_transaccion"));
        d.setEstadoPago(rs.getString("estado_conciliacion"));
        return d;
    }
}
