package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import src.modelo.Certificado;
import src.utilidades.Tabla;

// CERTIFICADO: títulos, certificados y constancias emitidos. Nunca se borran: ANUCERT los pasa a ANULADO.
// El motivo y la fecha de la anulación están en HISTORIAL_ESTADO, cuyo motivo empieza con el código del documento
public class CertificadoDAO {

    // Última anulación registrada del documento
    private static final String ANULACION = "LEFT JOIN LATERAL (SELECT to_char(h.fecha_cambio, 'YYYY-MM-DD') AS fecha_anulacion, h.motivo AS motivo_anulacion "
            + "FROM HISTORIAL_ESTADO h WHERE h.id_inscripcion = c.id_inscripcion AND h.estado_nuevo = 'ANULADO' "
            + "AND h.motivo LIKE c.codigo_alfanumerico_unico || ' (%' ORDER BY h.id_historial DESC LIMIT 1) an ON c.estado = 'ANULADO' ";

    // El titular y el programa salen de la inscripción del documento
    private static final String CONSULTA = "SELECT c.*, e.nombre || ' ' || e.paterno || COALESCE(' ' || e.materno, '') AS titular, "
            + "pr.codigo || ' - ' || pr.nombre AS programa, an.fecha_anulacion, an.motivo_anulacion "
            + "FROM CERTIFICADO c "
            + "JOIN INSCRIPCION i ON i.id_inscripcion = c.id_inscripcion "
            + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
            + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
            + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
            + "JOIN PROGRAMA pr ON pr.id_programa = v.id_programa "
            + ANULACION;

    private final Connection con;

