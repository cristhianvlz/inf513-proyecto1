package src.utilidades;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

// PDF de un reporte del CU-08 en hoja carta horizontal: encabezado institucional, cifras destacadas, gráficos y las
// tablas paginadas (la cabecera de una tabla se repite en cada página). Usa Helvetica, que todo lector de PDF trae,
// para que el archivo pese poco
public final class ReportePDF {

    private static final double ANCHO = 792;
    private static final double ALTO = 612;
    private static final double MARGEN = 36;
    private static final double PIE = 30;
    private static final double UTIL = ANCHO - 2 * MARGEN;
    private static final double SEPARACION = 18;

    private static final String TINTA = "#1f2328";
    private static final String SECUNDARIA = "#52514e";
    private static final String TENUE = "#898781";
    private static final String REJILLA = "#e1e0d9";
    private static final String EJE = "#c3c2b7";
    private static final String AZUL_ICAP = "#1565c0";
    private static final String FONDO = "#f4f6f9";
    private static final String CEBRA = "#f7f8fa";
    private static final String FILA_TOTAL = "#e3ecf8";

    private static BufferedImage logo;
    private static boolean logoCargado;

    private final Reporte r;
    private final DocumentoPDF doc = new DocumentoPDF(ANCHO, ALTO);
    private final int normal;
    private final int negrita;
    private double y;

    private ReportePDF(Reporte r) {
        this.r = r;
        normal = doc.fuente(null, false);
        negrita = doc.fuente(null, true);
    }

    public static byte[] generar(Reporte r) throws IOException {
        return new ReportePDF(r).dibujar();
    }

    private byte[] dibujar() throws IOException {
        encabezado();
        indicadores();
        graficos();
        for (Reporte.Seccion s : r.getSecciones()) {
            tabla(s);
        }
        notas();
        int total = doc.getPaginas();
        for (int i = 0; i < total; i++) {
            doc.irAPagina(i);
            pie(i + 1, total);
        }
        return doc.generar(r.getTitulo() + " - " + r.getAlcance());
    }

    // ---------------------------------------------------------------------
    // Encabezado, pie y saltos de página
    // ---------------------------------------------------------------------

    private void encabezado() {
        double tope = ALTO - MARGEN;
        BufferedImage img = logo();
        double tx = MARGEN;
        if (img != null) {
            doc.imagen(img, MARGEN, tope - 46, 36, 46, true);
            tx += 46;
        }
        doc.color(TINTA);
        doc.texto(negrita, 9, tx, tope - 11, "UNIVERSIDAD AUTÓNOMA GABRIEL RENÉ MORENO");
        doc.color(SECUNDARIA);
        doc.texto(normal, 8, tx, tope - 23, "FACULTAD POLITÉCNICA · ICAP");
        doc.color(TENUE);
        doc.texto(normal, 7.5, tx, tope - 34, "Sistema de Gestión Académica por correo electrónico (Grupo 15 SC)");

        // Comando que generó el reporte, como una etiqueta a la derecha
        double ancho = doc.ancho(negrita, 9, r.getComando()) + 16;
        doc.color(AZUL_ICAP);
        doc.rectanguloRedondeado(ANCHO - MARGEN - ancho, tope - 18, ancho, 16, 3, 3, 3, 3);
        doc.color("#ffffff");
        doc.textoCentrado(negrita, 9, ANCHO - MARGEN - ancho / 2, tope - 13.2, r.getComando());
        doc.color(TENUE);
        doc.textoDerecha(normal, 7.5, ANCHO - MARGEN, tope - 30, "Generado el " + r.getGenerado());

        double tamano = 17;
        while (tamano > 11 && doc.ancho(negrita, tamano, r.getTitulo()) > UTIL) {
            tamano -= 0.5;
        }
        y = tope - 70;
        doc.color(TINTA);
        doc.texto(negrita, tamano, MARGEN, y, r.getTitulo());
        y -= 15;
        doc.color(SECUNDARIA);
        doc.texto(normal, 10, MARGEN, y, cortar(normal, 10, r.getAlcance(), UTIL));
        if (!r.getGeneradoPor().isEmpty()) {
            y -= 12;
            doc.color(TENUE);
            doc.texto(normal, 8, MARGEN, y, "Solicitado por " + r.getGeneradoPor());
        }
        y -= 9;
        doc.color(EJE);
        doc.grosor(0.6);
        doc.linea(MARGEN, y, ANCHO - MARGEN, y);
        y -= 14;
    }

