package src.utilidades;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// Libro de Excel (.xlsx) de un reporte del CU-08 sin librerías externas: una hoja "Resumen" con las cifras
// destacadas y una hoja por cada tabla. Los montos y cantidades se guardan como números (se pueden sumar y
// filtrar), la cabecera queda fija al desplazarse y trae el filtro automático de Excel
public final class HojaExcel {

    // Estilos de styles.xml
    private static final int TEXTO = 0;
    private static final int NEGRITA = 1;
    private static final int CABECERA = 2;
    private static final int DECIMAL = 3;
    private static final int DECIMAL_NEGRITA = 4;
    private static final int TITULO = 5;
    private static final int ENTERO = 6;
    private static final int ENTERO_NEGRITA = 7;
    private static final int TENUE = 8;

    private HojaExcel() {
    }

    public static byte[] generar(Reporte r) throws IOException {
        List<String> nombres = new ArrayList<>();
        List<String> hojas = new ArrayList<>();
        List<String> filtros = new ArrayList<>();
        Set<String> usados = new HashSet<>();
        nombres.add(nombreUnico("Resumen", usados));
        hojas.add(resumen(r));
        filtros.add(null);
        for (Reporte.Seccion s : r.getSecciones()) {
            nombres.add(nombreUnico(s.titulo(), usados));
            hojas.add(hoja(s, filtros));
        }

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(salida, StandardCharsets.UTF_8)) {
            StringBuilder tipos = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
            for (int i = 1; i <= hojas.size(); i++) {
                tipos.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            escribir(zip, "[Content_Types].xml", tipos.append("</Types>").toString());
            escribir(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "</Relationships>");
            escribir(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + xml(r.getTitulo() + " - " + r.getAlcance())
                    + "</dc:title><dc:creator>Sistema de Gestión Académica ICAP - Grupo 15 SC</dc:creator></cp:coreProperties>");

            StringBuilder libro = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder relaciones = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 1; i <= hojas.size(); i++) {
                libro.append("<sheet name=\"").append(xml(nombres.get(i - 1))).append("\" sheetId=\"").append(i).append("\" r:id=\"rId")
                        .append(i).append("\"/>");
                relaciones.append("<Relationship Id=\"rId").append(i)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i).append(".xml\"/>");
                escribir(zip, "xl/worksheets/sheet" + i + ".xml", hojas.get(i - 1));
            }
            relaciones.append("<Relationship Id=\"rId").append(hojas.size() + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            // El filtro automático de cada hoja se declara también en el libro, como lo guarda Excel
            libro.append("</sheets><definedNames>");
            for (int i = 0; i < hojas.size(); i++) {
                if (filtros.get(i) != null) {
                    String[] rango = filtros.get(i).split(":");
                    libro.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i).append("\" hidden=\"1\">'")
                            .append(xml(nombres.get(i).replace("'", "''"))).append("'!").append(absoluta(rango[0])).append(':')
                            .append(absoluta(rango[1])).append("</definedName>");
                }
            }
            escribir(zip, "xl/workbook.xml", libro.append("</definedNames></workbook>").toString().replace("<definedNames></definedNames>", ""));
            escribir(zip, "xl/_rels/workbook.xml.rels", relaciones.append("</Relationships>").toString());
            escribir(zip, "xl/styles.xml", ESTILOS);
        }
        return salida.toByteArray();
    }

    // Hoja de portada: título, alcance, cifras destacadas y notas
    private static String resumen(Reporte r) {
        List<String> filas = new ArrayList<>();
        filas.add(fila(1, celdaTexto("A", 1, r.getTitulo() + " (" + r.getComando() + ")", TITULO)));
        filas.add(fila(2, celdaTexto("A", 2, r.getAlcance(), TEXTO)));
        filas.add(fila(3, celdaTexto("A", 3, "Generado el " + r.getGenerado()
                + (r.getGeneradoPor().isEmpty() ? "" : " por " + r.getGeneradoPor()), TENUE)));
        int n = 5;
        if (!r.getIndicadores().isEmpty()) {
            filas.add(fila(n, celdaTexto("A", n, "INDICADOR", CABECERA) + celdaTexto("B", n, "VALOR", CABECERA)
                    + celdaTexto("C", n, "DETALLE", CABECERA)));
            n++;
            for (Reporte.Indicador ind : r.getIndicadores()) {
                filas.add(fila(n, celdaTexto("A", n, ind.etiqueta(), NEGRITA) + celdaTexto("B", n, ind.valor(), TEXTO)
                        + celdaTexto("C", n, ind.detalle() == null ? "" : ind.detalle(), TEXTO)));
                n++;
            }
            n++;
        }
        for (String nota : r.getNotas()) {
            filas.add(fila(n, celdaTexto("A", n, "• " + nota, TENUE)));
            n++;
        }
        return hojaXml(new double[] { 34, 22, 60 }, filas, 0, null);
    }

    // Hoja de una tabla: título en la fila 1, cabecera en la 2 y los datos desde la 3
    private static String hoja(Reporte.Seccion s, List<String> filtros) {
        Tabla t = s.tabla();
        int columnas = t.getCabeceras().size();
        double[] anchos = new double[columnas];
        boolean[] decimales = new boolean[columnas];
        StringBuilder cabecera = new StringBuilder();
        for (int c = 0; c < columnas; c++) {
            String nombre = t.getCabeceras().get(c).toUpperCase().replace('_', ' ');
            anchos[c] = Math.max(8, nombre.length() + 3);
            cabecera.append(celdaTexto(columna(c), 2, nombre, CABECERA));
        }
        for (String[] f : t.getFilas()) {
            for (int c = 0; c < columnas; c++) {
                String v = f[c] == null ? "-" : f[c];
                anchos[c] = Math.min(60, Math.max(anchos[c], v.length() + 2));
                decimales[c] |= t.esNumerica(c) && v.contains(".");
            }
        }
        List<String> filas = new ArrayList<>();
        filas.add(fila(1, celdaTexto("A", 1, s.titulo(), TITULO)));
        filas.add(fila(2, cabecera.toString()));
        int n = 3;
        for (String[] f : t.getFilas()) {
            boolean total = f.length > 0 && "TOTAL".equals(f[0]);
            StringBuilder celdas = new StringBuilder();
            for (int c = 0; c < columnas; c++) {
                String v = f[c];
                if (v == null) {
                    celdas.append(celdaTexto(columna(c), n, "-", total ? NEGRITA : TEXTO));
                } else if (t.esNumerica(c) && v.matches("-?\\d+(\\.\\d+)?")) {
                    int estilo = decimales[c] ? (total ? DECIMAL_NEGRITA : DECIMAL) : (total ? ENTERO_NEGRITA : ENTERO);
                    celdas.append("<c r=\"").append(columna(c)).append(n).append("\" s=\"").append(estilo).append("\"><v>").append(v)
                            .append("</v></c>");
                } else if (!v.isEmpty()) {
                    celdas.append(celdaTexto(columna(c), n, v, total ? NEGRITA : TEXTO));
                }
            }
            filas.add(fila(n, celdas.toString()));
            n++;
        }
        if (s.nota() != null && !s.nota().isEmpty()) {
            filas.add(fila(n + 1, celdaTexto("A", n + 1, s.nota(), TENUE)));
        }
        String filtro = columnas > 0 && !t.estaVacia() ? "A2:" + columna(columnas - 1) + (n - 1) : null;
        filtros.add(filtro);
        return hojaXml(anchos, filas, 2, filtro);
    }

    private static String hojaXml(double[] anchos, List<String> filas, int filasFijas, String filtro) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (filasFijas > 0) {
            sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"").append(filasFijas).append("\" topLeftCell=\"A")
                    .append(filasFijas + 1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        }
        sb.append("<cols>");
        for (int c = 0; c < anchos.length; c++) {
            sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(anchos[c])
                    .append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        for (String f : filas) {
            sb.append(f);
        }
        sb.append("</sheetData>");
        if (filtro != null) {
            sb.append("<autoFilter ref=\"").append(filtro).append("\"/>");
        }
        return sb.append("</worksheet>").toString();
    }

    private static String fila(int numero, String celdas) {
        return "<row r=\"" + numero + "\">" + celdas + "</row>";
    }

    private static String celdaTexto(String columna, int fila, String texto, int estilo) {
        return "<c r=\"" + columna + fila + "\" t=\"inlineStr\" s=\"" + estilo + "\"><is><t xml:space=\"preserve\">" + xml(texto)
                + "</t></is></c>";
    }

    // B12 -> $B$12
    private static String absoluta(String celda) {
        return celda.replaceAll("^([A-Z]+)(\\d+)$", "\\$$1\\$$2");
    }

    // A, B, ..., Z, AA, AB...
    private static String columna(int indice) {
        StringBuilder sb = new StringBuilder();
        for (int i = indice + 1; i > 0; i = (i - 1) / 26) {
            sb.insert(0, (char) ('A' + (i - 1) % 26));
        }
        return sb.toString();
    }

    // Excel exige nombres de hoja de hasta 31 caracteres, sin : \ / ? * [ ] y sin repetir
    private static String nombreUnico(String titulo, Set<String> usados) {
        String base = titulo.replaceAll("[:\\\\/?*\\[\\]]", " ").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) {
            base = "Hoja";
        }
        base = base.substring(0, 1).toUpperCase() + base.substring(1);
        if (base.length() > 28) {
            base = base.substring(0, 28).trim();
        }
        String nombre = base;
        for (int i = 2; usados.contains(nombre.toLowerCase()); i++) {
            nombre = base + " " + i;
        }
        usados.add(nombre.toLowerCase());
        return nombre;
    }

    private static String xml(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 32 || c == '\t' || c == '\n') {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void escribir(ZipOutputStream zip, String nombre, String contenido) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(contenido.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // Fuentes: 0 normal, 1 negrita, 2 negrita blanca, 3 título, 4 gris. Rellenos: 2 azul de la cabecera.
    // Estilos (cellXfs) en el orden de las constantes de arriba
    private static final String ESTILOS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"5\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"14\"/><color rgb=\"FF1565C0\"/><name val=\"Calibri\"/></font>"
            + "<font><sz val=\"10\"/><color rgb=\"FF6B6A66\"/><name val=\"Calibri\"/></font>"
            + "</fonts>"
            + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1565C0\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"9\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            + "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"4\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"1\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
}
