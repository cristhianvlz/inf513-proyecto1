package src.negocio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import src.modelo.ActividadEvaluativa;
import src.modelo.Cursante;
import src.modelo.Inscripcion;
import src.persistencia.ActaDAO;
import src.persistencia.ActividadDAO;
import src.persistencia.AsistenciaDAO;
import src.persistencia.CalificacionDAO;
import src.persistencia.HistorialDAO;
import src.persistencia.InscripcionDAO;
import src.persistencia.InscripcionModuloDAO;

// Cálculo del resultado de un módulo. Lo usan los comandos del CU-05 y el cierre automático de actas (CierreActas).
// La nota final se actualiza con cada nota cargada, pero el módulo sigue EN_CURSO hasta que el estudiante tiene todas las
// notas del plan o hasta que se cierra el acta: una nota parcial no lo deja reprobado a mitad del módulo
final class ResultadosModulo {
    static final BigDecimal NOTA_DE_APROBACION = new BigDecimal(51);
    static final BigDecimal ASISTENCIA_MINIMA = new BigDecimal(80);

    private final Connection con;
    // Usuario al que se atribuyen los cambios en el historial; 0 en el cierre automático, que no lo escribe
    private final int idUsuario;

    ResultadosModulo(Connection con, int idUsuario) {
        this.con = con;
        this.idUsuario = idUsuario;
    }

    // APROBADO con 51 o más; por debajo de la asistencia mínima el módulo se pierde. El recuperatorio solo depende de la nota
    static String estadoPorNota(BigDecimal nota, BigDecimal asistencia, boolean recuperatorio) {
        if (!recuperatorio && asistencia != null && asistencia.compareTo(ASISTENCIA_MINIMA) < 0) {
            return "PERDIDA_POR_INASISTENCIA";
        }
        return nota.compareTo(NOTA_DE_APROBACION) >= 0 ? "APROBADO" : "REPROBADO";
    }

    // Vuelve a calcular asistencia, notas finales y estados. Con el acta cerrada solo cambia el resultado de un recuperatorio.
    // Devuelve los resultados que quedaron fijados, para mostrarlos en la respuesta
    List<String> recalcular(int idAsignacion) throws SQLException {
        String[] acta = new ActaDAO(con).vigente(idAsignacion);
        boolean actaAbierta = acta != null && "ABIERTA".equals(acta[1]);
        if (actaAbierta) {
            new AsistenciaDAO(con).recalcularPorcentajes(idAsignacion);
        }
        ActividadDAO actividadDAO = new ActividadDAO(con);
        List<ActividadEvaluativa> plan = new ArrayList<>();
        ActividadEvaluativa examen = null;
        int suma = 0;
        for (ActividadEvaluativa a : actividadDAO.listarModelos(idAsignacion)) {
            if (a.esRecuperatorio()) {
                examen = a;
            } else {
                plan.add(a);
                suma += a.getPonderacion();
            }
        }
        Map<Integer, Map<Integer, BigDecimal>> notas = new CalificacionDAO(con).deLaAsignacion(idAsignacion);
        InscripcionModuloDAO dao = new InscripcionModuloDAO(con);
        List<String> resultados = new ArrayList<>();
        for (Cursante c : dao.cursantes(idAsignacion)) {
            Map<Integer, BigDecimal> suyas = notas.getOrDefault(c.getIdInscripcionModulo(), new HashMap<>());
            BigDecimal nota;
            String estado;
            if (c.esRecuperatorio()) {
                nota = examen == null ? null : suyas.get(examen.getIdActividad());
                // Con el acta cerrada, un recuperatorio ya calificado solo cambia por enmienda: aquí no se le toca
                if (nota == null || (!actaAbierta && !"EN_CURSO".equals(c.getEstado()))) {
                    continue;
                }
                estado = estadoPorNota(nota, null, true);
            } else if (!actaAbierta) {
                continue;
            } else {
                nota = suyas.isEmpty() ? null : sumaPonderada(plan, suyas);
                boolean completo = suma == 100 && suyas.keySet().containsAll(idsDe(plan));
                estado = completo ? estadoPorNota(nota, c.getPorcentajeAsistencia(), false) : "EN_CURSO";
            }
            guardar(dao, c, nota, estado, resultados);
        }
        return resultados;
    }

