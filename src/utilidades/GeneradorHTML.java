package src.utilidades;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class GeneradorHTML {

    public static String mensajeExito(String titulo, String detalle) {
        return "<html><body style='font-family:Arial,sans-serif;'>"
                + "<div style='border:2px solid #2e7d32;padding:15px;border-radius:8px;background:#f1f8e9;'>"
                + "<h3 style='color:#2e7d32;margin-top:0;'>✅ Éxito: " + titulo + "</h3>"
                + "<p>" + detalle + "</p>"
                + "<hr><small>Sistema de Gestión Académica ICAP - UAGRM (Grupo 15 SC)</small>"
                + "</div></body></html>";
    }

    public static String mensajeError(String detalle) {
        return "<html><body style='font-family:Arial,sans-serif;'>"
                + "<div style='border:2px solid #c62828;padding:15px;border-radius:8px;background:#ffebee;'>"
                + "<h3 style='color:#c62828;margin-top:0;'>❌ Error al intentar realizar la orden</h3>"
                + "<p>" + detalle + "</p>"
                + "<p><small>Envíe el asunto <b>help!me</b> para consultar la sintaxis correcta de los comandos.</small></p>"
                + "</div></body></html>";
    }

    // Notificaciones automáticas del sistema (avisos de cupo, cancelación de ofertas)
    public static String mensajeAviso(String titulo, String detalle) {
        return "<html><body style='font-family:Arial,sans-serif;'>"
                + "<div style='border:2px solid #ef6c00;padding:15px;border-radius:8px;background:#fff3e0;'>"
                + "<h3 style='color:#ef6c00;margin-top:0;'>⚠️ Aviso: " + titulo + "</h3>"
                + "<p>" + detalle + "</p>"
                + "<hr><small>Sistema de Gestión Académica ICAP - UAGRM (Grupo 15 SC)</small>"
                + "</div></body></html>";
    }

    public static String formatearErrorSQL(SQLException ex) {
        if (ex == null) {
            return "Ocurrió un error inesperado al procesar la solicitud.";
        }
        String msg = ex.getMessage();
        if (msg == null) {
            return "Ocurrió un error inesperado en la base de datos.";
        }
        String sqlState = ex.getSQLState();

        // 23505: Violación de restricción UNIQUE (clave duplicada)
        if ("23505".equals(sqlState) || msg.contains("viola restricción de unicidad") || msg.contains("duplicate key")) {
            String valor = "";
            int idxIgual = msg.indexOf(")=(");
            if (idxIgual != -1) {
                int idxCierre = msg.indexOf(")", idxIgual + 3);
                if (idxCierre != -1) {
                    valor = msg.substring(idxIgual + 3, idxCierre);
                }
            }

            if (msg.contains("usuario_usuario_key") || msg.contains("usuario_email_key")) {
                return "El correo electrónico o usuario " + (!valor.isEmpty() ? "<b>" + valor + "</b> " : "")
                        + "ya se encuentra registrado en el sistema. Por favor, utilice un correo diferente.";
            } else if (msg.contains("personal_ci_key")) {
                return "El número de documento de identidad (CI) " + (!valor.isEmpty() ? "<b>" + valor + "</b> " : "")
                        + "ya se encuentra registrado para otro personal.";
            } else if (msg.contains("estudiante_ci_key")) {
                return "El número de documento de identidad (CI) " + (!valor.isEmpty() ? "<b>" + valor + "</b> " : "")
                        + "ya se encuentra registrado para otro estudiante.";
            } else if (msg.contains("estudiante_codigo_estudiante_key")) {
                return "El código de estudiante " + (!valor.isEmpty() ? "<b>" + valor + "</b> " : "")
                        + "ya se encuentra asignado en el sistema.";
            } else {
                return "Ya existe un registro con los mismos datos principales " + (!valor.isEmpty() ? "(<b>" + valor + "</b>) " : "")
                        + "en el sistema.";
            }
        }

        // 23503: Violación de llave foránea (registro padre no existe)
        if ("23503".equals(sqlState) || msg.contains("violates foreign key") || msg.contains("viola la llave foránea")) {
            return "El registro está relacionado con otros datos del sistema (verifique roles, identificadores o dependencias asociadas).";
        }

        // 23502: Valor NOT NULL omitido
        if ("23502".equals(sqlState) || msg.contains("not-null") || msg.contains("nulo")) {
            return "Faltan datos obligatorios para poder completar el registro.";
        }

        // 22001: Texto demasiado largo
        if ("22001".equals(sqlState) || msg.contains("value too long")) {
            return "Uno de los textos ingresados supera el límite de caracteres permitido.";
        }

        return "No se pudo procesar la solicitud: " + msg;
    }

    public static String tablaResultSet(String titulo, ResultSet rs) throws SQLException {
        return tabla(titulo, Tabla.desde(rs));
    }

    public static String tabla(String titulo, Tabla t) {
        return tablas(new String[] { titulo }, t);
    }

    // Varias tablas en una sola respuesta (KARDEX): un título por tabla
    public static String tablas(String[] titulos, Tabla... tablas) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Arial,sans-serif;'>");
        for (int i = 0; i < tablas.length; i++) {
            agregarTabla(sb, titulos[i], tablas[i]);
        }
        sb.append("<hr><small>Éxito en la consulta - ICAP Facultad Politécnica UAGRM</small></body></html>");
        return sb.toString();
    }

    private static void agregarTabla(StringBuilder sb, String titulo, Tabla t) {
        sb.append("<h3 style='color:#1565c0;'>📋 Listando ").append(titulo).append("</h3>");
        sb.append(
                "<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;width:100%;font-size:13px;'>");

        sb.append("<tr style='background:#1565c0;color:white;'>");
        for (String cab : t.getCabeceras()) {
            sb.append("<th>").append(cab.toUpperCase()).append("</th>");
        }
        sb.append("</tr>");

        for (String[] fila : t.getFilas()) {
            sb.append("<tr>");
            for (String val : fila) {
                sb.append("<td>").append(val != null ? escapar(val) : "-").append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        if (t.estaVacia()) {
            sb.append("<p><i>No se encontraron registros para el criterio solicitado.</i></p>");
        }
    }

    // Los datos de la tabla pueden venir de comandos públicos: se muestran como texto, nunca como HTML
    private static String escapar(String texto) {
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // VERACTA: el acta lista para imprimir y para la firma del capacitador
    public static String acta(String numero, String estado, String oferta, String modulo, String capacitador, Tabla t) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Arial,sans-serif;'>");
        sb.append("<h2 style='color:#0d47a1;text-align:center;margin-bottom:0;'>ACTA DE CALIFICACIONES N° ").append(numero).append("</h2>");
        sb.append("<p style='text-align:center;margin-top:4px;'>Instituto de Capacitación Popular (ICAP) - UAGRM</p>");
        sb.append("<p><b>Oferta:</b> ").append(oferta).append("<br><b>Módulo:</b> ").append(modulo)
                .append("<br><b>Capacitador:</b> ").append(capacitador).append("<br><b>Estado del acta:</b> ").append(estado).append("</p>");
        sb.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;width:100%;font-size:13px;'>");
        sb.append("<tr style='background:#1565c0;color:white;'>");
        for (String cab : t.getCabeceras()) {
            sb.append("<th>").append(cab.toUpperCase()).append("</th>");
        }
        sb.append("</tr>");
        for (String[] fila : t.getFilas()) {
            sb.append("<tr>");
            for (String val : fila) {
                sb.append("<td>").append(val != null ? escapar(val) : "-").append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        if (t.estaVacia()) {
            sb.append("<p><i>El módulo todavía no tiene estudiantes registrados.</i></p>");
        }
        sb.append("<p style='margin-top:60px;text-align:center;'>_______________________________<br>").append(capacitador)
                .append("<br><small>Firma del capacitador</small></p></body></html>");
        return sb.toString();
    }

    // SOLCERT: cada requisito del documento con lo que cumple y lo que falta. filas: {requisito, "SI"/"NO", detalle}
    public static String requisitos(String titulo, String detalle, List<String[]> filas, boolean completo, String pie) {
        String color = completo ? "#2e7d32" : "#ef6c00";
        StringBuilder sb = new StringBuilder("<html><body style='font-family:Arial,sans-serif;'>");
        sb.append("<div style='border:2px solid ").append(color).append(";padding:15px;border-radius:8px;background:")
                .append(completo ? "#f1f8e9" : "#fff3e0").append(";'>");
        sb.append("<h3 style='color:").append(color).append(";margin-top:0;'>").append(completo ? "✅ " : "⚠️ ").append(titulo).append("</h3>");
        sb.append("<p>").append(detalle).append("</p>");
        sb.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;width:100%;font-size:13px;background:white;'>");
        sb.append("<tr style='background:#1565c0;color:white;'><th>REQUISITO</th><th>CUMPLE</th><th>DETALLE</th></tr>");
        for (String[] f : filas) {
            sb.append("<tr><td>").append(escapar(f[0])).append("</td><td style='text-align:center;'>")
                    .append("SI".equals(f[1]) ? "✅ SI" : "❌ NO").append("</td><td>").append(escapar(f[2])).append("</td></tr>");
        }
        sb.append("</table><p>").append(pie).append("</p>");
        sb.append("<hr><small>Sistema de Gestión Académica ICAP - UAGRM (Grupo 15 SC)</small></div></body></html>");
        return sb.toString();
    }

    // VERCERT: resultado de la verificación pública de un documento. datos: {etiqueta, valor}
    public static String verificacion(boolean vigente, String titulo, String[][] datos, String nota) {
        String color = vigente ? "#2e7d32" : "#c62828";
        StringBuilder sb = new StringBuilder("<html><body style='font-family:Arial,sans-serif;'>");
        sb.append("<div style='border:3px double ").append(color).append(";padding:18px;border-radius:8px;background:")
                .append(vigente ? "#f1f8e9" : "#ffebee").append(";'>");
        sb.append("<h2 style='color:").append(color).append(";margin-top:0;'>").append(vigente ? "✅ " : "❌ ").append(titulo).append("</h2>");
        sb.append("<table cellpadding='6' cellspacing='0' style='border-collapse:collapse;font-size:14px;'>");
        for (String[] d : datos) {
            sb.append("<tr><td><b>").append(d[0]).append(":</b></td><td>").append(d[1] == null ? "-" : escapar(d[1])).append("</td></tr>");
        }
        sb.append("</table><p>").append(nota).append("</p>");
        sb.append("<hr><small>Verificación pública de documentos - ICAP Facultad Politécnica UAGRM</small></div></body></html>");
        return sb.toString();
    }

    // =========================================================================
    // CU-08: reporte con cifras destacadas, gráficos y tablas. Los gráficos son barras armadas con tablas HTML de
    // ancho proporcional, porque los clientes de correo no ejecutan scripts ni muestran SVG
    // =========================================================================

    public static String reporte(Reporte r, List<String> adjuntos) {
        StringBuilder sb = new StringBuilder("<html><body style='font-family:Arial,sans-serif;color:#1f2328;'>");
        sb.append("<h2 style='color:#1565c0;margin:0 0 4px 0;'>📊 ").append(escapar(r.getTitulo()))
                .append(" <span style='font-size:13px;color:#52514e;font-weight:normal;'>(").append(r.getComando()).append(")</span></h2>")
                .append("<div style='color:#52514e;font-size:14px;'>").append(escapar(r.getAlcance())).append("</div>")
                .append("<div style='color:#898781;font-size:12px;margin:2px 0 10px 0;'>Generado el ").append(r.getGenerado())
                .append(r.getGeneradoPor().isEmpty() ? "" : " por " + escapar(r.getGeneradoPor())).append("</div>");

        List<Reporte.Indicador> indicadores = r.getIndicadores();
        if (!indicadores.isEmpty()) {
            // Hasta 5 cifras van en una fila; 6 se reparten en dos filas de 3
            int porFila = indicadores.size() <= 5 ? indicadores.size() : indicadores.size() == 6 ? 3 : 4;
            sb.append("<table cellpadding='0' cellspacing='8' style='border-collapse:separate;margin-left:-8px;'>");
            for (int i = 0; i < indicadores.size(); i++) {
                if (i % porFila == 0) {
                    sb.append(i == 0 ? "<tr>" : "</tr><tr>");
                }
                Reporte.Indicador ind = indicadores.get(i);
                sb.append("<td style='background:#f4f6f9;border-radius:6px;padding:10px 14px;vertical-align:top;min-width:130px;'>")
                        .append("<div style='font-size:12px;color:#52514e;'>").append(escapar(ind.etiqueta())).append("</div>")
                        .append("<div style='font-size:20px;font-weight:bold;color:#1f2328;margin:2px 0;'>").append(escapar(ind.valor())).append("</div>")
                        .append("<div style='font-size:11px;color:#898781;'>").append(ind.detalle() == null ? "" : escapar(ind.detalle()))
                        .append("</div></td>");
            }
            sb.append("</tr></table>");
        }
        for (Grafico g : r.getGraficos()) {
            grafico(sb, g);
        }
        for (Reporte.Seccion s : r.getSecciones()) {
            tablaDeReporte(sb, s);
        }
        if (!adjuntos.isEmpty()) {
            sb.append("<p style='background:#e3f2fd;border-radius:6px;padding:10px 12px;font-size:13px;'>📎 <b>Descargue el reporte:</b> ");
            for (int i = 0; i < adjuntos.size(); i++) {
                String a = adjuntos.get(i);
                sb.append(i == 0 ? "" : i == adjuntos.size() - 1 ? " y " : ", ").append("<b>").append(escapar(a)).append("</b>")
                        .append(a.endsWith(".pdf") ? " (con los gráficos, para imprimir o archivar)" : a.endsWith(".xlsx") ? " (los datos en Excel)" : "");
            }
            sb.append(" van adjuntos a este correo.</p>");
        }
        if (!r.getNotas().isEmpty()) {
            sb.append("<ul style='color:#52514e;font-size:12px;padding-left:18px;'>");
            for (String n : r.getNotas()) {
                sb.append("<li>").append(n).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("<hr><small>Éxito en la consulta - ICAP Facultad Politécnica UAGRM</small></body></html>");
        return sb.toString();
    }

    // Barras horizontales: la más larga ocupa el 78 % del ancho y el resto queda para el valor
    private static void grafico(StringBuilder sb, Grafico g) {
        sb.append("<h3 style='color:#1565c0;font-size:15px;margin:18px 0 4px 0;'>📈 ").append(escapar(g.getTitulo())).append("</h3>");
        int series = g.getSeries().size();
        if (g.muestraLeyenda()) {
            sb.append("<div style='font-size:12px;color:#52514e;margin-bottom:4px;'>");
            for (int s = 0; s < series; s++) {
                sb.append("<span style='display:inline-block;width:10px;height:10px;background:").append(g.getColores().get(s))
                        .append(";border-radius:2px;'></span>&nbsp;").append(escapar(g.getSeries().get(s))).append("&nbsp;&nbsp;&nbsp; ");
            }
            sb.append("</div>");
        }
        if (g.getReferencia() != null) {
            sb.append("<div style='font-size:12px;color:#52514e;margin-bottom:4px;'>Referencia: ").append(escapar(g.getEtiquetaReferencia()))
                    .append("</div>");
        }
        double maximo = g.maximo();
        int limite = Math.min(g.getCategorias().size(), 40);
        // En una serie larga en el tiempo (días de un mes) el correo omite los días sin movimiento; el PDF los muestra
        boolean omitirCeros = g.getTipo() == Grafico.Tipo.COLUMNAS && g.getCategorias().size() > 12;
        if (omitirCeros) {
            limite = g.getCategorias().size();
        }
        sb.append("<table cellpadding='0' cellspacing='0' style='width:100%;max-width:760px;font-size:12px;'>");
        for (int c = 0; c < limite; c++) {
            if (omitirCeros && g.total(c) <= 0) {
                continue;
            }
            sb.append("<tr><td style='width:28%;padding:3px 8px 3px 0;text-align:right;color:#52514e;vertical-align:middle;'>")
                    .append(escapar(g.getCategorias().get(c))).append("</td><td style='padding:3px 0;vertical-align:middle;'>");
            if (series > 1 && !g.esApilado()) {
                for (int s = 0; s < series; s++) {
                    double v = g.valor(c, s);
                    barra(sb, new double[] { v }, new String[] { g.getColores().get(s) }, maximo, g.formato(v), 8);
                }
            } else {
                double[] valores = new double[series];
                for (int s = 0; s < series; s++) {
                    valores[s] = g.valor(c, s);
                }
                String texto = g.formato(g.total(c)) + (g.conPorcentajes() ? " (" + g.porcentajeDe(c) + ")" : "");
                barra(sb, valores, g.getColores().toArray(new String[0]), maximo, texto, 14);
            }
            sb.append("</td></tr>");
        }
        sb.append("</table>");
        if (omitirCeros) {
            sb.append("<div style='font-size:12px;color:#898781;'>Se omiten los días sin movimiento; el PDF adjunto muestra todos.</div>");
        }
        if (limite < g.getCategorias().size()) {
            sb.append("<div style='font-size:12px;color:#898781;'>... y ").append(g.getCategorias().size() - limite)
                    .append(" más (el detalle completo está en la tabla y en los adjuntos)</div>");
        }
    }

    // Una barra (o una barra apilada) con su valor en la punta; 2 px de blanco separan los segmentos
    private static void barra(StringBuilder sb, double[] valores, String[] colores, double maximo, String texto, int alto) {
        int ultima = -1;
        for (int s = 0; s < valores.length; s++) {
            if (valores[s] > 0) {
                ultima = s;
            }
        }
        sb.append("<table cellpadding='0' cellspacing='0' style='width:100%;margin:1px 0;'><tr>");
        for (int s = 0; s <= ultima; s++) {
            if (valores[s] <= 0 || maximo <= 0) {
                continue;
            }
            double ancho = Math.max(0.4, 78 * valores[s] / maximo);
            sb.append("<td style='width:").append(String.format(java.util.Locale.ROOT, "%.1f", ancho)).append("%;background:").append(colores[s])
                    .append(";height:").append(alto).append("px;font-size:1px;line-height:1px;")
                    .append(s == ultima ? "border-radius:0 3px 3px 0;" : "border-right:2px solid #ffffff;").append("'>&nbsp;</td>");
        }
        sb.append("<td style='padding-left:6px;white-space:nowrap;color:#1f2328;font-size:").append(alto < 10 ? 11 : 12).append("px;'>")
                .append(escapar(texto)).append("</td></tr></table>");
    }

    // Tabla del reporte: números alineados a la derecha y la fila TOTAL resaltada
    private static void tablaDeReporte(StringBuilder sb, Reporte.Seccion s) {
        Tabla t = s.tabla();
        sb.append("<h3 style='color:#1565c0;font-size:15px;margin:18px 0 6px 0;'>📋 ").append(escapar(s.titulo())).append("</h3>")
                .append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;width:100%;font-size:13px;border-color:#d0d7de;'>")
                .append("<tr style='background:#1565c0;color:white;'>");
        for (String cab : t.getCabeceras()) {
            sb.append("<th>").append(escapar(cab.toUpperCase().replace('_', ' '))).append("</th>");
        }
        sb.append("</tr>");
        for (String[] fila : t.getFilas()) {
            boolean total = fila.length > 0 && "TOTAL".equals(fila[0]);
            sb.append(total ? "<tr style='background:#e3f2fd;font-weight:bold;'>" : "<tr>");
            for (int c = 0; c < fila.length; c++) {
                sb.append(t.esNumerica(c) && !(total && c == 0) ? "<td style='text-align:right;'>" : "<td>")
                        .append(fila[c] != null ? escapar(fila[c]) : "-").append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        if (t.estaVacia()) {
            sb.append("<p><i>No se encontraron registros para el criterio solicitado.</i></p>");
        }
        if (s.nota() != null && !s.nota().isEmpty()) {
            sb.append("<div style='font-size:12px;color:#898781;margin-top:4px;'>").append(s.nota()).append("</div>");
        }
    }

    // ---------------------------------------------------------------------
    // Manual de help!me: cada comando con los campos que lleva, un ejemplo y sus observaciones
    // ---------------------------------------------------------------------

    private static final String BORDE = "border:1px solid #c5cfdf;padding:5px 8px;vertical-align:top;";
    private static final String CABECERA = "background:#0d47a1;color:white;text-align:left;" + BORDE;

    // Cómo se indica cada registro (no se usan códigos internos): registro, campos, ejemplo.
    // Las plantillas se escriben con comillas simples para no escapar cada campo; en el correo van con comillas dobles
    private static final String[][] CLAVES = {
            { "Personal, capacitador o estudiante", "'ci'", "'4715292'" },
            { "Estudiante inscrito", "'codigo_estudiante' (o su CI)", "'20267654321'" },
            { "Programa", "'codigo'", "'TAC'" },
            { "Versión de la malla", "'programa', 'version'", "'TAC', 'v1'" },
            { "Módulo", "'codigo'", "'CONT-1'" },
            { "Oferta académica", "'programa', 'gestion', 'grupo'", "'TAC', '2-2026', 'A'" },
            { "Módulo de una oferta (asignación)", "'programa', 'gestion', 'grupo', 'modulo'", "'TAC', '2-2026', 'A', 'CONT-1'" },
            { "Arancel", "'concepto', 'gestion'", "'MATRICULA', '2-2026'" },
            { "Convenio", "'institucion'", "'Alcaldia SC'" },
            { "Pago", "'numero_transaccion'", "'VOUCHER-001'" },
            { "Certificado emitido", "'codigo_alfanumerico'", "'ICAP-2026-000001'" },
            { "Gestión y fechas", "semestre-año y AAAA-MM-DD", "'2-2026' y '2026-11-09'" },
    };

    // Filas del manual: CU = título del caso de uso, F = flujo (y quién lo envía), N = nota,
    // C = comando con sus campos, ejemplo(s) y observaciones. Varias formas van separadas por salto de línea
    private static final String[][] MANUAL = {
            { "CU", "CU-01 Gestión de Usuarios" },
            { "F", "Personal administrativo (Director)" },
            { "C", "ADDPER['ci', 'ci_expedicion', 'nombre', 'apellidos', 'direccion', 'telefono', 'email', 'rol']",
                    "ADDPER['4715292', 'SC', 'Juan', 'Perez Rojas', 'Av. Busch', '71055123', 'jperez@gmail.com', 'Encargado de Registro']",
                    "Roles: Coordinación Académica, CPD, Jefe Administrativo Financiero, Encargado de Registro. La clave inicial es el CI" },
            { "C", "LISPER['*']\nLISPER['ci']", "LISPER['*']\nLISPER['4715292']", "Todo el personal, o una persona" },
            { "C", "MODPER['ci', 'campo', 'nuevo_valor']", "MODPER['4715292', 'telefono', '70000000']",
                    "Campos: nombre, apellidos, ci_expedicion, direccion, telefono, email, usuario" },
            { "C", "DELPER['ci']", "DELPER['4715292']", "Baja lógica: pasa a INACTIVO" },
            { "C", "RESPER['ci']", "RESPER['4715292']", "Revierte DELPER" },
            { "F", "Capacitadores (Coordinación Académica)" },
            { "C", "ADDCAP['ci', 'ci_expedicion', 'nombre', 'apellidos', 'direccion', 'telefono', 'email', 'titulo_academico', "
                    + "'grado_academico', 'experiencia_lab', 'cv_documento']",
                    "ADDCAP['5566778', 'SC', 'Ana', 'Lopez', 'Calle 1', '77001122', 'alopez@gmail.com', 'Lic. Contaduria', 'Licenciatura', "
                    + "'5 años', 'cv_alopez.pdf']",
                    "Crea la cuenta con el rol Capacitador" },
            { "C", "LISCAP['*']\nLISCAP['ci']", "LISCAP['*']\nLISCAP['5566778']", "" },
            { "C", "MODCAP['ci', 'campo', 'nuevo_valor']", "MODCAP['5566778', 'grado_academico', 'Maestria']",
                    "Campos: los de MODPER y titulo_academico, grado_academico, experiencia_lab, cv_documento" },
            { "C", "DELCAP['ci']", "DELCAP['5566778']", "Se rechaza si tiene un acta ABIERTA" },
            { "C", "RESCAP['ci']", "RESCAP['5566778']", "Revierte DELCAP" },
            { "F", "Estudiantes (Encargado de Registro)" },
            { "C", "ADDEST['ci', 'ci_expedicion', 'nombre', 'paterno', 'materno', 'fecha_nacimiento', 'direccion', 'telefono', 'email', "
                    + "'nivel_escolaridad', 'ciudad', 'pais']",
                    "ADDEST['8912345', 'SC', 'Cristhian', 'Velasquez', 'Reynaga', '2000-05-12', 'Av. Santa Cruz', '71798326', "
                    + "'cristhian@gmail.com', 'Bachiller', 'Santa Cruz', 'Bolivia']",
                    "Edad mínima: 14 años. El estudiante no tiene cuenta de acceso" },
            { "C", "LISEST['*']\nLISEST['ci']\nLISEST['codigo_estudiante']", "LISEST['*']\nLISEST['8912345']", "" },
            { "C", "MODEST['ci', 'campo', 'nuevo_valor']", "MODEST['8912345', 'email', 'nuevo@gmail.com']",
                    "Campos: nombre, paterno, materno, ci_expedicion, fecha_nacimiento, direccion, telefono, email, nivel_escolaridad, ciudad, pais" },
            { "C", "DELEST['ci']", "DELEST['8912345']", "Solo si no tiene inscripciones" },
            { "F", "Roles (Director)" },
            { "C", "ASIGROL['email', 'rol']", "ASIGROL['jperez@gmail.com', 'Coordinacion Academica']", "Agrega un rol; un usuario puede tener varios" },
            { "C", "LISROL['*']\nLISROL['email']", "LISROL['*']\nLISROL['jperez@gmail.com']", "Con \"*\", los roles con sus permisos" },
            { "C", "QUIROL['email', 'rol']", "QUIROL['jperez@gmail.com', 'Coordinacion Academica']", "No quita el único rol" },
            { "F", "Listado general" },
            { "C", "LISUSR['*']", "LISUSR['*']", "Todas las cuentas con su rol y estado" },
            { "F", "Cuentas y claves" },
            { "C", "MODPER['ci', 'usuario', 'nuevo_usuario']\nMODCAP['ci', 'usuario', 'nuevo_usuario']",
                    "MODPER['4715292', 'usuario', 'jperez']\nMODCAP['5566778', 'usuario', 'alopez']",
                    "Director para el personal; Coord. Académica para los capacitadores" },
            { "C", "MODCLAVE['clave_actual', 'clave_nueva']", "MODCLAVE['4715292', 'Clave2026']", "Cada usuario cambia su propia clave" },
            { "C", "RESCLAVE['ci']", "RESCLAVE['4715292']",
                    "La clave vuelve al CI y se avisa por correo. Director para el personal; Coord. Académica para los capacitadores" },

            { "CU", "CU-02 Catálogo de Programas, Mallas y Ofertas Académicas" },
            { "F", "Programas (Coordinación Académica)" },
            { "C", "ADDPROG['codigo', 'nombre', 'nivel_titulacion', 'codigo_prerrequisito']", "ADDPROG['TAC', 'Tecnico Auxiliar Contable', 'TECNICO_AUXILIAR']",
                    "Niveles: TECNICO_MEDIO, TECNICO_AUXILIAR, CERTIFICACION_COMPETENCIA_LABORAL, CURSO_CORTO. El prerrequisito (programa exigido) es opcional" },
            { "C", "LISPROG['*']\nLISPROG['codigo']", "LISPROG['*']\nLISPROG['TAC']", "Con sus versiones" },
            { "C", "MODPROG['codigo', 'campo', 'nuevo_valor']", "MODPROG['TAC', 'nombre', 'Tecnico Auxiliar en Contabilidad']",
                    "Campos: nombre, nivel_titulacion, prerrequisito, estado" },
            { "C", "DELPROG['codigo']", "DELPROG['TAC']", "Con versiones pasa a INACTIVO; se reactiva con MODPROG estado ACTIVO" },
            { "F", "Versiones y malla curricular (Coordinación Académica; aprueba el Director)" },
            { "C", "ADDVER['programa', 'version', 'total_horas']", "ADDVER['TAC', 'v1', '240']", "Nace EN_ELABORACION" },
            { "C", "LISVER['programa']", "LISVER['TAC']", "" },
            { "C", "MODVER['programa', 'version', 'campo', 'nuevo_valor']", "MODVER['TAC', 'v1', 'total_horas', '300']",
                    "Campos: version, total_horas. Solo EN_ELABORACION" },
            { "C", "DELVER['programa', 'version']", "DELVER['TAC', 'v1']", "En elaboración se borra; aprobada pasa a CERRADA" },
            { "C", "ADDMALLA['programa', 'version', 'modulo', 'orden', 'carga_horaria']", "ADDMALLA['TAC', 'v1', 'CONT-1', '1', '120']", "" },
            { "C", "ADDPRE['programa', 'version', 'modulo', 'modulo_prerrequisito']", "ADDPRE['TAC', 'v1', 'CONT-2', 'CONT-1']",
                    "CONT-1 debe aprobarse antes de cursar CONT-2" },
            { "C", "LISMALLA['programa', 'version']\nLISPRE['programa', 'version']", "LISMALLA['TAC', 'v1']\nLISPRE['TAC']",
                    "Sin la versión, muestran la VIGENTE" },
            { "C", "MODMALLA['programa', 'version', 'modulo', 'campo', 'nuevo_valor']", "MODMALLA['TAC', 'v1', 'CONT-1', 'carga_horaria', '100']",
                    "Campos: orden, carga_horaria" },
            { "C", "DELMALLA['programa', 'version', 'modulo']\nDELPRE['programa', 'version', 'modulo', 'modulo_prerrequisito']",
                    "DELMALLA['TAC', 'v1', 'CONT-1']\nDELPRE['TAC', 'v1', 'CONT-2', 'CONT-1']", "Solo EN_ELABORACION" },
            { "C", "APRVER['programa', 'version', 'numero_resolucion', 'tipo_resolucion', 'fecha_aprobacion']",
                    "APRVER['TAC', 'v1', 'RES-ICAP-010/2026', 'ICAP', '2026-10-30']",
                    "Director. Tipo: ICAP, DECANATO o VICERRECTORADO. Las cargas horarias deben sumar total_horas" },
            { "F", "Módulos (Coordinación Académica)" },
            { "C", "ADDMOD['codigo', 'nombre']", "ADDMOD['CONT-1', 'Contabilidad Basica']", "" },
            { "C", "LISMOD['*']\nLISMOD['codigo']", "LISMOD['*']\nLISMOD['CONT-1']", "Indica en qué versiones participa" },
            { "C", "MODMOD['codigo', 'campo', 'nuevo_valor']", "MODMOD['CONT-1', 'nombre', 'Contabilidad General']", "Campos: codigo, nombre" },
            { "C", "DELMOD['codigo']", "DELMOD['CONT-1']", "Solo si no está en ninguna malla" },
            { "F", "Ofertas académicas (Coordinación Académica; aprueba el Director)" },
            { "C", "ADDOFER['programa', 'gestion', 'grupo', 'modalidad', 'lugar', 'fecha_inicio', 'fecha_fin', 'cupo_min', 'cupo_max', 'costo']",
                    "ADDOFER['TAC', '2-2026', 'A', 'PRESENCIAL', 'Sede Central', '2026-11-16', '2027-02-26', '10', '30', '350']",
                    "Sobre la versión VIGENTE; nace PLANIFICADA. Modalidad: PRESENCIAL, SEMIPRESENCIAL o VIRTUAL" },
            { "C", "APROFER['programa', 'gestion', 'grupo']", "APROFER['TAC', '2-2026', 'A']",
                    "Director. Abre las preinscripciones y la publica en CATALOGO" },
            { "C", "CATALOGO", "CATALOGO", "Público: las ofertas con preinscripciones abiertas" },
            { "C", "LISOFER['*']\nLISOFER['estado']\nLISOFER['gestion']\nLISOFER['programa']", "LISOFER['*']\nLISOFER['2-2026']",
                    "Con los preinscritos frente al cupo mínimo" },
            { "C", "MODOFER['programa', 'gestion', 'grupo', 'campo', 'nuevo_valor']", "MODOFER['TAC', '2-2026', 'A', 'estado', 'CONFIRMADA']",
                    "Campos: fecha_inicio, fecha_fin, cupo_min, cupo_max, modalidad, lugar, costo, estado (CONFIRMADA, POSTERGADA, EN_CURSO, FINALIZADA)" },
            { "C", "DELOFER['programa', 'gestion', 'grupo']", "DELOFER['TAC', '2-2026', 'A']",
                    "PLANIFICADA sin preinscritos se borra; si no, pasa a CANCELADA y avisa a los preinscritos" },
            { "F", "Asignación de capacitadores (Coordinación Académica)" },
            { "C", "ADDASIG['programa', 'gestion', 'grupo', 'modulo', 'ci_capacitador', 'fecha_inicio', 'fecha_fin', 'programa_analitico', "
                    + "'propuesta_tecnica']",
                    "ADDASIG['TAC', '2-2026', 'A', 'CONT-1', '5566778', '2026-11-16', '2026-12-18', 'programa.pdf', 'propuesta.pdf']",
                    "Los dos últimos son opcionales. Crea el acta ABIERTA" },
            { "C", "LISASIG['programa', 'gestion', 'grupo']", "LISASIG['TAC', '2-2026', 'A']", "" },
            { "C", "MODASIG['programa', 'gestion', 'grupo', 'modulo', 'campo', 'nuevo_valor']",
                    "MODASIG['TAC', '2-2026', 'A', 'CONT-1', 'capacitador', '5566778']",
                    "Campos: capacitador, fecha_inicio, fecha_fin, programa_analitico, propuesta_tecnica" },
            { "C", "DELASIG['programa', 'gestion', 'grupo', 'modulo']", "DELASIG['TAC', '2-2026', 'A', 'CONT-1']",
                    "Solo si el módulo no tiene estudiantes inscritos" },
            { "F", "Horarios (Coordinación Académica)" },
            { "C", "ADDHOR['programa', 'gestion', 'grupo', 'modulo', 'dia_semana', 'hora_inicio', 'hora_fin', 'aula']",
                    "ADDHOR['TAC', '2-2026', 'A', 'CONT-1', 'LUNES', '19:00', '21:30', 'Aula 5']",
                    "Valida choques de aula y de capacitador; genera las sesiones de clase" },
            { "C", "LISHOR['programa', 'gestion', 'grupo']", "LISHOR['TAC', '2-2026', 'A']", "Horario semanal de la oferta" },
            { "C", "MODHOR['programa', 'gestion', 'grupo', 'modulo', 'dia_semana', 'campo', 'nuevo_valor']",
                    "MODHOR['TAC', '2-2026', 'A', 'CONT-1', 'LUNES', 'aula', 'Aula 7']", "Campos: dia_semana, hora_inicio, hora_fin, aula" },
            { "C", "DELHOR['programa', 'gestion', 'grupo', 'modulo', 'dia_semana']", "DELHOR['TAC', '2-2026', 'A', 'CONT-1', 'LUNES']",
                    "Borra también sus sesiones futuras" },

            { "CU", "CU-03 Preinscripción, Inscripción y Ciclo de Vida del Estudiante" },
            { "F", "Preinscripción (público, sin cuenta)" },
            { "C", "PREINS['ci', 'nombre', 'paterno', 'materno', 'fecha_nacimiento', 'telefono', 'ciudad', 'programa', 'gestion', 'grupo']",
                    "PREINS['7654321', 'Maria', 'Rojas', 'Paz', '2001-03-15', '70011122', 'Santa Cruz', 'TAC', '2-2026', 'A']",
                    "Con la fotocopia del CI adjunta. La oferta debe estar en PREINSCRIPCIONES_ABIERTAS" },
            { "F", "Preinscritos (Encargado de Registro)" },
            { "C", "LISINS['programa', 'gestion', 'grupo', 'estado']", "LISINS['TAC', '2-2026', 'A', 'PREINSCRITO']",
                    "Sin el estado, todas las inscripciones de la oferta" },
            { "C", "MODPRE['ci', 'campo', 'nuevo_valor']", "MODPRE['7654321', 'telefono', '70000000']", "Mientras siga PREINSCRITO. Campos: los de MODEST" },
            { "C", "ANUPRE['ci', 'programa', 'gestion', 'grupo', 'motivo']", "ANUPRE['7654321', 'TAC', '2-2026', 'A', 'Desistio']",
                    "Motivo opcional. Pasa a RETIRADO" },
            { "C", "RESPRE['ci', 'programa', 'gestion', 'grupo']", "RESPRE['7654321', 'TAC', '2-2026', 'A']", "Revierte ANUPRE" },
            { "F", "Formalización de la inscripción (Encargado de Registro)" },
            { "C", "FORMINS['ci', 'programa', 'gestion', 'grupo']", "FORMINS['7654321', 'TAC', '2-2026', 'A']",
                    "Oferta CONFIRMADA y matrícula conciliada. Asigna el código de estudiante" },
            { "C", "ESTINS['ci', 'programa', 'gestion', 'grupo', 'nuevo_estado', 'motivo']",
                    "ESTINS['7654321', 'TAC', '2-2026', 'A', 'RETIRADO', 'Cambio de ciudad']",
                    "Estados: ABANDONO, RETIRADO. Sus módulos en curso pasan a ABANDONO" },
            { "C", "RESINS['ci', 'programa', 'gestion', 'grupo', 'motivo']", "RESINS['7654321', 'TAC', '2-2026', 'A']",
                    "Vuelve a INSCRITO; cada módulo se restaura con RESMOD" },
            { "F", "Registro de asignaturas (Encargado de Registro)" },
            { "C", "INSMOD['codigo_estudiante o ci', 'modulo', 'programa', 'gestion', 'grupo']", "INSMOD['20267654321', 'CONT-1']",
                    "La oferta es opcional. Exige la mensualidad conciliada, los prerrequisitos aprobados, sin choque de horario y hasta 2 módulos a la vez" },
            { "C", "RETMOD['codigo_estudiante o ci', 'modulo', 'motivo']", "RETMOD['20267654321', 'CONT-1']", "Motivo opcional. Pasa a ABANDONO" },
            { "C", "RESMOD['codigo_estudiante o ci', 'modulo']", "RESMOD['20267654321', 'CONT-1']", "Vuelve a EN_CURSO sin un pago nuevo" },
            { "C", "KARDEX['codigo_estudiante o ci']", "KARDEX['20267654321']", "Historial académico completo. Enc. Registro y Coord. Académica" },

            { "CU", "CU-04 Aranceles, Becas y Conciliación de Pagos (Jefe Administrativo Financiero)" },
            { "F", "Aranceles" },
            { "C", "ADDARAN['concepto', 'monto', 'gestion', 'numero_resolucion', 'fecha_resolucion']",
                    "ADDARAN['MATRICULA', '100', '2-2026', 'RES-ICAP-020/2026', '2026-11-05']",
                    "Conceptos: MATRICULA, MODULO_MENSUAL_CIUDAD, MODULO_MENSUAL_PROVINCIA, TITULO, CERTIFICADO, READMISION, TRASPASO, "
                    + "CAMBIO_RESIDENCIA, RETIRO_VOLUNTARIO, RECUPERATORIO" },
            { "C", "LISARAN['gestion', 'estado']", "LISARAN['2-2026']", "Sin el estado, los VIGENTE" },
            { "C", "MODARAN['concepto', 'gestion', 'campo', 'nuevo_valor']", "MODARAN['MATRICULA', '2-2026', 'monto', '120']",
                    "Campos: monto, numero_resolucion, fecha_resolucion. Solo si no tiene pagos" },
            { "C", "DELARAN['concepto', 'gestion']", "DELARAN['MATRICULA', '2-2026']", "Pasa a HISTORICO" },
            { "C", "RESARAN['concepto', 'gestion']", "RESARAN['MATRICULA', '2-2026']", "Vuelve a VIGENTE" },
            { "F", "Convenios y becas" },
            { "C", "ADDCONV['institucion', 'tipo_convenio', 'porcentaje_descuento', 'fecha_inicio', 'fecha_fin']",
                    "ADDCONV['Alcaldia SC', 'Beca institucional', '50', '2026-11-05', '2027-12-31']", "La fecha de fin es opcional" },
            { "C", "LISCONV['*']\nLISCONV['estado']", "LISCONV['*']", "Con sus beneficiarios" },
            { "C", "MODCONV['institucion', 'campo', 'nuevo_valor']", "MODCONV['Alcaldia SC', 'porcentaje_descuento', '40']",
                    "Campos: institucion, tipo_convenio, porcentaje_descuento, fecha_inicio, fecha_fin. Solo afecta a los pagos posteriores" },
            { "C", "DELCONV['institucion']", "DELCONV['Alcaldia SC']", "Pasa a RESCINDIDO" },
            { "C", "RESCONV['institucion']", "RESCONV['Alcaldia SC']", "Vuelve a VIGENTE, o a VENCIDO si ya terminó" },
            { "C", "ASIGCONV['ci', 'programa', 'gestion', 'grupo', 'institucion']", "ASIGCONV['7654321', 'TAC', '2-2026', 'A', 'Alcaldia SC']",
                    "Aplica el convenio a esa inscripción" },
            { "C", "QUICONV['ci', 'programa', 'gestion', 'grupo']", "QUICONV['7654321', 'TAC', '2-2026', 'A']", "Retira el convenio" },
            { "F", "Registro del comprobante" },
            { "C", "ADDPAGO['ci', 'concepto', 'monto', 'numero_transaccion', 'fecha_pago', 'nombre_del_depositante', 'relacion_depositante', "
                    + "'cuenta_origen', 'modulo', 'programa', 'gestion', 'grupo']",
                    "ADDPAGO['7654321', 'MATRICULA', '100', 'VOUCHER-001', '2026-11-05', 'Antonio Rojas', 'Maria Rojas', '10000123']\n"
                    + "ADDPAGO['7654321', 'MODULO_MENSUAL_CIUDAD', '150', 'VOUCHER-002', '2026-12-01', 'Antonio Rojas', 'Maria Rojas', '10000123', 'CONT-1']",
                    "Con la boleta adjunta. El módulo va en mensualidades y recuperatorios; la oferta, solo si tiene varias inscripciones. "
                    + "Nace PENDIENTE, u OBSERVADO si el monto no es el esperado" },
            { "C", "ESTCTA['codigo_estudiante o ci']", "ESTCTA['7654321']", "Aranceles debidos, pagos conciliados y saldo" },
            { "C", "LISPAGO['*']\nLISPAGO['estado']\nLISPAGO['fecha']", "LISPAGO['PENDIENTE']\nLISPAGO['2026-11-05']",
                    "Estados: PENDIENTE, CONCILIADO, OBSERVADO, ANULADO" },
            { "C", "MODPAGO['numero_transaccion', 'campo', 'nuevo_valor']", "MODPAGO['VOUCHER-001', 'monto', '100']",
                    "Campos: monto, numero_transaccion, fecha_pago, nombre_del_depositante, relacion_depositante, cuenta_origen. Solo PENDIENTE u OBSERVADO" },
            { "C", "ANUPAGO['numero_transaccion', 'motivo']", "ANUPAGO['VOUCHER-001', 'Deposito duplicado']", "Pasa a ANULADO; nunca se borra" },
            { "C", "RESPAGO['numero_transaccion']", "RESPAGO['VOUCHER-001']", "Vuelve a PENDIENTE" },
            { "F", "Conciliación" },
            { "C", "CONCPAGO['numero_transaccion']", "CONCPAGO['VOUCHER-001']",
                    "Avisa al estudiante; si es la matrícula, también al Encargado de Registro" },
            { "C", "OBSPAGO['numero_transaccion', 'motivo']", "OBSPAGO['VOUCHER-001', 'El monto no coincide con el extracto']",
                    "Avisa el motivo al estudiante" },

            { "CU", "CU-05 Asistencia, Evaluaciones y Corrección de Notas" },
            { "F", "Plan de evaluación (Capacitador)" },
            { "C", "ADDACT['programa', 'gestion', 'grupo', 'modulo', 'tipo_evaluacion', 'titulo', 'ponderacion', 'fecha_limite']",
                    "ADDACT['TAC', '2-2026', 'A', 'CONT-1', 'PARCIAL', 'Primer parcial', '35', '2026-12-01']",
                    "Tipos: ASISTENCIA (10), TRABAJOS PRACTICOS (15, de 1 a 4), PARCIAL (35), FINAL (40); deben sumar 100. La fecha límite es opcional" },
            { "C", "LISACT['programa', 'gestion', 'grupo', 'modulo']", "LISACT['TAC', '2-2026', 'A', 'CONT-1']", "El plan con la suma de ponderaciones" },
            { "C", "MODACT['programa', 'gestion', 'grupo', 'modulo', 'titulo', 'campo', 'nuevo_valor']",
                    "MODACT['TAC', '2-2026', 'A', 'CONT-1', 'Primer parcial', 'fecha_limite', '2026-12-05']",
                    "Campos: titulo, ponderacion_designada, fecha_limite" },
            { "C", "DELACT['programa', 'gestion', 'grupo', 'modulo', 'titulo']", "DELACT['TAC', '2-2026', 'A', 'CONT-1', 'Primer parcial']",
                    "Solo si no tiene notas" },
            { "F", "Sesiones y asistencia (Capacitador)" },
            { "C", "LISSES['programa', 'gestion', 'grupo', 'modulo']", "LISSES['TAC', '2-2026', 'A', 'CONT-1']", "Las sesiones con su estado" },
            { "C", "MODSES['programa', 'gestion', 'grupo', 'modulo', 'fecha', 'estado']", "MODSES['TAC', '2-2026', 'A', 'CONT-1', '2026-11-23', 'DICTADA']",
                    "Estados: DICTADA, SUSPENDIDA (no cuenta para la asistencia), PROGRAMADA" },
            { "C", "ADDSES['programa', 'gestion', 'grupo', 'modulo', 'fecha', 'hora_inicio', 'hora_fin']",
                    "ADDSES['TAC', '2-2026', 'A', 'CONT-1', '2026-12-20', '09:00', '11:00']", "Clase de recuperación" },
            { "C", "ADDASIS['programa', 'gestion', 'grupo', 'modulo', 'fecha', 'codigo:estado; codigo:estado']",
                    "ADDASIS['TAC', '2-2026', 'A', 'CONT-1', '2026-11-23', '20267654321:PRESENTE; 20268100200:AUSENTE']",
                    "Estados: PRESENTE, AUSENTE, LICENCIA, JUSTIFICADO. La sesión debe estar DICTADA" },
            { "C", "MODASIS['programa', 'gestion', 'grupo', 'modulo', 'fecha', 'codigo_estudiante o ci', 'nuevo_estado']",
                    "MODASIS['TAC', '2-2026', 'A', 'CONT-1', '2026-11-23', '20267654321', 'LICENCIA']", "Con el acta ABIERTA" },
            { "C", "LISASIS['programa', 'gestion', 'grupo', 'modulo']", "LISASIS['TAC', '2-2026', 'A', 'CONT-1']",
                    "Planilla con el porcentaje de asistencia de cada estudiante" },
            { "F", "Calificaciones (Capacitador)" },
            { "C", "ADDNOTA['programa', 'gestion', 'grupo', 'modulo', 'titulo', 'codigo:nota; codigo:nota']",
                    "ADDNOTA['TAC', '2-2026', 'A', 'CONT-1', 'Primer parcial', '20267654321:85; 20268100200:70']",
                    "Notas de 0 a 100, con el acta ABIERTA" },
            { "C", "MODNOTA['programa', 'gestion', 'grupo', 'modulo', 'titulo', 'codigo_estudiante o ci', 'nueva_nota']",
                    "MODNOTA['TAC', '2-2026', 'A', 'CONT-1', 'Primer parcial', '20267654321', '90']", "Con el acta ABIERTA" },
            { "C", "LISNOTA['programa', 'gestion', 'grupo', 'modulo']", "LISNOTA['TAC', '2-2026', 'A', 'CONT-1']",
                    "Planilla con la nota final calculada" },
            { "F", "Actas (Coordinación Académica)" },
            { "C", "VERACTA['programa', 'gestion', 'grupo', 'modulo']", "VERACTA['TAC', '2-2026', 'A', 'CONT-1']",
                    "El acta vigente, lista para imprimir y firmar" },
            { "C", "LISACTA['programa', 'gestion', 'grupo', 'modulo']", "LISACTA['TAC', '2-2026', 'A', 'CONT-1']", "También las anuladas" },
            { "C", "CIEACTA['programa', 'gestion', 'grupo', 'modulo']", "CIEACTA['TAC', '2-2026', 'A', 'CONT-1']",
                    "Cierra el acta antes del plazo; si no, se cierra sola 72 horas después del fin del módulo" },
            { "F", "Examen recuperatorio (Coordinación Académica)" },
            { "C", "HABREC['codigo_estudiante o ci', 'modulo']", "HABREC['20267654321', 'CONT-1']",
                    "Reprobado con 80% o más de asistencia y pago de RECUPERATORIO conciliado. La nota la carga el capacitador con ADDNOTA" },
            { "F", "Enmienda de notas (CPD)" },
            { "C", "ENMIENDA['codigo_estudiante o ci', 'modulo', 'nota_nueva', 'numero_resolucion']",
                    "ENMIENDA['20267654321', 'CONT-1', '95', 'RES-ICAP-045/2026']", "Con el acta CERRADA: la anula y emite una nueva" },
            { "C", "LISENM['codigo_estudiante o ci']\nLISENM['programa', 'gestion', 'grupo', 'modulo']",
                    "LISENM['20267654321']\nLISENM['TAC', '2-2026', 'A', 'CONT-1']", "Las enmiendas con su resolución" },

            { "CU", "CU-06 Certificados Digitales con QR y Código Único" },
            { "N", "Tipos de documento: TITULO, NOTAS, ESTUDIANTE, CONCLUSION, VENCIMIENTO, HORARIOS, ASISTENCIA, ADMISION, PROGRAMA_ANALITICO." },
            { "F", "Solicitud (público, sin cuenta)" },
            { "C", "SOLCERT['ci', 'codigo_estudiante', 'tipo_documento', 'programa o modulo']",
                    "SOLCERT['7654321', '20267654321', 'NOTAS']\nSOLCERT['7654321', '20267654321', 'PROGRAMA_ANALITICO', 'CONT-1']",
                    "El cuarto valor es opcional: el programa, o el módulo para PROGRAMA_ANALITICO. El resultado va al correo registrado del estudiante" },
            { "F", "Habilitados para título (Coordinación Académica)" },
            { "C", "LISELEG['programa', 'gestion', 'grupo']", "LISELEG['TAC', '2-2026', 'A']", "Triple condición: académica, financiera y documental" },
            { "F", "Emisión (Director)" },
            { "C", "GENCERT['codigo_estudiante o ci', 'tipo_documento', 'programa o modulo']",
                    "GENCERT['20267654321', 'NOTAS', 'TAC']\nGENCERT['20267654321', 'TITULO', 'TAC']",
                    "Vuelve a validar los requisitos y envía el PDF con QR al estudiante" },
            { "C", "LISCERT['*']\nLISCERT['gestion']\nLISCERT['tipo_documento']\nLISCERT['codigo_estudiante o ci']",
                    "LISCERT['*']\nLISCERT['2-2026']\nLISCERT['NOTAS']\nLISCERT['20267654321']", "" },
            { "C", "ANUCERT['codigo_alfanumerico', 'motivo']", "ANUCERT['ICAP-2026-000001', 'Error en el nombre']",
                    "Pasa a ANULADO; se emite uno nuevo con GENCERT" },
            { "F", "Verificación (público, sin cuenta)" },
            { "C", "VERCERT['codigo_alfanumerico']\nVERCERT['hash_qr']", "VERCERT['ICAP-2026-000001']", "Solo los datos públicos de acreditación" },

            { "CU", "CU-07 Repositorio Documental Histórico" },
            { "N", "Tipos de documento: CARNET_IDENTIDAD, FORMULARIO_INSCRIPCION, FOTOGRAFIA (JPG o PNG), TITULO_BACHILLER, TITULO_TECNICO_AUXILIAR, "
                    + "FORMULARIO_EXPERIENCIA, LIBRETA_ESCOLAR, CERTIFICADO_NACIMIENTO, BOLETA_PAGO. El expediente tiene un documento de cada tipo." },
            { "F", "Expediente (Encargado de Registro)" },
            { "C", "ADDDOC['codigo_estudiante o ci', 'tipo_documento']\nADDDOC['codigo_estudiante o ci', 'BOLETA_PAGO', 'numero_transaccion']",
                    "ADDDOC['7654321', 'FOTOGRAFIA']\nADDDOC['7654321', 'BOLETA_PAGO', 'CERT-0001']",
                    "Con el archivo escaneado adjunto. La boleta, para un pago que todavía no la tiene" },
            { "C", "LISDOC['codigo_estudiante o ci']\nLISDOC['codigo_estudiante o ci', 'documento']",
                    "LISDOC['7654321']\nLISDOC['7654321', 'CARNET_IDENTIDAD']\nLISDOC['7654321', 'BOLETA_PAGO', 'CERT-0001']\nLISDOC['7654321', 'TODOS']",
                    "Indica qué documentos faltan. Con el documento (o TODOS), lo adjunta a la respuesta" },
            { "C", "MODDOC['codigo_estudiante o ci', 'tipo_documento', 'numero_transaccion']",
                    "MODDOC['7654321', 'CARNET_IDENTIDAD']\nMODDOC['7654321', 'BOLETA_PAGO', 'CERT-0001']",
                    "Con el nuevo archivo adjunto. La transacción solo va en BOLETA_PAGO. El archivo anterior queda en el histórico" },
            { "C", "DELDOC['codigo_estudiante o ci', 'tipo_documento', 'numero_transaccion']", "DELDOC['7654321', 'FORMULARIO_INSCRIPCION']",
                    "Se rechaza si la boleta respalda un pago CONCILIADO" },
            { "F", "Búsqueda de expediente (Coordinación Académica, Director)" },
            { "C", "BUSDOC['ci']\nBUSDOC['apellido']\nBUSDOC['codigo_estudiante']\nBUSDOC['gestion']\nBUSDOC['ci o codigo_estudiante', 'documento']",
                    "BUSDOC['7654321']\nBUSDOC['Rojas']\nBUSDOC['20267654321']\nBUSDOC['2-2026']\nBUSDOC['7654321', 'TODOS']",
                    "Documentos, actas y certificados en una sola tabla. Con el documento, adjunta el archivo" },

            { "CU", "CU-08 Reportes y Estadísticas" },
            { "N", "Solo consulta. Cada reporte llega con sus cifras, gráficos y tablas, y adjunta el PDF y el Excel. "
                    + "Periodo: una gestión (2-2026, por mes), un mes (2026-11, por día) o una fecha (2026-11-09)." },
            { "F", "REPING: reporte de ingresos (Jefe Administrativo Financiero, Director)" },
            { "C", "REPING['periodo']", "REPING['2-2026']\nREPING['2026-11']\nREPING['2026-11-09']", "Recaudación conciliada por mes o por día" },
            { "C", "REPING['programa']\nREPING['periodo', 'programa']\nREPING['programa', 'gestion']",
                    "REPING['TAC']\nREPING['2026-11', 'TAC']\nREPING['TAC', '2-2026']", "Un programa, en todas sus gestiones o en un periodo" },
            { "C", "REPING['programa', 'gestion', 'grupo']\nREPING['mes o fecha', 'programa', 'gestion', 'grupo']",
                    "REPING['TAC', '2-2026', 'A']\nREPING['2026-11', 'TAC', '2-2026', 'A']", "Una oferta, completa o en un mes o un día" },
            { "F", "REPACAD y REPDEM: reportes académicos (Coordinación Académica, Director)" },
            { "C", "REPACAD['programa', 'gestion', 'grupo']\nREPACAD['gestion']", "REPACAD['TAC', '2-2026', 'A']\nREPACAD['2-2026']",
                    "Inscritos, aprobados, reprobados y abandonos por oferta y por módulo" },
            { "C", "REPDEM\nREPDEM['programa', 'gestion', 'grupo']", "REPDEM\nREPDEM['TAC', '2-2026', 'A']",
                    "Preinscritos frente al cupo mínimo, para confirmar o postergar" },
            { "F", "REPNOTAS: notas procesadas (CPD, Director)" },
            { "C", "REPNOTAS['programa', 'gestion', 'grupo', 'modulo']", "REPNOTAS['TAC', '2-2026', 'A', 'CONT-1']",
                    "Actas, notas finales y enmiendas del módulo" },
            { "F", "REPCERT: certificados emitidos (Director)" },
            { "C", "REPCERT['gestion', 'semestre']\nREPCERT['semestre-gestion']", "REPCERT['2026', '2']\nREPCERT['2-2026']",
                    "Semestre 1: enero a junio; 2: julio a diciembre" },
            { "F", "REPLOG: bitácora (solo Director)" },
            { "C", "REPLOG['fecha_inicio', 'fecha_fin']\nREPLOG['fecha']", "REPLOG['2026-11-01', '2026-11-09']\nREPLOG['2026-11-09']",
                    "Acciones del LOG por usuario, tabla y fecha" },
    };

    public static String manualAyudaHTML() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Arial,sans-serif;font-size:13px;color:#222;'>")
                .append("<h2 style='color:#0d47a1;margin-bottom:6px;'>📖 Manual de Comandos vía Email - Sistema ICAP (Grupo 15 SC)</h2>")
                .append("<p style='margin-top:0;'>Escriba el comando en el <b>Asunto</b> del correo, con cada valor entre comillas y separado por comas: ")
                .append("<code>COMANDO[\"valor1\", \"valor2\"]</code>. Da igual escribirlo en mayúsculas o en minúsculas. ")
                .append("Si a un comando le faltan valores, el sistema le responde con los campos que lleva.</p>")
                .append("<p style='margin-bottom:4px;'><b>Cómo se indica cada registro</b> (no se usan códigos internos):</p>")
                .append(tablaInicio()).append("<tr><th style='").append(CABECERA).append("width:30%;'>Registro</th><th style='").append(CABECERA)
                .append("width:35%;'>Campos</th><th style='").append(CABECERA).append("'>Ejemplo</th></tr>");
        for (String[] c : CLAVES) {
            sb.append("<tr><td style='").append(BORDE).append("'>").append(c[0]).append("</td><td style='").append(BORDE).append("'>")
                    .append(codigos(c[1])).append("</td><td style='").append(BORDE).append("'>").append(codigos(c[2])).append("</td></tr>");
        }
        sb.append("</table>");
        boolean tablaAbierta = false;
        for (String[] fila : MANUAL) {
            switch (fila[0]) {
                case "CU" -> {
                    if (tablaAbierta) {
                        sb.append("</table>");
                    }
                    sb.append("<h3 style='color:#0d47a1;margin:22px 0 6px;'>").append(fila[1]).append("</h3>").append(tablaInicio())
                            .append("<tr><th style='").append(CABECERA).append("width:40%;'>Comando y sus campos</th><th style='").append(CABECERA)
                            .append("width:38%;'>Ejemplo</th><th style='").append(CABECERA).append("'>Observaciones</th></tr>");
                    tablaAbierta = true;
                }
                case "F" -> sb.append("<tr><td colspan='3' style='").append(BORDE).append("background:#e8eef7;font-weight:bold;'>")
                        .append(fila[1]).append("</td></tr>");
                case "N" -> sb.append("<tr><td colspan='3' style='").append(BORDE).append("font-size:12px;color:#444;'>")
                        .append(fila[1]).append("</td></tr>");
                default -> sb.append("<tr><td style='").append(BORDE).append("'>").append(codigos(fila[1])).append("</td><td style='").append(BORDE)
                        .append("'>").append(codigos(fila[2])).append("</td><td style='").append(BORDE).append("font-size:12px;color:#444;'>")
                        .append(fila[3]).append("</td></tr>");
            }
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String tablaInicio() {
        return "<table cellpadding='0' cellspacing='0' style='border-collapse:collapse;width:100%;'>";
    }

    // Una línea <code> por cada forma del comando, ya con comillas dobles
    private static String codigos(String formas) {
        StringBuilder sb = new StringBuilder();
        for (String f : formas.split("\n")) {
            if (sb.length() > 0) {
                sb.append("<br>");
            }
            sb.append("<code>").append(escapar(f.replace('\'', '"'))).append("</code>");
        }
        return sb.toString();
    }
}
