package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import src.modelo.OfertaAcademica;
import src.utilidades.Tabla;

public class OfertaDAO {
    // Columnas que MODOFER puede modificar
    public static final Set<String> CAMPOS = Set.of("fecha_inicio", "fecha_fin", "cupo_min", "cupo_max", "lugar",
            "costo", "estado");

    // Preinscritos de la oferta: inscripciones vigentes (PREINSCRITO o INSCRITO)
    private static final String PREINSCRITOS = "(SELECT COUNT(*) FROM INSCRIPCION i WHERE i.id_oferta = o.id_oferta "
            + "AND i.estado IN ('PREINSCRITO','INSCRITO'))";

    private static final String DESDE = "FROM OFERTA_ACADEMICA o "
            + "JOIN VERSION_PROGRAMA v ON v.id_version = o.id_version "
            + "JOIN PROGRAMA p ON p.id_programa = v.id_programa ";

    // La oferta con el código de su programa, que junto con la gestión y el grupo la identifica en los comandos
    private static final String SELECCION = "SELECT o.*, p.codigo AS codigo_programa " + DESDE;

    private final Connection con;

    public OfertaDAO(Connection con) {
        this.con = con;
    }

    public int insertar(OfertaAcademica o) throws SQLException {
        String sql = "INSERT INTO OFERTA_ACADEMICA (gestion, grupo, modalidad, lugar, fecha_inicio, fecha_fin, "
                + "cupo_min, cupo_max, costo, id_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id_oferta";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, o.getGestion());
            ps.setString(2, o.getGrupo());
            ps.setString(3, o.getModalidad());
            ps.setString(4, o.getLugar());
            Sql.asignar(ps, 5, o.getFechaInicio());
            Sql.asignar(ps, 6, o.getFechaFin());
            ps.setInt(7, o.getCupoMin());
            Sql.asignar(ps, 8, o.getCupoMax());
            ps.setBigDecimal(9, o.getCosto());
            ps.setInt(10, o.getIdVersion());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                o.setIdOferta(rs.getInt(1));
                return o.getIdOferta();
            }
        }
    }

    public OfertaAcademica buscarPorId(int idOferta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECCION + "WHERE o.id_oferta = ?")) {
            ps.setInt(1, idOferta);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    // En los comandos la oferta se identifica por programa, gestión y grupo: "TAC", "2-2026", "A".
    // ADDOFER no repite el grupo dentro del programa y la gestión, aunque cambie la versión
    public OfertaAcademica buscar(String codigoPrograma, String gestion, String grupo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECCION + "WHERE UPPER(p.codigo) = UPPER(?) "
                + "AND UPPER(o.gestion) = UPPER(?) AND UPPER(o.grupo) = UPPER(?) ORDER BY o.id_oferta DESC LIMIT 1")) {
            ps.setString(1, codigoPrograma.trim());
            ps.setString(2, gestion.trim());
            ps.setString(3, grupo.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public void actualizarCampo(int idOferta, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en OFERTA_ACADEMICA: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE OFERTA_ACADEMICA SET " + campo + " = ? WHERE id_oferta = ?")) {
            Sql.asignar(ps, 1, valor);
            ps.setInt(2, idOferta);
            ps.executeUpdate();
        }
    }

    public void cambiarEstado(int idOferta, String estado) throws SQLException {
        actualizarCampo(idOferta, "estado", estado);
    }

    // Solo para ofertas PLANIFICADA sin inscripciones: sus asignaciones, horarios y sesiones se borran en cascada
    public void eliminar(int idOferta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM OFERTA_ACADEMICA WHERE id_oferta = ?")) {
            ps.setInt(1, idOferta);
            ps.executeUpdate();
        }
    }

    public int contarPreinscritos(int idOferta) throws SQLException {
        return contar("SELECT COUNT(*) FROM INSCRIPCION WHERE id_oferta = ? AND estado IN ('PREINSCRITO','INSCRITO')",
                idOferta);
    }

    public int contarInscripciones(int idOferta) throws SQLException {
        return contar("SELECT COUNT(*) FROM INSCRIPCION WHERE id_oferta = ?", idOferta);
    }

    // [codigo, fecha_inicio, fecha_fin] de las asignaciones que quedarían fuera del nuevo rango de fechas de la oferta
    public List<String[]> asignacionesFueraDeRango(int idOferta, LocalDate inicio, LocalDate fin) throws SQLException {
        String sql = "SELECT m.codigo, am.fecha_inicio, am.fecha_fin FROM ASIGNACION_MODULO am "
                + "JOIN MODULO m ON m.id_modulo = am.id_modulo "
                + "WHERE am.id_oferta = ? AND (am.fecha_inicio < ? OR am.fecha_fin > ?) ORDER BY am.fecha_inicio";
        List<String[]> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            Sql.asignar(ps, 2, inicio);
            Sql.asignar(ps, 3, fin);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
                }
            }
        }
        return lista;
    }

    // [email, nombre] de los preinscritos e inscritos con correo registrado, para notificarles
    public List<String[]> correosPreinscritos(int idOferta) throws SQLException {
        String sql = "SELECT e.email, e.nombre || ' ' || e.paterno FROM INSCRIPCION i "
                + "JOIN ESTUDIANTE e ON e.id_estudiante = i.id_estudiante "
                + "WHERE i.id_oferta = ? AND i.estado IN ('PREINSCRITO','INSCRITO') AND e.email IS NOT NULL";
        List<String[]> correos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idOferta);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    correos.add(new String[] { rs.getString(1), rs.getString(2) });
                }
            }
        }
        return correos;
    }

    // LISOFER["*" | estado | gestion | codigo_programa]: con los preinscritos frente al cupo mínimo
    public Tabla listar(String filtro) throws SQLException {
        String sql = "SELECT p.codigo AS programa, o.gestion, o.grupo, p.nombre, v.numero_version AS version, "
                + "o.modalidad, o.lugar, o.fecha_inicio, o.fecha_fin, "
                + PREINSCRITOS + " AS preinscritos, o.cupo_min, o.cupo_max, o.costo, o.estado "
                + DESDE
                + "WHERE ? = '*' OR o.estado = UPPER(?) OR UPPER(o.gestion) = UPPER(?) OR p.codigo ILIKE ? "
                + "ORDER BY o.fecha_inicio, p.codigo, o.grupo";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, filtro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    // CATALOGO (público): ofertas con preinscripciones abiertas
    public Tabla catalogo() throws SQLException {
        String sql = "SELECT p.codigo AS programa, o.gestion, o.grupo, p.nombre, p.nivel_titulacion AS nivel, "
                + "o.modalidad, o.lugar, o.fecha_inicio, o.fecha_fin, o.costo, "
                + "CASE WHEN o.cupo_max IS NULL THEN 'Sin limite' "
                + "     ELSE GREATEST(o.cupo_max - " + PREINSCRITOS + ", 0)::text END AS cupos_disponibles "
                + DESDE
                + "WHERE o.estado = 'PREINSCRIPCIONES_ABIERTAS' ORDER BY o.fecha_inicio, p.codigo, o.grupo";
        try (PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return Tabla.desde(rs);
        }
    }

    // Aviso automático: ofertas que empiezan en 3 días o menos sin alcanzar el cupo mínimo.
    // Columnas: id_oferta, codigo_programa, programa, gestion, grupo, fecha_inicio, cupo_min, preinscritos
    public Tabla ofertasSinCupoMinimo() throws SQLException {
        String sql = "SELECT o.id_oferta, p.codigo, p.nombre AS programa, o.gestion, o.grupo, o.fecha_inicio, o.cupo_min, "
                + PREINSCRITOS + " AS preinscritos "
                + DESDE
                + "WHERE o.estado IN ('PREINSCRIPCIONES_ABIERTAS','POSTERGADA') "
                + "AND o.fecha_inicio BETWEEN CURRENT_DATE AND CURRENT_DATE + 3 "
                + "AND " + PREINSCRITOS + " < o.cupo_min "
                + "ORDER BY o.fecha_inicio";
        try (PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return Tabla.desde(rs);
        }
    }

    private int contar(String sql, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private OfertaAcademica mapear(ResultSet rs) throws SQLException {
        OfertaAcademica o = new OfertaAcademica();
        o.setIdOferta(rs.getInt("id_oferta"));
        o.setGestion(rs.getString("gestion"));
        o.setGrupo(rs.getString("grupo"));
        o.setModalidad(rs.getString("modalidad"));
        o.setLugar(rs.getString("lugar"));
        o.setFechaInicio(Sql.fecha(rs.getDate("fecha_inicio")));
        o.setFechaFin(Sql.fecha(rs.getDate("fecha_fin")));
        o.setCupoMin(rs.getInt("cupo_min"));
        int cupoMax = rs.getInt("cupo_max");
        o.setCupoMax(rs.wasNull() ? null : cupoMax);
        o.setCosto(rs.getBigDecimal("costo"));
        o.setEstado(rs.getString("estado"));
        o.setIdVersion(rs.getInt("id_version"));
        o.setCodigoPrograma(rs.getString("codigo_programa"));
        return o;
    }
}
