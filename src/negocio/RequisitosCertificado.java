package src.negocio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import src.modelo.Arancel;
import src.modelo.AsignacionModulo;
import src.modelo.Certificado;
import src.modelo.Estudiante;
import src.modelo.Inscripcion;
import src.modelo.Modulo;
import src.modelo.ModuloCursado;
import src.modelo.OfertaAcademica;
import src.modelo.Pago;
import src.modelo.Programa;
import src.modelo.VersionPrograma;
import src.persistencia.ArancelDAO;
import src.persistencia.AsignacionDAO;
import src.persistencia.CertificadoDAO;
import src.persistencia.DocumentoDAO;
import src.persistencia.InscripcionModuloDAO;
import src.persistencia.OfertaDAO;
import src.persistencia.PagoDAO;
import src.persistencia.ProgramaDAO;
import src.persistencia.VersionDAO;
import src.utilidades.Tabla;

// CU-06: requisitos de cada tipo de documento. Los usan SOLCERT (informa al estudiante), GENCERT (vuelve a
// validarlos antes de emitir) y LISELEG (triple condición académica, financiera y documental del título)
final class RequisitosCertificado {

    static final String ACADEMICA = "Académica";
    static final String FINANCIERA = "Financiera";
    static final String DOCUMENTAL = "Documental";

    // Documento final que otorga cada nivel de programa
    private static final Map<String, String> FINAL_POR_NIVEL = Map.of(
            "TECNICO_MEDIO", "TITULO_TECNICO_MEDIO",
            "TECNICO_AUXILIAR", "TITULO_TECNICO_AUXILIAR",
            "CERTIFICACION_COMPETENCIA_LABORAL", "CERTIFICADO_COMPETENCIA_LABORAL",
            "CURSO_CORTO", "CERTIFICADO_PARTICIPACION");

    static final class Requisito {
        final String condicion;
        final String descripcion;
        final boolean cumple;
        final String detalle;

        Requisito(String condicion, String descripcion, boolean cumple, String detalle) {
            this.condicion = condicion;
            this.descripcion = descripcion;
            this.cumple = cumple;
            this.detalle = detalle;
        }
    }

    // Lo que se necesita saber del estudiante en la inscripción del documento
    static final class Expediente {
        Estudiante estudiante;
        Inscripcion inscripcion;
        OfertaAcademica oferta;
        VersionPrograma version;
        Programa programa;
        List<ModuloCursado> malla;
        // Solo en la constancia de programa analítico
        Modulo modulo;
        AsignacionModulo asignacion;

        List<ModuloCursado> aprobados() {
            List<ModuloCursado> lista = new ArrayList<>();
            for (ModuloCursado m : malla) {
                if (m.estaAprobado()) {
                    lista.add(m);
                }
            }
            return lista;
        }

        boolean culminoLaMalla() {
            return !malla.isEmpty() && aprobados().size() == malla.size();
        }

        int horasAprobadas() {
            int total = 0;
            for (ModuloCursado m : aprobados()) {
                total += m.getCargaHoraria();
            }
            return total;
        }

        // Promedio simple de las notas finales de los módulos aprobados; null si no aprobó ninguno
        BigDecimal promedio() {
            List<ModuloCursado> a = aprobados();
            if (a.isEmpty()) {
                return null;
            }
            BigDecimal suma = BigDecimal.ZERO;
            for (ModuloCursado m : a) {
                suma = suma.add(m.getNotaFinal());
            }
            return suma.divide(new BigDecimal(a.size()), 2, RoundingMode.HALF_UP);
        }

        String tipoFinal() {
            return FINAL_POR_NIVEL.get(programa.getNivelTitulacion());
        }
    }

    private final Connection con;

    RequisitosCertificado(Connection con) {
        this.con = con;
    }

    Expediente expediente(Estudiante e, Inscripcion i, Modulo modulo) throws SQLException {
        Expediente x = new Expediente();
        x.estudiante = e;
        x.inscripcion = i;
        x.oferta = new OfertaDAO(con).buscarPorId(i.getIdOferta());
        x.version = new VersionDAO(con).buscarPorId(x.oferta.getIdVersion());
        x.programa = new ProgramaDAO(con).buscarPorId(x.version.getIdPrograma());
        x.malla = new InscripcionModuloDAO(con).malla(i.getIdInscripcion());
        if (modulo != null) {
            x.modulo = modulo;
            x.asignacion = new AsignacionDAO(con).buscar(x.oferta.getIdOferta(), modulo.getIdModulo());
        }
        return x;
    }

