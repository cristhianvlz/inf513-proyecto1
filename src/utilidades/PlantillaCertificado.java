package src.utilidades;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

// Plantilla institucional del ICAP en tamaño oficio (8,5 x 13 pulgadas), copiada del título oficial de Técnico Auxiliar:
// marco ornamental, logo de la UAGRM a la izquierda y escudo de Bolivia a la derecha, encabezado en letra gótica,
// numeración, código QR único, fotografía, datos del titular, fecha, firmas, sello y pie con el Art. 95 de la CPE.
// Las posiciones y tamaños se midieron sobre el título escaneado (en píxeles) y se convierten a puntos del papel oficio
public final class PlantillaCertificado {

    public static final double ANCHO = 612;
    public static final double ALTO = 936;
    private static final double MARGEN = 14;
    // En el escaneo el marco va de (4; 1,5) a (513; 731,5) píxeles
    private static final double SX = (ANCHO - 2 * MARGEN) / 509.0;
    private static final double SY = (ALTO - 2 * MARGEN) / 730.0;

    private static final String CARPETA_RECURSOS = "recursos";
    private static final String[] MESES = { "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre",
            "Octubre", "Noviembre", "Diciembre" };

    private static final String ART_95 = "Art. 95 CPE establece que las “Universidades deben crear y sostener centros interculturales de "
            + "Capacitación Técnica y Cultural de libre acceso al pueblo”, en concordancia con los principios y fines del sistema educativo.";

    // Fuentes del título oficial; si alguna no está instalada se usa Helvetica
    private static final Map<String, FuenteTrueType> CACHE = new HashMap<>();
    private static final Map<String, BufferedImage> LOGOS = new HashMap<>();

    public static final class Firma {
        public final String nombre;
        public final String cargo;

        public Firma(String nombre, String cargo) {
            this.nombre = nombre;
            this.cargo = cargo;
        }
    }

    // Un bloque del cuerpo de una certificación: un párrafo, un dato con su valor o una tabla
    public static final class Bloque {
        final String texto;
        final String valor;
        final String[] cabeceras;
        final double[] proporciones;
        final List<String[]> filas;

        private Bloque(String texto, String valor, String[] cabeceras, double[] proporciones, List<String[]> filas) {
            this.texto = texto;
            this.valor = valor;
            this.cabeceras = cabeceras;
            this.proporciones = proporciones;
            this.filas = filas;
        }

        public static Bloque parrafo(String texto) {
            return new Bloque(texto, null, null, null, null);
        }

        public static Bloque dato(String etiqueta, String valor) {
            return new Bloque(etiqueta, valor, null, null, null);
        }

        public static Bloque tabla(String[] cabeceras, double[] proporciones, List<String[]> filas) {
            return new Bloque(null, null, cabeceras, proporciones, filas);
        }
    }

    public static final class Datos {
        // Comunes
        public String codigo;
        public String numeroSerie;
        public String contenidoQR;
        public String instruccion;
        public LocalDate fecha;
        public Firma[] firmas;
        public String titular;
        public String ci;
        // Título (diseño del título oficial)
        public String presente;
        public String grado;
        public String mencion;
        public LocalDate nacimiento;
        public String lugarNacimiento;
        public String nota;
        public String habilitacion;
        public String rutaFoto;
        // Certificación o constancia
        public String titulo;
        public String subtitulo;
        public String introduccion;
        public String registro;
        public final List<Bloque> cuerpo = new ArrayList<>();
    }

    private final DocumentoPDF pdf = new DocumentoPDF(ANCHO, ALTO);
    private final int gotica;
    private final int serif;
    private final int serifNegrita;
    private final int texto;
    private final int textoNegrita;
    private final int nombre;
    private final int numero;

    private PlantillaCertificado() {
        gotica = pdf.fuente(fuente("OLDENGL.TTF", 0), true);
        serif = pdf.fuente(fuente("cambria.ttc", 0), false);
        serifNegrita = pdf.fuente(fuente("cambriab.ttf", 0), true);
        texto = pdf.fuente(fuente("corbel.ttf", 0), false);
        textoNegrita = pdf.fuente(fuente("corbelb.ttf", 0), true);
        nombre = pdf.fuente(fuente("calibri.ttf", 0), false);
        numero = pdf.fuente(fuente("OCRAEXT.TTF", 0), true);
    }

    // Píxel del escaneo -> punto del oficio
    private static double x(double px) {
        return MARGEN + (px - 4) * SX;
    }

    private static double y(double px) {
        return ALTO - MARGEN - (px - 1.5) * SY;
    }

