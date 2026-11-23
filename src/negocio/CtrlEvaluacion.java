package src.negocio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import src.modelo.ActividadEvaluativa;
import src.modelo.Cursante;
import src.modelo.SesionClase;
import src.modelo.TipoEvaluacion;
import src.persistencia.ActividadDAO;
import src.persistencia.AsistenciaDAO;
import src.persistencia.CalificacionDAO;
import src.persistencia.InscripcionModuloDAO;
import src.persistencia.SesionDAO;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-05 (Flujos A, B y C): PLAN DE EVALUACIÓN, SESIONES, ASISTENCIA Y CALIFICACIONES
// En los comandos nada se identifica por ID: una actividad por su título dentro de la asignación
// y una sesión por su fecha ("2026-11-23", o "2026-11-23 19:00" si ese día hubo dos clases)
// =========================================================================
public class CtrlEvaluacion extends CtrlModulo {

    private static final int MAX_TRABAJOS_PRACTICOS = 4;
    private static final int MAX_LICENCIAS_PRESENCIAL = 2;
    private static final int MAX_LICENCIAS_SEMIPRESENCIAL = 1;

    private static final List<String> ESTADOS_ASISTENCIA = List.of("PRESENTE", "AUSENTE", "LICENCIA", "JUSTIFICADO");

    // PROGRAMADA revierte una marca hecha por error
    private static final List<String> ESTADOS_SESION = List.of("DICTADA", "SUSPENDIDA", "PROGRAMADA");

    // Formas cortas del tipo de evaluación en los comandos
    private static final Map<String, String> ALIAS_TIPOS = Map.of(
            "ASISTENCIA", "ASISTENCIA, PARTICIPACION Y DESEMPENO",
            "PARTICIPACION", "ASISTENCIA, PARTICIPACION Y DESEMPENO",
            "TP", "TRABAJOS PRACTICOS",
            "TRABAJO PRACTICO", "TRABAJOS PRACTICOS",
            "PARCIAL", "EXAMEN PARCIAL",
            "FINAL", "EXAMEN FINAL");

    // Nombre del campo en el comando -> columna en la BD
    private static final Map<String, String> ALIAS_ACTIVIDAD = Map.of(
            "PONDERACION", "ponderacion_designada",
            "FECHA", "fecha_limite");

    public CtrlEvaluacion(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Plan de evaluación (Capacitador)
    // ---------------------------------------------------------------------

    // ADDACT[programa, gestion, grupo, modulo, tipo_evaluacion, titulo, ponderacion, fecha_limite]
    public String registrarActividad(String[] p) {
        if (p.length < 7) {
            return formato("ADDACT[" + ASIGNACION + ", \"tipo_evaluacion\", \"titulo\", \"ponderacion\", \"fecha_limite (opcional)\"] "
                    + "(tipos: ASISTENCIA, TRABAJOS PRACTICOS, PARCIAL, FINAL)");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            TipoEvaluacion tipo = buscarTipo(con, p[4]);
            if (TipoEvaluacion.RECUPERATORIO.equals(tipo.getNombre())) {
                throw new ReglaNegocioException("El examen recuperatorio no se registra con ADDACT: lo crea HABREC.");
            }
            requerido(p[5], "titulo");
            ActividadDAO dao = new ActividadDAO(con);
            if (dao.buscar(a.id(), p[5]) != null) {
                throw new ReglaNegocioException("Ya existe la actividad <b>" + p[5].trim() + "</b> en " + a.descripcion() + ".");
            }
            int ponderacion = ponderacionValida(p[6]);
            validarPonderacionDelTipo(dao, a, tipo, ponderacion, 0);
            int maximoActividades = TipoEvaluacion.TRABAJOS_PRACTICOS.equals(tipo.getNombre()) ? MAX_TRABAJOS_PRACTICOS : 1;
            if (dao.contarPorTipo(a.id(), tipo.getIdTipo()) >= maximoActividades) {
                throw new ReglaNegocioException("El plan admite como máximo " + maximoActividades + " actividad(es) de <b>" + tipo.getNombre() + "</b>.");
            }
            ActividadEvaluativa actividad = new ActividadEvaluativa();
            actividad.setTitulo(p[5].trim());
            actividad.setPonderacion(ponderacion);
            actividad.setFechaLimite(Validador.vacio(param(p, 7)) ? null : fecha(p[7], "fecha_limite"));
            actividad.setIdAsignacion(a.id());
            actividad.setIdTipo(tipo.getIdTipo());
            dao.insertar(actividad);
            log(con, "ACTIVIDAD_EVALUATIVA", "INSERT");
            return GeneradorHTML.mensajeExito("Plan de Evaluación",
                    "Se registró <b>" + actividad.getTitulo() + "</b> (" + tipo.getNombre() + ", " + ponderacion + "%) en " + a.descripcion()
                            + ".<br>El plan suma <b>" + dao.sumaPlan(a.id()) + "</b> de 100.");
        });
    }

