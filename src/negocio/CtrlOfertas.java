package src.negocio;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import src.modelo.AsignacionModulo;
import src.modelo.Capacitador;
import src.modelo.Horario;
import src.modelo.Modulo;
import src.modelo.OfertaAcademica;
import src.modelo.Programa;
import src.modelo.VersionPrograma;
import src.persistencia.AsignacionDAO;
import src.persistencia.CapacitadorDAO;
import src.persistencia.HorarioDAO;
import src.persistencia.MallaDAO;
import src.persistencia.OfertaDAO;
import src.persistencia.ProgramaDAO;
import src.persistencia.VersionDAO;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-02 (Flujos D, E y F): OFERTAS ACADÉMICAS, ASIGNACIÓN DE CAPACITADORES Y HORARIOS
// En los comandos nada se identifica por ID: una oferta por su programa, gestión y grupo ("TAC", "2-2026", "A"),
// una asignación por la oferta y el módulo ("CONT-1") y un horario por la asignación y el día ("LUNES")
// =========================================================================
public class CtrlOfertas extends CtrlBase {

    private static final List<String> MODALIDADES = List.of("PRESENCIAL", "SEMIPRESENCIAL", "VIRTUAL");

    // Estados que se fijan con MODOFER (PREINSCRIPCIONES_ABIERTAS es con APROFER y CANCELADA con DELOFER)
    private static final List<String> ESTADOS_MODOFER = List.of("CONFIRMADA", "POSTERGADA", "EN_CURSO", "FINALIZADA");

    // Estado destino de MODOFER -> estados desde los que se permite llegar
    private static final Map<String, List<String>> TRANSICIONES = Map.of(
            "CONFIRMADA", List.of("PREINSCRIPCIONES_ABIERTAS", "POSTERGADA"),
            "POSTERGADA", List.of("PREINSCRIPCIONES_ABIERTAS", "CONFIRMADA"),
            "EN_CURSO", List.of("CONFIRMADA"),
            "FINALIZADA", List.of("EN_CURSO"));

    // Nombre del campo en el comando -> columna en la BD
    private static final Map<String, String> ALIAS_CAMPOS = Map.of(
            "INICIO", "fecha_inicio",
            "FIN", "fecha_fin",
            "CUPO_MINIMO", "cupo_min",
            "CUPO_MAXIMO", "cupo_max",
            "CAPACITADOR", "id_personal",
            "CI_CAPACITADOR", "id_personal",
            "DIA", "dia_semana");

    public CtrlOfertas(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo D - Ofertas académicas
    // ---------------------------------------------------------------------

    // ADDOFER[programa, gestion, grupo, modalidad, lugar, fecha_inicio, fecha_fin, cupo_min, cupo_max, costo]
    // La oferta se abre sobre la versión VIGENTE del programa
    public String registrarOferta(String[] p) {
        if (p.length < 10) {
            return formato("ADDOFER[" + OFERTA + ", \"modalidad\", \"lugar\", \"fecha_inicio\", \"fecha_fin\", "
                    + "\"cupo_min\", \"cupo_max\", \"costo\"]");
        }
        return enTransaccion(con -> {
            Programa prog = new ProgramaDAO(con).buscarPorCodigo(p[0]);
            if (prog == null) {
                throw new ReglaNegocioException("No existe un programa con el código <b>" + p[0] + "</b>. Consulte LISPROG[\"*\"].");
            }
            if (!"ACTIVO".equals(prog.getEstado())) {
                throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> está INACTIVO.");
            }
            VersionPrograma v = new VersionDAO(con).buscarVigente(prog.getIdPrograma());
            if (v == null) {
                throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> no tiene una versión VIGENTE. "
                        + "Solo se ofertan versiones aprobadas por el Director con APRVER.");
            }
            requerido(p[1], "gestion");
            requerido(p[2], "grupo");
            requerido(p[4], "lugar");
            OfertaAcademica o = new OfertaAcademica();
            o.setIdVersion(v.getIdVersion());
            o.setCodigoPrograma(prog.getCodigo());
            o.setGestion(p[1].trim().toUpperCase());
            o.setGrupo(p[2].trim().toUpperCase());
            o.setModalidad(opcion(p[3], MODALIDADES, "modalidad"));
            o.setLugar(p[4].trim());
            o.setFechaInicio(fecha(p[5], "fecha_inicio"));
            o.setFechaFin(fecha(p[6], "fecha_fin"));
            o.setCupoMin(entero(p[7], "cupo_min"));
            o.setCupoMax(Validador.vacio(p[8]) ? null : entero(p[8], "cupo_max"));
            o.setCosto(decimal(p[9], "costo"));
            if (o.getFechaInicio().isBefore(LocalDate.now())) {
                throw new ReglaNegocioException("La fecha de inicio (" + o.getFechaInicio() + ") ya pasó.");
            }
            validarOferta(o);

            OfertaDAO dao = new OfertaDAO(con);
            OfertaAcademica existente = dao.buscar(prog.getCodigo(), o.getGestion(), o.getGrupo());
            if (existente != null) {
                throw new ReglaNegocioException("Ya existe la " + existente.getDescripcion() + " (" + existente.getEstado()
                        + "). Use otro grupo.");
            }
            dao.insertar(o);
            log(con, "OFERTA_ACADEMICA", "INSERT");
            return GeneradorHTML.mensajeExito("Registro de Oferta",
                    "Se registró la " + o.getDescripcion() + " de <b>" + prog.getNombre() + "</b> (versión "
                            + v.getNumeroVersion() + ", " + o.getModalidad() + ") del " + o.getFechaInicio() + " al "
                            + o.getFechaFin() + " en estado <b>PLANIFICADA</b>.<br>"
                            + "Cupo mínimo " + o.getCupoMin() + (o.getCupoMax() != null ? ", máximo " + o.getCupoMax() : "")
                            + ". Costo: Bs " + o.getCosto() + ".<br>"
                            + "Siguientes pasos: asignar sus módulos con <code>ADDASIG[" + o.getReferencia() + ", ...]</code> "
                            + "y que el Director la publique con <code>APROFER[" + o.getReferencia() + "]</code>.");
        });
    }

