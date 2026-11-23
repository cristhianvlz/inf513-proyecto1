package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import src.utilidades.Tabla;

// CU-08: consultas de los reportes. Solo leen: no registran, modifican ni eliminan datos
public class ReporteDAO {

    private static final String OFERTA = "pr.codigo || ' ' || o.gestion || ' ' || o.grupo";
    private static final String DESDE_OFERTA = "FROM OFERTA_ACADEMICA o JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
            + "JOIN PROGRAMA pr ON pr.id_programa = v.id_programa ";

    private final Connection con;

    public ReporteDAO(Connection con) {
        this.con = con;
    }

    // REPING: qué pagos entran al reporte. gestion "*" = todas; idPrograma e idOferta 0 = todos; desde y hasta acotan
    // la fecha del depósito (null = sin límite)
    public record FiltroPagos(String gestion, int idPrograma, int idOferta, LocalDate desde, LocalDate hasta) {
    }

    private static final String DESDE_PAGOS = "FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
            + "JOIN INSCRIPCION i ON i.id_inscripcion = p.id_inscripcion "
            + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
            + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version JOIN PROGRAMA pr ON pr.id_programa = v.id_programa "
            + "LEFT JOIN CONVENIO cv ON cv.id_convenio = i.id_convenio ";

    private static final String FILTRO_PAGOS = "(? = '*' OR UPPER(o.gestion) = UPPER(?)) AND (? = 0 OR pr.id_programa = ?) "
            + "AND (? = 0 OR o.id_oferta = ?) AND (?::date IS NULL OR p.fecha_pago::date >= ?::date) "
            + "AND (?::date IS NULL OR p.fecha_pago::date <= ?::date) ";

    private static final String CONVENIO = "COALESCE(cv.institucion || ' (' || cv.porcentaje_descuento || '%)', 'Sin convenio')";

    // REPING: recaudación conciliada por periodo (MES o DIA), programa, oferta, concepto y convenio
    public Tabla recaudacion(String periodo, FiltroPagos f) throws SQLException {
        String formato = "MES".equals(periodo) ? "YYYY-MM" : "YYYY-MM-DD";
        String sql = "SELECT to_char(p.fecha_pago, '" + formato + "') AS " + ("MES".equals(periodo) ? "mes" : "dia") + ", "
                + "pr.codigo AS programa, o.gestion || ' ' || o.grupo AS oferta, a.concepto_arancel AS concepto, "
                + CONVENIO + " AS convenio, COUNT(*) AS pagos, SUM(p.monto) AS total_bs "
                + DESDE_PAGOS + "WHERE p.estado_conciliacion = 'CONCILIADO' AND " + FILTRO_PAGOS
                + "GROUP BY 1, 2, 3, 4, 5 ORDER BY 1, 2, 3, 4, 5";
        return consultaPagos(sql, f);
    }

    // REPING: pagos con el mismo filtro según su estado de conciliación (los PENDIENTE y OBSERVADO aún no son ingreso).
    // Una orden de la pasarela sin pagar no es un depósito por conciliar: va aparte, como POR PAGAR (PASARELA)
    public Tabla pagosPorEstado(FiltroPagos f) throws SQLException {
        String sql = "SELECT CASE WHEN p.estado_conciliacion = 'PENDIENTE' AND p.relacion_depositante = 'PASARELA' "
                + "THEN 'POR PAGAR (PASARELA)' ELSE p.estado_conciliacion END AS estado, COUNT(*) AS pagos, SUM(p.monto) AS total_bs, "
                + "COUNT(DISTINCT i.id_estudiante) AS estudiantes "
                + DESDE_PAGOS + "WHERE " + FILTRO_PAGOS + "GROUP BY 1 ORDER BY 1";
        return consultaPagos(sql, f);
    }