    static String tipoFinal(String nivelTitulacion) {
        return FINAL_POR_NIVEL.get(nivelTitulacion);
    }

    // Arancel que respalda el documento
    static String concepto(String tipo) {
        return Certificado.esFinal(tipo) ? "TITULO" : "CERTIFICADO";
    }

    // ---------------------------------------------------------------------
    // Requisitos por tipo de documento
    // ---------------------------------------------------------------------

    List<Requisito> evaluar(Expediente x, String tipo) throws SQLException {
        if (Certificado.esFinal(tipo)) {
            return tripleCondicion(x, tipo);
        }
        List<Requisito> r = new ArrayList<>();
        String estado = x.inscripcion.getEstado();
        switch (tipo) {
            case "CERTIFICADO_ESTUDIANTE":
            case "CONSTANCIA_HORARIOS":
            case "CONSTANCIA_ASISTENCIA":
                r.add(new Requisito(null, "Inscripción vigente en estado INSCRITO", "INSCRITO".equals(estado),
                        "la inscripción está " + estado));
                break;
            case "CONSTANCIA_ADMISION":
                r.add(new Requisito(null, "Inscripción formalizada", !"PREINSCRITO".equals(estado),
                        "PREINSCRITO".equals(estado) ? "todavía está PREINSCRITO" : "formalizada (" + estado + ")"));
                break;
            case "CERTIFICADO_NOTAS":
                List<String> conNota = new ArrayList<>();
                for (ModuloCursado m : x.malla) {
                    if (m.tieneNotaOficial()) {
                        conNota.add(m.getCodigo());
                    }
                }
                r.add(new Requisito(null, "Al menos un módulo con nota final registrada en acta cerrada", !conNota.isEmpty(),
                        conNota.isEmpty() ? "ningún módulo tiene todavía su nota en un acta cerrada"
                                : conNota.size() + " módulo(s): " + String.join(", ", conNota)));
                break;
            case "CONSTANCIA_PROGRAMA_ANALITICO":
                String archivo = x.asignacion == null ? null : x.asignacion.getProgramaAnalitico();
                r.add(new Requisito(null, "Programa analítico registrado en la asignación de " + x.modulo.getCodigo(),
                        archivo != null && !archivo.isBlank(),
                        archivo != null && !archivo.isBlank() ? archivo : "el módulo no tiene programa analítico registrado en la oferta"));
                break;
            default:
                // CERTIFICADO_CONCLUSION y CERTIFICADO_VENCIMIENTO
                r.add(modulosAprobados(x));
                break;
        }
        r.add(pago(x, tipo));
        return r;
    }

    // Triple condición del título (Flujo B): académica, financiera y documental
    List<Requisito> tripleCondicion(Expediente x, String tipo) throws SQLException {
        List<Requisito> r = new ArrayList<>();
        r.add(modulosAprobados(x));
        int horas = x.horasAprobadas();
        int total = x.version.getTotalHoras();
        r.add(new Requisito(ACADEMICA, "Carga horaria del programa completa", horas >= total, horas + " de " + total + " horas"));

        BigDecimal saldo = saldoMensualidades(x);
        r.add(new Requisito(FINANCIERA, "Todas las mensualidades conciliadas", saldo.signum() <= 0,
                saldo.signum() <= 0 ? "al día" : "saldo pendiente de Bs " + saldo));
        r.add(pago(x, tipo));

        DocumentoDAO docs = new DocumentoDAO(con);
        int id = x.estudiante.getIdEstudiante();
        boolean carnet = docs.tiene(id, "CARNET_IDENTIDAD");
        r.add(new Requisito(DOCUMENTAL, "Carnet de identidad en el expediente", carnet, carnet ? "registrado" : "no está registrado"));
        if ("TITULO_TECNICO_MEDIO".equals(tipo)) {
            boolean bachiller = docs.tiene(id, "TITULO_BACHILLER");
            r.add(new Requisito(DOCUMENTAL, "Título de bachiller en el expediente", bachiller, bachiller ? "registrado" : "no está registrado"));
            // El de Técnico Auxiliar puede estar en el expediente o haberlo emitido el propio ICAP
            boolean enExpediente = docs.tiene(id, "TITULO_TECNICO_AUXILIAR");
            boolean emitido = new CertificadoDAO(con).tieneVigente(id, "TITULO_TECNICO_AUXILIAR");
            r.add(new Requisito(DOCUMENTAL, "Título de Técnico Auxiliar", enExpediente || emitido,
                    enExpediente ? "registrado en el expediente" : emitido ? "emitido por el ICAP" : "no está registrado"));
        }
        return r;
    }

