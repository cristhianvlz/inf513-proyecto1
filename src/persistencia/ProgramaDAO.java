package src.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import src.modelo.Programa;
import src.utilidades.Tabla;

public class ProgramaDAO {
    // Columnas que MODPROG puede modificar directamente
    public static final Set<String> CAMPOS = Set.of("nombre", "nivel_titulacion");

    private final Connection con;

    public ProgramaDAO(Connection con) {
        this.con = con;
    }

    public int insertar(Programa p) throws SQLException {
        String sql = "INSERT INTO PROGRAMA (codigo, nombre, nivel_titulacion) VALUES (?, ?, ?) RETURNING id_programa";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, p.getCodigo());
            ps.setString(2, p.getNombre());
            ps.setString(3, p.getNivelTitulacion());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                p.setIdPrograma(rs.getInt(1));
                return p.getIdPrograma();
            }
        }
    }

    public Programa buscarPorCodigo(String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM PROGRAMA WHERE UPPER(codigo) = UPPER(?)")) {
            ps.setString(1, codigo.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public Programa buscarPorId(int idPrograma) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM PROGRAMA WHERE id_programa = ?")) {
            ps.setInt(1, idPrograma);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public void actualizarCampo(int idPrograma, String campo, String valor) throws SQLException {
        if (!CAMPOS.contains(campo)) {
            throw new IllegalArgumentException("Campo no modificable en PROGRAMA: " + campo);
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE PROGRAMA SET " + campo + " = ? WHERE id_programa = ?")) {
            ps.setString(1, valor);
            ps.setInt(2, idPrograma);
            ps.executeUpdate();
        }
    }

    public void cambiarEstado(int idPrograma, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE PROGRAMA SET estado = ? WHERE id_programa = ?")) {
            ps.setString(1, estado);
            ps.setInt(2, idPrograma);
            ps.executeUpdate();
        }
    }

    public boolean tieneVersiones(int idPrograma) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM VERSION_PROGRAMA WHERE id_programa = ? LIMIT 1")) {
            ps.setInt(1, idPrograma);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Solo para programas sin versiones: sus prerrequisitos se borran en cascada
    public void eliminar(int idPrograma) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM PROGRAMA WHERE id_programa = ?")) {
            ps.setInt(1, idPrograma);
            ps.executeUpdate();
        }
    }

    // PROGRAMA_PRERREQUISITO: programa que debe estar concluido antes (ej. Técnico Auxiliar para Técnico Medio)
    public void agregarPrerrequisito(int idPrograma, int idProgramaPrerrequisito) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO PROGRAMA_PRERREQUISITO (id_programa, id_programa_prerrequisito) VALUES (?, ?)")) {
            ps.setInt(1, idPrograma);
            ps.setInt(2, idProgramaPrerrequisito);
            ps.executeUpdate();
        }
    }

    public void quitarPrerrequisitos(int idPrograma) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM PROGRAMA_PRERREQUISITO WHERE id_programa = ?")) {
            ps.setInt(1, idPrograma);
            ps.executeUpdate();
        }
    }

    // ¿El programa 'idPrerrequisito' ya exige (directa o indirectamente) al programa 'idPrograma'?
    public boolean generaCiclo(int idPrograma, int idPrerrequisito) throws SQLException {
        String sql = "WITH RECURSIVE cadena(id) AS ("
                + " SELECT id_programa_prerrequisito FROM PROGRAMA_PRERREQUISITO WHERE id_programa = ?"
                + " UNION SELECT pp.id_programa_prerrequisito FROM PROGRAMA_PRERREQUISITO pp JOIN cadena c ON pp.id_programa = c.id)"
                + " SELECT 1 FROM cadena WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, idPrerrequisito);
            ps.setInt(2, idPrograma);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // LISPROG["*" | codigo | nombre]: programas con su prerrequisito y sus versiones
    public Tabla listar(String filtro) throws SQLException {
        String sql = "SELECT p.codigo, p.nombre, p.nivel_titulacion AS nivel, "
                + "COALESCE((SELECT string_agg(pr.codigo, ', ') FROM PROGRAMA_PRERREQUISITO pp "
                + "  JOIN PROGRAMA pr ON pr.id_programa = pp.id_programa_prerrequisito WHERE pp.id_programa = p.id_programa), '-') AS requiere, "
                + "COALESCE((SELECT string_agg(v.numero_version || ' (' || v.estado || ')', ', ' ORDER BY v.id_version) "
                + "  FROM VERSION_PROGRAMA v WHERE v.id_programa = p.id_programa), '-') AS versiones, "
                + "p.estado "
                + "FROM PROGRAMA p "
                + "WHERE ? = '*' OR p.codigo ILIKE ? OR p.nombre ILIKE '%' || ? || '%' "
                + "ORDER BY p.codigo";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 3; i++) {
                ps.setString(i, filtro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return Tabla.desde(rs);
            }
        }
    }

    private Programa mapear(ResultSet rs) throws SQLException {
        Programa p = new Programa(rs.getString("codigo"), rs.getString("nombre"), rs.getString("nivel_titulacion"));
        p.setIdPrograma(rs.getInt("id_programa"));
        p.setEstado(rs.getString("estado"));
        return p;
    }
}
