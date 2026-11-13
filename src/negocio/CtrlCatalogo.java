package src.negocio;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import src.modelo.Modulo;
import src.modelo.ModuloVersion;
import src.modelo.Programa;
import src.modelo.VersionPrograma;
import src.persistencia.MallaDAO;
import src.persistencia.ModuloDAO;
import src.persistencia.ProgramaDAO;
import src.persistencia.VersionDAO;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-02 (Flujos A, B y C): PROGRAMAS, VERSIONES DE MALLA CURRICULAR Y MÓDULOS
// En los comandos nada se identifica por ID: un programa por su código ("TAC"),
// una versión por su programa y número ("TAC", "v1") y un módulo por su código ("CONT-1")
// =========================================================================
public class CtrlCatalogo extends CtrlBase {

    private static final List<String> NIVELES = List.of("TECNICO_MEDIO", "TECNICO_AUXILIAR",
            "CERTIFICACION_COMPETENCIA_LABORAL", "CURSO_CORTO");

    private static final List<String> TIPOS_RESOLUCION = List.of("RESOLUCION_ICAP", "RESOLUCION_DECANATO",
            "RESOLUCION_VICERRECTORADO");

    private static final List<String> ESTADOS_PROGRAMA = List.of("ACTIVO", "INACTIVO");

    // Nombre del campo en el comando -> columna en la BD
    private static final Map<String, String> ALIAS_CAMPOS = Map.of(
            "NIVEL", "nivel_titulacion",
            "VERSION", "numero_version",
            "HORAS", "total_horas",
            "ORDEN", "orden_modulo",
            "CARGA", "carga_horaria",
            "PRERREQUISITO", "prerrequisito");