    // Tamaño de letra medido en píxeles -> puntos
    private static double t(double px) {
        return px * SX;
    }

    // ---------------------------------------------------------------------
    // Título o certificado final: igual al título oficial
    // ---------------------------------------------------------------------

    public static byte[] titulo(Datos d) throws IOException {
        PlantillaCertificado p = new PlantillaCertificado();
        DocumentoPDF pdf = p.pdf;
        p.marco();
        p.encabezado(d);

        pdf.color(0, 0, 0);
        pdf.parrafo(p.texto, t(10.25), x(41), y(163.5), x(479) - x(41), t(16.5), true,
                "En nombre de la Universidad Autónoma Gabriel René Moreno, de conformidad con lo establecido por el Artículo 92 inciso III "
                        + "de la Constitución Política del Estado y la Resolución ICU 001/89, como público testimonio de su idoneidad, otorga el "
                        + "presente " + d.presente + " de:");
        double tamGrado = p.ajustar(p.serifNegrita, t(42.82), d.grado, x(372) - x(43));
        pdf.texto(p.serifNegrita, tamGrado, x(43), y(254), d.grado);

        // Código QR único y, a su lado, cómo verificarlo
        double lado = x(94) - x(35);
        pdf.qr(CodigoQR.generar(d.contenidoQR), x(35), y(268) - lado, lado);
        pdf.color(0.25, 0.25, 0.25);
        pdf.parrafo(p.textoNegrita, 8.5, x(35) + lado + 12, y(268) - 12, x(372) - x(35) - lado - 12, 11, false, "N° " + d.codigo);
        pdf.parrafo(p.texto, 8, x(35) + lado + 12, y(268) - 25, x(372) - x(35) - lado - 12, 10.5, false, d.instruccion);
        pdf.color(0, 0, 0);
        p.fotografia(d.rutaFoto, x(379), y(308), x(462) - x(379), y(227) - y(308));

        pdf.texto(p.serif, t(12.88), x(43), y(369), "En:");
        p.lineaAjustada(p.serifNegrita, t(35.6), x(89), y(369), x(482) - x(89), d.mencion.toUpperCase());
        pdf.texto(p.serif, t(13.06), x(43), y(398), "Otorgado a:");
        p.lineaAjustada(p.nombre, t(24.18), x(133), y(399), x(482) - x(133), d.titular);

        double tam = t(12.78);
        int s = p.serif;
        int sn = p.serifNegrita;
        LocalDate n = d.nacimiento;
        p.campos(y(450), s, tam, "Nacido en fecha", 43, sn, tam, String.valueOf(n.getDayOfMonth()), 142, s, tam, "de", 171,
                sn, tam, MESES[n.getMonthValue() - 1], 209, s, tam, "de", 263, sn, tam, String.valueOf(n.getYear()), 283,
                s, tam, "en", 330, sn, tam, d.lugarNacimiento, 354);
        if (d.nota != null) {
            // El título de Técnico Medio consigna la calificación; el de Técnico Auxiliar no
            p.campos(y(472), s, tam, "con C.I. N°", 43, sn, tam, d.ci, 120, s, tam, "Promedio general:", 300, sn, tam, d.nota, 396);
        } else {
            p.campos(y(472), s, tam, "con C.I. N°", 43, sn, tam, d.ci, 120);
        }
        pdf.parrafo(p.texto, t(9.8), x(44), y(504), x(479) - x(44), t(16.5), true, d.habilitacion);

        p.fecha(d.fecha);
        p.firma(d.firmas[0], 117);
        p.firma(d.firmas[1], 395);
        p.sello();
        p.pie();
        return pdf.generar(d.presente + " de " + d.grado + " " + d.codigo);
    }

    // ---------------------------------------------------------------------
    // Certificaciones y constancias: el mismo marco, encabezado, fecha, firma, sello y pie
    // ---------------------------------------------------------------------

    public static byte[] certificacion(Datos d) throws IOException {
        // Si el cuerpo no cabe sobre la fecha, se vuelve a armar con letra más chica
        for (double escala = 1; ; escala -= 0.08) {
            PlantillaCertificado p = new PlantillaCertificado();
            if (p.armarCertificacion(d, escala) || escala < 0.7) {
                return p.pdf.generar(d.titulo + " " + d.codigo);
            }
        }
    }

