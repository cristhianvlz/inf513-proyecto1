package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Set;
import src.modelo.VersionPrograma;
import src.utilidades.Tabla;

public class VersionDAO {
    // Columnas que MODVER puede modificar (solo en versiones EN_ELABORACION)
    public static final Set<String> CAMPOS = Set.of("numero_version", "total_horas");

    private final Connection con;

    public VersionDAO(Connection con) {
        this.con = con;
    }

    // La resolución queda en NULL hasta que el Director la aprueba con APRVER
    public int insertar(VersionPrograma v) throws SQLException {
        String sql = "INSERT INTO VERSION_PROGRAMA (numero_version, total_horas, id_programa) VALUES (?, ?, ?) RETURNING id_version";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, v.getNumeroVersion());
            ps.setInt(2, v.getTotalHoras());
            ps.setInt(3, v.getIdPrograma());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                v.setIdVersion(rs.getInt(1));
                return v.getIdVersion();
            }
        }
    }

    // En los comandos una versión se identifica por su programa y su número: "TAC", "v1"
    public VersionPrograma buscarPorNumero(int idPrograma, String numeroVersion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM VERSION_PROGRAMA WHERE id_programa = ? AND UPPER(numero_version) = UPPER(?)")) {
            ps.setInt(1, idPrograma);
            ps.setString(2, numeroVersion.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public VersionPrograma buscarPorId(int idVersion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM VERSION_PROGRAMA WHERE id_version = ?")) {
            ps.setInt(1, idVersion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public boolean existeNumero(int idPrograma, String numeroVersion) throws SQLException {
        return buscarPorNumero(idPrograma, numeroVersion) != null;
    }

    public VersionPrograma buscarVigente(int idPrograma) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM VERSION_PROGRAMA WHERE id_programa = ? AND estado = 'VIGENTE'")) {
            ps.setInt(1, idPrograma);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public void actualizarCampo(int idVersion, String campo, Object valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en VERSION_PROGRAMA: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE VERSION_PROGRAMA SET " + campo + " = ? WHERE id_version = ?")) {
            Sql.asignar(ps, 1, valor);
            ps.setInt(2, idVersion);
            ps.executeUpdate();
        }
    }

    // APRVER: registra la resolución y pone la versión VIGENTE desde hoy
    public void aprobar(int idVersion, String numeroResolucion, String tipoResolucion, LocalDate fechaAprobacion)
            throws SQLException {
        String sql = "UPDATE VERSION_PROGRAMA SET estado = 'VIGENTE', numero_resolucion = ?, tipo_resolucion = ?, "
                + "fecha_aprobacion = ?, fecha_inicio_vigencia = CURRENT_DATE WHERE id_version = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, numeroResolucion);
            ps.setString(2, tipoResolucion);
            ps.setDate(3, java.sql.Date.valueOf(fechaAprobacion));
            ps.setInt(4, idVersion);
            ps.executeUpdate();
        }
    }

    public void cambiarEstado(int idVersion, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE VERSION_PROGRAMA SET estado = ? WHERE id_version = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idVersion);
            ps.executeUpdate();
        }
    }

    // Solo para versiones EN_ELABORACION: su malla y prerrequisitos se borran en cascada
    public void eliminar(int idVersion) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM VERSION_PROGRAMA WHERE id_version = ?")) {
            ps.setInt(1, idVersion);
            ps.executeUpdate();
        }
    }

    // LISVER[codigo_programa]
    public Tabla listarPorPrograma(int idPrograma) throws SQLException {
        String sql = "SELECT v.numero_version AS version, v.total_horas, "
                + "COALESCE(SUM(mv.carga_horaria), 0) AS horas_en_malla, COUNT(mv.id_modulo_version) AS modulos, "
                + "v.estado, v.numero_resolucion AS resolucion, v.tipo_resolucion, v.fecha_aprobacion, v.fecha_inicio_vigencia AS vigente_desde "
                + "FROM VERSION_PROGRAMA v LEFT JOIN MODULO_VERSION mv ON mv.id_version = v.id_version "
                + "WHERE v.id_programa = ? GROUP BY v.id_version ORDER BY v.id_version";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idPrograma);
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private VersionPrograma mapear(ResultSet rs) throws SQLException {
        VersionPrograma v = new VersionPrograma(rs.getInt("id_programa"), rs.getString("numero_version"),
                rs.getInt("total_horas"));
        v.setIdVersion(rs.getInt("id_version"));
        v.setNumeroResolucion(rs.getString("numero_resolucion"));
        v.setTipoResolucion(rs.getString("tipo_resolucion"));
        v.setFechaAprobacion(Sql.fecha(rs.getDate("fecha_aprobacion")));
        v.setFechaInicioVigencia(Sql.fecha(rs.getDate("fecha_inicio_vigencia")));
        v.setEstado(rs.getString("estado"));
        return v;
    }
}