    public CtrlCatalogo(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Programas (Coordinación Académica)
    // ---------------------------------------------------------------------

    // ADDPROG[codigo, nombre, nivel_titulacion, codigo_programa_prerrequisito]
    public String registrarPrograma(String[] p) {
        if (p.length < 3) {
            return formato("ADDPROG[\"codigo\", \"nombre\", \"nivel_titulacion\", \"codigo_prerrequisito (opcional)\"]");
        }
        return enTransaccion(con -> {
            requerido(p[0], "codigo");
            requerido(p[1], "nombre");
            Programa prog = new Programa(p[0].trim().toUpperCase(), p[1].trim(), opcion(p[2], NIVELES, "nivel_titulacion"));
            ProgramaDAO dao = new ProgramaDAO(con);
            Programa existente = dao.buscarPorCodigo(prog.getCodigo());
            if (existente != null) {
                throw new ReglaNegocioException("Ya existe un programa con el código <b>" + prog.getCodigo() + "</b>"
                        + ("INACTIVO".equals(existente.getEstado())
                                ? " (INACTIVO). Para reactivarlo envíe <code>MODPROG[\"" + prog.getCodigo()
                                        + "\", \"estado\", \"ACTIVO\"]</code>."
                                : "."));
            }
            dao.insertar(prog);
            log(con, "PROGRAMA", "INSERT");

            String detalle = "";
            if (!Validador.vacio(param(p, 3))) {
                Programa pre = asignarPrerrequisito(con, prog, p[3]);
                detalle = "<br>Requiere haber concluido: <b>" + pre.getCodigo() + " - " + pre.getNombre() + "</b>";
            }
            return GeneradorHTML.mensajeExito("Registro de Programa",
                    "Se registró el programa <b>" + prog.getCodigo() + " - " + prog.getNombre() + "</b> ("
                            + prog.getNivelTitulacion() + ") en estado ACTIVO." + detalle
                            + "<br>Siguiente paso: registrar su versión, por ejemplo <code>ADDVER[\"" + prog.getCodigo()
                            + "\", \"v1\", \"240\"]</code>.");
        });
    }

    // LISPROG["*" | codigo]
    public String listarProgramas(String filtro) {
        return enTransaccion(con -> {
            Tabla t = new ProgramaDAO(con).listar(filtroListado(filtro));
            log(con, "PROGRAMA", "SELECT");
            return GeneradorHTML.tabla("los Programas (CU-02)", t);
        });
    }

    // MODPROG[codigo, campo, nuevo_valor]
    public String modificarPrograma(String[] p) {
        if (p.length < 3) {
            return formato("MODPROG[\"codigo\", \"campo\", \"nuevo_valor\"] (campos: nombre, nivel_titulacion, prerrequisito, estado)");
        }
        return enTransaccion(con -> {
            ProgramaDAO dao = new ProgramaDAO(con);
            Programa prog = buscarPrograma(con, p[0]);
            String campo = columna(p[1], ALIAS_CAMPOS);
            String valor;
            if (campo.equals("prerrequisito")) {
                // Reemplaza el prerrequisito actual; un valor vacío lo quita
                dao.quitarPrerrequisitos(prog.getIdPrograma());
                valor = "ninguno";
                if (!Validador.vacio(p[2])) {
                    valor = asignarPrerrequisito(con, prog, p[2]).getCodigo();
                }
                log(con, "PROGRAMA_PRERREQUISITO", "UPDATE");
            } else if (campo.equals("estado")) {
                // Con ACTIVO se reactiva un programa dado de baja con DELPROG
                valor = opcion(p[2], ESTADOS_PROGRAMA, "estado");
                if (valor.equals(prog.getEstado())) {
                    throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> ya está " + valor + ".");
                }
                dao.cambiarEstado(prog.getIdPrograma(), valor);
                log(con, "PROGRAMA", "UPDATE");
            } else if (ProgramaDAO.CAMPOS.contains(campo)) {
                valor = campo.equals("nivel_titulacion") ? opcion(p[2], NIVELES, "nivel_titulacion") : p[2].trim();
                requerido(valor, campo);
                dao.actualizarCampo(prog.getIdPrograma(), campo, valor);
                log(con, "PROGRAMA", "UPDATE");
            } else {
                throw new ReglaNegocioException("El campo <b>" + p[1] + "</b> no se puede modificar. "
                        + "Campos válidos: nombre, nivel_titulacion, prerrequisito, estado.");
            }
            return GeneradorHTML.mensajeExito("Modificación de Programa",
                    "Se actualizó <b>" + campo + "</b> del programa <b>" + prog.getCodigo() + "</b> a <b>" + valor + "</b>.");
        });
    }

    // DELPROG[codigo]: sin versiones se borra; con versiones pasa a INACTIVO para preservar el historial de egresados
    public String eliminarPrograma(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("DELPROG[\"codigo\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            ProgramaDAO dao = new ProgramaDAO(con);
            if (!dao.tieneVersiones(prog.getIdPrograma())) {
                dao.eliminar(prog.getIdPrograma());
                log(con, "PROGRAMA", "DELETE");
                return GeneradorHTML.mensajeExito("Eliminación de Programa",
                        "Se eliminó el programa <b>" + prog.getCodigo() + " - " + prog.getNombre() + "</b> (no tenía versiones).");
            }
            if ("INACTIVO".equals(prog.getEstado())) {
                throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> ya está INACTIVO.");
            }
            dao.cambiarEstado(prog.getIdPrograma(), "INACTIVO");
            log(con, "PROGRAMA", "UPDATE");
            return GeneradorHTML.mensajeExito("Baja de Programa",
                    "El programa <b>" + prog.getCodigo() + " - " + prog.getNombre() + "</b> tiene versiones: no se borra y "
                            + "pasó a INACTIVO. Para reactivarlo envíe <code>MODPROG[\"" + prog.getCodigo()
                            + "\", \"estado\", \"ACTIVO\"]</code>.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Versiones y malla curricular
    // ---------------------------------------------------------------------

    // ADDVER[programa, version, total_horas]
    public String registrarVersion(String[] p) {
        if (p.length < 3) {
            return formato("ADDVER[\"programa\", \"version\", \"total_horas\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            if (!"ACTIVO".equals(prog.getEstado())) {
                throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> está INACTIVO.");
            }
            requerido(p[1], "version");
            int horas = entero(p[2], "total_horas");
            if (horas <= 0) {
                throw new ReglaNegocioException("<b>total_horas</b> debe ser mayor a cero.");
            }
            VersionDAO dao = new VersionDAO(con);
            if (dao.existeNumero(prog.getIdPrograma(), p[1])) {
                throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> ya tiene la versión <b>"
                        + p[1].trim() + "</b>.");
            }
            VersionPrograma v = new VersionPrograma(prog.getIdPrograma(), p[1].trim(), horas);
            dao.insertar(v);
            log(con, "VERSION_PROGRAMA", "INSERT");
            return GeneradorHTML.mensajeExito("Registro de Versión",
                    "Se creó la versión <b>" + nombre(prog, v) + "</b> con <b>" + horas + " horas</b>, en estado EN_ELABORACION.<br>"
                            + "Siguiente paso: armar su malla, por ejemplo <code>ADDMALLA[" + ref(prog, v)
                            + ", \"CONT-1\", \"1\", \"120\"]</code>.");
        });
    }

    // LISVER[programa]
    public String listarVersiones(String codigoPrograma) {
        if (Validador.vacio(codigoPrograma)) {
            return formato("LISVER[\"programa\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, codigoPrograma);
            Tabla t = new VersionDAO(con).listarPorPrograma(prog.getIdPrograma());
            log(con, "VERSION_PROGRAMA", "SELECT");
            return GeneradorHTML.tabla("las Versiones de " + prog.getCodigo() + " - " + prog.getNombre(), t);
        });
    }

    // MODVER[programa, version, campo, nuevo_valor]
    public String modificarVersion(String[] p) {
        if (p.length < 4) {
            return formato("MODVER[\"programa\", \"version\", \"campo\", \"nuevo_valor\"] (campos: version, total_horas)");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = buscarVersionEnElaboracion(con, prog, p[1]);
            String campo = columna(p[2], ALIAS_CAMPOS);
            Object valor;
            if (campo.equals("total_horas")) {
                int horas = entero(p[3], "total_horas");
                if (horas <= 0) {
                    throw new ReglaNegocioException("<b>total_horas</b> debe ser mayor a cero.");
                }
                valor = horas;
            } else if (campo.equals("numero_version")) {
                requerido(p[3], "version");
                if (new VersionDAO(con).existeNumero(prog.getIdPrograma(), p[3])) {
                    throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> ya tiene la versión <b>"
                            + p[3].trim() + "</b>.");
                }
                valor = p[3].trim();
            } else {
                throw new ReglaNegocioException("El campo <b>" + p[2] + "</b> no se puede modificar. "
                        + "Campos válidos: version, total_horas.");
            }
            new VersionDAO(con).actualizarCampo(v.getIdVersion(), campo, valor);
            log(con, "VERSION_PROGRAMA", "UPDATE");
            return GeneradorHTML.mensajeExito("Modificación de Versión",
                    "Se actualizó <b>" + campo + "</b> de la versión <b>" + nombre(prog, v) + "</b> a <b>" + valor + "</b>.");
        });
    }