    private boolean armarCertificacion(Datos d, double escala) {
        marco();
        encabezado(d);
        pdf.color(0, 0, 0);
        double tamTitulo = ajustar(serifNegrita, t(30), d.titulo, x(480) - x(41));
        pdf.textoCentrado(serifNegrita, tamTitulo, ANCHO / 2, y(150), d.titulo);
        if (d.subtitulo != null) {
            double tamSub = ajustar(serif, t(12.88), d.subtitulo, x(480) - x(41));
            pdf.textoCentrado(serif, tamSub, ANCHO / 2, y(171), d.subtitulo);
        }
        double tamTexto = t(10.25) * escala;
        double interlineado = t(16.5) * escala;
        double ancho = x(479) - x(41);
        double yy = pdf.parrafo(texto, tamTexto, x(41), y(196), ancho, interlineado, true, d.introduccion);
        yy -= 34 * escala;
        pdf.texto(serif, t(12.88), x(43), yy, "Estudiante:");
        lineaAjustada(nombre, t(22) * escala, x(133), yy, x(482) - x(133), d.titular);
        yy -= 25 * escala;
        double tamDato = t(12.78) * escala;
        campos(yy, serif, tamDato, "con C.I. N°", 43, serifNegrita, tamDato, d.ci, 120, serif, tamDato, "Registro N°", 263,
                serifNegrita, tamDato, d.registro, 333);
        yy -= 32 * escala;
        for (Bloque b : d.cuerpo) {
            if (b.valor != null) {
                // Dato con su valor resaltado (los números de Corbel son antiguos: el valor va en Cambria)
                campos(yy, texto, tamTexto, b.texto, 41, serifNegrita, t(11.2) * escala, b.valor, 0);
                yy -= interlineado + 6 * escala;
            } else if (b.texto != null) {
                yy = pdf.parrafo(texto, tamTexto, x(41), yy, ancho, interlineado, true, b.texto) - interlineado - 6 * escala;
            } else {
                yy = tabla(b, yy + 4 * escala, escala) - 14 * escala;
            }
        }
        fecha(d.fecha);
        // A la izquierda de la firma del Director, el código QR único con su número y cómo verificarlo
        double lado = x(94) - x(35);
        double arribaQR = y(560);
        pdf.qr(CodigoQR.generar(d.contenidoQR), x(43), arribaQR - lado, lado);
        pdf.color(0.25, 0.25, 0.25);
        pdf.texto(textoNegrita, 7.5, x(43), arribaQR - lado - 10, "N° " + d.codigo);
        pdf.parrafo(texto, 7.2, x(43) + lado + 8, arribaQR - 6, 268 - x(43) - lado - 8 - 10, 9, false, d.instruccion);
        pdf.color(0, 0, 0);
        firma(d.firmas[0], 395);
        sello();
        pie();
        return yy > y(550) + 24;
    }

    private double tabla(Bloque b, double yArriba, double escala) {
        double total = 0;
        for (double p : b.proporciones) {
            total += p;
        }
        double izquierda = x(41);
        double ancho = x(479) - x(41);
        double alto = 16 * escala;
        double tam = 10.2 * escala;
        pdf.color(0.90, 0.92, 0.96);
        pdf.rectanguloRelleno(izquierda, yArriba - alto, ancho, alto);
        pdf.color(0, 0, 0);
        double yy = yArriba - alto + 4.6 * escala;
        fila(b.cabeceras, b.proporciones, total, izquierda, ancho, yy, textoNegrita, tam);
        pdf.grosor(0.4);
        for (String[] f : b.filas) {
            yy -= alto;
            fila(f, b.proporciones, total, izquierda, ancho, yy, nombre, tam);
            pdf.color(0.6, 0.6, 0.6);
            pdf.linea(izquierda, yy - 4.6 * escala, izquierda + ancho, yy - 4.6 * escala);
            pdf.color(0, 0, 0);
        }
        return yy - alto;
    }

    private void fila(String[] celdas, double[] proporciones, double total, double izquierda, double ancho, double yy, int f, double tam) {
        double xx = izquierda;
        for (int i = 0; i < proporciones.length; i++) {
            double w = ancho * proporciones[i] / total;
            String c = i < celdas.length && celdas[i] != null ? celdas[i] : "-";
            while (c.length() > 1 && pdf.ancho(f, tam, c) > w - 6) {
                c = c.substring(0, c.length() - 2) + "…";
            }
            pdf.texto(f, tam, xx + 3, yy, c);
            xx += w;
        }
    }

    // ---------------------------------------------------------------------
    // Partes comunes
    // ---------------------------------------------------------------------