    // APROFER[programa, gestion, grupo] (Director): abre las preinscripciones y la publica en CATALOGO
    public String aprobarOferta(String[] p) {
        if (p.length < 3) {
            return formato("APROFER[" + OFERTA + "]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOferta(con, p, 0);
            if (!"PLANIFICADA".equals(o.getEstado()) && !"POSTERGADA".equals(o.getEstado())) {
                throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado()
                        + "</b>. Solo se aprueban ofertas PLANIFICADA o POSTERGADA.");
            }
            new OfertaDAO(con).cambiarEstado(o.getIdOferta(), "PREINSCRIPCIONES_ABIERTAS");
            log(con, "OFERTA_ACADEMICA", "UPDATE");
            return GeneradorHTML.mensajeExito("Aprobación de Oferta",
                    "La " + o.getDescripcion() + " pasó a <b>PREINSCRIPCIONES_ABIERTAS</b> y ya es visible en el "
                            + "comando público <code>CATALOGO</code>.");
        });
    }

    // CATALOGO (público, cualquier remitente)
    public String catalogo() {
        return enTransaccion(con -> {
            Tabla t = new OfertaDAO(con).catalogo();
            log(con, "OFERTA_ACADEMICA", "SELECT");
            return GeneradorHTML.tabla("el Catálogo de Ofertas con Preinscripciones Abiertas (ICAP)", t);
        });
    }

    // LISOFER["*" | estado | gestion | programa]
    public String listarOfertas(String filtro) {
        return enTransaccion(con -> {
            Tabla t = new OfertaDAO(con).listar(filtroListado(filtro));
            log(con, "OFERTA_ACADEMICA", "SELECT");
            return GeneradorHTML.tabla("las Ofertas Académicas (CU-02)", t);
        });
    }

    // MODOFER[programa, gestion, grupo, campo, nuevo_valor]
    public String modificarOferta(String[] p) {
        if (p.length < 5) {
            return formato("MODOFER[" + OFERTA + ", \"campo\", \"nuevo_valor\"] (campos: fecha_inicio, fecha_fin, cupo_min, "
                    + "cupo_max, modalidad, lugar, costo, estado)");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            OfertaDAO dao = new OfertaDAO(con);
            String campo = columna(p[3], ALIAS_CAMPOS);
            String nuevo = p[4];
            Object valor;
            switch (campo) {
                case "fecha_inicio":
                    o.setFechaInicio(fecha(nuevo, "fecha_inicio"));
                    valor = o.getFechaInicio();
                    break;
                case "fecha_fin":
                    o.setFechaFin(fecha(nuevo, "fecha_fin"));
                    valor = o.getFechaFin();
                    break;
                case "cupo_min":
                    o.setCupoMin(entero(nuevo, "cupo_min"));
                    valor = o.getCupoMin();
                    break;
                case "cupo_max":
                    o.setCupoMax(Validador.vacio(nuevo) ? null : entero(nuevo, "cupo_max"));
                    valor = o.getCupoMax();
                    break;
                case "modalidad":
                    valor = opcion(nuevo, MODALIDADES, "modalidad");
                    break;
                case "lugar":
                    requerido(nuevo, "lugar");
                    o.setLugar(nuevo.trim());
                    valor = o.getLugar();
                    break;
                case "costo":
                    o.setCosto(decimal(nuevo, "costo"));
                    valor = o.getCosto();
                    break;
                case "estado":
                    valor = validarCambioEstado(con, o, nuevo);
                    break;
                default:
                    throw new ReglaNegocioException("El campo <b>" + p[3] + "</b> no se puede modificar. Campos válidos: "
                            + "fecha_inicio, fecha_fin, cupo_min, cupo_max, modalidad, lugar, costo, estado.");
            }
            validarOferta(o);
            if (campo.startsWith("fecha_")) {
                // Los módulos ya asignados deben seguir dentro de las fechas de la oferta
                List<String[]> fuera = dao.asignacionesFueraDeRango(o.getIdOferta(), o.getFechaInicio(), o.getFechaFin());
                if (!fuera.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String[] f : fuera) {
                        sb.append("<br>• ").append(f[0]).append(": ").append(f[1]).append(" a ").append(f[2]);
                    }
                    throw new ReglaNegocioException("Con esa fecha, estos módulos asignados quedarían fuera de la oferta "
                            + "(" + o.getFechaInicio() + " a " + o.getFechaFin() + "):" + sb
                            + "<br>Ajuste primero sus fechas con <code>MODASIG[" + o.getReferencia() + ", \"modulo\", "
                            + "\"fecha_inicio\", \"AAAA-MM-DD\"]</code>.");
                }
            }
            if (campo.equals("lugar")) {
                // El choque de aula se evalúa dentro del mismo lugar: se revisan los horarios en el lugar nuevo
                validarHorariosDeOferta(con, o);
            }
            dao.actualizarCampo(o.getIdOferta(), campo, valor);
            log(con, "OFERTA_ACADEMICA", "UPDATE");
            return GeneradorHTML.mensajeExito("Modificación de Oferta",
                    "Se actualizó <b>" + campo + "</b> de la " + o.getDescripcion() + " a <b>"
                            + (valor != null ? valor : "sin límite") + "</b>.");
        });
    }

    // DELOFER[programa, gestion, grupo]: borra una oferta PLANIFICADA sin preinscritos; si no, la CANCELA y avisa a los preinscritos
    public String eliminarOferta(String[] p) {
        if (p.length < 3) {
            return formato("DELOFER[" + OFERTA + "]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            OfertaDAO dao = new OfertaDAO(con);
            if ("PLANIFICADA".equals(o.getEstado()) && dao.contarInscripciones(o.getIdOferta()) == 0) {
                dao.eliminar(o.getIdOferta());
                log(con, "OFERTA_ACADEMICA", "DELETE");
                return GeneradorHTML.mensajeExito("Eliminación de Oferta",
                        "Se eliminó la " + o.getDescripcion() + " (PLANIFICADA, sin preinscritos) junto con sus asignaciones y horarios.");
            }
            dao.cambiarEstado(o.getIdOferta(), "CANCELADA");
            log(con, "OFERTA_ACADEMICA", "UPDATE");

            List<String[]> preinscritos = dao.correosPreinscritos(o.getIdOferta());
            for (String[] est : preinscritos) {
                notificar(est[0], "Cancelacion de la oferta " + o.getCodigoPrograma() + " " + o.getGestion() + " grupo " + o.getGrupo(),
                        GeneradorHTML.mensajeAviso("Oferta cancelada",
                                "Estimado(a) <b>" + est[1] + "</b>: la " + o.getDescripcion() + " que iniciaba el "
                                        + o.getFechaInicio() + " fue <b>CANCELADA</b> por el ICAP. "
                                        + "Consulte otras ofertas disponibles enviando el comando <b>CATALOGO</b>."));
            }
            return GeneradorHTML.mensajeExito("Cancelación de Oferta",
                    "La " + o.getDescripcion() + " estaba " + o.getEstado() + " y pasó a <b>CANCELADA</b>. "
                            + "Se notificó a <b>" + preinscritos.size() + "</b> preinscrito(s).");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo E - Asignación de capacitadores (Coordinación Académica)
    // ---------------------------------------------------------------------

    // ADDASIG[programa, gestion, grupo, modulo, ci_capacitador, fecha_inicio, fecha_fin, programa_analitico, propuesta_tecnica]
    public String registrarAsignacion(String[] p) {
        if (p.length < 7) {
            return formato("ADDASIG[" + OFERTA + ", \"modulo\", \"ci_capacitador\", \"fecha_inicio\", \"fecha_fin\", "
                    + "\"programa_analitico (opcional)\", \"propuesta_tecnica (opcional)\"]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            Modulo m = buscarModulo(con, p[3]);
            if (new MallaDAO(con).buscar(o.getIdVersion(), m.getIdModulo()) == null) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> no pertenece a la malla de la "
                        + o.getDescripcion() + ". Consulte <code>LISMALLA[\"" + o.getCodigoPrograma() + "\"]</code>.");
            }
            AsignacionDAO dao = new AsignacionDAO(con);
            if (dao.buscar(o.getIdOferta(), m.getIdModulo()) != null) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> ya está asignado en la "
                        + o.getDescripcion() + ". Use MODASIG para cambiar el capacitador o las fechas.");
            }
            Capacitador cap = buscarCapacitadorActivo(con, p[4]);

            AsignacionModulo a = new AsignacionModulo();
            a.setIdOferta(o.getIdOferta());
            a.setIdModulo(m.getIdModulo());
            a.setIdPersonal(cap.getIdPersonal());
            a.setFechaInicio(fecha(p[5], "fecha_inicio"));
            a.setFechaFin(fecha(p[6], "fecha_fin"));
            a.setProgramaAnalitico(nulo(param(p, 7)));
            a.setPropuestaTecnica(nulo(param(p, 8)));
            validarFechasAsignacion(a, o);

            dao.insertar(a);
            dao.crearActa(a.getIdAsignacion());
            log(con, "ASIGNACION_MODULO", "INSERT");
            log(con, "ACTA_CALIFICACION", "INSERT");
            return GeneradorHTML.mensajeExito("Asignación de Módulo",
                    "Se asignó <b>" + m.getCodigo() + " - " + m.getNombre() + "</b> de la " + o.getDescripcion() + " a <b>"
                            + cap.getNombreCompleto() + "</b> del " + a.getFechaInicio() + " al " + a.getFechaFin() + ".<br>"
                            + "Se creó su acta de calificación en estado ABIERTA.<br>"
                            + "Siguiente paso: registrar su horario, por ejemplo <code>ADDHOR[" + o.getReferencia() + ", \""
                            + m.getCodigo() + "\", \"LUNES\", \"19:00\", \"21:30\", \"Aula 5\"]</code>.");
        });
    }

    // LISASIG[programa, gestion, grupo]
    public String listarAsignaciones(String[] p) {
        if (p.length < 3) {
            return formato("LISASIG[" + OFERTA + "]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOferta(con, p, 0);
            Tabla t = new AsignacionDAO(con).listarPorOferta(o.getIdOferta());
            log(con, "ASIGNACION_MODULO", "SELECT");
            return GeneradorHTML.tabla("los Módulos Asignados de la oferta " + o.getCodigoPrograma() + " " + o.getGestion()
                    + " grupo " + o.getGrupo(), t);
        });
    }

    // MODASIG[programa, gestion, grupo, modulo, campo, nuevo_valor]
    public String modificarAsignacion(String[] p) {
        if (p.length < 6) {
            return formato("MODASIG[" + OFERTA + ", \"modulo\", \"campo\", \"nuevo_valor\"] (campos: capacitador, fecha_inicio, "
                    + "fecha_fin, programa_analitico, propuesta_tecnica)");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            Modulo m = buscarModulo(con, p[3]);
            AsignacionModulo a = buscarAsignacion(con, o, m);
            AsignacionDAO dao = new AsignacionDAO(con);
            HorarioDAO horarioDAO = new HorarioDAO(con);
            List<Horario> horarios = horarioDAO.listarPorAsignacion(a.getIdAsignacion());
            String campo = columna(p[4], ALIAS_CAMPOS);
            Object valor;
            String detalle = "";
            switch (campo) {
                case "id_personal":
                    Capacitador cap = buscarCapacitadorActivo(con, p[5]);
                    a.setIdPersonal(cap.getIdPersonal());
                    for (Horario h : horarios) {
                        validarChoques(horarioDAO, h, a, o);
                    }
                    valor = cap.getIdPersonal();
                    detalle = "Nuevo capacitador: <b>" + cap.getNombreCompleto() + "</b>.";
                    break;
                case "fecha_inicio":
                case "fecha_fin":
                    LocalDate f = fecha(p[5], campo);
                    if (campo.equals("fecha_inicio")) {
                        a.setFechaInicio(f);
                    } else {
                        a.setFechaFin(f);
                    }
                    validarFechasAsignacion(a, o);
                    for (Horario h : horarios) {
                        validarChoques(horarioDAO, h, a, o);
                    }
                    valor = f;
                    break;
                case "programa_analitico":
                case "propuesta_tecnica":
                    valor = nulo(p[5]);
                    break;
                default:
                    throw new ReglaNegocioException("El campo <b>" + p[4] + "</b> no se puede modificar. Campos válidos: "
                            + "capacitador, fecha_inicio, fecha_fin, programa_analitico, propuesta_tecnica.");
            }
            dao.actualizarCampo(a.getIdAsignacion(), campo, valor);
            log(con, "ASIGNACION_MODULO", "UPDATE");

            // Con otras fechas, las sesiones futuras se regeneran dentro del nuevo rango
            if (campo.startsWith("fecha_") && !horarios.isEmpty()) {
                int creadas = 0;
                for (Horario h : horarios) {
                    horarioDAO.eliminarSesionesFuturas(h);
                    creadas += horarioDAO.generarSesiones(h, desdeHoy(a.getFechaInicio()), a.getFechaFin());
                }
                log(con, "SESION_CLASE", "UPDATE");
                detalle = "Se regeneraron las sesiones futuras: <b>" + creadas + "</b> sesión(es) programada(s).";
            }
            return GeneradorHTML.mensajeExito("Modificación de Asignación",
                    "Se actualizó <b>" + p[4] + "</b> del módulo <b>" + m.getCodigo() + "</b> en la " + o.getDescripcion()
                            + ". " + detalle);
        });
    }

    // DELASIG[programa, gestion, grupo, modulo]: solo si el módulo todavía no tiene estudiantes inscritos
    public String eliminarAsignacion(String[] p) {
        if (p.length < 4) {
            return formato("DELASIG[" + OFERTA + ", \"modulo\"]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            Modulo m = buscarModulo(con, p[3]);
            AsignacionModulo a = buscarAsignacion(con, o, m);
            AsignacionDAO dao = new AsignacionDAO(con);
            if (dao.tieneEstudiantes(a.getIdAsignacion())) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> de la " + o.getDescripcion()
                        + " ya tiene estudiantes inscritos. Use MODASIG para reemplazar al capacitador.");
            }
            dao.eliminar(a.getIdAsignacion());
            log(con, "ASIGNACION_MODULO", "DELETE");
            return GeneradorHTML.mensajeExito("Eliminación de Asignación",
                    "Se quitó la asignación del módulo <b>" + m.getCodigo() + "</b> de la " + o.getDescripcion()
                            + ", con su acta, horarios y sesiones.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo F - Horarios y sesiones de clase
    // ---------------------------------------------------------------------

    // ADDHOR[programa, gestion, grupo, modulo, dia_semana, hora_inicio, hora_fin, aula]
    public String registrarHorario(String[] p) {
        if (p.length < 7) {
            return formato("ADDHOR[" + OFERTA + ", \"modulo\", \"dia_semana\", \"hora_inicio\", \"hora_fin\", \"aula\"]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            Modulo m = buscarModulo(con, p[3]);
            AsignacionModulo a = buscarAsignacion(con, o, m);
            Horario h = new Horario();
            h.setIdAsignacion(a.getIdAsignacion());
            h.setDiaSemana(opcion(p[4], Horario.DIAS, "dia_semana"));
            h.setHoraInicio(hora(p[5], "hora_inicio"));
            h.setHoraFin(hora(p[6], "hora_fin"));
            h.setAula(nulo(param(p, 7)));
            validarHoras(h);
            if (h.getAula() == null && "PRESENCIAL".equals(o.getModalidad())) {
                throw new ReglaNegocioException("La " + o.getDescripcion() + " es PRESENCIAL: indique el <b>aula</b>.");
            }

            HorarioDAO dao = new HorarioDAO(con);
            if (dao.buscar(a.getIdAsignacion(), h.getDiaSemana()) != null) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> ya tiene horario el <b>" + h.getDiaSemana()
                        + "</b>. Para cambiarlo use <code>MODHOR[" + o.getReferencia() + ", \"" + m.getCodigo() + "\", \""
                        + h.getDiaSemana() + "\", \"campo\", \"nuevo_valor\"]</code>.");
            }
            validarChoques(dao, h, a, o);
            dao.insertar(h);
            int creadas = dao.generarSesiones(h, a.getFechaInicio(), a.getFechaFin());
            log(con, "HORARIO", "INSERT");
            log(con, "SESION_CLASE", "INSERT");
            return GeneradorHTML.mensajeExito("Registro de Horario",
                    "Se registró el horario <b>" + h.getDescripcion() + "</b> del módulo <b>" + m.getCodigo() + "</b> en la "
                            + o.getDescripcion() + ".<br>Se generaron <b>" + creadas + "</b> sesión(es) de clase PROGRAMADA "
                            + "entre el " + a.getFechaInicio() + " y el " + a.getFechaFin() + "."
                            + (creadas == 0 ? "<br><b>Atención:</b> ningún " + h.getDiaSemana() + " cae dentro de esas fechas." : ""));
        });
    }

    // LISHOR[programa, gestion, grupo]
    public String listarHorarios(String[] p) {
        if (p.length < 3) {
            return formato("LISHOR[" + OFERTA + "]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOferta(con, p, 0);
            Tabla t = new HorarioDAO(con).listarPorOferta(o.getIdOferta());
            log(con, "HORARIO", "SELECT");
            return GeneradorHTML.tabla("el Horario Semanal de la oferta " + o.getCodigoPrograma() + " " + o.getGestion()
                    + " grupo " + o.getGrupo(), t);
        });
    }

    // MODHOR[programa, gestion, grupo, modulo, dia_semana, campo, nuevo_valor]:
    // repite la validación de choques y regenera las sesiones futuras
    public String modificarHorario(String[] p) {
        if (p.length < 7) {
            return formato("MODHOR[" + OFERTA + ", \"modulo\", \"dia_semana\", \"campo\", \"nuevo_valor\"] "
                    + "(campos: dia_semana, hora_inicio, hora_fin, aula)");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            Modulo m = buscarModulo(con, p[3]);
            AsignacionModulo a = buscarAsignacion(con, o, m);
            HorarioDAO dao = new HorarioDAO(con);
            Horario h = buscarHorario(dao, o, m, a, p[4]);
            Horario anterior = new Horario(h);
            String campo = columna(p[5], ALIAS_CAMPOS);
            switch (campo) {
                case "dia_semana":
                    h.setDiaSemana(opcion(p[6], Horario.DIAS, "dia_semana"));
                    if (!h.getDiaSemana().equals(anterior.getDiaSemana())
                            && dao.buscar(a.getIdAsignacion(), h.getDiaSemana()) != null) {
                        throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> ya tiene horario el <b>"
                                + h.getDiaSemana() + "</b>.");
                    }
                    break;
                case "hora_inicio":
                    h.setHoraInicio(hora(p[6], "hora_inicio"));
                    break;
                case "hora_fin":
                    h.setHoraFin(hora(p[6], "hora_fin"));
                    break;
                case "aula":
                    h.setAula(nulo(p[6]));
                    if (h.getAula() == null && "PRESENCIAL".equals(o.getModalidad())) {
                        throw new ReglaNegocioException("La " + o.getDescripcion() + " es PRESENCIAL: el aula no puede quedar vacía.");
                    }
                    break;
                default:
                    throw new ReglaNegocioException("El campo <b>" + p[5] + "</b> no se puede modificar. Campos válidos: "
                            + "dia_semana, hora_inicio, hora_fin, aula.");
            }
            validarHoras(h);
            validarChoques(dao, h, a, o);
            dao.actualizar(h);
            int borradas = dao.eliminarSesionesFuturas(anterior);
            int creadas = dao.generarSesiones(h, desdeHoy(a.getFechaInicio()), a.getFechaFin());
            log(con, "HORARIO", "UPDATE");
            log(con, "SESION_CLASE", "UPDATE");
            return GeneradorHTML.mensajeExito("Modificación de Horario",
                    "El horario del módulo <b>" + m.getCodigo() + "</b> en la " + o.getDescripcion() + " quedó <b>"
                            + h.getDescripcion() + "</b>.<br>Sesiones futuras regeneradas: " + borradas + " eliminada(s), <b>"
                            + creadas + "</b> programada(s).");
        });
    }

    // DELHOR[programa, gestion, grupo, modulo, dia_semana]: borra el horario y sus sesiones futuras aún no dictadas
    public String eliminarHorario(String[] p) {
        if (p.length < 5) {
            return formato("DELHOR[" + OFERTA + ", \"modulo\", \"dia_semana\"]");
        }
        return enTransaccion(con -> {
            OfertaAcademica o = buscarOfertaAbierta(con, p, 0);
            Modulo m = buscarModulo(con, p[3]);
            AsignacionModulo a = buscarAsignacion(con, o, m);
            HorarioDAO dao = new HorarioDAO(con);
            Horario h = buscarHorario(dao, o, m, a, p[4]);
            int borradas = dao.eliminarSesionesFuturas(h);
            dao.eliminar(h.getIdHorario());
            log(con, "HORARIO", "DELETE");
            log(con, "SESION_CLASE", "DELETE");
            return GeneradorHTML.mensajeExito("Eliminación de Horario",
                    "Se eliminó el horario <b>" + h.getDescripcion() + "</b> del módulo <b>" + m.getCodigo() + "</b> y "
                            + borradas + " sesión(es) futura(s). Las sesiones ya pasadas se conservan.");
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    private static void validarOferta(OfertaAcademica o) {
        if (o.getFechaFin().isBefore(o.getFechaInicio())) {
            throw new ReglaNegocioException("La fecha de fin (" + o.getFechaFin() + ") es anterior a la de inicio ("
                    + o.getFechaInicio() + ").");
        }
        if (o.getCupoMin() <= 0) {
            throw new ReglaNegocioException("El <b>cupo_min</b> debe ser mayor a cero.");
        }
        if (o.getCupoMax() != null && o.getCupoMax() < o.getCupoMin()) {
            throw new ReglaNegocioException("El <b>cupo_max</b> (" + o.getCupoMax() + ") no puede ser menor que el cupo mínimo ("
                    + o.getCupoMin() + ").");
        }
        if (o.getCosto().compareTo(BigDecimal.ZERO) < 0) {
            throw new ReglaNegocioException("El <b>costo</b> no puede ser negativo.");
        }
    }

    // Una oferta no pasa a CONFIRMADA si los preinscritos no alcanzan el cupo mínimo
    private String validarCambioEstado(Connection con, OfertaAcademica o, String valor) throws SQLException {
        String destino = opcion(valor, ESTADOS_MODOFER, "estado");
        if (!TRANSICIONES.get(destino).contains(o.getEstado())) {
            throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado() + "</b> y no puede pasar a <b>"
                    + destino + "</b>. Se permite desde: " + String.join(", ", TRANSICIONES.get(destino)) + ".");
        }
        if (destino.equals("CONFIRMADA")) {
            int preinscritos = new OfertaDAO(con).contarPreinscritos(o.getIdOferta());
            if (preinscritos < o.getCupoMin()) {
                throw new ReglaNegocioException("No se puede confirmar: hay <b>" + preinscritos + "</b> preinscrito(s) y el cupo "
                        + "mínimo es <b>" + o.getCupoMin() + "</b>. Puede postergarla (<code>MODOFER[" + o.getReferencia()
                        + ", \"estado\", \"POSTERGADA\"]</code>) o cancelarla (<code>DELOFER[" + o.getReferencia() + "]</code>).");
            }
        }
        return destino;
    }

    private static void validarFechasAsignacion(AsignacionModulo a, OfertaAcademica o) {
        if (a.getFechaFin().isBefore(a.getFechaInicio())) {
            throw new ReglaNegocioException("La fecha de fin de la asignación es anterior a la de inicio.");
        }
        if (a.getFechaInicio().isBefore(o.getFechaInicio()) || a.getFechaFin().isAfter(o.getFechaFin())) {
            throw new ReglaNegocioException("Las fechas del módulo (" + a.getFechaInicio() + " a " + a.getFechaFin()
                    + ") deben estar dentro de las de la oferta (" + o.getFechaInicio() + " a " + o.getFechaFin() + ").");
        }
    }

    private static void validarHoras(Horario h) {
        if (!h.getHoraFin().isAfter(h.getHoraInicio())) {
            throw new ReglaNegocioException("La hora de fin (" + h.getHoraFin() + ") debe ser posterior a la de inicio ("
                    + h.getHoraInicio() + ").");
        }
    }

    // Ni el capacitador ni el aula pueden tener otra clase en ese día y franja mientras ambas asignaciones estén vigentes
    private static void validarChoques(HorarioDAO dao, Horario h, AsignacionModulo a, OfertaAcademica o) throws SQLException {
        String choque = dao.choqueCapacitador(h, a.getIdPersonal(), a.getFechaInicio(), a.getFechaFin());
        if (choque != null) {
            throw new ReglaNegocioException("Choque de horario del capacitador: ya dicta el " + choque + ".");
        }
        choque = dao.choqueAula(h, o.getLugar(), a.getFechaInicio(), a.getFechaFin());
        if (choque != null) {
            throw new ReglaNegocioException("Choque de aula: <b>" + h.getAula() + "</b> ya está ocupada por el " + choque + ".");
        }
    }

    // Revisa los choques de todos los horarios de la oferta (por ejemplo, al cambiar de lugar)
    private static void validarHorariosDeOferta(Connection con, OfertaAcademica o) throws SQLException {
        HorarioDAO horarioDAO = new HorarioDAO(con);
        for (AsignacionModulo a : new AsignacionDAO(con).listarModelos(o.getIdOferta())) {
            for (Horario h : horarioDAO.listarPorAsignacion(a.getIdAsignacion())) {
                validarChoques(horarioDAO, h, a, o);
            }
        }
    }

    // Las sesiones se regeneran solo desde hoy: las pasadas ya se dictaron o se suspendieron
    private static LocalDate desdeHoy(LocalDate inicio) {
        LocalDate hoy = LocalDate.now();
        return inicio.isAfter(hoy) ? inicio : hoy;
    }

    private OfertaAcademica buscarOfertaAbierta(Connection con, String[] p, int i) throws SQLException {
        OfertaAcademica o = buscarOferta(con, p, i);
        if (o.estaCerrada()) {
            throw new ReglaNegocioException("La " + o.getDescripcion() + " está <b>" + o.getEstado() + "</b> y ya no se puede modificar.");
        }
        return o;
    }

    private AsignacionModulo buscarAsignacion(Connection con, OfertaAcademica o, Modulo m) throws SQLException {
        AsignacionModulo a = new AsignacionDAO(con).buscar(o.getIdOferta(), m.getIdModulo());
        if (a == null) {
            throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> no está asignado en la " + o.getDescripcion()
                    + ". Asígnelo con ADDASIG o consulte <code>LISASIG[" + o.getReferencia() + "]</code>.");
        }
        return a;
    }

    private Horario buscarHorario(HorarioDAO dao, OfertaAcademica o, Modulo m, AsignacionModulo a, String dia)
            throws SQLException {
        Horario h = dao.buscar(a.getIdAsignacion(), opcion(dia, Horario.DIAS, "dia_semana"));
        if (h == null) {
            throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> no tiene horario el <b>" + dia.trim().toUpperCase()
                    + "</b>. Consulte <code>LISHOR[" + o.getReferencia() + "]</code>.");
        }
        return h;
    }

    private Capacitador buscarCapacitadorActivo(Connection con, String ci) throws SQLException {
        requerido(ci, "ci_capacitador");
        Capacitador cap = new CapacitadorDAO(con).buscarPorCi(ci.trim());
        if (cap == null) {
            throw new ReglaNegocioException("No existe un capacitador con el CI <b>" + ci + "</b>. Regístrelo con ADDCAP.");
        }
        if (!"ACTIVO".equals(cap.getEstado())) {
            throw new ReglaNegocioException("El capacitador <b>" + cap.getNombreCompleto() + "</b> está INACTIVO. "
                    + "Puede reactivarlo con <code>RESCAP[\"" + cap.getCi() + "\"]</code>.");
        }
        return cap;
    }
}