    // DELVER[programa, version]: borra una versión en elaboración; una versión aprobada pasa a CERRADA
    public String eliminarVersion(String[] p) {
        if (p.length < 2) {
            return formato("DELVER[\"programa\", \"version\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = buscarVersion(con, prog, p[1]);
            VersionDAO dao = new VersionDAO(con);
            if (v.enElaboracion()) {
                dao.eliminar(v.getIdVersion());
                log(con, "VERSION_PROGRAMA", "DELETE");
                return GeneradorHTML.mensajeExito("Eliminación de Versión",
                        "Se eliminó la versión <b>" + nombre(prog, v) + "</b> (en elaboración) junto con su malla.");
            }
            if ("CERRADA".equals(v.getEstado())) {
                throw new ReglaNegocioException("La versión <b>" + nombre(prog, v) + "</b> ya está CERRADA.");
            }
            dao.cambiarEstado(v.getIdVersion(), "CERRADA");
            log(con, "VERSION_PROGRAMA", "UPDATE");
            return GeneradorHTML.mensajeExito("Cierre de Versión",
                    "La versión <b>" + nombre(prog, v) + "</b> estaba " + v.getEstado()
                            + ": no se borra para preservar el historial de los egresados y pasó a <b>CERRADA</b>. "
                            + "Ya no se pueden registrar ofertas nuevas con ella.");
        });
    }