    // Línea exterior y cordón ornamental formado por dos ondas opuestas entre dos filetes
    private void marco() {
        pdf.color(0.12, 0.12, 0.12);
        pdf.grosor(1.8);
        pdf.rectangulo(MARGEN, MARGEN, ANCHO - 2 * MARGEN, ALTO - 2 * MARGEN);
        double afuera = MARGEN + 8;
        double adentro = MARGEN + 22;
        pdf.grosor(0.5);
        pdf.rectangulo(afuera, afuera, ANCHO - 2 * afuera, ALTO - 2 * afuera);
        pdf.rectangulo(adentro, adentro, ANCHO - 2 * adentro, ALTO - 2 * adentro);
        double c = (afuera + adentro) / 2;
        double amplitud = (adentro - afuera) / 2 - 1.3;
        double[][] lados = { { c, c, c, ALTO - c }, { c, ALTO - c, ANCHO - c, ALTO - c }, { ANCHO - c, ALTO - c, ANCHO - c, c },
                { ANCHO - c, c, c, c } };
        pdf.grosor(0.6);
        for (double[] l : lados) {
            int medias = (int) Math.round(Math.hypot(l[2] - l[0], l[3] - l[1]) / 4.2);
            pdf.onda(l[0], l[1], l[2], l[3], amplitud, medias, 0);
            pdf.onda(l[0], l[1], l[2], l[3], amplitud, medias, 1);
        }
        pdf.grosor(1);
    }

    // Logos, nombre de la Universidad y del Instituto en letra gótica, y numeración arriba a la derecha
    private void encabezado(Datos d) {
        BufferedImage uagrm = logo("logo_uagrm.png");
        if (uagrm != null) {
            pdf.imagen(uagrm, x(36), y(119), x(109) - x(36), y(36) - y(119), true);
        }
        BufferedImage escudo = logo("escudo_bolivia.png");
        if (escudo != null) {
            pdf.imagen(escudo, x(406), y(118), x(488) - x(406), y(45) - y(118), true);
        }
        pdf.color(0, 0, 0);
        pdf.textoCentrado(gotica, t(15.86), x(254), y(54), "Universidad Autónoma Gabriel René Moreno");
        pdf.textoCentrado(gotica, t(20.91), x(261), y(86), "Instituto de Capacitación Popular");
        if (d.numeroSerie != null) {
            // Numeración del título: dígitos altos y angostos, como los de la numeradora
            double alto = 15 * SY;
            double tam = alto * 1000 / Math.max(500, pdf.alturaMayusculas(numero, 1000));
            double escala = (x(474) - x(414)) / pdf.ancho(numero, tam, d.numeroSerie);
            pdf.textoEscalado(numero, tam, escala, x(414), y(41), d.numeroSerie);
        } else {
            pdf.textoDerecha(textoNegrita, 9.5, x(482), y(38), "N° " + d.codigo);
        }
    }

    private void fotografia(String ruta, double xx, double yy, double w, double h) {
        BufferedImage foto = null;
        if (ruta != null) {
            try {
                foto = ImageIO.read(new File(ruta));
            } catch (IOException e) {
                foto = null;
            }
        }
        if (foto != null) {
            pdf.imagen(foto, xx, yy, w, h, true);
        } else {
            pdf.color(0.55, 0.55, 0.55);
            pdf.grosor(0.6);
            pdf.rectangulo(xx, yy, w, h);
            pdf.textoCentrado(texto, 8, xx + w / 2, yy + h / 2 - 3, "FOTOGRAFÍA");
            pdf.color(0, 0, 0);
        }
    }

    // Santa Cruz de la Sierra   24   de   Junio   de 2025
    private void fecha(LocalDate f) {
        double tam = t(12.78);
        campos(y(550), texto, t(10), "Santa Cruz de la Sierra", 44, serifNegrita, tam, String.valueOf(f.getDayOfMonth()), 157, serif, tam,
                "de", 202, serifNegrita, tam, MESES[f.getMonthValue() - 1], 251, serif, tam, "de", 402, serifNegrita, tam,
                String.valueOf(f.getYear()), 425);
    }

    // Nombre, cargo e "ICAP - UAGRM" centrados en la posición del título oficial
    private void firma(Firma f, double centroPx) {
        double cx = x(centroPx);
        if (f.nombre != null) {
            pdf.textoCentrado(texto, ajustar(texto, t(9.6), f.nombre, 175), cx, y(604), f.nombre);
        }
        pdf.textoCentrado(serifNegrita, t(9.6), cx, y(615), f.cargo);
        pdf.textoCentrado(serifNegrita, t(9.6), cx, y(627), "ICAP - UAGRM");
    }

