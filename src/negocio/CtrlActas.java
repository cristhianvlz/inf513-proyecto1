package src.negocio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import src.modelo.ActividadEvaluativa;
import src.modelo.Cursante;
import src.modelo.Estudiante;
import src.modelo.Inscripcion;
import src.modelo.InscripcionModulo;
import src.modelo.Modulo;
import src.modelo.OfertaAcademica;
import src.modelo.Personal;
import src.modelo.TipoEvaluacion;
import src.modelo.Usuario;
import src.persistencia.ActaDAO;
import src.persistencia.ActividadDAO;
import src.persistencia.EnmiendaDAO;
import src.persistencia.HistorialDAO;
import src.persistencia.InscripcionDAO;
import src.persistencia.InscripcionModuloDAO;
import src.persistencia.OfertaDAO;
import src.persistencia.PagoDAO;
import src.persistencia.PersonalDAO;
import src.persistencia.UsuarioDAO;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-05 (Flujos D, E y F): ACTAS, EXAMEN RECUPERATORIO Y ENMIENDA DE NOTAS
// =========================================================================
public class CtrlActas extends CtrlModulo {

    public CtrlActas(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo D - Actas
    // ---------------------------------------------------------------------

    // VERACTA[programa, gestion, grupo, modulo]: el acta vigente, lista para imprimir y firmar
    public String verActa(String[] p) {
        if (p.length < 4) {
            return formato("VERACTA[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarConsulta(con, a);
            log(con, "ACTA_CALIFICACION", "SELECT");
            return acta(con, a);
        });
    }

    // LISACTA[programa, gestion, grupo, modulo]: también las anuladas
    public String listarActas(String[] p) {
        if (p.length < 4) {
            return formato("LISACTA[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            Tabla t = new ActaDAO(con).listar(a.id());
            log(con, "ACTA_CALIFICACION", "SELECT");
            return GeneradorHTML.tabla("las Actas de " + a.modulo.getCodigo() + " en la oferta " + a.oferta.getCodigoPrograma() + " "
                    + a.oferta.getGestion() + " grupo " + a.oferta.getGrupo(), t);
        });
    }

    // CIEACTA[programa, gestion, grupo, modulo]: cierra el acta antes del plazo de 72 horas
    public String cerrarActa(String[] p) {
        if (p.length < 4) {
            return formato("CIEACTA[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarActaAbierta(con, a);
            int suma = new ActividadDAO(con).sumaPlan(a.id());
            if (suma != 100) {
                throw new ReglaNegocioException("El plan de evaluación suma <b>" + suma + "</b>: no se cierra el acta antes del plazo hasta que sume 100. Consulte <code>LISACT["
                        + a.referencia() + "]</code>.");
            }
            ResultadosModulo resultados = new ResultadosModulo(con, idRemitente);
            List<String> pendientes = resultados.sinNotasCompletas(a.id());
            if (!pendientes.isEmpty()) {
                throw new ReglaNegocioException("No se cierra el acta antes del plazo con notas pendientes. Les falta alguna nota a: <b>"
                        + String.join(", ", pendientes) + "</b>. Consulte <code>LISNOTA[" + a.referencia() + "]</code>.");
            }
            int idActa = Integer.parseInt(new ActaDAO(con).vigente(a.id())[0]);
            List<String> fijados = resultados.cerrarActa(a.id(), idActa);
            log(con, "ACTA_CALIFICACION", "UPDATE");
            return GeneradorHTML.mensajeExito("Cierre de Acta",
                    "El acta N° " + idActa + " de " + a.descripcion() + " quedó <b>CERRADA</b>. Desde ahora, toda corrección requiere una enmienda con resolución."
                            + (fijados.isEmpty() ? "" : "<br>Resultados: " + String.join("; ", fijados) + "."));
        });
    }

    // ---------------------------------------------------------------------
    // Flujo E - Examen recuperatorio
    // ---------------------------------------------------------------------

    // HABREC[estudiante, modulo]: si reprobó con al menos 80% de asistencia y pagó nuevamente
    public String habilitarRecuperatorio(String[] p) {
        if (p.length < 2) {
            return formato("HABREC[\"codigo_estudiante o ci\", \"modulo\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            Modulo m = buscarModulo(con, p[1]);
            InscripcionModuloDAO dao = new InscripcionModuloDAO(con);
            InscripcionModulo ultimo = ultimoIntento(dao, e, m);
            if (!"REPROBADO".equals(ultimo.getEstado())) {
                throw new ReglaNegocioException("El último intento de <b>" + e.getNombreCompleto() + "</b> en <b>" + m.getCodigo() + "</b> está <b>"
                        + ultimo.getEstado() + "</b>: el recuperatorio solo se habilita a quien reprobó.");
            }
            if ("RECUPERATORIO".equals(ultimo.getCondicion())) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya reprobó el examen recuperatorio de <b>" + m.getCodigo()
                        + "</b>: pierde la asignatura y debe repetirla por repechaje con INSMOD.");
            }
            BigDecimal asistencia = ultimo.getPorcentajeAsistencia();
            if (asistencia == null || asistencia.compareTo(ResultadosModulo.ASISTENCIA_MINIMA) < 0) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> tiene " + (asistencia == null ? "sin registrar la" : asistencia + "% de")
                        + " asistencia: el recuperatorio exige al menos " + ResultadosModulo.ASISTENCIA_MINIMA + "%.");
            }
            Inscripcion i = new InscripcionDAO(con).buscarPorId(ultimo.getIdInscripcion());
            if (!"INSCRITO".equals(i.getEstado())) {
                throw new ReglaNegocioException("La inscripción de <b>" + e.getNombreCompleto() + "</b> está <b>" + i.getEstado() + "</b>.");
            }
            PagoDAO pagoDAO = new PagoDAO(con);
            Integer pago = pagoDAO.recuperatorioDisponible(i.getIdInscripcion());
            if (pago == null) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene un pago de RECUPERATORIO conciliado disponible. "
                        + "La Secretaria debe registrarlo y conciliarlo.");
            }
            OfertaAcademica o = new OfertaDAO(con).buscarPorId(i.getIdOferta());
            Asignacion a = asignacionDe(con, o, m);

            InscripcionModulo nuevo = new InscripcionModulo();
            nuevo.setNumeroIntento(ultimo.getNumeroIntento() + 1);
            nuevo.setCondicion("RECUPERATORIO");
            nuevo.setPorcentajeAsistencia(asistencia);
            nuevo.setIdInscripcion(i.getIdInscripcion());
            nuevo.setIdModulo(m.getIdModulo());
            dao.insertar(nuevo);
            pagoDAO.asociarModulo(pago, nuevo.getIdInscripcionModulo());
            crearActividadDeRecuperatorio(con, a);
            new HistorialDAO(con).registrar(null, "EN_CURSO", "Módulo " + m.getCodigo() + ": recuperatorio habilitado, intento " + nuevo.getNumeroIntento(),
                    e.getIdEstudiante(), i.getIdInscripcion(), idRemitente);
            log(con, "INSCRIPCION_MODULO", "INSERT");
            log(con, "PAGO", "UPDATE");

            String comando = "ADDNOTA[" + a.referencia() + ", \"" + TipoEvaluacion.RECUPERATORIO + "\", \"" + e.getCodigoEstudiante() + ":nota\"]";
            Usuario capacitador = new UsuarioDAO(con).buscarPorIdPersonal(a.datos.getIdPersonal());
            if (capacitador != null) {
                notificar(capacitador.getEmail(), "Examen recuperatorio habilitado: " + m.getCodigo(), GeneradorHTML.mensajeAviso("Examen recuperatorio",
                        "Se habilitó el examen recuperatorio de <b>" + e.getNombreCompleto() + "</b> en " + a.descripcion()
                                + ". Cargue su nota con <code>" + comando + "</code>."));
            }
            return GeneradorHTML.mensajeExito("Examen Recuperatorio",
                    "Se habilitó el examen recuperatorio de <b>" + e.getNombreCompleto() + "</b> en " + a.descripcion() + " (intento "
                            + nuevo.getNumeroIntento() + ", con su pago de RECUPERATORIO).<br>El profesor carga la nota con <code>" + comando + "</code>.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo F - Enmienda de notas (Director)
    // ---------------------------------------------------------------------

    // ENMIENDA[estudiante, modulo, nota_nueva, numero_resolucion]: con el acta cerrada; la anula y emite una nueva
    public String enmendarNota(String[] p) {
        if (p.length < 4) {
            return formato("ENMIENDA[\"codigo_estudiante o ci\", \"modulo\", \"nota_nueva\", \"numero_resolucion\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = buscarEstudiante(con, p[0]);
            Modulo m = buscarModulo(con, p[1]);
            BigDecimal nueva = decimal(p[2], "nota_nueva").setScale(2, RoundingMode.HALF_UP);
            if (nueva.signum() < 0 || nueva.compareTo(new BigDecimal(100)) > 0) {
                throw new ReglaNegocioException("La <b>nota_nueva</b> debe estar entre 0 y 100.");
            }
            requerido(p[3], "numero_resolucion");
            InscripcionModuloDAO dao = new InscripcionModuloDAO(con);
            InscripcionModulo intento = ultimoIntento(dao, e, m);
            if (intento.getNotaFinal() == null || "EN_CURSO".equals(intento.getEstado())) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> todavía no tiene un resultado en <b>" + m.getCodigo() + "</b>.");
            }
            if (intento.getNotaFinal().compareTo(nueva) == 0) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> ya tiene la nota <b>" + nueva + "</b> en <b>" + m.getCodigo() + "</b>.");
            }
            Inscripcion i = new InscripcionDAO(con).buscarPorId(intento.getIdInscripcion());
            Asignacion a = asignacionDe(con, new OfertaDAO(con).buscarPorId(i.getIdOferta()), m);
            ActaDAO actaDAO = new ActaDAO(con);
            String[] vigente = actaDAO.vigente(a.id());
            if (vigente == null || !"CERRADA".equals(vigente[1])) {
                throw new ReglaNegocioException("El acta de " + a.descripcion() + " sigue abierta: la nota se corrige con MODNOTA, sin enmienda.");
            }

            new EnmiendaDAO(con).insertar(intento.getNotaFinal(), nueva, p[3].trim(), intento.getIdInscripcionModulo(), idRemitente);
            String estado = ResultadosModulo.estadoPorNota(nueva, intento.getPorcentajeAsistencia(), "RECUPERATORIO".equals(intento.getCondicion()));
            dao.guardarResultado(intento.getIdInscripcionModulo(), nueva, estado);
            actaDAO.anular(Integer.parseInt(vigente[0]));
            int nuevaActa = actaDAO.emitirCerrada(a.id());
            new ResultadosModulo(con, idRemitente).actualizarConclusion(i.getIdInscripcion());
            log(con, "ENMIENDA_NOTA", "INSERT");
            log(con, "INSCRIPCION_MODULO", "UPDATE");
            log(con, "ACTA_CALIFICACION", "UPDATE");

            Usuario capacitador = new UsuarioDAO(con).buscarPorIdPersonal(a.datos.getIdPersonal());
            if (capacitador != null) {
                notificar(capacitador.getEmail(), "Nueva acta para firmar: " + m.getCodigo(), acta(con, a));
            }
            return GeneradorHTML.mensajeExito("Enmienda de Nota",
                    "La nota de <b>" + e.getNombreCompleto() + "</b> en " + a.descripcion() + " pasó de <b>" + intento.getNotaFinal() + "</b> a <b>" + nueva
                            + "</b> (resolución " + p[3].trim() + "). Estado: <b>" + estado + "</b>.<br>Se anuló el acta N° " + vigente[0] + " y se emitió el acta N° "
                            + nuevaActa + (capacitador != null ? ", enviada al profesor para su firma." : "."));
        });
    }

    // LISENM[estudiante] o LISENM[programa, gestion, grupo, modulo]
    public String listarEnmiendas(String[] p) {
        if (Validador.vacio(param(p, 0))) {
            return formato("LISENM[\"codigo_estudiante o ci\"] o LISENM[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            EnmiendaDAO dao = new EnmiendaDAO(con);
            String titulo;
            Tabla t;
            if (p.length >= 4) {
                Asignacion a = buscarAsignacion(con, p, 0);
                t = dao.listarPorAsignacion(a.id());
                titulo = "las Enmiendas de " + a.modulo.getCodigo() + " en la oferta " + a.oferta.getCodigoPrograma() + " " + a.oferta.getGestion() + " grupo " + a.oferta.getGrupo();
            } else {
                Estudiante e = buscarEstudiante(con, p[0]);
                t = dao.listarPorEstudiante(e.getIdEstudiante());
                titulo = "las Enmiendas de " + e.getNombreCompleto() + " (CI " + e.getCi() + ")";
            }
            log(con, "ENMIENDA_NOTA", "SELECT");
            return GeneradorHTML.tabla(titulo, t);
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    private InscripcionModulo ultimoIntento(InscripcionModuloDAO dao, Estudiante e, Modulo m) throws SQLException {
        List<InscripcionModulo> intentos = dao.intentos(e.getIdEstudiante(), m.getIdModulo());
        if (intentos.isEmpty()) {
            throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> no tiene registrado <b>" + m.getCodigo() + "</b>.");
        }
        return intentos.get(intentos.size() - 1);
    }

    // El examen se crea una sola vez por asignación, aunque lo rindan varios estudiantes. No forma parte del plan de 100
    private void crearActividadDeRecuperatorio(Connection con, Asignacion a) throws SQLException {
        ActividadDAO dao = new ActividadDAO(con);
        if (dao.buscarRecuperatorio(a.id()) != null) {
            return;
        }
        for (TipoEvaluacion t : dao.listarTipos()) {
            if (TipoEvaluacion.RECUPERATORIO.equals(t.getNombre())) {
                ActividadEvaluativa examen = new ActividadEvaluativa();
                examen.setTitulo(TipoEvaluacion.RECUPERATORIO);
                examen.setPonderacion(t.getPonderacionMaxima());
                examen.setIdAsignacion(a.id());
                examen.setIdTipo(t.getIdTipo());
                dao.insertar(examen);
                log(con, "ACTIVIDAD_EVALUATIVA", "INSERT");
                return;
            }
        }
        throw new ReglaNegocioException("Falta el tipo de evaluación <b>" + TipoEvaluacion.RECUPERATORIO + "</b> en la base de datos.");
    }

    private String acta(Connection con, Asignacion a) throws SQLException {
        String[] vigente = new ActaDAO(con).vigente(a.id());
        Personal capacitador = new PersonalDAO(con).buscarPorId(a.datos.getIdPersonal());
        List<String> cabeceras = List.of("codigo", "ci", "estudiante", "intento", "condicion", "asistencia_%", "nota_final", "estado");
        List<String[]> filas = new ArrayList<>();
        for (Cursante c : new InscripcionModuloDAO(con).cursantes(a.id())) {
            filas.add(new String[] { c.getCodigo(), c.getCi(), c.getNombre(), String.valueOf(c.getNumeroIntento()), c.getCondicion(),
                    c.getPorcentajeAsistencia() == null ? null : c.getPorcentajeAsistencia().toString(),
                    c.getNotaFinal() == null ? null : c.getNotaFinal().toString(), c.getEstado() });
        }
        return GeneradorHTML.acta(vigente == null ? "-" : vigente[0], vigente == null ? "SIN ACTA" : vigente[1],
                a.oferta.getCodigoPrograma() + " " + a.oferta.getGestion() + " grupo " + a.oferta.getGrupo(),
                a.modulo.getCodigo() + " - " + a.modulo.getNombre(), capacitador.getNombreCompleto(), Tabla.de(cabeceras, filas));
    }
}