    private void encabezadoDeContinuacion() {
        double tope = ALTO - MARGEN;
        doc.color(TINTA);
        doc.texto(negrita, 8.5, MARGEN, tope - 9, cortar(negrita, 8.5, "ICAP · " + r.getTitulo(), UTIL * 0.55));
        doc.color(TENUE);
        doc.textoDerecha(normal, 8, ANCHO - MARGEN, tope - 9, cortar(normal, 8, r.getComando() + " · " + r.getAlcance(), UTIL * 0.43));
        doc.color(EJE);
        doc.grosor(0.6);
        doc.linea(MARGEN, tope - 15, ANCHO - MARGEN, tope - 15);
        y = tope - 30;
    }

    private void pie(int pagina, int total) {
        doc.color(EJE);
        doc.grosor(0.5);
        doc.linea(MARGEN, MARGEN - 4, ANCHO - MARGEN, MARGEN - 4);
        doc.color(TENUE);
        doc.texto(normal, 7, MARGEN, MARGEN - 14, "Sistema de Gestión Académica ICAP - UAGRM (Grupo 15 SC) · " + r.getComando() + " · generado el "
                + r.getGenerado());
        doc.textoDerecha(normal, 7, ANCHO - MARGEN, MARGEN - 14, "Página " + pagina + " de " + total);
    }

    private double fondo() {
        return MARGEN + PIE - 14;
    }