    // Sello del Instituto entre las dos firmas
    private void sello() {
        double cx = ANCHO / 2;
        double cy = y(598);
        double r = 44;
        pdf.color(0.11, 0.22, 0.52);
        pdf.grosor(1.3);
        pdf.circulo(cx, cy, r);
        pdf.grosor(0.6);
        pdf.circulo(cx, cy, r - 11);
        textoEnArco("INSTITUTO DE CAPACITACIÓN POPULAR", cx, cy, r - 8.3, true, 5.9);
        textoEnArco("SANTA CRUZ - BOLIVIA", cx, cy, r - 2.8, false, 5.9);
        pdf.textoCentrado(serifNegrita, 15, cx, cy - 3, "ICAP");
        pdf.textoCentrado(texto, 5.6, cx, cy - 12, "U.A.G.R.M.");
        pdf.color(0, 0, 0);
        pdf.grosor(1);
    }

    // Texto curvo: arriba se lee de izquierda a derecha en sentido horario; abajo, en sentido antihorario
    private void textoEnArco(String s, double cx, double cy, double radio, boolean arriba, double tam) {
        double total = pdf.ancho(serifNegrita, tam, s) * 1.08;
        double inicio = arriba ? Math.PI / 2 + total / radio / 2 : 3 * Math.PI / 2 - total / radio / 2;
        double recorrido = 0;
        for (char c : s.toCharArray()) {
            String letra = String.valueOf(c);
            double phi = arriba ? inicio - recorrido / radio : inicio + recorrido / radio;
            double giro = arriba ? phi - Math.PI / 2 : phi + Math.PI / 2;
            pdf.textoRotado(serifNegrita, tam, cx + radio * Math.cos(phi), cy + radio * Math.sin(phi), giro, letra);
            recorrido += pdf.ancho(serifNegrita, tam, letra) * 1.08;
        }
    }

    private void pie() {
        pdf.color(0, 0, 0);
        pdf.parrafo(texto, t(10.25), x(41), y(643), x(480) - x(41), t(15.5), true, ART_95);
        pdf.textoCentrado(texto, t(12.75), x(256), y(702), "Santa Cruz - Bolivia");
    }

    // Una línea de texto con partes en distinta letra, cada una en su posición del original o, si no cabe, después de la
    // anterior. partes: fuente, tamaño, texto y posición en píxeles, repetidos
    private void campos(double yy, Object... partes) {
        double fin = 0;
        for (int i = 0; i < partes.length; i += 4) {
            int f = (Integer) partes[i];
            double tam = ((Number) partes[i + 1]).doubleValue();
            String s = (String) partes[i + 2];
            if (s == null || s.isEmpty()) {
                continue;
            }
            double preferida = x(((Number) partes[i + 3]).doubleValue());
            double xx = i == 0 ? preferida : Math.max(preferida, fin + pdf.ancho(f, tam, " ") * 1.5);
            double tamParte = ajustar(f, tam, s, x(486) - xx);
            pdf.texto(f, tamParte, xx, yy, s);
            fin = xx + pdf.ancho(f, tamParte, s);
        }
    }

    // Una línea que se achica hasta caber en el ancho (nombre del titular, mención)
    private void lineaAjustada(int f, double tam, double xx, double yy, double ancho, String s) {
        pdf.texto(f, ajustar(f, tam, s, ancho), xx, yy, s);
    }

    private double ajustar(int f, double tam, String s, double ancho) {
        double t = tam;
        while (t > 6 && pdf.ancho(f, t, s) > ancho) {
            t -= 0.5;
        }
        return t;
    }

    private static FuenteTrueType fuente(String archivo, int indice) {
        String clave = archivo + "#" + indice;
        if (!CACHE.containsKey(clave)) {
            FuenteTrueType f = null;
            String windir = System.getenv("WINDIR");
            Path ruta = Paths.get(windir != null ? windir : "C:\\Windows", "Fonts", archivo);
            try {
                f = FuenteTrueType.cargar(ruta, indice);
            } catch (IOException | RuntimeException e) {
                System.out.println(">>> [PDF]: No se pudo usar la fuente " + archivo + " (" + e.getMessage() + "); se usa Helvetica.");
            }
            CACHE.put(clave, f);
        }
        return CACHE.get(clave);
    }

    private static BufferedImage logo(String archivo) {
        if (!LOGOS.containsKey(archivo)) {
            BufferedImage img = null;
            try {
                img = ImageIO.read(new File(CARPETA_RECURSOS, archivo));
            } catch (IOException e) {
                img = null;
            }
            if (img == null) {
                System.out.println(">>> [PDF]: Falta la imagen " + CARPETA_RECURSOS + "/" + archivo + "; el documento sale sin ese logo.");
            }
            LOGOS.put(archivo, img);
        }
        return LOGOS.get(archivo);
    }
}
