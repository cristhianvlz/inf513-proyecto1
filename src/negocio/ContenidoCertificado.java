package src.negocio;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import src.comunicacion.ClienteSMTP;
import src.modelo.Certificado;
import src.modelo.Estudiante;
import src.modelo.Horario;
import src.modelo.ModuloCursado;
import src.modelo.Personal;
import src.persistencia.DocumentoDAO;
import src.persistencia.HorarioDAO;
import src.persistencia.PersonalDAO;
import src.utilidades.PlantillaCertificado;
import src.utilidades.PlantillaCertificado.Bloque;
import src.utilidades.PlantillaCertificado.Datos;
import src.utilidades.PlantillaCertificado.Firma;

// CU-06: contenido de cada título, certificado o constancia sobre la plantilla institucional (diseño del título oficial)
final class ContenidoCertificado {

    static final Map<String, String> NOMBRES = Map.ofEntries(
            Map.entry("TITULO_TECNICO_MEDIO", "Título de Técnico Medio"),
            Map.entry("TITULO_TECNICO_AUXILIAR", "Título de Técnico Auxiliar"),
            Map.entry("CERTIFICADO_COMPETENCIA_LABORAL", "Certificado de Competencia Laboral"),
            Map.entry("CERTIFICADO_PARTICIPACION", "Certificado de Participación"),
            Map.entry("CERTIFICADO_NOTAS", "Certificado de Notas"),
            Map.entry("CERTIFICADO_ESTUDIANTE", "Certificado de Estudiante"),
            Map.entry("CERTIFICADO_CONCLUSION", "Certificado de Conclusión"),
            Map.entry("CERTIFICADO_VENCIMIENTO", "Certificado de Vencimiento"),
            Map.entry("CONSTANCIA_HORARIOS", "Constancia de Horarios"),
            Map.entry("CONSTANCIA_ASISTENCIA", "Constancia de Asistencia"),
            Map.entry("CONSTANCIA_ADMISION", "Constancia de Admisión"),
            Map.entry("CONSTANCIA_PROGRAMA_ANALITICO", "Constancia del Contenido del Programa Analítico"));

    // Lo que va en letra grande en el título: "otorga el presente Título de: Técnico Auxiliar"
    private static final Map<String, String> GRADOS = Map.of(
            "TITULO_TECNICO_MEDIO", "Técnico Medio",
            "TITULO_TECNICO_AUXILIAR", "Técnico Auxiliar",
            "CERTIFICADO_COMPETENCIA_LABORAL", "Competencia Laboral",
            "CERTIFICADO_PARTICIPACION", "Participación");

    private static final Map<String, String> NIVELES = Map.of(
            "TECNICO_MEDIO", "Técnico Medio",
            "TECNICO_AUXILIAR", "Técnico Auxiliar",
            "CERTIFICACION_COMPETENCIA_LABORAL", "Certificación de Competencia Laboral",
            "CURSO_CORTO", "Curso Corto");

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String HABILITACION = "Por tanto queda habilitado (a) para el ejercicio de la profesión en todo el territorio del "
            + "Estado Plurinacional de Bolivia, con todos los derechos y obligaciones que la ley señala.";
    private static final String CIERRE = "Se extiende la presente a solicitud de la parte interesada, para los fines que viere conveniente.";

    private final Connection con;

    ContenidoCertificado(Connection con) {
        this.con = con;
    }

    static String nombre(String tipo) {
        return NOMBRES.getOrDefault(tipo, tipo);
    }

    // "el Certificado de Notas" / "la Constancia de Horarios"
    static String el(String tipo) {
        return (tipo.startsWith("CONSTANCIA") ? "la " : "el ") + nombre(tipo);
    }

    // "del Certificado de Notas" / "de la Constancia de Horarios"
    static String del(String tipo) {
        return (tipo.startsWith("CONSTANCIA") ? "de la " : "del ") + nombre(tipo);
    }

