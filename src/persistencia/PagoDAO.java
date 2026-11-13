package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.Pago;
import src.utilidades.Tabla;

public class PagoDAO {
    // Columnas que MODPAGO puede modificar (la cuenta de origen va dentro de concepto_pago)
    public static final Set<String> CAMPOS = Set.of("monto", "numero_transaccion", "fecha_pago", "nombre_del_depositante",
            "relacion_depositante");

    private static final String LISTADO = "SELECT p.numero_transaccion AS transaccion, to_char(p.fecha_pago, 'YYYY-MM-DD') AS fecha, "
            + "e.ci, e.nombre || ' ' || e.paterno AS estudiante, pr.codigo || ' ' || o.gestion || ' ' || o.grupo AS oferta, "
            + "a.concepto_arancel AS concepto, m.codigo AS modulo, p.monto, p.nombre_del_depositante AS depositante, "
            + "p.relacion_depositante AS \"nombre del estudiante\", p.estado_conciliacion AS estado, p.concepto_pago AS detalle "
            + "FROM PAGO p "
            + "JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
            + "JOIN INSCRIPCION i ON i.id_inscripcion = p.id_inscripcion "
            + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
            + "JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta "
            + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
            + "JOIN PROGRAMA pr ON pr.id_programa = v.id_programa "
            + "LEFT JOIN INSCRIPCION_MODULO im ON im.id_inscripcion_modulo = p.id_inscripcion_modulo "
            + "LEFT JOIN MODULO m ON m.id_modulo = im.id_modulo ";

    private final Connection con;