    public CertificadoDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Certificado c) throws SQLException {
        String sql = "INSERT INTO CERTIFICADO (tipo_certificado, grado_otorgado, numero_titulo, codigo_alfanumerico_unico, "
                + "codigo_qr_hash, fecha_emision, estado, id_inscripcion) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id_certificado";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, c.getTipoCertificado());
            ps.setString(2, c.getGradoOtorgado());
            ps.setString(3, c.getNumeroTitulo());
            ps.setString(4, c.getCodigoAlfanumerico());
            ps.setString(5, c.getCodigoQrHash());
            Sql.asignar(ps, 6, c.getFechaEmision());
            ps.setString(7, c.getEstado());
            ps.setInt(8, c.getIdInscripcion());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                c.setIdCertificado(rs.getInt(1));
                return c.getIdCertificado();
            }
        }
    }

    // VERCERT acepta el código único o el hash del código QR
    public Certificado buscarPorCodigoOHash(String valor) throws SQLException {
        String sql = CONSULTA + "WHERE UPPER(c.codigo_alfanumerico_unico) = UPPER(?) OR LOWER(c.codigo_qr_hash) = LOWER(?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, valor.trim());
            ps.setString(2, valor.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Certificado buscarPorCodigo(String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(CONSULTA + "WHERE UPPER(c.codigo_alfanumerico_unico) = UPPER(?)")) {
            ps.setString(1, codigo.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // Siguiente correlativo: con prefijo "ICAP-2026-" cuenta los códigos del año; sin prefijo, la numeración de títulos
    public int siguienteCorrelativo(String columna, String prefijo) throws SQLException {
        if (!columna.equals("codigo_alfanumerico_unico") && !columna.equals("numero_titulo")) {
            throw new IllegalArgumentException("Columna sin correlativo: " + columna);
        }
        String sql = "SELECT COALESCE(MAX(CAST(SUBSTRING(" + columna + " FROM ?) AS INT)), 0) + 1 FROM CERTIFICADO "
                + "WHERE " + columna + " ~ ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, prefijo.length() + 1);
            ps.setString(2, "^" + prefijo + "[0-9]+$");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void anular(int idCertificado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE CERTIFICADO SET estado = 'ANULADO' WHERE id_certificado = ?")) {
            ps.setInt(1, idCertificado);
            ps.executeUpdate();
        }
    }

    // Código del documento VIGENTE de ese tipo que el estudiante ya tiene en el programa; null si no tiene
    public String vigenteEnPrograma(int idEstudiante, int idPrograma, String tipo) throws SQLException {
        String sql = "SELECT c.codigo_alfanumerico_unico FROM CERTIFICADO c "
                + "JOIN INSCRIPCION i ON i.id_inscripcion = c.id_inscripcion "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
                + "WHERE i.id_estudiante = ? AND v.id_programa = ? AND c.tipo_certificado = ? AND c.estado = 'VIGENTE' "
                + "ORDER BY c.id_certificado DESC LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            ps.setInt(2, idPrograma);
            ps.setString(3, tipo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ¿El ICAP ya le emitió un documento VIGENTE de este tipo en cualquier programa? (título de Técnico Auxiliar propio)
    public boolean tieneVigente(int idEstudiante, String tipo) throws SQLException {
        String sql = "SELECT 1 FROM CERTIFICADO c JOIN INSCRIPCION i ON i.id_inscripcion = c.id_inscripcion "
                + "WHERE i.id_estudiante = ? AND c.tipo_certificado = ? AND c.estado = 'VIGENTE' LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            ps.setString(2, tipo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // LISCERT: sin filtro ("*"), por gestión de la oferta, por tipo (patrón LIKE: "TITULO\_%" son los títulos) o por estudiante.
    // El hash no se lista
    public Tabla listar(String gestion, String tipo, int idEstudiante) throws SQLException {
        String sql = "SELECT c.codigo_alfanumerico_unico AS codigo, c.tipo_certificado AS tipo, c.numero_titulo, "
                + "e.codigo_estudiante AS registro, e.ci, e.nombre || ' ' || e.paterno || COALESCE(' ' || e.materno, '') AS titular, "
                + "pr.codigo || ' ' || o.gestion || ' ' || o.grupo AS oferta, c.grado_otorgado AS detalle, c.fecha_emision, c.estado, "
                + "an.fecha_anulacion || ': ' || substring(an.motivo_anulacion FROM position('): ' IN an.motivo_anulacion) + 3) AS anulacion "
                + "FROM CERTIFICADO c "
                + "JOIN INSCRIPCION i ON i.id_inscripcion = c.id_inscripcion "
                + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
                + "JOIN PROGRAMA pr ON pr.id_programa = v.id_programa "
                + ANULACION
                + "WHERE (? = '*' OR UPPER(o.gestion) = UPPER(?)) AND (? = '*' OR c.tipo_certificado LIKE ?) AND (? = 0 OR e.id_estudiante = ?) "
                + "ORDER BY c.fecha_emision DESC, c.id_certificado DESC";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, gestion);
            ps.setString(2, gestion);
            ps.setString(3, tipo);
            ps.setString(4, tipo);
            ps.setInt(5, idEstudiante);
            ps.setInt(6, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Certificado mapear(ResultSet rs) throws SQLException {
        Certificado c = new Certificado();
        c.setIdCertificado(rs.getInt("id_certificado"));
        c.setTipoCertificado(rs.getString("tipo_certificado"));
        c.setGradoOtorgado(rs.getString("grado_otorgado"));
        c.setNumeroTitulo(rs.getString("numero_titulo"));
        c.setCodigoAlfanumerico(rs.getString("codigo_alfanumerico_unico"));
        c.setCodigoQrHash(rs.getString("codigo_qr_hash"));
        c.setFechaEmision(Sql.fecha(rs.getDate("fecha_emision")));
        c.setEstado(rs.getString("estado"));
        c.setIdInscripcion(rs.getInt("id_inscripcion"));
        c.setTitular(rs.getString("titular"));
        c.setPrograma(rs.getString("programa"));
        c.setFechaAnulacion(rs.getString("fecha_anulacion"));
        String motivo = rs.getString("motivo_anulacion");
        c.setMotivoAnulacion(motivo == null ? null : motivo.substring(motivo.indexOf("): ") + 3));
        return c;
    }
}