    private Requisito modulosAprobados(Expediente x) {
        List<String> pendientes = new ArrayList<>();
        for (ModuloCursado m : x.malla) {
            if (!m.estaAprobado()) {
                pendientes.add(m.getCodigo() + " (" + situacion(m) + ")");
            }
        }
        int aprobados = x.malla.size() - pendientes.size();
        return new Requisito(ACADEMICA, "Todos los módulos de la malla aprobados con nota mayor o igual a 51 en acta cerrada",
                !x.malla.isEmpty() && pendientes.isEmpty(),
                aprobados + " de " + x.malla.size() + (pendientes.isEmpty() ? "" : "; pendientes: " + String.join(", ", pendientes)));
    }

    private static String situacion(ModuloCursado m) {
        if (!m.fueCursado()) {
            return "sin cursar";
        }
        if ("APROBADO".equals(m.getEstado()) && !m.isActaCerrada()) {
            return "APROBADO con el acta abierta";
        }
        return m.getEstado();
    }

    // Pago conciliado del arancel del documento que todavía no respalda otro documento vigente
    private Requisito pago(Expediente x, String tipo) throws SQLException {
        String concepto = concepto(tipo);
        String condicion = Certificado.esFinal(tipo) ? FINANCIERA : null;
        String descripcion = "Arancel de " + (Certificado.esFinal(tipo) ? "título" : "certificado") + " pagado y conciliado";
        int idInscripcion = x.inscripcion.getIdInscripcion();
        PagoDAO dao = new PagoDAO(con);
        Pago p = dao.documentoDisponible(idInscripcion, concepto, tipo);
        if (p != null) {
            return new Requisito(condicion, descripcion, true, "pago " + p.getNumeroTransaccion() + " conciliado");
        }
        if (Certificado.esFinal(tipo)) {
            String emitido = new CertificadoDAO(con).vigenteEnPrograma(x.estudiante.getIdEstudiante(), x.programa.getIdPrograma(), tipo);
            if (emitido != null) {
                return new Requisito(condicion, descripcion, true, "aplicado al documento ya emitido " + emitido);
            }
        }
        String sinConciliar = dao.pagoSinConciliar(idInscripcion, concepto);
        if (sinConciliar != null) {
            return new Requisito(condicion, descripcion, false, "el pago " + sinConciliar + " todavía no está conciliado");
        }
        Arancel a = new ArancelDAO(con).buscarVigente(concepto, x.oferta.getGestion());
        return new Requisito(condicion, descripcion, false, a == null
                ? "no registra el pago y todavía no hay un arancel de " + concepto + " vigente para la gestión " + x.oferta.getGestion()
                : "no registra el pago del arancel de " + concepto + " (Bs " + a.getMonto() + ", gestión " + a.getGestion() + ")");
    }

    // Saldo de mensualidades de la inscripción según el estado de cuenta del CU-04 (positivo = debe)
    private BigDecimal saldoMensualidades(Expediente x) throws SQLException {
        Tabla cuenta = new PagoDAO(con).estadoDeCuenta(x.estudiante.getIdEstudiante());
        List<String> c = cuenta.getCabeceras();
        for (String[] fila : cuenta.getFilas()) {
            if (x.oferta.getCodigoPrograma().equals(fila[c.indexOf("programa")]) && x.oferta.getGestion().equals(fila[c.indexOf("gestion")])
                    && x.oferta.getGrupo().equals(fila[c.indexOf("grupo")]) && "MENSUALIDADES".equals(fila[c.indexOf("concepto")])) {
                return new BigDecimal(fila[c.indexOf("saldo")]);
            }
        }
        return BigDecimal.ZERO;
    }

    static boolean cumpleTodos(List<Requisito> requisitos) {
        for (Requisito r : requisitos) {
            if (!r.cumple) {
                return false;
            }
        }
        return true;
    }

    // "Descripción: detalle" de los que faltan
    static List<String> faltantes(List<Requisito> requisitos) {
        List<String> lista = new ArrayList<>();
        for (Requisito r : requisitos) {
            if (!r.cumple) {
                lista.add((r.condicion != null ? r.condicion + " - " : "") + r.descripcion + ": " + r.detalle);
            }
        }
        return lista;
    }
}