    static String nivel(String nivelTitulacion) {
        return NIVELES.getOrDefault(nivelTitulacion, nivelTitulacion);
    }

    // Texto del código QR: abre un correo al sistema con el asunto VERCERT listo para enviar
    static String contenidoQR(String hash) {
        return "mailto:" + ClienteSMTP.CORREO + "?subject=VERCERT%5B%22" + hash + "%22%5D";
    }

    static String instruccionVerificacion(String codigo) {
        return "Verifique su autenticidad escaneando el código QR o enviando un correo a " + ClienteSMTP.CORREO
                + " con el asunto VERCERT[\"" + codigo + "\"].";
    }

    // director: quien emite; coordinador: quien firma con él los títulos (null si no hay nadie con ese rol)
    byte[] pdf(RequisitosCertificado.Expediente x, Certificado c, String director, String coordinador) throws SQLException {
        String tipo = c.getTipoCertificado();
        Estudiante e = x.estudiante;
        Datos d = new Datos();
        d.codigo = c.getCodigoAlfanumerico();
        d.contenidoQR = contenidoQR(c.getCodigoQrHash());
        d.instruccion = instruccionVerificacion(c.getCodigoAlfanumerico());
        d.fecha = c.getFechaEmision();
        d.titular = e.getNombreCompleto();
        d.ci = e.getCi() + (e.getCiExpedicion() != null ? " " + e.getCiExpedicion() : "");
        try {
            if (Certificado.esFinal(tipo)) {
                documentoFinal(d, x, c);
                d.firmas = new Firma[] { new Firma(coordinador, "Coordinador(a) General"), new Firma(director, "Director") };
                return PlantillaCertificado.titulo(d);
            }
            d.titulo = nombre(tipo);
            d.subtitulo = "CONSTANCIA_PROGRAMA_ANALITICO".equals(tipo) ? x.modulo.getCodigo() + " - " + x.modulo.getNombre()
                    : x.programa.getNombre();
            d.introduccion = "El Director del Instituto de Capacitación Popular (ICAP) de la Universidad Autónoma Gabriel René Moreno, "
                    + "a solicitud de la parte interesada, CERTIFICA que:";
            d.registro = e.getCodigoEstudiante();
            certificacion(d, x, tipo);
            d.cuerpo.add(Bloque.parrafo(CIERRE));
            d.firmas = new Firma[] { new Firma(director, "Director") };
            return PlantillaCertificado.certificacion(d);
        } catch (IOException ex) {
            throw new ReglaNegocioException("No se pudo armar el PDF del documento: " + ex.getMessage());
        }
    }

    // Títulos y certificados finales: datos del titular como en el título oficial
    private void documentoFinal(Datos d, RequisitosCertificado.Expediente x, Certificado c) throws SQLException {
        String tipo = c.getTipoCertificado();
        Estudiante e = x.estudiante;
        d.numeroSerie = c.getNumeroTitulo();
        d.presente = tipo.startsWith("TITULO_") ? "Título" : "Certificado";
        d.grado = GRADOS.get(tipo);
        d.mencion = x.programa.getNombre();
        d.nacimiento = e.getFechaNacimiento();
        d.lugarNacimiento = (e.getCiudad() != null && !e.getCiudad().isBlank() ? e.getCiudad().trim() + " – " : "") + e.getPais();
        d.rutaFoto = new DocumentoDAO(con).ultimaRuta(e.getIdEstudiante(), "FOTOGRAFIA");
        String horas = x.horasAprobadas() + " horas académicas";
        switch (tipo) {
            case "TITULO_TECNICO_MEDIO":
                // Consigna la calificación; el de Técnico Auxiliar no
                d.nota = x.promedio().toPlainString();
                d.habilitacion = HABILITACION;
                break;
            case "TITULO_TECNICO_AUXILIAR":
                d.habilitacion = HABILITACION;
                break;
            case "CERTIFICADO_COMPETENCIA_LABORAL":
                d.habilitacion = "Por tanto queda acreditado (a) en la competencia laboral señalada, habiendo aprobado la totalidad de los "
                        + "módulos del programa con una carga horaria de " + horas + ".";
                break;
            default:
                d.habilitacion = "Por haber participado y aprobado el curso, con una carga horaria de " + horas + ".";
                break;
        }
    }

