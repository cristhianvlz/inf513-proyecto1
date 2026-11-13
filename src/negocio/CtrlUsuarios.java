package src.negocio;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import src.modelo.Capacitador;
import src.modelo.Estudiante;
import src.modelo.Personal;
import src.modelo.Rol;
import src.modelo.Usuario;
import src.persistencia.CapacitadorDAO;
import src.persistencia.EstudianteDAO;
import src.persistencia.PersonalDAO;
import src.persistencia.RolDAO;
import src.persistencia.UsuarioDAO;
import src.utilidades.GeneradorHTML;
import src.utilidades.Tabla;
import src.utilidades.Validador;

// =========================================================================
// CU-01: GESTIÓN DE USUARIOS (Personal, Capacitadores, Estudiantes y Roles)
// =========================================================================
public class CtrlUsuarios extends CtrlBase {

    private static final String ROL_DIRECTOR = "DIRECTOR";
    private static final String ROL_CAPACITADOR = "CAPACITADOR";

    // Roles que el Director asigna con ADDPER (nombres normalizados)
    private static final Set<String> ROLES_PERSONAL = Set.of("COORDINACION ACADEMICA",
            "ENCARGADO DE PROCESAMIENTO DE NOTAS", "JEFE ADMINISTRATIVO FINANCIERO", "ENCARGADO DE REGISTRO");

    // Abreviaturas aceptadas en los comandos
    private static final Map<String, String> ALIAS_ROLES = Map.of(
            "CPD", "ENCARGADO DE PROCESAMIENTO DE NOTAS",
            "JAF", "JEFE ADMINISTRATIVO FINANCIERO");

    // Nombre del campo en el comando -> columna en la BD
    private static final Map<String, String> ALIAS_CAMPOS = Map.of(
            "CORREO", "email",
            "EXPEDICION", "ci_expedicion",
            "FECHA_NAC", "fecha_nacimiento",
            "TITULO", "titulo_academico",
            "GRADO", "grado_academico",
            "EXPERIENCIA", "experiencia_lab",
            "CV", "cv_documento",
            "CONTRASENA", "clave");

    public CtrlUsuarios(int idRemitente) {
        super(idRemitente);
    }

    // ---------------------------------------------------------------------
    // Flujo A - Personal administrativo (Director)
    // ---------------------------------------------------------------------

    // ADDPER[ci, ci_expedicion, nombre, apellidos, direccion, telefono, email, rol]
    public String registrarPersonal(String[] p) {
        if (p.length < 8) {
            return formato("ADDPER[\"ci\", \"ci_expedicion\", \"nombre\", \"apellidos\", \"direccion\", \"telefono\", \"email\", \"rol\"]");
        }
        return enTransaccion(con -> {
            Personal per = new Personal(p[0], p[1], p[2], p[3], nulo(p[4]), nulo(p[5]));
            String email = p[6].toLowerCase();
            validarPersona(per, email);

            Rol rol = resolverRol(con, p[7]);
            if (!ROLES_PERSONAL.contains(Validador.normalizar(rol.getNombre()))) {
                throw new ReglaNegocioException("El rol <b>" + rol.getNombre() + "</b> no se asigna con ADDPER. "
                        + "Roles permitidos: Coordinación Académica, CPD (Encargado de Procesamiento de Notas), "
                        + "Jefe Administrativo Financiero y Encargado de Registro. Los capacitadores se registran con ADDCAP.");
            }
            validarCiYEmailLibres(con, per.getCi(), email);

            new PersonalDAO(con).insertar(per);
            Usuario u = crearCuenta(con, per, email, rol);
            log(con, "PERSONAL", "INSERT");

            return GeneradorHTML.mensajeExito("Registro de Personal",
                    "Se registró a <b>" + per.getNombreCompleto() + "</b> con el rol <b>" + rol.getNombre() + "</b>.<br>"
                            + credenciales(u, per));
        });
    }

    // LISPER["*" | ci]
    public String listarPersonal(String filtro) {
        return enTransaccion(con -> {
            Tabla t = new PersonalDAO(con).listar(filtroListado(filtro));
            log(con, "PERSONAL", "SELECT");
            return GeneradorHTML.tabla("el Personal Administrativo (CU-01)", t);
        });
    }