    // APRVER[programa, version, numero_resolucion, tipo_resolucion, fecha_aprobacion] (Director)
    public String aprobarVersion(String[] p) {
        if (p.length < 5) {
            return formato("APRVER[\"programa\", \"version\", \"numero_resolucion\", \"tipo_resolucion\", \"fecha_aprobacion\"] "
                    + "(tipo: ICAP, DECANATO o VICERRECTORADO)");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            if (!"ACTIVO".equals(prog.getEstado())) {
                throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> está INACTIVO.");
            }
            VersionPrograma v = buscarVersionEnElaboracion(con, prog, p[1]);
            requerido(p[2], "numero_resolucion");
            String tipo = Validador.normalizar(p[3]).replace(' ', '_');
            if (!tipo.startsWith("RESOLUCION_")) {
                tipo = "RESOLUCION_" + tipo;
            }
            tipo = opcion(tipo, TIPOS_RESOLUCION, "tipo_resolucion");
            LocalDate fechaAprobacion = fecha(p[4], "fecha_aprobacion");
            if (fechaAprobacion.isAfter(LocalDate.now())) {
                throw new ReglaNegocioException("La fecha de aprobación (" + fechaAprobacion + ") no puede ser futura.");
            }

            MallaDAO malla = new MallaDAO(con);
            if (malla.contarModulos(v.getIdVersion()) == 0) {
                throw new ReglaNegocioException("La versión <b>" + nombre(prog, v) + "</b> no tiene módulos en su malla. "
                        + "Agréguelos con ADDMALLA.");
            }
            int suma = malla.sumaCargaHoraria(v.getIdVersion());
            if (suma != v.getTotalHoras()) {
                throw new ReglaNegocioException("La suma de las cargas horarias de la malla (<b>" + suma
                        + " h</b>) no coincide con el total de la versión (<b>" + v.getTotalHoras() + " h</b>).");
            }

            VersionDAO dao = new VersionDAO(con);
            VersionPrograma anterior = dao.buscarVigente(prog.getIdPrograma());
            String detalle = "";
            if (anterior != null) {
                dao.cambiarEstado(anterior.getIdVersion(), "CERRADA");
                detalle = "<br>La versión anterior <b>" + anterior.getNumeroVersion() + "</b> pasó a CERRADA.";
            }
            dao.aprobar(v.getIdVersion(), p[2].trim(), tipo, fechaAprobacion);
            log(con, "VERSION_PROGRAMA", "UPDATE");
            return GeneradorHTML.mensajeExito("Aprobación de Versión",
                    "La versión <b>" + nombre(prog, v) + "</b> quedó <b>VIGENTE</b> con la " + tipo + " N° <b>"
                            + p[2].trim() + "</b> del " + fechaAprobacion + "." + detalle
                            + "<br>Ya se pueden registrar ofertas del programa, por ejemplo <code>ADDOFER[\""
                            + prog.getCodigo() + "\", \"2-2026\", \"A\", ...]</code>.");
        });
    }

    // ADDMALLA[programa, version, modulo, orden, carga_horaria]
    public String registrarMalla(String[] p) {
        if (p.length < 5) {
            return formato("ADDMALLA[\"programa\", \"version\", \"modulo\", \"orden\", \"carga_horaria\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = buscarVersionEnElaboracion(con, prog, p[1]);
            Modulo m = buscarModulo(con, p[2]);
            int orden = entero(p[3], "orden");
            int carga = entero(p[4], "carga_horaria");
            if (orden <= 0 || carga <= 0) {
                throw new ReglaNegocioException("El <b>orden</b> y la <b>carga_horaria</b> deben ser mayores a cero.");
            }
            MallaDAO dao = new MallaDAO(con);
            if (dao.buscar(v.getIdVersion(), m.getIdModulo()) != null) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> ya está en la malla de "
                        + nombre(prog, v) + ".");
            }
            if (dao.ordenOcupado(v.getIdVersion(), orden, 0)) {
                throw new ReglaNegocioException("El orden <b>" + orden + "</b> ya está ocupado en la malla de "
                        + nombre(prog, v) + ".");
            }
            dao.insertar(new ModuloVersion(v.getIdVersion(), m.getIdModulo(), orden, carga));
            log(con, "MODULO_VERSION", "INSERT");
            int suma = dao.sumaCargaHoraria(v.getIdVersion());
            return GeneradorHTML.mensajeExito("Malla Curricular",
                    "Se agregó <b>" + m.getCodigo() + " - " + m.getNombre() + "</b> (orden " + orden + ", " + carga
                            + " h) a la versión <b>" + nombre(prog, v) + "</b>.<br>Horas en malla: <b>" + suma
                            + "</b> de <b>" + v.getTotalHoras() + "</b>."
                            + (suma > v.getTotalHoras() ? "<br><b>Atención:</b> la malla supera el total de horas de la versión." : ""));
        });
    }

    // ADDPRE[programa, version, modulo, modulo_prerrequisito]
    public String registrarPrerrequisito(String[] p) {
        if (p.length < 4) {
            return formato("ADDPRE[\"programa\", \"version\", \"modulo\", \"modulo_prerrequisito\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = buscarVersionEnElaboracion(con, prog, p[1]);
            ModuloVersion mod = buscarEnMalla(con, prog, v, p[2]);
            ModuloVersion pre = buscarEnMalla(con, prog, v, p[3]);
            if (mod.getIdModuloVersion() == pre.getIdModuloVersion()) {
                throw new ReglaNegocioException("Un módulo no puede ser prerrequisito de sí mismo.");
            }
            // El prerrequisito debe cursarse antes: esto además impide ciclos
            if (pre.getOrdenModulo() >= mod.getOrdenModulo()) {
                throw new ReglaNegocioException("El prerrequisito <b>" + p[3].toUpperCase() + "</b> (orden " + pre.getOrdenModulo()
                        + ") debe tener un orden menor que <b>" + p[2].toUpperCase() + "</b> (orden " + mod.getOrdenModulo() + ").");
            }
            MallaDAO dao = new MallaDAO(con);
            if (dao.existePrerrequisito(mod.getIdModuloVersion(), pre.getIdModuloVersion())) {
                throw new ReglaNegocioException("Ese prerrequisito ya está registrado.");
            }
            dao.agregarPrerrequisito(mod.getIdModuloVersion(), pre.getIdModuloVersion());
            log(con, "MODULO_VERSION_PRERREQUISITO", "INSERT");
            return GeneradorHTML.mensajeExito("Prerrequisito",
                    "En la versión <b>" + nombre(prog, v) + "</b>, <b>" + p[2].toUpperCase() + "</b> ahora requiere <b>"
                            + p[3].toUpperCase() + "</b> aprobado.");
        });
    }

    // LISMALLA[programa, version]: sin versión muestra la VIGENTE
    public String listarMalla(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("LISMALLA[\"programa\", \"version (opcional: sin ella se muestra la VIGENTE)\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = versionAConsultar(con, prog, param(p, 1), "LISMALLA");
            MallaDAO dao = new MallaDAO(con);
            Tabla t = dao.listarMalla(v.getIdVersion());
            log(con, "MODULO_VERSION", "SELECT");
            return GeneradorHTML.tabla("la Malla de " + nombre(prog, v) + " [" + v.getEstado() + "] - "
                    + dao.sumaCargaHoraria(v.getIdVersion()) + " de " + v.getTotalHoras() + " h", t);
        });
    }

    // LISPRE[programa, version]: sin versión muestra la VIGENTE
    public String listarPrerrequisitos(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("LISPRE[\"programa\", \"version (opcional: sin ella se muestra la VIGENTE)\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = versionAConsultar(con, prog, param(p, 1), "LISPRE");
            Tabla t = new MallaDAO(con).listarPrerrequisitos(v.getIdVersion());
            log(con, "MODULO_VERSION_PRERREQUISITO", "SELECT");
            return GeneradorHTML.tabla("los Prerrequisitos de " + nombre(prog, v) + " [" + v.getEstado() + "]", t);
        });
    }

    // MODMALLA[programa, version, modulo, campo, nuevo_valor]
    public String modificarMalla(String[] p) {
        if (p.length < 5) {
            return formato("MODMALLA[\"programa\", \"version\", \"modulo\", \"campo\", \"nuevo_valor\"] (campos: orden, carga_horaria)");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = buscarVersionEnElaboracion(con, prog, p[1]);
            ModuloVersion mv = buscarEnMalla(con, prog, v, p[2]);
            String campo = columna(p[3], ALIAS_CAMPOS);
            if (!MallaDAO.CAMPOS.contains(campo)) {
                throw new ReglaNegocioException("El campo <b>" + p[3] + "</b> no se puede modificar. Campos válidos: orden, carga_horaria.");
            }
            int valor = entero(p[4], campo);
            if (valor <= 0) {
                throw new ReglaNegocioException("<b>" + campo + "</b> debe ser mayor a cero.");
            }
            MallaDAO dao = new MallaDAO(con);
            if (campo.equals("orden_modulo")) {
                if (dao.ordenOcupado(v.getIdVersion(), valor, mv.getIdModuloVersion())) {
                    throw new ReglaNegocioException("El orden <b>" + valor + "</b> ya está ocupado en la malla.");
                }
                Integer maxPre = dao.maxOrdenPrerrequisitos(mv.getIdModuloVersion());
                Integer minDep = dao.minOrdenDependientes(mv.getIdModuloVersion());
                if ((maxPre != null && valor <= maxPre) || (minDep != null && valor >= minDep)) {
                    throw new ReglaNegocioException("Con el orden <b>" + valor + "</b> el módulo quedaría antes de sus "
                            + "prerrequisitos o después de los módulos que lo requieren. Revise <code>LISPRE[" + ref(prog, v)
                            + "]</code>.");
                }
            }
            dao.actualizarCampo(mv.getIdModuloVersion(), campo, valor);
            log(con, "MODULO_VERSION", "UPDATE");
            return GeneradorHTML.mensajeExito("Modificación de Malla",
                    "Se actualizó <b>" + campo + "</b> de <b>" + p[2].toUpperCase() + "</b> a <b>" + valor
                            + "</b> en la versión <b>" + nombre(prog, v) + "</b>.");
        });
    }

    // DELMALLA[programa, version, modulo]
    public String eliminarMalla(String[] p) {
        if (p.length < 3) {
            return formato("DELMALLA[\"programa\", \"version\", \"modulo\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = buscarVersionEnElaboracion(con, prog, p[1]);
            ModuloVersion mv = buscarEnMalla(con, prog, v, p[2]);
            new MallaDAO(con).eliminar(mv.getIdModuloVersion());
            log(con, "MODULO_VERSION", "DELETE");
            return GeneradorHTML.mensajeExito("Malla Curricular",
                    "Se quitó <b>" + p[2].toUpperCase() + "</b> de la malla de <b>" + nombre(prog, v)
                            + "</b>, junto con sus prerrequisitos.");
        });
    }

    // DELPRE[programa, version, modulo, modulo_prerrequisito]
    public String eliminarPrerrequisito(String[] p) {
        if (p.length < 4) {
            return formato("DELPRE[\"programa\", \"version\", \"modulo\", \"modulo_prerrequisito\"]");
        }
        return enTransaccion(con -> {
            Programa prog = buscarPrograma(con, p[0]);
            VersionPrograma v = buscarVersionEnElaboracion(con, prog, p[1]);
            ModuloVersion mod = buscarEnMalla(con, prog, v, p[2]);
            ModuloVersion pre = buscarEnMalla(con, prog, v, p[3]);
            MallaDAO dao = new MallaDAO(con);
            if (!dao.existePrerrequisito(mod.getIdModuloVersion(), pre.getIdModuloVersion())) {
                throw new ReglaNegocioException("<b>" + p[2].toUpperCase() + "</b> no tiene a <b>" + p[3].toUpperCase()
                        + "</b> como prerrequisito.");
            }
            dao.quitarPrerrequisito(mod.getIdModuloVersion(), pre.getIdModuloVersion());
            log(con, "MODULO_VERSION_PRERREQUISITO", "DELETE");
            return GeneradorHTML.mensajeExito("Prerrequisito",
                    "Se quitó el prerrequisito <b>" + p[3].toUpperCase() + "</b> de <b>" + p[2].toUpperCase()
                            + "</b> en la versión <b>" + nombre(prog, v) + "</b>.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo C - Módulos
    // ---------------------------------------------------------------------

    // ADDMOD[codigo, nombre]
    public String registrarModulo(String[] p) {
        if (p.length < 2) {
            return formato("ADDMOD[\"codigo\", \"nombre\"]");
        }
        return enTransaccion(con -> {
            requerido(p[0], "codigo");
            requerido(p[1], "nombre");
            Modulo m = new Modulo(p[0].trim().toUpperCase(), p[1].trim());
            ModuloDAO dao = new ModuloDAO(con);
            if (dao.buscarPorCodigo(m.getCodigo()) != null) {
                throw new ReglaNegocioException("Ya existe un módulo con el código <b>" + m.getCodigo() + "</b>.");
            }
            dao.insertar(m);
            log(con, "MODULO", "INSERT");
            return GeneradorHTML.mensajeExito("Registro de Módulo",
                    "Se registró el módulo <b>" + m.getCodigo() + " - " + m.getNombre() + "</b>.");
        });
    }

    // LISMOD["*" | codigo]
    public String listarModulos(String filtro) {
        return enTransaccion(con -> {
            Tabla t = new ModuloDAO(con).listar(filtroListado(filtro));
            log(con, "MODULO", "SELECT");
            return GeneradorHTML.tabla("los Módulos (CU-02)", t);
        });
    }

    // MODMOD[codigo, campo, nuevo_valor]
    public String modificarModulo(String[] p) {
        if (p.length < 3) {
            return formato("MODMOD[\"codigo\", \"campo\", \"nuevo_valor\"] (campos: codigo, nombre)");
        }
        return enTransaccion(con -> {
            Modulo m = buscarModulo(con, p[0]);
            String campo = columna(p[1], ALIAS_CAMPOS);
            if (!ModuloDAO.CAMPOS.contains(campo)) {
                throw new ReglaNegocioException("El campo <b>" + p[1] + "</b> no se puede modificar. Campos válidos: codigo, nombre.");
            }
            requerido(p[2], campo);
            String valor = campo.equals("codigo") ? p[2].trim().toUpperCase() : p[2].trim();
            ModuloDAO dao = new ModuloDAO(con);
            if (campo.equals("codigo")) {
                Modulo existente = dao.buscarPorCodigo(valor);
                if (existente != null && existente.getIdModulo() != m.getIdModulo()) {
                    throw new ReglaNegocioException("Ya existe un módulo con el código <b>" + valor + "</b>.");
                }
            }
            dao.actualizarCampo(m.getIdModulo(), campo, valor);
            log(con, "MODULO", "UPDATE");
            return GeneradorHTML.mensajeExito("Modificación de Módulo",
                    "Se actualizó <b>" + campo + "</b> del módulo <b>" + m.getCodigo() + "</b> a <b>" + valor + "</b>.");
        });
    }

    // DELMOD[codigo]: solo si el módulo no pertenece a ninguna malla
    public String eliminarModulo(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("DELMOD[\"codigo\"]");
        }
        return enTransaccion(con -> {
            Modulo m = buscarModulo(con, p[0]);
            ModuloDAO dao = new ModuloDAO(con);
            if (dao.estaEnUso(m.getIdModulo())) {
                throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> pertenece a una malla o tiene "
                        + "asignaciones. Quítelo primero con DELMALLA (solo en versiones en elaboración). "
                        + "Consulte en qué versiones está con <code>LISMOD[\"" + m.getCodigo() + "\"]</code>.");
            }
            dao.eliminar(m.getIdModulo());
            log(con, "MODULO", "DELETE");
            return GeneradorHTML.mensajeExito("Eliminación de Módulo",
                    "Se eliminó el módulo <b>" + m.getCodigo() + " - " + m.getNombre() + "</b>.");
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    // "TAC v1"
    private static String nombre(Programa prog, VersionPrograma v) {
        return prog.getCodigo() + " " + v.getNumeroVersion();
    }

    // Parámetros que identifican la versión en los comandos: "TAC", "v1"
    private static String ref(Programa prog, VersionPrograma v) {
        return "\"" + prog.getCodigo() + "\", \"" + v.getNumeroVersion() + "\"";
    }

    private Programa buscarPrograma(Connection con, String codigo) throws SQLException {
        requerido(codigo, "programa");
        Programa prog = new ProgramaDAO(con).buscarPorCodigo(codigo);
        if (prog == null) {
            throw new ReglaNegocioException("No existe un programa con el código <b>" + codigo + "</b>. Consulte LISPROG[\"*\"].");
        }
        return prog;
    }

    private Programa asignarPrerrequisito(Connection con, Programa prog, String codigoPrerrequisito) throws SQLException {
        Programa pre = buscarPrograma(con, codigoPrerrequisito);
        ProgramaDAO dao = new ProgramaDAO(con);
        if (pre.getIdPrograma() == prog.getIdPrograma()) {
            throw new ReglaNegocioException("Un programa no puede ser prerrequisito de sí mismo.");
        }
        if (dao.generaCiclo(prog.getIdPrograma(), pre.getIdPrograma())) {
            throw new ReglaNegocioException("<b>" + pre.getCodigo() + "</b> ya exige a <b>" + prog.getCodigo()
                    + "</b>: se formaría un ciclo de prerrequisitos.");
        }
        dao.agregarPrerrequisito(prog.getIdPrograma(), pre.getIdPrograma());
        log(con, "PROGRAMA_PRERREQUISITO", "INSERT");
        return pre;
    }

    private VersionPrograma buscarVersion(Connection con, Programa prog, String numeroVersion) throws SQLException {
        requerido(numeroVersion, "version");
        VersionPrograma v = new VersionDAO(con).buscarPorNumero(prog.getIdPrograma(), numeroVersion);
        if (v == null) {
            throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> no tiene la versión <b>"
                    + numeroVersion.trim() + "</b>. Consulte <code>LISVER[\"" + prog.getCodigo() + "\"]</code>.");
        }
        return v;
    }

    // La malla y los datos de una versión solo se modifican mientras está EN_ELABORACION
    private VersionPrograma buscarVersionEnElaboracion(Connection con, Programa prog, String numeroVersion)
            throws SQLException {
        VersionPrograma v = buscarVersion(con, prog, numeroVersion);
        if (!v.enElaboracion()) {
            throw new ReglaNegocioException("La versión <b>" + nombre(prog, v) + "</b> está <b>" + v.getEstado()
                    + "</b>: su malla ya no se puede modificar. Registre una versión nueva con ADDVER.");
        }
        return v;
    }

    // Para las consultas la versión es opcional: sin ella se usa la VIGENTE
    private VersionPrograma versionAConsultar(Connection con, Programa prog, String numeroVersion, String comando)
            throws SQLException {
        if (!Validador.vacio(numeroVersion)) {
            return buscarVersion(con, prog, numeroVersion);
        }
        VersionPrograma v = new VersionDAO(con).buscarVigente(prog.getIdPrograma());
        if (v == null) {
            throw new ReglaNegocioException("El programa <b>" + prog.getCodigo() + "</b> no tiene una versión VIGENTE. "
                    + "Indique cuál consultar, por ejemplo <code>" + comando + "[\"" + prog.getCodigo()
                    + "\", \"v1\"]</code> (vea sus versiones con LISVER).");
        }
        return v;
    }

    private ModuloVersion buscarEnMalla(Connection con, Programa prog, VersionPrograma v, String codigoModulo)
            throws SQLException {
        Modulo m = buscarModulo(con, codigoModulo);
        ModuloVersion mv = new MallaDAO(con).buscar(v.getIdVersion(), m.getIdModulo());
        if (mv == null) {
            throw new ReglaNegocioException("El módulo <b>" + m.getCodigo() + "</b> no está en la malla de <b>"
                    + nombre(prog, v) + "</b>.");
        }
        return mv;
    }
}