    private void certificacion(Datos d, RequisitosCertificado.Expediente x, String tipo) throws SQLException {
        String programa = "programa " + x.programa.getNombre() + " (" + nivel(x.programa.getNivelTitulacion()) + "), versión "
                + x.version.getNumeroVersion();
        String oferta = "la oferta de la gestión " + x.oferta.getGestion() + ", grupo " + x.oferta.getGrupo() + ", modalidad "
                + x.oferta.getModalidad().toLowerCase();
        switch (tipo) {
            case "CERTIFICADO_ESTUDIANTE": {
                List<String> enCurso = new ArrayList<>();
                for (ModuloCursado m : x.malla) {
                    if ("EN_CURSO".equals(m.getEstado())) {
                        enCurso.add(m.getCodigo() + " " + m.getNombre());
                    }
                }
                d.cuerpo.add(Bloque.parrafo("es estudiante regular del " + programa + ", en " + oferta + ", que se desarrolla en "
                        + x.oferta.getLugar() + " del " + x.oferta.getFechaInicio().format(FECHA) + " al " + x.oferta.getFechaFin().format(FECHA)
                        + ", con su inscripción vigente a la fecha." + (enCurso.isEmpty() ? "" : " Actualmente cursa: "
                                + String.join("; ", enCurso) + ".")));
                break;
            }
            case "CONSTANCIA_HORARIOS": {
                List<String[]> filas = new ArrayList<>();
                HorarioDAO dao = new HorarioDAO(con);
                for (ModuloCursado m : x.malla) {
                    if ("EN_CURSO".equals(m.getEstado()) && m.getIdAsignacion() > 0) {
                        for (Horario h : dao.listarPorAsignacion(m.getIdAsignacion())) {
                            filas.add(new String[] { m.getCodigo() + " " + m.getNombre(), h.getDiaSemana(),
                                    h.getHoraInicio() + " a " + h.getHoraFin(), h.getAula() });
                        }
                    }
                }
                d.cuerpo.add(Bloque.parrafo("es estudiante regular del " + programa + ", en " + oferta + ", y "
                        + (filas.isEmpty() ? "a la fecha no tiene módulos en curso con horario asignado." : "tiene el siguiente horario de clases:")));
                if (!filas.isEmpty()) {
                    d.cuerpo.add(Bloque.tabla(new String[] { "Módulo", "Día", "Horario", "Aula" }, new double[] { 5, 2, 2, 2 }, filas));
                }
                break;
            }
            case "CONSTANCIA_ASISTENCIA": {
                List<String[]> filas = new ArrayList<>();
                for (ModuloCursado m : x.malla) {
                    if (m.fueCursado()) {
                        filas.add(new String[] { m.getCodigo(), m.getNombre(), String.valueOf(m.getIntento()),
                                m.getAsistencia() == null ? "sin registro" : m.getAsistencia() + " %", m.getEstado() });
                    }
                }
                d.cuerpo.add(Bloque.parrafo("es estudiante regular del " + programa + ", en " + oferta + ", y registra la siguiente "
                        + "asistencia en los módulos que cursa o cursó (porcentaje sobre las clases dictadas):"));
                d.cuerpo.add(Bloque.tabla(new String[] { "Código", "Módulo", "Intento", "Asistencia", "Estado" }, new double[] { 2, 5, 1.3, 2, 3 }, filas));
                break;
            }
            case "CONSTANCIA_ADMISION":
                d.cuerpo.add(Bloque.parrafo("fue admitido (a) en el " + programa + ", en " + oferta + ", con inicio de actividades el "
                        + x.oferta.getFechaInicio().format(FECHA) + " en " + x.oferta.getLugar()
                        + ". Su inscripción fue formalizada con el Registro N° " + x.estudiante.getCodigoEstudiante() + "."));
                break;
            case "CERTIFICADO_NOTAS": {
                List<String[]> filas = new ArrayList<>();
                for (ModuloCursado m : x.malla) {
                    if (m.tieneNotaOficial()) {
                        filas.add(new String[] { m.getCodigo(), m.getNombre(), String.valueOf(m.getCargaHoraria()),
                                String.valueOf(m.getIntento()), m.getNotaFinal().toPlainString(), m.getEstado() });
                    }
                }
                d.cuerpo.add(Bloque.parrafo("cursó en el " + programa + ", los módulos que se detallan, con las calificaciones sobre 100 "
                        + "puntos registradas en actas cerradas (nota mínima de aprobación: 51):"));
                d.cuerpo.add(Bloque.tabla(new String[] { "Código", "Módulo", "Horas", "Intento", "Nota", "Resultado" },
                        new double[] { 2, 5, 1.3, 1.3, 1.3, 3.6 }, filas));
                BigDecimal promedio = x.promedio();
                if (promedio != null) {
                    d.cuerpo.add(Bloque.dato("Promedio de los módulos aprobados:", promedio.toPlainString()));
                }
                break;
            }
            case "CERTIFICADO_CONCLUSION":
                d.cuerpo.add(Bloque.parrafo("concluyó satisfactoriamente el plan de estudios del " + programa + ", aprobando la totalidad "
                        + "de los módulos de su malla curricular (" + x.malla.size() + "), con una carga horaria de " + x.horasAprobadas()
                        + " horas académicas."));
                d.cuerpo.add(Bloque.dato("Promedio general:", x.promedio().toPlainString()));
                break;
            case "CERTIFICADO_VENCIMIENTO": {
                List<String[]> filas = new ArrayList<>();
                for (ModuloCursado m : x.malla) {
                    filas.add(new String[] { m.getCodigo(), m.getNombre(), String.valueOf(m.getCargaHoraria()),
                            m.getNotaFinal().toPlainString(), m.getEstado() });
                }
                d.cuerpo.add(Bloque.parrafo("venció la totalidad de las asignaturas del plan de estudios del " + programa
                        + ", según el siguiente detalle:"));
                d.cuerpo.add(Bloque.tabla(new String[] { "Código", "Módulo", "Horas", "Nota", "Resultado" }, new double[] { 2, 6, 1.3, 1.3, 3 }, filas));
                d.cuerpo.add(Bloque.dato("Carga horaria total:", x.horasAprobadas() + " horas académicas"));
                d.cuerpo.add(Bloque.dato("Promedio general:", x.promedio().toPlainString()));
                break;
            }
            default: {
                // CONSTANCIA_PROGRAMA_ANALITICO
                ModuloCursado cursado = null;
                for (ModuloCursado m : x.malla) {
                    if (m.getCodigo().equals(x.modulo.getCodigo())) {
                        cursado = m;
                    }
                }
                Personal capacitador = new PersonalDAO(con).buscarPorId(x.asignacion.getIdPersonal());
                d.cuerpo.add(Bloque.parrafo("cursó el módulo " + x.modulo.getCodigo() + " - " + x.modulo.getNombre()
                        + (cursado != null ? ", con una carga horaria de " + cursado.getCargaHoraria() + " horas" : "") + ", en el " + programa
                        + ", en " + oferta + ", dictado del " + x.asignacion.getFechaInicio().format(FECHA) + " al "
                        + x.asignacion.getFechaFin().format(FECHA) + (capacitador != null ? " por " + capacitador.getNombreCompleto() : "")
                        + ". Su contenido corresponde al programa analítico registrado en la asignación del módulo, "
                        + "que se entrega adjunto a esta constancia."));
                break;
            }
        }
    }
}