    // Salta de página si lo que sigue no entra
    private boolean asegurar(double alto) {
        if (y - alto < fondo()) {
            doc.nuevaPagina();
            encabezadoDeContinuacion();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Cifras destacadas
    // ---------------------------------------------------------------------

    private void indicadores() {
        List<Reporte.Indicador> lista = r.getIndicadores();
        int porFila = lista.size() <= 6 ? Math.max(lista.size(), 4) : 5;
        for (int inicio = 0; inicio < lista.size(); inicio += porFila) {
            int n = Math.min(porFila, lista.size() - inicio);
            double ancho = (UTIL - (porFila - 1) * 8) / porFila;
            double alto = 50;
            asegurar(alto + 8);
            for (int i = 0; i < n; i++) {
                Reporte.Indicador ind = lista.get(inicio + i);
                double x = MARGEN + i * (ancho + 8);
                doc.color(FONDO);
                doc.rectanguloRedondeado(x, y - alto, ancho, alto, 4, 4, 4, 4);
                doc.color(SECUNDARIA);
                doc.texto(normal, 7.5, x + 9, y - 13, cortar(normal, 7.5, ind.etiqueta(), ancho - 18));
                double tamano = 15;
                while (tamano > 8 && doc.ancho(negrita, tamano, ind.valor()) > ancho - 18) {
                    tamano -= 0.5;
                }
                doc.color(TINTA);
                doc.texto(negrita, tamano, x + 9, y - 31, ind.valor());
                if (ind.detalle() != null && !ind.detalle().isEmpty()) {
                    doc.color(TENUE);
                    doc.texto(normal, 6.8, x + 9, y - 43, cortar(normal, 6.8, ind.detalle(), ancho - 18));
                }
            }
            y -= alto + 8;
        }
        if (!lista.isEmpty()) {
            y -= 6;
        }
    }

    // ---------------------------------------------------------------------
    // Gráficos: de a dos por fila; una serie en el tiempo con muchas columnas ocupa todo el ancho
    // ---------------------------------------------------------------------

    private void graficos() {
        List<Grafico> lista = r.getGraficos();
        double medio = (UTIL - SEPARACION) / 2;
        int i = 0;
        while (i < lista.size()) {
            Grafico a = lista.get(i);
            if (anchoCompleto(a) || i + 1 >= lista.size() || anchoCompleto(lista.get(i + 1))) {
                double w = anchoCompleto(a) ? UTIL : medio;
                double h = alto(a, w);
                asegurar(h + 10);
                dibujar(a, MARGEN, y, w, h);
                y -= h + 16;
                i++;
            } else {
                Grafico b = lista.get(i + 1);
                double h = Math.max(alto(a, medio), alto(b, medio));
                asegurar(h + 10);
                dibujar(a, MARGEN, y, medio, h);
                dibujar(b, MARGEN + medio + SEPARACION, y, medio, h);
                y -= h + 16;
                i += 2;
            }
        }
    }

    private static boolean anchoCompleto(Grafico g) {
        return g.getTipo() == Grafico.Tipo.COLUMNAS && g.getCategorias().size() > 12;
    }

    private static double cabecera(Grafico g) {
        return 18 + (g.muestraLeyenda() ? 13 : 0);
    }

    private static double fila(Grafico g) {
        return g.getSeries().size() > 1 && !g.esApilado() ? g.getSeries().size() * 7 + (g.getSeries().size() - 1) + 7 : 16;
    }

    // Cuántas barras entran como máximo; las demás se resumen en una nota (el detalle completo está en la tabla)
    private static int visibles(Grafico g) {
        return Math.min(g.getCategorias().size(), (int) Math.floor(380 / fila(g)));
    }

    private double alto(Grafico g, double w) {
        if (g.getTipo() == Grafico.Tipo.COLUMNAS) {
            return cabecera(g) + 175;
        }
        boolean recortado = visibles(g) < g.getCategorias().size();
        return cabecera(g) + visibles(g) * fila(g) + 14 + (recortado ? 10 : 0);
    }

    private void dibujar(Grafico g, double x, double tope, double w, double h) {
        doc.color(TINTA);
        doc.texto(negrita, 9.5, x, tope - 10, cortar(negrita, 9.5, g.getTitulo(), w));
        double yy = tope - 18;
        if (g.muestraLeyenda()) {
            leyenda(g, x, yy - 8, w);
            yy -= 13;
        }
        if (g.getTipo() == Grafico.Tipo.COLUMNAS) {
            columnas(g, x, yy, w, tope - h);
        } else {
            barras(g, x, yy, w);
        }
    }

    private void leyenda(Grafico g, double x, double base, double w) {
        double lx = x;
        for (int s = 0; s < g.getSeries().size(); s++) {
            String nombre = g.getSeries().get(s);
            double largo = 10 + doc.ancho(normal, 7.5, nombre) + 12;
            if (lx + largo > x + w && lx > x) {
                break;
            }
            doc.color(g.getColores().get(s));
            doc.rectanguloRedondeado(lx, base, 7, 7, 1.5, 1.5, 1.5, 1.5);
            doc.color(SECUNDARIA);
            doc.texto(normal, 7.5, lx + 10, base + 0.8, nombre);
            lx += largo;
        }
    }

    // Barras horizontales con el valor en la punta
    private void barras(Grafico g, double x, double tope, double w) {
        int n = visibles(g);
        double fila = fila(g);
        double anchoEtiquetas = 0;
        String[] textos = new String[n];
        double anchoValores = 0;
        for (int c = 0; c < n; c++) {
            anchoEtiquetas = Math.max(anchoEtiquetas, doc.ancho(normal, 7.5, g.getCategorias().get(c)));
            if (g.getSeries().size() == 1 || g.esApilado()) {
                textos[c] = g.formato(g.total(c)) + (g.conPorcentajes() ? "  (" + g.porcentajeDe(c) + ")" : "");
                anchoValores = Math.max(anchoValores, doc.ancho(normal, 7, textos[c]));
            } else {
                for (int s = 0; s < g.getSeries().size(); s++) {
                    anchoValores = Math.max(anchoValores, doc.ancho(normal, 6.5, g.formato(g.valor(c, s))));
                }
            }
        }
        anchoEtiquetas = Math.min(anchoEtiquetas, w * 0.36);
        double x0 = x + anchoEtiquetas + 6;
        double ancho = w - anchoEtiquetas - 6 - anchoValores - 6;
        double[] escala = g.escala();
        double maximo = escala[0];
        double arriba = tope;
        double abajo = tope - n * fila;

        doc.grosor(0.5);
        for (double v = 0; v <= maximo + 1e-9; v += escala[1]) {
            double gx = x0 + ancho * v / maximo;
            doc.color(REJILLA);
            doc.linea(gx, arriba, gx, abajo);
            doc.color(TENUE);
            doc.textoCentrado(normal, 6.5, gx, abajo - 8, g.marca(v));
        }
        doc.color(EJE);
        doc.grosor(0.8);
        doc.linea(x0, arriba, x0, abajo);

        for (int c = 0; c < n; c++) {
            double centro = arriba - c * fila - fila / 2;
            doc.color(SECUNDARIA);
            doc.textoDerecha(normal, 7.5, x0 - 6, centro - 2.6, cortar(normal, 7.5, g.getCategorias().get(c), anchoEtiquetas));
            if (g.getSeries().size() > 1 && !g.esApilado()) {
                int series = g.getSeries().size();
                double grosor = 7;
                double inicio = centro + (series * grosor + (series - 1)) / 2;
                for (int s = 0; s < series; s++) {
                    double by = inicio - (s + 1) * grosor - s;
                    double v = g.valor(c, s);
                    double bw = ancho * v / maximo;
                    if (bw > 0) {
                        doc.color(g.getColores().get(s));
                        doc.rectanguloRedondeado(x0, by, bw, grosor, 0, 2, 2, 0);
                    }
                    doc.color(SECUNDARIA);
                    doc.texto(normal, 6.5, x0 + bw + 3, by + 1.4, g.formato(v));
                }
            } else {
                double grosor = 9;
                double by = centro - grosor / 2;
                int ultima = -1;
                for (int s = 0; s < g.getSeries().size(); s++) {
                    if (g.valor(c, s) > 0) {
                        ultima = s;
                    }
                }
                double acumulado = 0;
                for (int s = 0; s <= ultima; s++) {
                    double v = g.valor(c, s);
                    if (v <= 0) {
                        continue;
                    }
                    double sx = x0 + ancho * acumulado / maximo;
                    double sw = ancho * v / maximo;
                    acumulado += v;
                    double radio = s == ultima ? 2.5 : 0;
                    doc.color(g.getColores().get(s));
                    doc.rectanguloRedondeado(sx, by, s == ultima ? sw : Math.max(sw - 1.5, 0.6), grosor, 0, radio, radio, 0);
                }
                doc.color(SECUNDARIA);
                doc.texto(normal, 7, x0 + ancho * g.total(c) / maximo + 4, centro - 2.5, textos[c]);
            }
        }
        if (g.getReferencia() != null) {
            referencia(g, x0 + ancho * g.getReferencia() / maximo, arriba, abajo, true);
        }
        if (n < g.getCategorias().size()) {
            doc.color(TENUE);
            doc.texto(normal, 6.8, x, abajo - 18, "... y " + (g.getCategorias().size() - n) + " más (el detalle completo está en la tabla)");
        }
    }

    // Columnas verticales (evolución por mes o por día) con el eje de valores a la izquierda
    private void columnas(Grafico g, double x, double tope, double w, double piso) {
        double[] escala = g.escala();
        double maximo = escala[0];
        double anchoMarcas = 0;
        for (double v = 0; v <= maximo + 1e-9; v += escala[1]) {
            anchoMarcas = Math.max(anchoMarcas, doc.ancho(normal, 6.5, g.marca(v)));
        }
        double x0 = x + anchoMarcas + 5;
        double ancho = x + w - x0;
        double abajo = piso + 14;
        double arriba = tope - 10;
        double alto = arriba - abajo;

        doc.grosor(0.5);
        for (double v = 0; v <= maximo + 1e-9; v += escala[1]) {
            double gy = abajo + alto * v / maximo;
            doc.color(REJILLA);
            doc.linea(x0, gy, x + w, gy);
            doc.color(TENUE);
            doc.textoDerecha(normal, 6.5, x0 - 4, gy - 2.2, g.marca(v));
        }
        doc.color(EJE);
        doc.grosor(0.8);
        doc.linea(x0, abajo, x + w, abajo);

        int n = g.getCategorias().size();
        double banda = ancho / n;
        double columna = Math.min(banda * 0.62, 20);
        double etiquetaMaxima = 0;
        int mayor = 0;
        for (int c = 0; c < n; c++) {
            etiquetaMaxima = Math.max(etiquetaMaxima, doc.ancho(normal, 6.5, g.getCategorias().get(c)));
            if (g.total(c) > g.total(mayor)) {
                mayor = c;
            }
        }
        int cada = Math.max(1, (int) Math.ceil((etiquetaMaxima + 4) / banda));
        for (int c = 0; c < n; c++) {
            double cx = x0 + banda * (c + 0.5);
            if (g.getSeries().size() > 1 && !g.esApilado()) {
                int series = g.getSeries().size();
                double sub = columna / series;
                for (int s = 0; s < series; s++) {
                    double sh = alto * g.valor(c, s) / maximo;
                    if (sh > 0) {
                        doc.color(g.getColores().get(s));
                        doc.rectanguloRedondeado(cx - columna / 2 + s * sub, abajo, Math.max(sub - 1, 0.6), sh, 0, 0, 2, 2);
                    }
                }
            } else {
                int ultima = -1;
                for (int s = 0; s < g.getSeries().size(); s++) {
                    if (g.valor(c, s) > 0) {
                        ultima = s;
                    }
                }
                double acumulado = 0;
                for (int s = 0; s <= ultima; s++) {
                    double v = g.valor(c, s);
                    if (v <= 0) {
                        continue;
                    }
                    double sy = abajo + alto * acumulado / maximo;
                    double sh = alto * v / maximo;
                    acumulado += v;
                    double radio = s == ultima ? 2.5 : 0;
                    doc.color(g.getColores().get(s));
                    doc.rectanguloRedondeado(cx - columna / 2, sy, columna, s == ultima ? sh : Math.max(sh - 1.5, 0.6), 0, 0, radio, radio);
                }
            }
            // El valor va sobre la columna si entra en su espacio; si no, solo en la más alta
            String valor = g.formato(g.total(c));
            if (g.total(c) > 0 && (doc.ancho(normal, 6.3, valor) <= banda - 2 || c == mayor)) {
                doc.color(SECUNDARIA);
                doc.textoCentrado(normal, 6.3, cx, abajo + alto * g.total(c) / maximo + 3, valor);
            }
            if (c % cada == 0) {
                doc.color(SECUNDARIA);
                doc.textoCentrado(normal, 6.5, cx, abajo - 9, g.getCategorias().get(c));
            }
        }
        if (g.getReferencia() != null) {
            double gy = abajo + alto * g.getReferencia() / maximo;
            doc.color(TINTA);
            doc.grosor(0.9);
            doc.linea(x0, gy, x + w, gy);
            doc.color(SECUNDARIA);
            doc.textoDerecha(normal, 6.5, x + w, gy + 2.5, g.getEtiquetaReferencia());
        }
    }

    private void referencia(Grafico g, double gx, double arriba, double abajo, boolean vertical) {
        doc.color(TINTA);
        doc.grosor(0.9);
        doc.linea(gx, arriba + 3, gx, abajo);
        doc.color(SECUNDARIA);
        doc.texto(normal, 6.5, gx + 3, arriba + 1, g.getEtiquetaReferencia());
    }

    // ---------------------------------------------------------------------
    // Tablas
    // ---------------------------------------------------------------------

    private static final double LETRA = 7.5;
    private static final double LETRA_CABECERA = 6.8;
    private static final double INTERLINEA = 9;

    private void tabla(Reporte.Seccion s) {
        Tabla t = s.tabla();
        int columnas = t.getCabeceras().size();
        String[] cabeceras = new String[columnas];
        double[] natural = new double[columnas];
        for (int c = 0; c < columnas; c++) {
            cabeceras[c] = t.getCabeceras().get(c).toUpperCase().replace('_', ' ');
            natural[c] = doc.ancho(negrita, LETRA_CABECERA, cabeceras[c]) + 10;
        }
        for (String[] f : t.getFilas()) {
            boolean total = esTotal(f);
            for (int c = 0; c < columnas; c++) {
                natural[c] = Math.max(natural[c], doc.ancho(total ? negrita : normal, LETRA, texto(f[c])) + 10);
            }
        }
        double[] w = anchos(natural);

        double altoCabecera = altoFila(cabeceras, w, negrita, LETRA_CABECERA);
        double primera = t.estaVacia() ? 12 : altoFila(t.getFilas().get(0), w, normal, LETRA);
        // Una tabla que entra entera en una página no se parte: si no cabe en lo que queda, empieza en la siguiente
        double completa = 21 + altoCabecera;
        for (String[] f : t.getFilas()) {
            completa += altoFila(f, w, esTotal(f) ? negrita : normal, LETRA);
        }
        double pagina = ALTO - MARGEN - 30 - fondo();
        asegurar(completa <= pagina ? completa : 20 + altoCabecera + primera);
        y -= 4;
        doc.color(TINTA);
        doc.texto(negrita, 10.5, MARGEN, y - 9, cortar(negrita, 10.5, s.titulo(), UTIL));
        y -= 17;
        boolean[] numericas = new boolean[columnas];
        for (int c = 0; c < columnas; c++) {
            numericas[c] = t.esNumerica(c);
        }
        cabecera(cabeceras, w, altoCabecera, numericas);
        for (int i = 0; i < t.getFilas().size(); i++) {
            String[] f = t.getFilas().get(i);
            boolean total = esTotal(f);
            int fuente = total ? negrita : normal;
            double alto = altoFila(f, w, fuente, LETRA);
            if (asegurar(alto)) {
                cabecera(cabeceras, w, altoCabecera, numericas);
            }
            double anchoTabla = suma(w);
            if (total || i % 2 == 1) {
                doc.color(total ? FILA_TOTAL : CEBRA);
                doc.rectanguloRelleno(MARGEN, y - alto, anchoTabla, alto);
            }
            doc.color(TINTA);
            double cx = MARGEN;
            for (int c = 0; c < columnas; c++) {
                List<String> lineas = lineas(fuente, LETRA, texto(f[c]), w[c] - 8);
                for (int l = 0; l < lineas.size(); l++) {
                    double base = y - 4 - 5.6 - l * INTERLINEA;
                    if (t.esNumerica(c) && !(total && c == 0)) {
                        doc.textoDerecha(fuente, LETRA, cx + w[c] - 4, base, lineas.get(l));
                    } else {
                        doc.texto(fuente, LETRA, cx + 4, base, lineas.get(l));
                    }
                }
                cx += w[c];
            }
            y -= alto;
            if (total) {
                doc.color(EJE);
                doc.grosor(0.6);
                doc.linea(MARGEN, y + alto, MARGEN + anchoTabla, y + alto);
            }
        }
        doc.color(EJE);
        doc.grosor(0.6);
        doc.linea(MARGEN, y, MARGEN + suma(w), y);
        if (t.estaVacia()) {
            doc.color(TENUE);
            doc.texto(normal, 7.5, MARGEN + 4, y - 10, "Sin registros para el criterio solicitado.");
            y -= 12;
        }
        if (s.nota() != null && !s.nota().isEmpty()) {
            doc.color(TENUE);
            y = doc.parrafo(normal, 7, MARGEN, y - 10, UTIL, 9, false, s.nota()) - 2;
        }
        y -= 14;
    }

    // Los títulos de las columnas numéricas van a la derecha, como sus valores
    private void cabecera(String[] cabeceras, double[] w, double alto, boolean[] numericas) {
        doc.color(AZUL_ICAP);
        doc.rectanguloRelleno(MARGEN, y - alto, suma(w), alto);
        doc.color("#ffffff");
        double cx = MARGEN;
        for (int c = 0; c < cabeceras.length; c++) {
            List<String> lineas = lineas(negrita, LETRA_CABECERA, cabeceras[c], w[c] - 8);
            for (int l = 0; l < lineas.size(); l++) {
                double base = y - 4 - 5.2 - l * INTERLINEA;
                if (numericas[c]) {
                    doc.textoDerecha(negrita, LETRA_CABECERA, cx + w[c] - 4, base, lineas.get(l));
                } else {
                    doc.texto(negrita, LETRA_CABECERA, cx + 4, base, lineas.get(l));
                }
            }
            cx += w[c];
        }
        y -= alto;
    }

    // Anchos de columna: los naturales si entran (estirados para ocupar la hoja si la tabla es ancha); si no entran,
    // las columnas cortas conservan su ancho y las largas se reparten el resto y parten su texto en líneas
    private static double[] anchos(double[] natural) {
        int n = natural.length;
        double[] w = new double[n];
        double total = suma(natural);
        if (total <= UTIL) {
            double factor = total < UTIL * 0.55 ? Math.min(1.25, UTIL / total) : UTIL / total;
            for (int c = 0; c < n; c++) {
                w[c] = natural[c] * factor;
            }
            return w;
        }
        boolean[] fijo = new boolean[n];
        double restante = UTIL;
        int libres = n;
        boolean cambio = true;
        while (cambio && libres > 0) {
            cambio = false;
            double justo = restante / libres;
            for (int c = 0; c < n; c++) {
                if (!fijo[c] && natural[c] <= justo) {
                    w[c] = natural[c];
                    fijo[c] = true;
                    restante -= natural[c];
                    libres--;
                    cambio = true;
                }
            }
        }
        for (int c = 0; c < n; c++) {
            if (!fijo[c]) {
                w[c] = restante / libres;
            }
        }
        return w;
    }

    private double altoFila(String[] celdas, double[] w, int fuente, double tamano) {
        int maximo = 1;
        for (int c = 0; c < celdas.length; c++) {
            maximo = Math.max(maximo, lineas(fuente, tamano, texto(celdas[c]), w[c] - 8).size());
        }
        return maximo * INTERLINEA + 5;
    }

    // Texto partido en líneas que entran en el ancho (una palabra demasiado larga se recorta)
    private List<String> lineas(int fuente, double tamano, String s, double ancho) {
        List<String> resultado = new ArrayList<>();
        for (String linea : doc.partir(fuente, tamano, s.isEmpty() ? " " : s, ancho)) {
            resultado.add(cortar(fuente, tamano, linea, ancho));
        }
        return resultado;
    }

    private static boolean esTotal(String[] f) {
        return f.length > 0 && "TOTAL".equals(f[0]);
    }

    private static String texto(String s) {
        return s == null ? "-" : s;
    }

    private static double suma(double[] v) {
        double t = 0;
        for (double x : v) {
            t += x;
        }
        return t;
    }

    // ---------------------------------------------------------------------
    // Notas finales
    // ---------------------------------------------------------------------

    private void notas() {
        if (r.getNotas().isEmpty()) {
            return;
        }
        asegurar(30);
        doc.color(TINTA);
        doc.texto(negrita, 9, MARGEN, y - 9, "Notas");
        y -= 20;
        doc.color(SECUNDARIA);
        for (String n : r.getNotas()) {
            List<String> lineas = doc.partir(normal, 7.5, n, UTIL - 10);
            asegurar(lineas.size() * 10 + 2);
            doc.texto(normal, 7.5, MARGEN, y, "•");
            y = doc.parrafo(normal, 7.5, MARGEN + 9, y, UTIL - 10, 10, false, n) - 13;
        }
    }

    // ---------------------------------------------------------------------
    // Auxiliares
    // ---------------------------------------------------------------------

    // Recorta el texto con "..." si no entra en el ancho
    private String cortar(int fuente, double tamano, String s, double ancho) {
        if (s == null) {
            return "";
        }
        if (doc.ancho(fuente, tamano, s) <= ancho) {
            return s;
        }
        String r = s;
        while (r.length() > 1 && doc.ancho(fuente, tamano, r + "...") > ancho) {
            r = r.substring(0, r.length() - 1);
        }
        return r.trim() + "...";
    }

    private static synchronized BufferedImage logo() {
        if (!logoCargado) {
            logoCargado = true;
            try {
                logo = ImageIO.read(new File("recursos", "logo_uagrm.png"));
            } catch (IOException e) {
                logo = null;
            }
        }
        return logo;
    }
}