    // Estudiantes a los que les falta alguna nota del plan (CIEACTA no cierra el acta con notas pendientes)
    List<String> sinNotasCompletas(int idAsignacion) throws SQLException {
        List<ActividadEvaluativa> plan = plan(idAsignacion);
        Map<Integer, Map<Integer, BigDecimal>> notas = new CalificacionDAO(con).deLaAsignacion(idAsignacion);
        List<String> pendientes = new ArrayList<>();
        for (Cursante c : new InscripcionModuloDAO(con).cursantes(idAsignacion)) {
            if (!c.esRecuperatorio() && "EN_CURSO".equals(c.getEstado())
                    && !notas.getOrDefault(c.getIdInscripcionModulo(), new HashMap<>()).keySet().containsAll(idsDe(plan))) {
                pendientes.add(c.getNombre());
            }
        }
        return pendientes;
    }

    // Cierra el acta y fija el resultado de quienes sigan EN_CURSO: una nota que falta cuenta como cero.
    // Un recuperatorio pendiente se puede calificar después del cierre
    List<String> cerrarActa(int idAsignacion, int idActa) throws SQLException {
        List<String> resultados = recalcular(idAsignacion);
        List<ActividadEvaluativa> plan = plan(idAsignacion);
        Map<Integer, Map<Integer, BigDecimal>> notas = new CalificacionDAO(con).deLaAsignacion(idAsignacion);
        InscripcionModuloDAO dao = new InscripcionModuloDAO(con);
        for (Cursante c : dao.cursantes(idAsignacion)) {
            if (c.esRecuperatorio() || !"EN_CURSO".equals(c.getEstado())) {
                continue;
            }
            BigDecimal nota = sumaPonderada(plan, notas.getOrDefault(c.getIdInscripcionModulo(), new HashMap<>()));
            guardar(dao, c, nota, estadoPorNota(nota, c.getPorcentajeAsistencia(), false), resultados);
        }
        new ActaDAO(con).cerrar(idActa);
        return resultados;
    }

    // Una inscripción queda CONCLUIDO al aprobar toda la malla, y vuelve a INSCRITO si una enmienda lo cambia
    void actualizarConclusion(int idInscripcion) throws SQLException {
        InscripcionDAO dao = new InscripcionDAO(con);
        Inscripcion i = dao.buscarPorId(idInscripcion);
        boolean aprobo = dao.aproboTodaLaMalla(idInscripcion);
        String nuevo = null;
        if (aprobo && "INSCRITO".equals(i.getEstado())) {
            nuevo = "CONCLUIDO";
        } else if (!aprobo && "CONCLUIDO".equals(i.getEstado())) {
            nuevo = "INSCRITO";
        }
        if (nuevo == null) {
            return;
        }
        dao.cambiarEstado(idInscripcion, nuevo);
        if (idUsuario > 0) {
            new HistorialDAO(con).registrar(i.getEstado(), nuevo, "Módulos de la malla: "
                    + ("CONCLUIDO".equals(nuevo) ? "todos aprobados" : "ya no están todos aprobados"), i.getIdEstudiante(), idInscripcion, idUsuario);
        }
    }

    private void guardar(InscripcionModuloDAO dao, Cursante c, BigDecimal nota, String estado, List<String> resultados) throws SQLException {
        boolean mismaNota = nota == null ? c.getNotaFinal() == null : c.getNotaFinal() != null && nota.compareTo(c.getNotaFinal()) == 0;
        boolean mismoEstado = estado.equals(c.getEstado());
        if (mismaNota && mismoEstado) {
            return;
        }
        dao.guardarResultado(c.getIdInscripcionModulo(), nota, estado);
        if (!"EN_CURSO".equals(estado)) {
            resultados.add(c.getNombre() + ": " + estado + " (nota " + nota + ")");
        }
        if (!mismoEstado) {
            actualizarConclusion(c.getIdInscripcion());
        }
    }

    private List<ActividadEvaluativa> plan(int idAsignacion) throws SQLException {
        List<ActividadEvaluativa> plan = new ArrayList<>();
        for (ActividadEvaluativa a : new ActividadDAO(con).listarModelos(idAsignacion)) {
            if (!a.esRecuperatorio()) {
                plan.add(a);
            }
        }
        return plan;
    }

    private static List<Integer> idsDe(List<ActividadEvaluativa> actividades) {
        List<Integer> ids = new ArrayList<>();
        for (ActividadEvaluativa a : actividades) {
            ids.add(a.getIdActividad());
        }
        return ids;
    }

    private static BigDecimal sumaPonderada(List<ActividadEvaluativa> plan, Map<Integer, BigDecimal> notas) {
        BigDecimal suma = BigDecimal.ZERO;
        for (ActividadEvaluativa a : plan) {
            BigDecimal nota = notas.get(a.getIdActividad());
            if (nota != null) {
                suma = suma.add(nota.multiply(new BigDecimal(a.getPonderacion())));
            }
        }
        return suma.divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
}