    // LISACT[programa, gestion, grupo, modulo]
    public String listarActividades(String[] p) {
        if (p.length < 4) {
            return formato("LISACT[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarConsulta(con, a);
            ActividadDAO dao = new ActividadDAO(con);
            Tabla t = dao.listar(a.id());
            log(con, "ACTIVIDAD_EVALUATIVA", "SELECT");
            return GeneradorHTML.tabla("el Plan de Evaluación de " + a.modulo.getCodigo() + ": suma " + dao.sumaPlan(a.id()) + " de 100", t);
        });
    }

    // MODACT[programa, gestion, grupo, modulo, titulo, campo, nuevo_valor]: la ponderación no cambia si ya hay notas
    public String modificarActividad(String[] p) {
        if (p.length < 7) {
            return formato("MODACT[" + ASIGNACION + ", \"titulo\", \"campo\", \"nuevo_valor\"] (campos: titulo, ponderacion_designada, fecha_limite)");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            ActividadEvaluativa actividad = buscarActividad(con, a, p[4]);
            if (actividad.esRecuperatorio()) {
                throw new ReglaNegocioException("El examen recuperatorio lo crea HABREC y no se modifica.");
            }
            ActividadDAO dao = new ActividadDAO(con);
            String campo = columna(p[5], ALIAS_ACTIVIDAD);
            Object valor;
            switch (campo) {
                case "titulo":
                    requerido(p[6], campo);
                    ActividadEvaluativa otra = dao.buscar(a.id(), p[6]);
                    if (otra != null && otra.getIdActividad() != actividad.getIdActividad()) {
                        throw new ReglaNegocioException("Ya existe la actividad <b>" + p[6].trim() + "</b>.");
                    }
                    valor = p[6].trim();
                    break;
                case "ponderacion_designada":
                    if (dao.tieneNotas(actividad.getIdActividad())) {
                        throw new ReglaNegocioException("La actividad <b>" + actividad.getTitulo() + "</b> ya tiene notas: su ponderación no se puede cambiar.");
                    }
                    int ponderacion = ponderacionValida(p[6]);
                    validarPonderacionDelTipo(dao, a, tipoDe(con, actividad), ponderacion, actividad.getIdActividad());
                    valor = ponderacion;
                    break;
                case "fecha_limite":
                    valor = Validador.vacio(p[6]) ? null : fecha(p[6], campo);
                    break;
                default:
                    throw new ReglaNegocioException("El campo <b>" + p[5] + "</b> no se puede modificar. Campos válidos: titulo, ponderacion_designada, fecha_limite.");
            }
            dao.actualizarCampo(actividad.getIdActividad(), campo, valor);
            log(con, "ACTIVIDAD_EVALUATIVA", "UPDATE");
            return GeneradorHTML.mensajeExito("Plan de Evaluación",
                    "Se actualizó <b>" + campo + "</b> de <b>" + actividad.getTitulo() + "</b> a <b>" + (valor != null ? valor : "sin fecha")
                            + "</b>.<br>El plan suma <b>" + dao.sumaPlan(a.id()) + "</b> de 100.");
        });
    }

    // DELACT[programa, gestion, grupo, modulo, titulo]: solo si la actividad todavía no tiene calificaciones
    public String eliminarActividad(String[] p) {
        if (p.length < 5) {
            return formato("DELACT[" + ASIGNACION + ", \"titulo\"]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            ActividadEvaluativa actividad = buscarActividad(con, a, p[4]);
            ActividadDAO dao = new ActividadDAO(con);
            if (actividad.esRecuperatorio()) {
                throw new ReglaNegocioException("El examen recuperatorio lo crea HABREC y no se elimina.");
            }
            if (dao.tieneNotas(actividad.getIdActividad())) {
                throw new ReglaNegocioException("La actividad <b>" + actividad.getTitulo() + "</b> ya tiene notas y no se puede eliminar.");
            }
            dao.eliminar(actividad.getIdActividad());
            log(con, "ACTIVIDAD_EVALUATIVA", "DELETE");
            return GeneradorHTML.mensajeExito("Plan de Evaluación",
                    "Se eliminó <b>" + actividad.getTitulo() + "</b> de " + a.descripcion() + ".<br>El plan suma <b>" + dao.sumaPlan(a.id()) + "</b> de 100.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Sesiones y asistencia (Capacitador)
    // ---------------------------------------------------------------------

    // ADDSES[programa, gestion, grupo, modulo, fecha, hora_inicio, hora_fin]: clase de recuperación
    public String registrarSesion(String[] p) {
        if (p.length < 7) {
            return formato("ADDSES[" + ASIGNACION + ", \"fecha\", \"hora_inicio\", \"hora_fin\"]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            SesionClase s = new SesionClase();
            s.setIdAsignacion(a.id());
            s.setFecha(fecha(p[4], "fecha"));
            s.setHoraInicio(hora(p[5], "hora_inicio"));
            s.setHoraFin(hora(p[6], "hora_fin"));
            if (!s.getHoraFin().isAfter(s.getHoraInicio())) {
                throw new ReglaNegocioException("La hora de fin (" + s.getHoraFin() + ") debe ser posterior a la de inicio (" + s.getHoraInicio() + ").");
            }
            if (s.getFecha().isBefore(a.datos.getFechaInicio())) {
                throw new ReglaNegocioException("La fecha " + s.getFecha() + " es anterior al inicio del módulo (" + a.datos.getFechaInicio() + ").");
            }
            SesionDAO dao = new SesionDAO(con);
            if (!dao.buscar(a.id(), s.getFecha(), s.getHoraInicio()).isEmpty()) {
                throw new ReglaNegocioException("Ya hay una sesión el " + s.getFecha() + " a las " + s.getHoraInicio() + ".");
            }
            dao.insertarRecuperacion(s);
            new ResultadosModulo(con, idRemitente).recalcular(a.id());
            log(con, "SESION_CLASE", "INSERT");
            return GeneradorHTML.mensajeExito("Clase de Recuperación",
                    "Se registró la clase de recuperación de " + a.descripcion() + " el <b>" + s.getFecha() + "</b> de " + s.getHoraInicio()
                            + " a " + s.getHoraFin() + ".<br>Registre su asistencia con <code>ADDASIS[" + a.referencia() + ", \"" + s.getFecha()
                            + "\", \"codigo:PRESENTE; codigo:AUSENTE\"]</code>.");
        });
    }

    // LISSES[programa, gestion, grupo, modulo]
    public String listarSesiones(String[] p) {
        if (p.length < 4) {
            return formato("LISSES[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarConsulta(con, a);
            Tabla t = new SesionDAO(con).listar(a.id());
            log(con, "SESION_CLASE", "SELECT");
            return GeneradorHTML.tabla("las Sesiones de " + a.modulo.getCodigo() + " en la oferta " + a.oferta.getCodigoPrograma() + " "
                    + a.oferta.getGestion() + " grupo " + a.oferta.getGrupo(), t);
        });
    }

    // MODSES[programa, gestion, grupo, modulo, fecha, estado]: DICTADA, SUSPENDIDA (no cuenta para la asistencia) o PROGRAMADA
    public String modificarSesion(String[] p) {
        if (p.length < 6) {
            return formato("MODSES[" + ASIGNACION + ", \"fecha\", \"estado\"] (estados: DICTADA, SUSPENDIDA, PROGRAMADA)");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            SesionClase s = buscarSesion(con, a, p[4]);
            String estado = opcion(p[5], ESTADOS_SESION, "estado");
            if ("DICTADA".equals(estado) && s.getFecha().isAfter(LocalDate.now())) {
                throw new ReglaNegocioException("La clase del " + s.getFecha() + " todavía no ocurrió: no se puede marcar como DICTADA.");
            }
            if (estado.equals(s.getEstado())) {
                throw new ReglaNegocioException("La sesión del " + s.getFecha() + " ya está <b>" + estado + "</b>.");
            }
            new SesionDAO(con).cambiarEstado(s.getIdSesion(), estado);
            new ResultadosModulo(con, idRemitente).recalcular(a.id());
            log(con, "SESION_CLASE", "UPDATE");
            return GeneradorHTML.mensajeExito("Sesión de Clase",
                    "La sesión del <b>" + s.getDescripcion() + "</b> de " + a.descripcion() + " quedó <b>" + estado + "</b>."
                            + ("DICTADA".equals(estado) ? "<br>Registre su asistencia con <code>ADDASIS[" + a.referencia() + ", \"" + s.getFecha()
                                    + "\", \"codigo:PRESENTE; codigo:AUSENTE\"]</code>." : ""));
        });
    }

    // ADDASIS[programa, gestion, grupo, modulo, fecha, "codigo:estado; codigo:estado"]
    public String registrarAsistencia(String[] p) {
        if (p.length < 6) {
            return formato("ADDASIS[" + ASIGNACION + ", \"fecha\", \"codigo:estado; codigo:estado\"] (estados: PRESENTE, AUSENTE, LICENCIA, JUSTIFICADO)");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            SesionClase s = buscarSesionDada(con, a, p[4]);
            List<Cursante> cursantes = new InscripcionModuloDAO(con).cursantes(a.id());
            AsistenciaDAO dao = new AsistenciaDAO(con);
            Map<Integer, String> registradas = dao.deLaSesion(s.getIdSesion());

            List<String> errores = new ArrayList<>();
            Map<Cursante, String> nuevas = new HashMap<>();
            Set<Integer> vistos = new HashSet<>();
            for (String[] par : pares(p[5])) {
                Cursante c = elegir(cursantes, par[0], false);
                if (c == null) {
                    errores.add("<b>" + par[0] + "</b> no cursa este módulo");
                } else if (!vistos.add(c.getIdInscripcionModulo())) {
                    errores.add("<b>" + par[0] + "</b> está repetido en la lista");
                } else if (registradas.containsKey(c.getIdInscripcionModulo())) {
                    errores.add("<b>" + c.getNombre() + "</b> ya tiene asistencia en esta sesión: corríjala con MODASIS");
                } else if (!ESTADOS_ASISTENCIA.contains(Validador.normalizar(par[1]))) {
                    errores.add("<b>" + par[1] + "</b> no es un estado válido (PRESENTE, AUSENTE, LICENCIA, JUSTIFICADO)");
                } else {
                    String estado = Validador.normalizar(par[1]);
                    if ("LICENCIA".equals(estado) && !puedeTenerLicencia(dao, a, c, 0)) {
                        errores.add("<b>" + c.getNombre() + "</b> ya usó el máximo de " + maxLicencias(a) + " licencia(s) en esta materia");
                    } else {
                        nuevas.put(c, estado);
                    }
                }
            }
            rechazarSiHayErrores(errores);
            for (Map.Entry<Cursante, String> n : nuevas.entrySet()) {
                dao.insertar(s.getIdSesion(), n.getKey().getIdInscripcionModulo(), n.getValue());
            }
            List<String> resultados = new ResultadosModulo(con, idRemitente).recalcular(a.id());
            log(con, "ASISTENCIA", "INSERT");
            return GeneradorHTML.mensajeExito("Asistencia",
                    "Se registró la asistencia de <b>" + nuevas.size() + "</b> estudiante(s) en la sesión del <b>" + s.getDescripcion() + "</b> de "
                            + a.descripcion() + "." + detalle(resultados));
        });
    }

    // MODASIS[programa, gestion, grupo, modulo, fecha, estudiante, nuevo_estado]: mientras el acta esté abierta
    public String modificarAsistencia(String[] p) {
        if (p.length < 7) {
            return formato("MODASIS[" + ASIGNACION + ", \"fecha\", \"codigo_estudiante o ci\", \"nuevo_estado\"]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            SesionClase s = buscarSesionDada(con, a, p[4]);
            Cursante c = buscarCursante(con, a, p[5], false);
            String estado = opcion(p[6], ESTADOS_ASISTENCIA, "nuevo_estado");
            AsistenciaDAO dao = new AsistenciaDAO(con);
            if (!dao.deLaSesion(s.getIdSesion()).containsKey(c.getIdInscripcionModulo())) {
                throw new ReglaNegocioException("<b>" + c.getNombre() + "</b> no tiene asistencia en esta sesión: regístrela con ADDASIS.");
            }
            if ("LICENCIA".equals(estado) && !puedeTenerLicencia(dao, a, c, s.getIdSesion())) {
                throw new ReglaNegocioException("<b>" + c.getNombre() + "</b> ya usó el máximo de " + maxLicencias(a) + " licencia(s) en esta materia.");
            }
            dao.actualizar(s.getIdSesion(), c.getIdInscripcionModulo(), estado);
            List<String> resultados = new ResultadosModulo(con, idRemitente).recalcular(a.id());
            log(con, "ASISTENCIA", "UPDATE");
            return GeneradorHTML.mensajeExito("Asistencia",
                    "La asistencia de <b>" + c.getNombre() + "</b> en la sesión del <b>" + s.getDescripcion() + "</b> quedó <b>" + estado + "</b>." + detalle(resultados));
        });
    }

    // LISASIS[programa, gestion, grupo, modulo]: planilla con una columna por clase dada y el porcentaje de cada estudiante
    public String listarAsistencia(String[] p) {
        if (p.length < 4) {
            return formato("LISASIS[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarConsulta(con, a);
            List<SesionClase> dadas = new ArrayList<>();
            for (SesionClase s : new SesionDAO(con).listarModelos(a.id())) {
                if (s.fueDada()) {
                    dadas.add(s);
                }
            }
            Map<Integer, Map<Integer, String>> asistencia = new AsistenciaDAO(con).deLaAsignacion(a.id());
            List<String> cabeceras = new ArrayList<>(List.of("codigo", "estudiante"));
            for (SesionClase s : dadas) {
                cabeceras.add(s.getFecha().toString());
            }
            cabeceras.add("asistencia_%");
            cabeceras.add("licencias");
            List<String[]> filas = new ArrayList<>();
            for (Cursante c : new InscripcionModuloDAO(con).cursantes(a.id())) {
                if (c.esRecuperatorio()) {
                    continue;
                }
                Map<Integer, String> suya = asistencia.getOrDefault(c.getIdInscripcionModulo(), new HashMap<>());
                List<String> fila = new ArrayList<>(List.of(c.getCodigo(), c.getNombre()));
                int licencias = 0;
                for (SesionClase s : dadas) {
                    fila.add(suya.get(s.getIdSesion()));
                }
                for (String estado : suya.values()) {
                    licencias += "LICENCIA".equals(estado) ? 1 : 0;
                }
                fila.add(c.getPorcentajeAsistencia() == null ? null : c.getPorcentajeAsistencia().toString());
                fila.add(String.valueOf(licencias));
                filas.add(fila.toArray(new String[0]));
            }
            log(con, "ASISTENCIA", "SELECT");
            return GeneradorHTML.tabla("la Planilla de Asistencia de " + a.modulo.getCodigo(), Tabla.de(cabeceras, filas));
        });
    }

    // ---------------------------------------------------------------------
    // Flujo C - Calificaciones (Capacitador)
    // ---------------------------------------------------------------------

    // ADDNOTA[programa, gestion, grupo, modulo, titulo, "codigo:nota; codigo:nota"]
    public String registrarNotas(String[] p) {
        if (p.length < 6) {
            return formato("ADDNOTA[" + ASIGNACION + ", \"titulo\", \"codigo:nota; codigo:nota\"]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            ActividadEvaluativa actividad = buscarActividad(con, a, p[4]);
            // El examen recuperatorio se puede tomar con el acta ya cerrada: lo habilitó el Director con HABREC
            if (!actividad.esRecuperatorio()) {
                validarActaAbierta(con, a);
                int suma = new ActividadDAO(con).sumaPlan(a.id());
                if (suma != 100) {
                    throw new ReglaNegocioException("El plan de evaluación suma <b>" + suma + "</b>: debe sumar 100 antes de cargar notas. Consulte <code>LISACT[" + a.referencia() + "]</code>.");
                }
            }
            List<Cursante> cursantes = new InscripcionModuloDAO(con).cursantes(a.id());
            CalificacionDAO dao = new CalificacionDAO(con);
            Map<Integer, Map<Integer, BigDecimal>> cargadas = dao.deLaAsignacion(a.id());

            List<String> errores = new ArrayList<>();
            Map<Cursante, BigDecimal> nuevas = new HashMap<>();
            Set<Integer> vistos = new HashSet<>();
            for (String[] par : pares(p[5])) {
                Cursante c = elegir(cursantes, par[0], actividad.esRecuperatorio());
                BigDecimal nota = notaOpcional(par[1]);
                if (c == null) {
                    errores.add("<b>" + par[0] + "</b> no " + (actividad.esRecuperatorio() ? "tiene el recuperatorio habilitado" : "cursa este módulo"));
                } else if (!vistos.add(c.getIdInscripcionModulo())) {
                    errores.add("<b>" + par[0] + "</b> está repetido en la lista");
                } else if (cargadas.getOrDefault(c.getIdInscripcionModulo(), new HashMap<>()).containsKey(actividad.getIdActividad())) {
                    errores.add("<b>" + c.getNombre() + "</b> ya tiene nota en esta actividad: corríjala con MODNOTA");
                } else if (nota == null) {
                    errores.add("<b>" + par[1] + "</b> no es una nota válida: debe estar entre 0 y 100");
                } else {
                    nuevas.put(c, nota);
                }
            }
            rechazarSiHayErrores(errores);
            for (Map.Entry<Cursante, BigDecimal> n : nuevas.entrySet()) {
                dao.insertar(actividad.getIdActividad(), n.getKey().getIdInscripcionModulo(), n.getValue());
            }
            List<String> resultados = new ResultadosModulo(con, idRemitente).recalcular(a.id());
            log(con, "CALIFICACION", "INSERT");
            return GeneradorHTML.mensajeExito("Calificaciones",
                    "Se registraron <b>" + nuevas.size() + "</b> nota(s) de <b>" + actividad.getTitulo() + "</b> en " + a.descripcion() + "." + detalle(resultados));
        });
    }

    // MODNOTA[programa, gestion, grupo, modulo, titulo, estudiante, nueva_nota]: mientras el acta esté abierta
    public String modificarNota(String[] p) {
        if (p.length < 7) {
            return formato("MODNOTA[" + ASIGNACION + ", \"titulo\", \"codigo_estudiante o ci\", \"nueva_nota\"]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarCapacitador(con, a);
            validarActaAbierta(con, a);
            ActividadEvaluativa actividad = buscarActividad(con, a, p[4]);
            Cursante c = buscarCursante(con, a, p[5], actividad.esRecuperatorio());
            BigDecimal nota = notaOpcional(p[6]);
            if (nota == null) {
                throw new ReglaNegocioException("<b>" + p[6] + "</b> no es una nota válida: debe estar entre 0 y 100.");
            }
            CalificacionDAO dao = new CalificacionDAO(con);
            if (!dao.deLaAsignacion(a.id()).getOrDefault(c.getIdInscripcionModulo(), new HashMap<>()).containsKey(actividad.getIdActividad())) {
                throw new ReglaNegocioException("<b>" + c.getNombre() + "</b> no tiene nota en <b>" + actividad.getTitulo() + "</b>: regístrela con ADDNOTA.");
            }
            dao.actualizar(actividad.getIdActividad(), c.getIdInscripcionModulo(), nota);
            List<String> resultados = new ResultadosModulo(con, idRemitente).recalcular(a.id());
            log(con, "CALIFICACION", "UPDATE");
            return GeneradorHTML.mensajeExito("Calificaciones",
                    "La nota de <b>" + c.getNombre() + "</b> en <b>" + actividad.getTitulo() + "</b> quedó en <b>" + nota + "</b>." + detalle(resultados));
        });
    }

    // LISNOTA[programa, gestion, grupo, modulo]: planilla con una columna por actividad y la nota final calculada
    public String listarNotas(String[] p) {
        if (p.length < 4) {
            return formato("LISNOTA[" + ASIGNACION + "]");
        }
        return enTransaccion(con -> {
            Asignacion a = buscarAsignacion(con, p, 0);
            validarConsulta(con, a);
            List<ActividadEvaluativa> actividades = new ActividadDAO(con).listarModelos(a.id());
            Map<Integer, Map<Integer, BigDecimal>> notas = new CalificacionDAO(con).deLaAsignacion(a.id());
            List<String> cabeceras = new ArrayList<>(List.of("codigo", "estudiante", "intento", "condicion"));
            for (ActividadEvaluativa actividad : actividades) {
                cabeceras.add(actividad.getTitulo() + " (" + actividad.getPonderacion() + "%)");
            }
            cabeceras.add("nota_final");
            cabeceras.add("estado");
            List<String[]> filas = new ArrayList<>();
            for (Cursante c : new InscripcionModuloDAO(con).cursantes(a.id())) {
                Map<Integer, BigDecimal> suyas = notas.getOrDefault(c.getIdInscripcionModulo(), new HashMap<>());
                List<String> fila = new ArrayList<>(List.of(c.getCodigo(), c.getNombre(), String.valueOf(c.getNumeroIntento()), c.getCondicion()));
                for (ActividadEvaluativa actividad : actividades) {
                    BigDecimal nota = suyas.get(actividad.getIdActividad());
                    fila.add(nota == null ? null : nota.toString());
                }
                fila.add(c.getNotaFinal() == null ? null : c.getNotaFinal().toString());
                fila.add(c.getEstado());
                filas.add(fila.toArray(new String[0]));
            }
            log(con, "CALIFICACION", "SELECT");
            return GeneradorHTML.tabla("la Planilla de Notas de " + a.modulo.getCodigo(), Tabla.de(cabeceras, filas));
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    private TipoEvaluacion buscarTipo(Connection con, String texto) throws SQLException {
        String buscado = Validador.normalizar(texto);
        buscado = ALIAS_TIPOS.getOrDefault(buscado, buscado);
        for (TipoEvaluacion t : new ActividadDAO(con).listarTipos()) {
            if (Validador.normalizar(t.getNombre()).equals(buscado)) {
                return t;
            }
        }
        throw new ReglaNegocioException("El tipo de evaluación <b>" + texto + "</b> no existe. Tipos: ASISTENCIA, TRABAJOS PRACTICOS, PARCIAL, FINAL.");
    }

    private TipoEvaluacion tipoDe(Connection con, ActividadEvaluativa actividad) throws SQLException {
        for (TipoEvaluacion t : new ActividadDAO(con).listarTipos()) {
            if (t.getIdTipo() == actividad.getIdTipo()) {
                return t;
            }
        }
        throw new ReglaNegocioException("No se encontró el tipo de evaluación de la actividad.");
    }

    private static int ponderacionValida(String texto) {
        int ponderacion = entero(texto, "ponderacion");
        if (ponderacion <= 0 || ponderacion > 100) {
            throw new ReglaNegocioException("La <b>ponderacion</b> debe estar entre 1 y 100.");
        }
        return ponderacion;
    }

    private static void validarPonderacionDelTipo(ActividadDAO dao, Asignacion a, TipoEvaluacion tipo, int ponderacion, int idActividadExcluida)
            throws SQLException {
        int otras = dao.sumaPorTipo(a.id(), tipo.getIdTipo(), idActividadExcluida);
        if (otras + ponderacion > tipo.getPonderacionMaxima()) {
            throw new ReglaNegocioException("<b>" + tipo.getNombre() + "</b> suma " + otras + " y con " + ponderacion + " superaría su máximo de <b>"
                    + tipo.getPonderacionMaxima() + "</b>.");
        }
    }

    private ActividadEvaluativa buscarActividad(Connection con, Asignacion a, String titulo) throws SQLException {
        requerido(titulo, "titulo");
        ActividadEvaluativa actividad = new ActividadDAO(con).buscar(a.id(), titulo);
        if (actividad == null) {
            throw new ReglaNegocioException("No existe la actividad <b>" + titulo.trim() + "</b> en " + a.descripcion()
                    + ". Consulte <code>LISACT[" + a.referencia() + "]</code>.");
        }
        return actividad;
    }

    // "2026-11-23" o "2026-11-23 19:00"
    private SesionClase buscarSesion(Connection con, Asignacion a, String texto) throws SQLException {
        requerido(texto, "fecha");
        String[] partes = texto.trim().split("\\s+");
        LocalDate dia = fecha(partes[0], "fecha");
        LocalTime horaInicio = partes.length > 1 ? hora(partes[1], "hora") : null;
        List<SesionClase> sesiones = new SesionDAO(con).buscar(a.id(), dia, horaInicio);
        if (sesiones.isEmpty()) {
            throw new ReglaNegocioException("No hay una sesión el <b>" + texto.trim() + "</b> en " + a.descripcion()
                    + ". Consulte <code>LISSES[" + a.referencia() + "]</code>.");
        }
        if (sesiones.size() > 1) {
            throw new ReglaNegocioException("Hay " + sesiones.size() + " sesiones el " + dia + ": indique también la hora, por ejemplo <b>\"" + dia + " "
                    + sesiones.get(0).getHoraInicio() + "\"</b>.");
        }
        return sesiones.get(0);
    }

    // La asistencia solo se registra en una clase que se llevó a cabo
    private SesionClase buscarSesionDada(Connection con, Asignacion a, String texto) throws SQLException {
        SesionClase s = buscarSesion(con, a, texto);
        if (!s.fueDada()) {
            throw new ReglaNegocioException("La sesión del " + s.getFecha() + " está <b>" + s.getEstado() + "</b>"
                    + (s.getFecha().isAfter(LocalDate.now()) ? " y todavía no ocurrió" : "")
                    + ": primero márquela con <code>MODSES[" + a.referencia() + ", \"" + s.getFecha() + "\", \"DICTADA\"]</code>.");
        }
        return s;
    }

    private Cursante buscarCursante(Connection con, Asignacion a, String valor, boolean recuperatorio) throws SQLException {
        requerido(valor, "estudiante");
        Cursante c = elegir(new InscripcionModuloDAO(con).cursantes(a.id()), valor, recuperatorio);
        if (c == null) {
            throw new ReglaNegocioException("<b>" + valor.trim() + "</b> no " + (recuperatorio ? "tiene el recuperatorio habilitado en " : "cursa ") + a.descripcion() + ".");
        }
        return c;
    }

    private static int maxLicencias(Asignacion a) {
        return "SEMIPRESENCIAL".equals(a.oferta.getModalidad()) ? MAX_LICENCIAS_SEMIPRESENCIAL : MAX_LICENCIAS_PRESENCIAL;
    }

    // La documentación fija el máximo para las clases presenciales y semipresenciales; en una oferta virtual no hay límite
    private static boolean puedeTenerLicencia(AsistenciaDAO dao, Asignacion a, Cursante c, int idSesionExcluida) throws SQLException {
        return "VIRTUAL".equals(a.oferta.getModalidad()) || dao.contarLicencias(c.getIdInscripcionModulo(), idSesionExcluida) < maxLicencias(a);
    }

    // "20267654321:PRESENTE; 20268100200:AUSENTE" -> [[20267654321, PRESENTE], [20268100200, AUSENTE]]
    private static List<String[]> pares(String lista) {
        requerido(lista, "lista de estudiantes");
        List<String[]> pares = new ArrayList<>();
        for (String entrada : lista.split(";")) {
            if (Validador.vacio(entrada)) {
                continue;
            }
            String[] par = entrada.split(":");
            if (par.length != 2 || Validador.vacio(par[0]) || Validador.vacio(par[1])) {
                throw new ReglaNegocioException("<b>" + entrada.trim() + "</b> no tiene el formato <code>codigo:valor</code>. Separe cada estudiante con punto y coma.");
            }
            pares.add(new String[] { par[0].trim(), par[1].trim() });
        }
        return pares;
    }

    private static BigDecimal notaOpcional(String texto) {
        try {
            BigDecimal nota = new BigDecimal(texto.trim().replace(',', '.'));
            return nota.signum() < 0 || nota.compareTo(new BigDecimal(100)) > 0 ? null : nota.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void rechazarSiHayErrores(List<String> errores) {
        if (!errores.isEmpty()) {
            throw new ReglaNegocioException("No se registró nada. Corrija la lista y vuelva a enviarla:<br>• " + String.join("<br>• ", errores));
        }
    }

    private static String detalle(List<String> resultados) {
        return resultados.isEmpty() ? "" : "<br>Resultados: " + String.join("; ", resultados) + ".";
    }
}