    public PagoDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Pago p) throws SQLException {
        String sql = "INSERT INTO PAGO (fecha_pago, monto, numero_transaccion, nombre_del_depositante, relacion_depositante, "
                + "concepto_pago, estado_conciliacion, id_inscripcion, id_arancel, id_inscripcion_modulo) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id_pago";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Sql.asignar(ps, 1, p.getFechaPago());
            ps.setBigDecimal(2, p.getMonto());
            ps.setString(3, p.getNumeroTransaccion());
            ps.setString(4, p.getNombreDepositante());
            ps.setString(5, p.getRelacionDepositante());
            ps.setString(6, p.getConceptoPago());
            ps.setString(7, p.getEstadoConciliacion());
            ps.setInt(8, p.getIdInscripcion());
            ps.setInt(9, p.getIdArancel());
            Sql.asignar(ps, 10, p.getIdInscripcionModulo());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                p.setIdPago(rs.getInt(1));
                return p.getIdPago();
            }
        }
    }

    // En los comandos el pago se identifica por su número de transacción, que es único
    public Pago buscarPorTransaccion(String numeroTransaccion) throws SQLException {
        String sql = "SELECT p.*, a.concepto_arancel FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "WHERE UPPER(p.numero_transaccion) = UPPER(?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, numeroTransaccion.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public void actualizarCampo(int idPago, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en PAGO: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE PAGO SET " + campo + " = ? WHERE id_pago = ?")) {
            Sql.asignar(ps, 1, valor);
            ps.setInt(2, idPago);
            ps.executeUpdate();
        }
    }

    // Guarda el estado junto con concepto_pago, que lleva la cuenta de origen y el motivo
    public void guardarEstado(Pago p) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE PAGO SET estado_conciliacion = ?, concepto_pago = ? WHERE id_pago = ?")) {
            ps.setString(1, p.getEstadoConciliacion());
            ps.setString(2, p.getConceptoPago());
            ps.setInt(3, p.getIdPago());
            ps.executeUpdate();
        }
    }

    // FORMINS exige la matrícula conciliada
    public boolean matriculaConciliada(int idInscripcion) throws SQLException {
        String sql = "SELECT 1 FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "WHERE p.id_inscripcion = ? AND a.concepto_arancel = 'MATRICULA' AND p.estado_conciliacion = 'CONCILIADO' LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Una matrícula ya depositada, en espera de conciliar o conciliada, salvo el pago indicado
    public boolean otraMatriculaActiva(int idInscripcion, int idPagoExcluido, boolean soloConciliada) throws SQLException {
        String sql = "SELECT 1 FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "WHERE p.id_inscripcion = ? AND p.id_pago <> ? AND a.concepto_arancel = 'MATRICULA' AND "
                + (soloConciliada ? "p.estado_conciliacion = 'CONCILIADO'" : "p.estado_conciliacion IN ('PENDIENTE','CONCILIADO')")
                + " LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            ps.setInt(2, idPagoExcluido);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Mensualidad conciliada que todavía no respalda ningún módulo; null si no hay.
    // PAGO apunta a INSCRIPCION_MODULO, que recién existe después de INSMOD: por eso el pago queda libre hasta entonces
    public Integer mensualidadDisponible(int idInscripcion) throws SQLException {
        String sql = "SELECT p.id_pago FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "WHERE p.id_inscripcion = ? AND a.concepto_arancel IN ('MODULO_MENSUAL_CIUDAD','MODULO_MENSUAL_PROVINCIA') "
                + "AND p.estado_conciliacion = 'CONCILIADO' AND p.id_inscripcion_modulo IS NULL "
                + "ORDER BY p.fecha_pago LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    // Pago de recuperatorio conciliado que todavía no respalda ningún intento; null si no hay (HABREC lo toma)
    public Integer recuperatorioDisponible(int idInscripcion) throws SQLException {
        String sql = "SELECT p.id_pago FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "WHERE p.id_inscripcion = ? AND a.concepto_arancel = 'RECUPERATORIO' "
                + "AND p.estado_conciliacion = 'CONCILIADO' AND p.id_inscripcion_modulo IS NULL ORDER BY p.fecha_pago LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    // Pago conciliado del arancel (TITULO o CERTIFICADO) que puede respaldar un documento nuevo; null si no hay.
    // Sirve uno libre o el de un documento del mismo tipo que se anuló: su reposición no se vuelve a cobrar
    public Pago documentoDisponible(int idInscripcion, String concepto, String tipoCertificado) throws SQLException {
        String sql = "SELECT p.*, a.concepto_arancel FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel "
                + "LEFT JOIN CERTIFICADO c ON c.id_certificado = p.id_certificado "
                + "WHERE p.id_inscripcion = ? AND a.concepto_arancel = ? AND p.estado_conciliacion = 'CONCILIADO' "
                + "AND (p.id_certificado IS NULL OR (c.estado = 'ANULADO' AND c.tipo_certificado = ?)) "
                + "ORDER BY p.id_certificado IS NULL, p.fecha_pago, p.id_pago LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            ps.setString(2, concepto);
            ps.setString(3, tipoCertificado);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // "numero (estado)" de un pago del concepto que todavía no está conciliado; null si no hay
    public String pagoSinConciliar(int idInscripcion, String concepto) throws SQLException {
        String sql = "SELECT p.numero_transaccion || ' (' || p.estado_conciliacion || ')' FROM PAGO p "
                + "JOIN ARANCEL a ON a.id_arancel = p.id_arancel WHERE p.id_inscripcion = ? AND a.concepto_arancel = ? "
                + "AND p.estado_conciliacion IN ('PENDIENTE','OBSERVADO') ORDER BY p.fecha_pago DESC LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idInscripcion);
            ps.setString(2, concepto);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void asociarCertificado(int idPago, int idCertificado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE PAGO SET id_certificado = ? WHERE id_pago = ?")) {
            ps.setInt(1, idCertificado);
            ps.setInt(2, idPago);
            ps.executeUpdate();
        }
    }

    public void asociarModulo(int idPago, int idInscripcionModulo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE PAGO SET id_inscripcion_modulo = ? WHERE id_pago = ?")) {
            ps.setInt(1, idInscripcionModulo);
            ps.setInt(2, idPago);
            ps.executeUpdate();
        }
    }

    // Código del módulo EN_CURSO que respalda el pago; null si no respalda ninguno
    public String moduloEnCursoRespaldado(int idPago) throws SQLException {
        String sql = "SELECT m.codigo FROM PAGO p "
                + "JOIN INSCRIPCION_MODULO im ON im.id_inscripcion_modulo = p.id_inscripcion_modulo AND im.estado = 'EN_CURSO' "
                + "JOIN MODULO m ON m.id_modulo = im.id_modulo WHERE p.id_pago = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idPago);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // LISPAGO: sin filtro ("*" en los dos), por estado o por fecha (AAAA-MM-DD)
    public Tabla listar(String estado, String fecha) throws SQLException {
        String sql = LISTADO + "WHERE (? = '*' OR p.estado_conciliacion = ?) "
                + "AND (? = '*' OR to_char(p.fecha_pago, 'YYYY-MM-DD') = ?) ORDER BY p.fecha_pago DESC, p.id_pago DESC";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, estado);
            ps.setString(2, estado);
            ps.setString(3, fecha);
            ps.setString(4, fecha);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // ESTCTA: todos los pagos del estudiante
    public Tabla listarPorEstudiante(int idEstudiante) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(LISTADO + "WHERE i.id_estudiante = ? ORDER BY p.fecha_pago, p.id_pago")) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // ESTCTA: por inscripción y concepto, lo debido, lo conciliado y el saldo (negativo = saldo a favor).
    // La matrícula se debe desde que la oferta se confirma, y una mensualidad por cada módulo registrado. Lo ya conciliado
    // no cambia aunque después cambie el arancel o el convenio; lo no pagado se calcula con el arancel y el convenio actuales.
    // Los demás conceptos no se deben hasta que se solicitan, así que solo se muestran si tienen pagos. La última columna es el saldo
    public Tabla estadoDeCuenta(int idEstudiante) throws SQLException {
        String sql = "WITH ins AS ("
                + " SELECT i.id_inscripcion, i.estado, pr.codigo AS programa, o.gestion, o.grupo,"
                + "  COALESCE(CASE WHEN c.estado <> 'RESCINDIDO' THEN c.porcentaje_descuento END, 0) AS descuento,"
                + "  (i.estado IN ('PREINSCRITO','INSCRITO','CONCLUIDO') AND o.estado IN ('CONFIRMADA','EN_CURSO','FINALIZADA')) AS cobrable"
                + " FROM INSCRIPCION i"
                + " JOIN OFERTA_ACADEMICA o ON o.id_oferta = i.id_oferta"
                + " JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version"
                + " JOIN PROGRAMA pr ON pr.id_programa = v.id_programa"
                + " LEFT JOIN CONVENIO c ON c.id_convenio = i.id_convenio"
                + " WHERE i.id_estudiante = ?"
                + "), conciliado AS ("
                + " SELECT p.id_inscripcion, CASE WHEN a.concepto_arancel LIKE 'MODULO_MENSUAL%' THEN 'MENSUALIDADES' ELSE a.concepto_arancel END AS concepto,"
                + "  SUM(p.monto) AS total"
                + " FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel"
                + " WHERE p.estado_conciliacion = 'CONCILIADO' AND p.id_inscripcion IN (SELECT id_inscripcion FROM ins)"
                + " GROUP BY 1, 2"
                + "), debido AS ("
                + " SELECT ins.id_inscripcion, 'MATRICULA' AS concepto,"
                + "  COALESCE((SELECT c.total FROM conciliado c WHERE c.id_inscripcion = ins.id_inscripcion AND c.concepto = 'MATRICULA'),"
                + "   CASE WHEN ins.cobrable THEN ROUND(COALESCE((SELECT a.monto FROM ARANCEL a WHERE a.concepto_arancel = 'MATRICULA'"
                + "    AND UPPER(a.gestion) = UPPER(ins.gestion) ORDER BY (a.estado = 'VIGENTE') DESC, a.id_arancel DESC LIMIT 1), 0)"
                + "    * (100 - ins.descuento) / 100, 2) ELSE 0 END) AS total"
                + " FROM ins"
                + " UNION ALL"
                + " SELECT ins.id_inscripcion, 'MENSUALIDADES',"
                + "  COALESCE(SUM(CASE WHEN im.id_inscripcion_modulo IS NULL THEN 0 ELSE COALESCE("
                + "   (SELECT SUM(p.monto) FROM PAGO p WHERE p.id_inscripcion_modulo = im.id_inscripcion_modulo AND p.estado_conciliacion = 'CONCILIADO'),"
                + "   ROUND(COALESCE("
                + "    (SELECT a.monto FROM PAGO p JOIN ARANCEL a ON a.id_arancel = p.id_arancel"
                + "     WHERE p.id_inscripcion_modulo = im.id_inscripcion_modulo ORDER BY p.id_pago LIMIT 1),"
                + "    (SELECT a.monto FROM ARANCEL a WHERE a.concepto_arancel = 'MODULO_MENSUAL_CIUDAD' AND UPPER(a.gestion) = UPPER(ins.gestion)"
                + "     ORDER BY (a.estado = 'VIGENTE') DESC, a.id_arancel DESC LIMIT 1), 0)"
                + "    * (100 - ins.descuento) / 100, 2)) END), 0)"
                + " FROM ins LEFT JOIN INSCRIPCION_MODULO im ON im.id_inscripcion = ins.id_inscripcion AND im.condicion <> 'RECUPERATORIO'"
                + " GROUP BY ins.id_inscripcion, ins.descuento"
                + ")"
                + " SELECT ins.programa, ins.gestion, ins.grupo, ins.estado AS inscripcion, ins.descuento AS descuento_pct, d.concepto,"
                + "  d.total AS debido, COALESCE(c.total, 0) AS conciliado, d.total - COALESCE(c.total, 0) AS saldo"
                + " FROM debido d JOIN ins ON ins.id_inscripcion = d.id_inscripcion"
                + " LEFT JOIN conciliado c ON c.id_inscripcion = d.id_inscripcion AND c.concepto = d.concepto"
                + " UNION ALL"
                + " SELECT ins.programa, ins.gestion, ins.grupo, ins.estado, ins.descuento, c.concepto, c.total, c.total, 0"
                + " FROM conciliado c JOIN ins ON ins.id_inscripcion = c.id_inscripcion"
                + " WHERE c.concepto NOT IN ('MATRICULA','MENSUALIDADES')"
                + " ORDER BY 1, 2, 3, 6";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idEstudiante);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Pago mapear(ResultSet rs) throws SQLException {
        Pago p = new Pago();
        p.setIdPago(rs.getInt("id_pago"));
        p.setFechaPago(rs.getTimestamp("fecha_pago").toLocalDateTime().toLocalDate());
        p.setMonto(rs.getBigDecimal("monto"));
        p.setNumeroTransaccion(rs.getString("numero_transaccion"));
        p.setNombreDepositante(rs.getString("nombre_del_depositante"));
        p.setRelacionDepositante(rs.getString("relacion_depositante"));
        p.setEstadoConciliacion(rs.getString("estado_conciliacion"));
        p.setIdInscripcion(rs.getInt("id_inscripcion"));
        p.setIdArancel(rs.getInt("id_arancel"));
        int modulo = rs.getInt("id_inscripcion_modulo");
        p.setIdInscripcionModulo(rs.wasNull() ? null : modulo);
        int certificado = rs.getInt("id_certificado");
        p.setIdCertificado(rs.wasNull() ? null : certificado);
        p.setConcepto(rs.getString("concepto_arancel"));
        p.leerConceptoPago(rs.getString("concepto_pago"));
        return p;
    }
}