    // REPING: lo conciliado por convenio; el descuento es el monto del arancel menos lo cobrado
    public Tabla recaudacionPorConvenio(FiltroPagos f) throws SQLException {
        String sql = "SELECT " + CONVENIO + " AS convenio, COUNT(DISTINCT i.id_estudiante) AS beneficiarios, COUNT(*) AS pagos, "
                + "SUM(a.monto) AS arancel_bs, SUM(GREATEST(a.monto - p.monto, 0)) AS descuento_bs, SUM(p.monto) AS cobrado_bs "
                + DESDE_PAGOS + "WHERE p.estado_conciliacion = 'CONCILIADO' AND " + FILTRO_PAGOS
                + "GROUP BY 1 ORDER BY (" + CONVENIO + " = 'Sin convenio'), 1";
        return consultaPagos(sql, f);
    }

    private Tabla consultaPagos(String sql, FiltroPagos f) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, f.gestion());
            ps.setString(2, f.gestion());
            ps.setInt(3, f.idPrograma());
            ps.setInt(4, f.idPrograma());
            ps.setInt(5, f.idOferta());
            ps.setInt(6, f.idOferta());
            Sql.asignar(ps, 7, f.desde());
            Sql.asignar(ps, 8, f.desde());
            Sql.asignar(ps, 9, f.hasta());
            Sql.asignar(ps, 10, f.hasta());
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // REPACAD: estados de las inscripciones de cada oferta (idOferta 0 = todas las de la gestión)
    public Tabla inscripcionesPorOferta(int idOferta, String gestion) throws SQLException {
        String sql = "SELECT " + OFERTA + " AS oferta, o.estado AS estado_oferta, "
                + "COUNT(i.*) FILTER (WHERE i.estado = 'PREINSCRITO') AS preinscritos, "
                + "COUNT(i.*) FILTER (WHERE i.estado = 'INSCRITO') AS inscritos, "
                + "COUNT(i.*) FILTER (WHERE i.estado = 'CONCLUIDO') AS concluidos, "
                + "COUNT(i.*) FILTER (WHERE i.estado = 'ABANDONO') AS abandonos, "
                + "COUNT(i.*) FILTER (WHERE i.estado = 'RETIRADO') AS retirados, "
                + "COUNT(i.*) FILTER (WHERE i.estado = 'TRASPASADO') AS traspasados, COUNT(i.*) AS total "
                + DESDE_OFERTA + "LEFT JOIN INSCRIPCION i ON i.id_oferta = o.id_oferta "
                + "WHERE (? = 0 OR o.id_oferta = ?) AND (? = '*' OR UPPER(o.gestion) = UPPER(?)) "
                + "GROUP BY o.id_oferta, pr.codigo, o.gestion, o.grupo, o.estado ORDER BY pr.codigo, o.grupo";
        return consultaOferta(sql, idOferta, gestion);
    }

    // REPACAD: resultado final (último intento) de cada estudiante en cada módulo de la oferta
    public Tabla resultadosPorModulo(int idOferta, String gestion) throws SQLException {
        String sql = "SELECT " + OFERTA + " AS oferta, m.codigo || ' ' || m.nombre AS modulo, COUNT(*) AS cursantes, "
                + "COUNT(*) FILTER (WHERE im.estado = 'EN_CURSO') AS en_curso, "
                + "COUNT(*) FILTER (WHERE im.estado = 'APROBADO') AS aprobados, "
                + "COUNT(*) FILTER (WHERE im.estado = 'REPROBADO') AS reprobados, "
                + "COUNT(*) FILTER (WHERE im.estado = 'PERDIDA_POR_INASISTENCIA') AS perdida_por_inasistencia, "
                + "COUNT(*) FILTER (WHERE im.estado = 'ABANDONO') AS abandonos, "
                + "CASE WHEN COUNT(*) FILTER (WHERE im.estado IN ('APROBADO','REPROBADO','PERDIDA_POR_INASISTENCIA')) = 0 THEN '-' "
                + " ELSE ROUND(100.0 * COUNT(*) FILTER (WHERE im.estado = 'APROBADO') / "
                + "  COUNT(*) FILTER (WHERE im.estado IN ('APROBADO','REPROBADO','PERDIDA_POR_INASISTENCIA')), 1) || ' %' END AS aprobacion, "
                + "COALESCE(ROUND(AVG(im.nota_final) FILTER (WHERE im.estado <> 'EN_CURSO'), 2)::text, '-') AS promedio "
                + DESDE_OFERTA
                + "JOIN INSCRIPCION i ON i.id_oferta = o.id_oferta "
                + "JOIN (SELECT DISTINCT ON (id_inscripcion, id_modulo) * FROM INSCRIPCION_MODULO "
                + "  ORDER BY id_inscripcion, id_modulo, numero_intento DESC) im ON im.id_inscripcion = i.id_inscripcion "
                + "JOIN MODULO m ON m.id_modulo = im.id_modulo "
                + "WHERE (? = 0 OR o.id_oferta = ?) AND (? = '*' OR UPPER(o.gestion) = UPPER(?)) "
                + "GROUP BY o.id_oferta, pr.codigo, o.gestion, o.grupo, m.codigo, m.nombre ORDER BY pr.codigo, o.grupo, m.codigo";
        return consultaOferta(sql, idOferta, gestion);
    }

    // REPDEM: preinscritos (PREINSCRITO o INSCRITO, la misma cuenta que exige MODOFER para confirmar) frente al cupo mínimo.
    // idOferta 0 = las ofertas que esperan la decisión de confirmarse o postergarse
    public Tabla demanda(int idOferta) throws SQLException {
        String sql = "SELECT " + OFERTA + " AS oferta, pr.codigo AS programa, o.gestion, o.grupo, o.estado, o.fecha_inicio, "
                + "(o.fecha_inicio - CURRENT_DATE) AS dias_al_inicio, o.cupo_min, o.cupo_max, "
                + "(SELECT COUNT(*) FROM INSCRIPCION i WHERE i.id_oferta = o.id_oferta AND i.estado IN ('PREINSCRITO','INSCRITO')) AS preinscritos "
                + DESDE_OFERTA
                + "WHERE (? > 0 AND o.id_oferta = ?) OR (? = 0 AND o.estado IN ('PLANIFICADA','PREINSCRIPCIONES_ABIERTAS','POSTERGADA')) "
                + "ORDER BY o.fecha_inicio, pr.codigo, o.grupo";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            ps.setInt(2, idOferta);
            ps.setInt(3, idOferta);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // REPNOTAS: todas las notas finales de la asignación, también los intentos de recuperatorio
    public Tabla notasDeAsignacion(int idAsignacion) throws SQLException {
        String sql = "SELECT e.codigo_estudiante AS codigo, e.ci, e.nombre || ' ' || e.paterno || COALESCE(' ' || e.materno, '') AS estudiante, "
                + "im.numero_intento AS intento, im.condicion, im.porcentaje_asistencia AS asistencia, im.nota_final, im.estado, "
                + "(SELECT COUNT(*) FROM ENMIENDA_NOTA en WHERE en.id_inscripcion_modulo = im.id_inscripcion_modulo) AS enmiendas "
                + "FROM ASIGNACION_MODULO a "
                + "JOIN INSCRIPCION i ON i.id_oferta = a.id_oferta "
                + "JOIN INSCRIPCION_MODULO im ON im.id_inscripcion = i.id_inscripcion AND im.id_modulo = a.id_modulo "
                + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "WHERE a.id_asignacion_modulo = ? ORDER BY e.paterno, e.nombre, im.numero_intento";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idAsignacion);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // REPCERT: cantidad de documentos emitidos por tipo en el periodo, vigentes y anulados
    public Tabla resumenCertificados(LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT c.tipo_certificado AS tipo, COUNT(*) FILTER (WHERE c.estado = 'VIGENTE') AS vigentes, "
                + "COUNT(*) FILTER (WHERE c.estado = 'ANULADO') AS anulados, COUNT(*) AS emitidos "
                + "FROM CERTIFICADO c WHERE c.fecha_emision BETWEEN ? AND ? GROUP BY 1 ORDER BY 1";
        return consultaPeriodo(sql, desde, hasta);
    }

    // REPCERT: el detalle de cada documento emitido en el periodo
    public Tabla certificados(LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT c.codigo_alfanumerico_unico AS codigo, c.tipo_certificado AS tipo, c.numero_titulo, "
                + "e.nombre || ' ' || e.paterno || COALESCE(' ' || e.materno, '') AS titular, e.ci, " + OFERTA + " AS oferta, "
                + "c.grado_otorgado AS detalle, c.fecha_emision, c.estado "
                + "FROM CERTIFICADO c JOIN INSCRIPCION i ON i.id_inscripcion = c.id_inscripcion "
                + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
                + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version JOIN PROGRAMA pr ON pr.id_programa = v.id_programa "
                + "WHERE c.fecha_emision BETWEEN ? AND ? ORDER BY c.fecha_emision, c.codigo_alfanumerico_unico";
        return consultaPeriodo(sql, desde, hasta);
    }

    // REPLOG: acciones de LOG en el rango, de la más antigua a la más nueva; como máximo "limite" filas
    public Tabla bitacora(LocalDate desde, LocalDate hasta, int limite) throws SQLException {
        String sql = "SELECT to_char(l.fecha, 'YYYY-MM-DD HH24:MI:SS') AS fecha, u.usuario, u.email, "
                + "COALESCE(pe.nombre || ' ' || pe.apellidos, '-') AS nombre, l.tabla_afectada AS tabla, l.accion "
                + "FROM LOG l JOIN USUARIO u ON u.id_usuario = l.id_usuario LEFT JOIN PERSONAL pe ON pe.id_personal = u.id_personal "
                + "WHERE l.fecha::date BETWEEN ? AND ? ORDER BY l.fecha, l.id_log LIMIT " + limite;
        return consultaPeriodo(sql, desde, hasta);
    }

    // REPLOG: cuántas acciones hizo cada usuario en el rango, por tipo de acción
    public Tabla resumenBitacora(LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT u.usuario, u.email, COUNT(*) AS acciones, "
                + "COUNT(*) FILTER (WHERE l.accion = 'INSERT') AS registros, COUNT(*) FILTER (WHERE l.accion = 'UPDATE') AS modificaciones, "
                + "COUNT(*) FILTER (WHERE l.accion = 'DELETE') AS eliminaciones, COUNT(*) FILTER (WHERE l.accion = 'SELECT') AS consultas, "
                + "COUNT(*) FILTER (WHERE l.accion = 'RECHAZADO') AS rechazados "
                + "FROM LOG l JOIN USUARIO u ON u.id_usuario = l.id_usuario "
                + "WHERE l.fecha::date BETWEEN ? AND ? GROUP BY u.id_usuario, u.usuario, u.email ORDER BY acciones DESC, u.usuario";
        return consultaPeriodo(sql, desde, hasta);
    }

    // REPLOG: cuántas acciones se registraron cada día del rango
    public Tabla bitacoraPorDia(LocalDate desde, LocalDate hasta) throws SQLException {
        String sql = "SELECT to_char(l.fecha, 'YYYY-MM-DD') AS dia, COUNT(*) AS acciones FROM LOG l "
                + "WHERE l.fecha::date BETWEEN ? AND ? GROUP BY 1 ORDER BY 1";
        return consultaPeriodo(sql, desde, hasta);
    }

    // Quién pidió el reporte: nombre del personal (o el usuario) y su correo
    public String solicitante(int idUsuario) throws SQLException {
        String sql = "SELECT COALESCE(pe.nombre || ' ' || pe.apellidos, u.usuario) || ' (' || u.email || ')' FROM USUARIO u "
                + "LEFT JOIN PERSONAL pe ON pe.id_personal = u.id_personal WHERE u.id_usuario = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private Tabla consultaOferta(String sql, int idOferta, String gestion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            ps.setInt(2, idOferta);
            ps.setString(3, gestion);
            ps.setString(4, gestion);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Tabla consultaPeriodo(String sql, LocalDate desde, LocalDate hasta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Sql.asignar(ps, 1, desde);
            Sql.asignar(ps, 2, hasta);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }
}
