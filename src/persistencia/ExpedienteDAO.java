package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import src.utilidades.Tabla;

// BUSDOC (CU-07): el expediente completo de uno o varios estudiantes en una sola tabla, para auditorías y
// requerimientos legales: los documentos de DOCUMENTO_DIGITAL, las actas de los módulos que cursó y sus certificados
public class ExpedienteDAO {

    private final Connection con;

    public ExpedienteDAO(Connection con) {
        this.con = con;
    }

    // Un solo criterio: idEstudiante (0 si no), apellido sin tildes y en minúsculas ("" si no) o gestión ("*" si no).
    // Con gestión, las actas y los certificados son solo los de las ofertas de esa gestión
    public Tabla buscar(int idEstudiante, String apellido, String gestion) throws SQLException {
        String filtroGestion = "(? = '*' OR UPPER(o.gestion) = UPPER(?))";
        String sql = "WITH est AS (SELECT e.* FROM ESTUDIANTE e "
                + " WHERE (? = 0 OR e.id_estudiante = ?) "
                + " AND (? = '' OR translate(lower(e.paterno || ' ' || COALESCE(e.materno, '')), 'áéíóúüñ', 'aeiouun') LIKE ?) "
                + " AND (? = '*' OR EXISTS (SELECT 1 FROM INSCRIPCION i JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "   WHERE i.id_estudiante = e.id_estudiante AND UPPER(o.gestion) = UPPER(?)))), "
                // Documentos del expediente y boletas con el estado de su pago
                + "docs AS (SELECT d.id_estudiante, 1 AS orden, 'DOCUMENTO' AS tipo, "
                + " d.tipo_documento || COALESCE(' del pago ' || p.numero_transaccion || ' (' || a.concepto_arancel || ')', '') AS detalle, "
                + " d.fecha_presentacion AS fecha, COALESCE('PAGO ' || p.estado_conciliacion, 'PRESENTADO') AS estado, d.url_doc AS ubicacion "
                + " FROM est JOIN DOCUMENTO_DIGITAL d ON d.id_estudiante = est.id_estudiante "
                + " LEFT JOIN PAGO p ON p.id_pago = d.id_pago LEFT JOIN ARANCEL a ON a.id_arancel = p.id_arancel), "
                // Actas (también las anuladas por enmienda) de los módulos que cursó, con su resultado en el último intento
                + "actas AS (SELECT i.id_estudiante, 2, 'ACTA', "
                + " 'Acta N° ' || ac.id_acta || ' de ' || m.codigo || ' ' || m.nombre || ' (' || pr.codigo || ' ' || o.gestion || ' ' || o.grupo || '): '"
                + "   || COALESCE('nota ' || im.nota_final::text || ' ', '') || im.estado, "
                + " ac.fecha_cierre, ac.estado_acta, "
                + " 'VERACTA[\"' || pr.codigo || '\", \"' || o.gestion || '\", \"' || o.grupo || '\", \"' || m.codigo || '\"]' "
                + " FROM est JOIN INSCRIPCION i ON i.id_estudiante = est.id_estudiante "
                + " JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + " JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version JOIN PROGRAMA pr ON pr.id_programa = v.id_programa "
                + " JOIN (SELECT DISTINCT ON (id_inscripcion, id_modulo) * FROM INSCRIPCION_MODULO "
                + "   ORDER BY id_inscripcion, id_modulo, numero_intento DESC) im ON im.id_inscripcion = i.id_inscripcion "
                + " JOIN MODULO m ON m.id_modulo = im.id_modulo "
                + " JOIN ASIGNACION_MODULO am ON am.id_oferta = i.id_oferta AND am.id_modulo = im.id_modulo "
                + " JOIN ACTA_CALIFICACION ac ON ac.id_asignacion_modulo = am.id_asignacion_modulo "
                + " WHERE " + filtroGestion + "), "
                // Títulos, certificados y constancias emitidos, con su copia de archivo
                + "certs AS (SELECT i.id_estudiante, 3, 'CERTIFICADO', "
                + " c.tipo_certificado || COALESCE(' N° ' || c.numero_titulo, '') || ' (' || pr.codigo || ' ' || o.gestion || ' ' || o.grupo || ')', "
                + " c.fecha_emision, c.estado, c.codigo_alfanumerico_unico || ' - documentos/' || est.ci || '/' || c.codigo_alfanumerico_unico || '.pdf' "
                + " FROM est JOIN INSCRIPCION i ON i.id_estudiante = est.id_estudiante "
                + " JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + " JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version JOIN PROGRAMA pr ON pr.id_programa = v.id_programa "
                + " JOIN CERTIFICADO c ON c.id_inscripcion = i.id_inscripcion "
                + " WHERE " + filtroGestion + ") "
                + "SELECT est.nombre || ' ' || est.paterno || COALESCE(' ' || est.materno, '') AS estudiante, est.ci, "
                + " est.codigo_estudiante AS registro, x.tipo, x.detalle, x.fecha, x.estado, x.ubicacion "
                + "FROM (SELECT * FROM docs UNION ALL SELECT * FROM actas UNION ALL SELECT * FROM certs) x "
                + "JOIN est ON est.id_estudiante = x.id_estudiante "
                + "ORDER BY est.paterno, est.nombre, est.id_estudiante, x.orden, x.fecha NULLS LAST, x.detalle";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, idEstudiante);
            ps.setInt(i++, idEstudiante);
            ps.setString(i++, apellido);
            ps.setString(i++, "%" + apellido + "%");
            ps.setString(i++, gestion);
            ps.setString(i++, gestion);
            for (int k = 0; k < 2; k++) {
                ps.setString(i++, gestion);
                ps.setString(i++, gestion);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