    // MODPER[ci, campo, nuevo_valor]
    public String modificarPersonal(String[] p) {
        if (p.length < 3) {
            return formato("MODPER[\"ci\", \"campo\", \"nuevo_valor\"] (campos: nombre, apellidos, ci_expedicion, direccion, telefono, email, usuario)");
        }
        return enTransaccion(con -> {
            Personal per = buscarPersonal(con, p[0]);
            if (new PersonalDAO(con).esCapacitador(per.getIdPersonal())) {
                throw new ReglaNegocioException("El CI <b>" + p[0] + "</b> corresponde a un capacitador. Use <b>MODCAP</b>.");
            }
            String campo = actualizarDatoPersonal(con, per, p[1], p[2], PersonalDAO.CAMPOS);
            return GeneradorHTML.mensajeExito("Modificación de Personal",
                    "Se actualizó <b>" + campo + "</b> de <b>" + per.getNombreCompleto() + "</b> a <b>" + p[2] + "</b>.");
        });
    }

    // DELPER[ci]: baja lógica de PERSONAL y USUARIO
    public String eliminarPersonal(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("DELPER[\"ci\"]");
        }
        return enTransaccion(con -> {
            Personal per = buscarPersonal(con, p[0]);
            if (new PersonalDAO(con).esCapacitador(per.getIdPersonal())) {
                throw new ReglaNegocioException("El CI <b>" + p[0] + "</b> corresponde a un capacitador. Use <b>DELCAP</b>.");
            }
            darDeBaja(con, per);
            return GeneradorHTML.mensajeExito("Baja de Personal",
                    "<b>" + per.getNombreCompleto() + "</b> pasó a estado INACTIVO. "
                            + "El sistema ya no aceptará comandos desde su correo.");
        });
    }

    // RESPER[ci]: revierte DELPER; PERSONAL y USUARIO vuelven a ACTIVO con los roles que ya tenían
    public String restaurarPersonal(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("RESPER[\"ci\"]");
        }
        return enTransaccion(con -> {
            Personal per = buscarPersonal(con, p[0]);
            if (new PersonalDAO(con).esCapacitador(per.getIdPersonal())) {
                throw new ReglaNegocioException("El CI <b>" + p[0] + "</b> corresponde a un capacitador. Use <b>RESCAP</b>.");
            }
            return GeneradorHTML.mensajeExito("Restauración de Personal", reactivar(con, per));
        });
    }

    // ---------------------------------------------------------------------
    // Flujo B - Capacitadores (Coordinación Académica)
    // ---------------------------------------------------------------------

    // ADDCAP[ci, ci_expedicion, nombre, apellidos, direccion, telefono, email,
    // titulo_academico, grado_academico, experiencia_lab, cv_documento]
    public String registrarCapacitador(String[] p) {
        if (p.length < 9) {
            return formato("ADDCAP[\"ci\", \"ci_expedicion\", \"nombre\", \"apellidos\", \"direccion\", \"telefono\", \"email\", "
                    + "\"titulo_academico\", \"grado_academico\", \"experiencia_lab\", \"cv_documento\"]");
        }
        return enTransaccion(con -> {
            Capacitador cap = new Capacitador(p[0], p[1], p[2], p[3], nulo(p[4]), nulo(p[5]), p[7], p[8],
                    nulo(param(p, 9)), nulo(param(p, 10)));
            String email = p[6].toLowerCase();
            validarPersona(cap, email);
            if (Validador.vacio(cap.getTituloAcademico()) || Validador.vacio(cap.getGradoAcademico())) {
                throw new ReglaNegocioException("El título académico y el grado académico son obligatorios.");
            }
            Rol rol = resolverRol(con, "Capacitador");
            validarCiYEmailLibres(con, cap.getCi(), email);

            new PersonalDAO(con).insertar(cap);
            new CapacitadorDAO(con).insertar(cap);
            Usuario u = crearCuenta(con, cap, email, rol);
            log(con, "PERSONAL", "INSERT");
            log(con, "CAPACITOR", "INSERT");

            return GeneradorHTML.mensajeExito("Registro de Capacitador",
                    "Se registró al capacitador <b>" + cap.getNombreCompleto() + "</b> (" + cap.getTituloAcademico() + ").<br>"
                            + credenciales(u, cap));
        });
    }

    // LISCAP["*" | ci]
    public String listarCapacitadores(String filtro) {
        return enTransaccion(con -> {
            Tabla t = new CapacitadorDAO(con).listar(filtroListado(filtro));
            log(con, "CAPACITOR", "SELECT");
            return GeneradorHTML.tabla("los Capacitadores (CU-01)", t);
        });
    }

    // MODCAP[ci, campo, nuevo_valor]
    public String modificarCapacitador(String[] p) {
        if (p.length < 3) {
            return formato("MODCAP[\"ci\", \"campo\", \"nuevo_valor\"] (campos: nombre, apellidos, ci_expedicion, direccion, "
                    + "telefono, email, usuario, titulo_academico, grado_academico, experiencia_lab, cv_documento)");
        }
        return enTransaccion(con -> {
            Capacitador cap = buscarCapacitador(con, p[0]);
            String campo = columna(p[1]);
            if (CapacitadorDAO.CAMPOS.contains(campo)) {
                if (Validador.vacio(p[2]) && (campo.equals("titulo_academico") || campo.equals("grado_academico"))) {
                    throw new ReglaNegocioException("El campo <b>" + campo + "</b> no puede quedar vacío.");
                }
                new CapacitadorDAO(con).actualizarCampo(cap.getIdPersonal(), campo, nulo(p[2]));
                log(con, "CAPACITOR", "UPDATE");
            } else {
                campo = actualizarDatoPersonal(con, cap, p[1], p[2], PersonalDAO.CAMPOS);
            }
            return GeneradorHTML.mensajeExito("Modificación de Capacitador",
                    "Se actualizó <b>" + campo + "</b> de <b>" + cap.getNombreCompleto() + "</b> a <b>" + p[2] + "</b>.");
        });
    }

    // DELCAP[ci]: baja lógica, rechazada si tiene un acta ABIERTA
    public String eliminarCapacitador(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("DELCAP[\"ci\"]");
        }
        return enTransaccion(con -> {
            Capacitador cap = buscarCapacitador(con, p[0]);
            if (new CapacitadorDAO(con).tieneActaAbierta(cap.getIdPersonal())) {
                throw new ReglaNegocioException("No se puede dar de baja a <b>" + cap.getNombreCompleto()
                        + "</b>: tiene una asignación de módulo en curso con el acta ABIERTA.");
            }
            darDeBaja(con, cap);
            return GeneradorHTML.mensajeExito("Baja de Capacitador",
                    "<b>" + cap.getNombreCompleto() + "</b> pasó a estado INACTIVO.");
        });
    }

    // RESCAP[ci]: revierte DELCAP; el capacitador vuelve a estar disponible para nuevas asignaciones (ADDASIG)
    public String restaurarCapacitador(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("RESCAP[\"ci\"]");
        }
        return enTransaccion(con -> {
            Capacitador cap = buscarCapacitador(con, p[0]);
            return GeneradorHTML.mensajeExito("Restauración de Capacitador", reactivar(con, cap)
                    + "<br>Ya se le pueden asignar módulos con <b>ADDASIG</b>.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo C - Estudiantes (Encargado de Registro)
    // ---------------------------------------------------------------------

    // ADDEST[ci, ci_expedicion, nombre, paterno, materno, fecha_nacimiento,
    // direccion, telefono, email, nivel_escolaridad, ciudad, pais]
    public String registrarEstudiante(String[] p) {
        if (p.length < 11) {
            return formato("ADDEST[\"ci\", \"ci_expedicion\", \"nombre\", \"paterno\", \"materno\", \"fecha_nacimiento(YYYY-MM-DD)\", "
                    + "\"direccion\", \"telefono\", \"email\", \"nivel_escolaridad\", \"ciudad\", \"pais\"]");
        }
        return enTransaccion(con -> {
            Estudiante e = new Estudiante();
            e.setCi(p[0]);
            e.setCiExpedicion(nulo(p[1]));
            e.setNombre(p[2]);
            e.setPaterno(p[3]);
            e.setMaterno(nulo(p[4]));
            e.setFechaNacimiento(validarFechaNacimiento(p[5]));
            e.setDireccion(nulo(p[6]));
            e.setTelefono(nulo(p[7]));
            e.setEmail(nulo(p[8].toLowerCase()));
            e.setNivelEscolaridad(nulo(p[9]));
            e.setCiudad(nulo(p[10]));
            if (!Validador.vacio(param(p, 11))) {
                e.setPais(p[11]);
            }
            // codigo_estudiante es NOT NULL; el código definitivo se asigna en FORMINS (CU-03)
            e.setCodigoEstudiante("PRE-" + e.getCi());

            if (Validador.vacio(e.getCi()) || Validador.vacio(e.getNombre()) || Validador.vacio(e.getPaterno())) {
                throw new ReglaNegocioException("El CI, el nombre y el apellido paterno son obligatorios.");
            }
            if (e.getEmail() != null && !Validador.esEmail(e.getEmail())) {
                throw new ReglaNegocioException("El correo <b>" + e.getEmail() + "</b> no tiene un formato válido.");
            }
            EstudianteDAO dao = new EstudianteDAO(con);
            if (dao.buscarPorCi(e.getCi()) != null) {
                throw new ReglaNegocioException("Ya existe un estudiante con el CI <b>" + e.getCi() + "</b>.");
            }
            dao.insertar(e);
            log(con, "ESTUDIANTE", "INSERT");

            return GeneradorHTML.mensajeExito("Registro de Estudiante",
                    "Se creó el expediente de <b>" + e.getNombreCompleto() + "</b> (CI " + e.getCi() + ", "
                            + Validador.edad(e.getFechaNacimiento()) + " años).<br>"
                            + "Código provisional: <b>" + e.getCodigoEstudiante()
                            + "</b>. El código definitivo se asigna al formalizar la inscripción (FORMINS).");
        });
    }

    // LISEST["*" | ci | codigo_estudiante]
    public String listarEstudiantes(String filtro) {
        return enTransaccion(con -> {
            Tabla t = new EstudianteDAO(con).listar(filtroListado(filtro));
            log(con, "ESTUDIANTE", "SELECT");
            return GeneradorHTML.tabla("los Estudiantes (CU-01)", t);
        });
    }

    // MODEST[ci, campo, nuevo_valor]
    public String modificarEstudiante(String[] p) {
        if (p.length < 3) {
            return formato("MODEST[\"ci\", \"campo\", \"nuevo_valor\"] (campos: nombre, paterno, materno, ci_expedicion, "
                    + "fecha_nacimiento, direccion, telefono, email, nivel_escolaridad, ciudad, pais)");
        }
        return enTransaccion(con -> {
            EstudianteDAO dao = new EstudianteDAO(con);
            Estudiante e = dao.buscarPorCi(p[0]);
            if (e == null) {
                throw new ReglaNegocioException("No existe un estudiante con el CI <b>" + p[0] + "</b>.");
            }
            String campo = columna(p[1]);
            if (!EstudianteDAO.CAMPOS.contains(campo)) {
                throw new ReglaNegocioException("El campo <b>" + p[1] + "</b> no se puede modificar. Campos válidos: "
                        + String.join(", ", EstudianteDAO.CAMPOS) + ".");
            }
            dao.actualizarCampo(e.getIdEstudiante(), campo, valorEstudiante(campo, p[2]));
            log(con, "ESTUDIANTE", "UPDATE");
            return GeneradorHTML.mensajeExito("Modificación de Estudiante",
                    "Se actualizó <b>" + campo + "</b> de <b>" + e.getNombreCompleto() + "</b> a <b>" + p[2] + "</b>.");
        });
    }

    // DELEST[ci]: solo si no tiene inscripciones; con inscripciones la baja se gestiona en CU-03
    public String eliminarEstudiante(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("DELEST[\"ci\"]");
        }
        return enTransaccion(con -> {
            EstudianteDAO dao = new EstudianteDAO(con);
            Estudiante e = dao.buscarPorCi(p[0]);
            if (e == null) {
                throw new ReglaNegocioException("No existe un estudiante con el CI <b>" + p[0] + "</b>.");
            }
            if (dao.tieneInscripciones(e.getIdEstudiante())) {
                throw new ReglaNegocioException("<b>" + e.getNombreCompleto() + "</b> tiene inscripciones registradas. "
                        + "Su baja se gestiona con el estado de la inscripción (ESTINS, CU-03).");
            }
            dao.eliminar(e.getIdEstudiante());
            log(con, "ESTUDIANTE", "DELETE");
            return GeneradorHTML.mensajeExito("Eliminación de Estudiante",
                    "Se eliminó el expediente de <b>" + e.getNombreCompleto() + "</b> (CI " + e.getCi() + ").");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo D - Roles (Director)
    // ---------------------------------------------------------------------

    // ASIGROL[email, rol]
    public String asignarRol(String[] p) {
        if (p.length < 2) {
            return formato("ASIGROL[\"email\", \"rol\"]");
        }
        return enTransaccion(con -> {
            Usuario u = buscarUsuario(con, p[0]);
            if (!u.estaActivo()) {
                throw new ReglaNegocioException("La cuenta <b>" + u.getEmail() + "</b> está " + u.getEstado() + ".");
            }
            Rol rol = resolverRol(con, p[1]);
            if (Validador.normalizar(rol.getNombre()).equals(ROL_CAPACITADOR)) {
                throw new ReglaNegocioException("El rol Capacitador requiere el perfil académico. Use <b>ADDCAP</b>.");
            }
            RolDAO rolDAO = new RolDAO(con);
            if (rolDAO.tieneRol(u.getIdUsuario(), rol.getIdRol())) {
                throw new ReglaNegocioException("<b>" + u.getEmail() + "</b> ya tiene el rol <b>" + rol.getNombre() + "</b>.");
            }
            rolDAO.asignar(rol.getIdRol(), u.getIdUsuario());
            log(con, "ROL_USUARIO", "INSERT");
            return GeneradorHTML.mensajeExito("Asignación de Rol",
                    "Se asignó el rol <b>" + rol.getNombre() + "</b> a <b>" + u.getEmail() + "</b>.");
        });
    }

    // LISROL["*"] devuelve los roles con sus permisos; LISROL[email] los roles del usuario
    public String listarRoles(String filtro) {
        return enTransaccion(con -> {
            RolDAO rolDAO = new RolDAO(con);
            String f = filtroListado(filtro);
            String html;
            if (f.equals("*")) {
                html = GeneradorHTML.tabla("los Roles y sus Permisos (CU-01)", rolDAO.rolesConPermisos());
            } else {
                Usuario u = buscarUsuario(con, f);
                html = GeneradorHTML.tabla("los Roles de " + u.getEmail(), rolDAO.rolesDeUsuario(u.getIdUsuario()));
            }
            log(con, "ROL_USUARIO", "SELECT");
            return html;
        });
    }

    // QUIROL[email, rol]
    public String quitarRol(String[] p) {
        if (p.length < 2) {
            return formato("QUIROL[\"email\", \"rol\"]");
        }
        return enTransaccion(con -> {
            Usuario u = buscarUsuario(con, p[0]);
            Rol rol = resolverRol(con, p[1]);
            RolDAO rolDAO = new RolDAO(con);
            if (!rolDAO.tieneRol(u.getIdUsuario(), rol.getIdRol())) {
                throw new ReglaNegocioException("<b>" + u.getEmail() + "</b> no tiene el rol <b>" + rol.getNombre() + "</b>.");
            }
            if (rolDAO.contarRoles(u.getIdUsuario()) <= 1) {
                throw new ReglaNegocioException("<b>" + rol.getNombre() + "</b> es el único rol de <b>" + u.getEmail()
                        + "</b>. Para retirar a la persona use <b>DELPER</b>.");
            }
            if (u.getIdUsuario() == idRemitente && Validador.normalizar(rol.getNombre()).equals(ROL_DIRECTOR)) {
                throw new ReglaNegocioException("No puede quitarse a sí mismo el rol Director.");
            }
            rolDAO.quitar(rol.getIdRol(), u.getIdUsuario());
            log(con, "ROL_USUARIO", "DELETE");
            return GeneradorHTML.mensajeExito("Retiro de Rol",
                    "Se quitó el rol <b>" + rol.getNombre() + "</b> a <b>" + u.getEmail() + "</b>.");
        });
    }

    // ---------------------------------------------------------------------
    // Flujo E - Listado general
    // ---------------------------------------------------------------------

    // LISUSR["*"]
    public String listarUsuarios(String filtro) {
        return enTransaccion(con -> {
            Tabla t = new UsuarioDAO(con).listar(filtroListado(filtro));
            log(con, "USUARIO", "SELECT");
            return GeneradorHTML.tabla("las Cuentas del Sistema (CU-01)", t);
        });
    }

    // ---------------------------------------------------------------------
    // Flujo F - Claves de acceso
    // ---------------------------------------------------------------------

    // MODCLAVE[clave_actual, clave_nueva]: cada usuario cambia su propia clave (la cuenta es la del remitente)
    public String cambiarClave(String[] p) {
        if (p.length < 2) {
            return formato("MODCLAVE[\"clave_actual\", \"clave_nueva\"]");
        }
        return enTransaccion(con -> {
            UsuarioDAO usuarioDAO = new UsuarioDAO(con);
            Usuario u = usuarioDAO.buscarPorId(idRemitente);
            if (!Validador.encriptarMD5(p[0]).equals(u.getClave())) {
                throw new ReglaNegocioException("La clave actual no es correcta. Si la olvidó, pida que se la restablezcan "
                        + "con RESCLAVE (Director para el personal, Coordinación Académica para los capacitadores).");
            }
            if (p[1].length() < 6) {
                throw new ReglaNegocioException("La clave nueva debe tener al menos 6 caracteres.");
            }
            if (p[1].equals(p[0])) {
                throw new ReglaNegocioException("La clave nueva debe ser distinta de la actual.");
            }
            usuarioDAO.actualizarClave(u.getIdUsuario(), Validador.encriptarMD5(p[1]));
            log(con, "USUARIO", "UPDATE");
            return GeneradorHTML.mensajeExito("Cambio de Clave",
                    "Se cambió la clave de la cuenta <b>" + u.getUsuario() + "</b>.<br>"
                            + "Por seguridad, borre de su bandeja de enviados el correo con el comando MODCLAVE.");
        });
    }

    // RESCLAVE[ci]: la clave vuelve al CI y se envía a la persona. Puede hacerlo quien puede modificarla:
    // MODPER (Director) para el personal administrativo y MODCAP (Coordinación Académica) para los capacitadores
    public String restablecerClave(String[] p) {
        if (Validador.vacio(p[0])) {
            return formato("RESCLAVE[\"ci\"]");
        }
        return enTransaccion(con -> {
            Personal per = buscarPersonal(con, p[0]);
            UsuarioDAO usuarioDAO = new UsuarioDAO(con);
            String permiso = new PersonalDAO(con).esCapacitador(per.getIdPersonal()) ? "MODCAP" : "MODPER";
            if (!usuarioDAO.tienePermiso(idRemitente, permiso)) {
                throw new ReglaNegocioException("La clave de <b>" + per.getNombreCompleto() + "</b> solo la puede restablecer "
                        + "quien tiene el permiso <b>" + permiso + "</b>.");
            }
            Usuario u = cuentaDe(usuarioDAO, per);
            if (!u.estaActivo()) {
                throw new ReglaNegocioException("La cuenta de <b>" + per.getNombreCompleto() + "</b> está " + u.getEstado()
                        + ". Reactívela antes de restablecer la clave.");
            }
            usuarioDAO.actualizarClave(u.getIdUsuario(), Validador.encriptarMD5(per.getCi()));
            log(con, "USUARIO", "UPDATE");

            notificar(u.getEmail(), "Su clave del Sistema ICAP fue restablecida", GeneradorHTML.mensajeExito("Clave restablecida",
                    "Estimado(a) <b>" + per.getNombreCompleto() + "</b>: su clave fue restablecida.<br>"
                            + "Usuario: <b>" + u.getUsuario() + "</b> | Clave: <b>" + per.getCi() + "</b> (número de CI)<br>"
                            + "Cámbiela enviando el asunto <b>MODCLAVE[\"" + per.getCi() + "\", \"clave_nueva\"]</b>."));
            return GeneradorHTML.mensajeExito("Restablecimiento de Clave",
                    "La clave de <b>" + per.getNombreCompleto() + "</b> volvió a ser su número de CI y se le envió a <b>"
                            + u.getEmail() + "</b>.");
        });
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    // "Dirección" -> "direccion", "correo" -> "email", "titulo" -> "titulo_academico"
    private static String columna(String campo) {
        return columna(campo, ALIAS_CAMPOS);
    }

    private void validarPersona(Personal per, String email) {
        if (Validador.vacio(per.getCi()) || Validador.vacio(per.getNombre()) || Validador.vacio(per.getApellidos())) {
            throw new ReglaNegocioException("El CI, el nombre y los apellidos son obligatorios.");
        }
        if (!Validador.esEmail(email)) {
            throw new ReglaNegocioException("El correo <b>" + email + "</b> no tiene un formato válido. "
                    + "Es la dirección desde la que la persona enviará sus comandos.");
        }
    }

    private void validarCiYEmailLibres(Connection con, String ci, String email) throws SQLException {
        if (new PersonalDAO(con).buscarPorCi(ci) != null) {
            throw new ReglaNegocioException("El CI <b>" + ci + "</b> ya está registrado en el personal.");
        }
        if (new UsuarioDAO(con).buscarPorEmail(email) != null) {
            throw new ReglaNegocioException("El correo <b>" + email + "</b> ya está en uso por otra cuenta.");
        }
    }

    // Crea el USUARIO (usuario derivado del email, clave inicial = CI en MD5), le asigna el rol
    // y le envía sus credenciales al correo registrado
    private Usuario crearCuenta(Connection con, Personal per, String email, Rol rol) throws SQLException {
        UsuarioDAO usuarioDAO = new UsuarioDAO(con);
        Usuario u = new Usuario(usuarioDAO.generarNombreUsuario(email), Validador.encriptarMD5(per.getCi()), email,
                per.getIdPersonal());
        usuarioDAO.insertar(u);
        new RolDAO(con).asignar(rol.getIdRol(), u.getIdUsuario());
        log(con, "USUARIO", "INSERT");
        log(con, "ROL_USUARIO", "INSERT");

        // La clave se guarda en MD5 y no se puede recuperar: este es el único momento en que se conoce en claro
        notificar(email, "Su cuenta en el Sistema ICAP", GeneradorHTML.mensajeExito("Cuenta creada en el Sistema ICAP",
                "Estimado(a) <b>" + per.getNombreCompleto() + "</b>: se creó su cuenta con el rol <b>" + rol.getNombre()
                        + "</b>.<br>" + credenciales(u, per) + "<br>"
                        + "Envíe sus comandos desde este correo a <b>grupo15sc@tecnoweb.org.bo</b>, escribiéndolos en el asunto. "
                        + "Para ver la sintaxis de los comandos envíe el asunto <b>help!me</b>."));
        return u;
    }

    private static String credenciales(Usuario u, Personal per) {
        return "Usuario: <b>" + u.getUsuario() + "</b> | Clave inicial: <b>" + per.getCi() + "</b> (número de CI)"
                + " | Correo de comandos: <b>" + u.getEmail() + "</b>";
    }

    private Rol resolverRol(Connection con, String nombre) throws SQLException {
        String buscado = Validador.normalizar(nombre);
        buscado = ALIAS_ROLES.getOrDefault(buscado, buscado);
        StringBuilder disponibles = new StringBuilder();
        for (Rol r : new RolDAO(con).listarRoles()) {
            if (Validador.normalizar(r.getNombre()).equals(buscado)) {
                return r;
            }
            disponibles.append(disponibles.length() > 0 ? ", " : "").append(r.getNombre());
        }
        throw new ReglaNegocioException("El rol <b>" + nombre + "</b> no existe. Roles disponibles: " + disponibles + ".");
    }

    private Personal buscarPersonal(Connection con, String ci) throws SQLException {
        Personal per = new PersonalDAO(con).buscarPorCi(ci);
        if (per == null) {
            throw new ReglaNegocioException("No existe personal registrado con el CI <b>" + ci + "</b>.");
        }
        return per;
    }

    private Capacitador buscarCapacitador(Connection con, String ci) throws SQLException {
        Capacitador cap = new CapacitadorDAO(con).buscarPorCi(ci);
        if (cap == null) {
            throw new ReglaNegocioException("No existe un capacitador registrado con el CI <b>" + ci + "</b>.");
        }
        return cap;
    }

    private Usuario buscarUsuario(Connection con, String email) throws SQLException {
        Usuario u = new UsuarioDAO(con).buscarPorEmail(email);
        if (u == null) {
            throw new ReglaNegocioException("No existe una cuenta con el correo <b>" + email + "</b>.");
        }
        return u;
    }

    private Usuario cuentaDe(UsuarioDAO usuarioDAO, Personal per) throws SQLException {
        Usuario u = usuarioDAO.buscarPorIdPersonal(per.getIdPersonal());
        if (u == null) {
            throw new ReglaNegocioException("<b>" + per.getNombreCompleto() + "</b> no tiene cuenta de usuario.");
        }
        return u;
    }

    // Modifica un dato de PERSONAL o el email / usuario de su USUARIO. Devuelve la columna modificada
    private String actualizarDatoPersonal(Connection con, Personal per, String campoBruto, String valor,
            Set<String> camposPermitidos) throws SQLException {
        String campo = columna(campoBruto);
        if (campo.equals("clave")) {
            throw new ReglaNegocioException("La clave no se modifica con este comando: cada persona cambia la suya con "
                    + "<b>MODCLAVE[\"clave_actual\", \"clave_nueva\"]</b>. Si la olvidó, restablézcala con <b>RESCLAVE[\""
                    + per.getCi() + "\"]</b>.");
        }
        if (campo.equals("email")) {
            String email = valor.toLowerCase();
            if (!Validador.esEmail(email)) {
                throw new ReglaNegocioException("El correo <b>" + email + "</b> no tiene un formato válido.");
            }
            UsuarioDAO usuarioDAO = new UsuarioDAO(con);
            Usuario existente = usuarioDAO.buscarPorEmail(email);
            Usuario propio = cuentaDe(usuarioDAO, per);
            if (existente != null && existente.getIdUsuario() != propio.getIdUsuario()) {
                throw new ReglaNegocioException("El correo <b>" + email + "</b> ya está en uso por otra cuenta.");
            }
            usuarioDAO.actualizarEmail(propio.getIdUsuario(), email);
            log(con, "USUARIO", "UPDATE");
            return campo;
        }
        if (campo.equals("usuario")) {
            String usuario = valor.trim().toLowerCase();
            if (!usuario.matches("[a-z0-9._-]{3,50}")) {
                throw new ReglaNegocioException("El usuario <b>" + valor + "</b> no es válido: use de 3 a 50 letras, "
                        + "números, punto, guion o guion bajo, sin espacios ni tildes.");
            }
            UsuarioDAO usuarioDAO = new UsuarioDAO(con);
            Usuario existente = usuarioDAO.buscarPorUsuario(usuario);
            Usuario propio = cuentaDe(usuarioDAO, per);
            if (existente != null && existente.getIdUsuario() != propio.getIdUsuario()) {
                throw new ReglaNegocioException("El usuario <b>" + usuario + "</b> ya está en uso por otra cuenta.");
            }
            usuarioDAO.actualizarUsuario(propio.getIdUsuario(), usuario);
            log(con, "USUARIO", "UPDATE");
            return campo;
        }
        if (!camposPermitidos.contains(campo)) {
            throw new ReglaNegocioException("El campo <b>" + campoBruto + "</b> no se puede modificar.");
        }
        if (Validador.vacio(valor) && (campo.equals("nombre") || campo.equals("apellidos"))) {
            throw new ReglaNegocioException("El campo <b>" + campo + "</b> no puede quedar vacío.");
        }
        new PersonalDAO(con).actualizarCampo(per.getIdPersonal(), campo, nulo(valor));
        log(con, "PERSONAL", "UPDATE");
        return campo;
    }

    // Baja lógica: PERSONAL y USUARIO pasan a INACTIVO (se conservan por LOG, ENMIENDA_NOTA e HISTORIAL_ESTADO)
    private void darDeBaja(Connection con, Personal per) throws SQLException {
        if ("INACTIVO".equals(per.getEstado())) {
            throw new ReglaNegocioException("<b>" + per.getNombreCompleto() + "</b> ya está INACTIVO.");
        }
        UsuarioDAO usuarioDAO = new UsuarioDAO(con);
        Usuario u = usuarioDAO.buscarPorIdPersonal(per.getIdPersonal());
        if (u != null && u.getIdUsuario() == idRemitente) {
            throw new ReglaNegocioException("No puede darse de baja a sí mismo.");
        }
        new PersonalDAO(con).cambiarEstado(per.getIdPersonal(), "INACTIVO");
        log(con, "PERSONAL", "UPDATE");
        if (u != null) {
            usuarioDAO.cambiarEstado(u.getIdUsuario(), "INACTIVO");
            log(con, "USUARIO", "UPDATE");
        }
    }

    // Revierte la baja lógica: PERSONAL y USUARIO vuelven a ACTIVO con los roles que ya tenían.
    // Devuelve el mensaje para la respuesta
    private String reactivar(Connection con, Personal per) throws SQLException {
        if (!"INACTIVO".equals(per.getEstado())) {
            throw new ReglaNegocioException("<b>" + per.getNombreCompleto() + "</b> ya está ACTIVO.");
        }
        new PersonalDAO(con).cambiarEstado(per.getIdPersonal(), "ACTIVO");
        log(con, "PERSONAL", "UPDATE");

        UsuarioDAO usuarioDAO = new UsuarioDAO(con);
        Usuario u = usuarioDAO.buscarPorIdPersonal(per.getIdPersonal());
        if (u == null) {
            return "<b>" + per.getNombreCompleto() + "</b> pasó a estado ACTIVO.";
        }
        usuarioDAO.cambiarEstado(u.getIdUsuario(), "ACTIVO");
        log(con, "USUARIO", "UPDATE");
        return "<b>" + per.getNombreCompleto() + "</b> pasó a estado ACTIVO. Vuelve a enviar comandos desde <b>"
                + u.getEmail() + "</b> con los roles que tenía.";
    }
}
