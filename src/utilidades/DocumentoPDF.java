package src.utilidades;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.Deflater;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

// Escribe un PDF sin librerías externas: texto con fuentes TrueType incrustadas (subconjunto) o con
// Helvetica si la fuente no está en el equipo, imágenes (JPEG, con transparencia), líneas, curvas y el código QR.
// Tiene una página; los reportes agregan más con nuevaPagina(). El texto usa la codificación WinAnsi, que incluye tildes y eñes. Coordenadas en puntos con el origen abajo a la izquierda
public final class DocumentoPDF {

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    // Anchos de Helvetica y Helvetica-Bold (por 1000 unidades) de los caracteres 32 a 126: fuente de respaldo
    private static final int[] HELVETICA = { 278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611,
            778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278,
            469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278,
            556, 500, 722, 500, 500, 500, 334, 260, 334, 584 };
    private static final int[] HELVETICA_NEGRITA = { 278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278,
            278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667,
            611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278,
            333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556,
            333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584 };

    // Una fuente registrada en el documento: TrueType incrustada o, si es null, Helvetica
    private static final class Fuente {
        final FuenteTrueType tt;
        final boolean negrita;
        final Set<Character> usados = new TreeSet<>();

        Fuente(FuenteTrueType tt, boolean negrita) {
            this.tt = tt;
            this.negrita = negrita;
        }

        double ancho(char c) {
            if (tt != null) {
                return tt.ancho(c);
            }
            int[] tabla = negrita ? HELVETICA_NEGRITA : HELVETICA;
            if (c >= 32 && c <= 126) {
                return tabla[c - 32];
            }
            String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            char b = base.isEmpty() ? ' ' : base.charAt(0);
            return b >= 32 && b <= 126 ? tabla[b - 32] : 556;
        }
    }

    private static final class Imagen {
        final BufferedImage img;
        final boolean jpeg;

        Imagen(BufferedImage img, boolean jpeg) {
            this.img = img;
            this.jpeg = jpeg;
        }
    }

    private final double ancho;
    private final double alto;
    private final List<Fuente> fuentes = new ArrayList<>();
    private final List<Imagen> imagenes = new ArrayList<>();
    private final List<StringBuilder> paginas = new ArrayList<>();
    private StringBuilder contenido;

    public DocumentoPDF(double ancho, double alto) {
        this.ancho = ancho;
        this.alto = alto;
        nuevaPagina();
    }

    public double getAncho() { return ancho; }
    public double getAlto() { return alto; }

    // Agrega una página al final; lo que se dibuje desde ahora va en ella
    public void nuevaPagina() {
        contenido = new StringBuilder();
        paginas.add(contenido);
    }

    public int getPaginas() { return paginas.size(); }

    // Vuelve a una página ya creada (índice desde 0), por ejemplo para escribir "Página 1 de 3"
    public void irAPagina(int indice) {
        contenido = paginas.get(indice);
    }

    // Devuelve el número con el que se usa la fuente; sin TrueType se usa Helvetica (negrita o no)
    public int fuente(FuenteTrueType tt, boolean negritaDeRespaldo) {
        fuentes.add(new Fuente(tt, negritaDeRespaldo));
        return fuentes.size() - 1;
    }

    public boolean esTrueType(int f) {
        return fuentes.get(f).tt != null;
    }

    // ---------------------------------------------------------------------
    // Texto
    // ---------------------------------------------------------------------

    public double ancho(int f, double tamano, String s) {
        Fuente fu = fuentes.get(f);
        double total = 0;
        for (char c : s.toCharArray()) {
            total += fu.ancho(c);
        }
        return total * tamano / 1000;
    }

    // Altura de las mayúsculas (para alinear texto dentro de un espacio)
    public double alturaMayusculas(int f, double tamano) {
        FuenteTrueType tt = fuentes.get(f).tt;
        return (tt != null ? tt.getAlturaMayusculas() : 718) * tamano / 1000;
    }

    public void texto(int f, double tamano, double x, double y, String s) {
        contenido.append("BT /F").append(f + 1).append(' ').append(n(tamano)).append(" Tf ").append(n(x)).append(' ').append(n(y))
                .append(" Td (").append(cadena(f, s)).append(") Tj ET\n");
    }

    public void textoCentrado(int f, double tamano, double centro, double y, String s) {
        texto(f, tamano, centro - ancho(f, tamano, s) / 2, y, s);
    }

    public void textoDerecha(int f, double tamano, double derecha, double y, String s) {
        texto(f, tamano, derecha - ancho(f, tamano, s), y, s);
    }

    // Texto estirado o comprimido a lo ancho (operador Tz), por ejemplo la numeración del título
    public void textoEscalado(int f, double tamano, double escala, double x, double y, String s) {
        contenido.append("BT /F").append(f + 1).append(' ').append(n(tamano)).append(" Tf ").append(n(escala * 100)).append(" Tz ")
                .append(n(x)).append(' ').append(n(y)).append(" Td (").append(cadena(f, s)).append(") Tj 100 Tz ET\n");
    }

    // Texto girado alrededor de (x, y); el ángulo en radianes, antihorario
    public void textoRotado(int f, double tamano, double x, double y, double angulo, String s) {
        double c = Math.cos(angulo);
        double si = Math.sin(angulo);
        contenido.append("BT /F").append(f + 1).append(' ').append(n(tamano)).append(" Tf ").append(n(c)).append(' ').append(n(si))
                .append(' ').append(n(-si)).append(' ').append(n(c)).append(' ').append(n(x)).append(' ').append(n(y))
                .append(" Tm (").append(cadena(f, s)).append(") Tj ET\n");
    }

    // Párrafo con saltos de línea automáticos; justificado deja todas las líneas menos la última del mismo ancho.
    // Devuelve la línea base de la última línea
    public double parrafo(int f, double tamano, double x, double y, double anchoMaximo, double interlineado, boolean justificado, String s) {
        List<String> lineas = partir(f, tamano, s, anchoMaximo);
        double yy = y;
        for (int i = 0; i < lineas.size(); i++) {
            String linea = lineas.get(i);
            int espacios = linea.length() - linea.replace(" ", "").length();
            if (justificado && i < lineas.size() - 1 && espacios > 0) {
                double extra = (anchoMaximo - ancho(f, tamano, linea)) / espacios;
                contenido.append("BT /F").append(f + 1).append(' ').append(n(tamano)).append(" Tf ").append(n(extra)).append(" Tw ")
                        .append(n(x)).append(' ').append(n(yy)).append(" Td (").append(cadena(f, linea)).append(") Tj 0 Tw ET\n");
            } else {
                texto(f, tamano, x, yy, linea);
            }
            yy -= interlineado;
        }
        return yy + interlineado;
    }

    public List<String> partir(int f, double tamano, String s, double anchoMaximo) {
        List<String> lineas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : s.trim().split("\\s+")) {
            String prueba = actual.length() == 0 ? palabra : actual + " " + palabra;
            if (ancho(f, tamano, prueba) <= anchoMaximo || actual.length() == 0) {
                actual.setLength(0);
                actual.append(prueba);
            } else {
                lineas.add(actual.toString());
                actual.setLength(0);
                actual.append(palabra);
            }
        }
        if (actual.length() > 0) {
            lineas.add(actual.toString());
        }
        return lineas;
    }

    // ---------------------------------------------------------------------
    // Dibujo
    // ---------------------------------------------------------------------

    public void color(double r, double g, double b) {
        contenido.append(n(r)).append(' ').append(n(g)).append(' ').append(n(b)).append(" RG ")
                .append(n(r)).append(' ').append(n(g)).append(' ').append(n(b)).append(" rg\n");
    }

    public void grosor(double g) {
        contenido.append(n(g)).append(" w\n");
    }

    public void linea(double x1, double y1, double x2, double y2) {
        contenido.append(n(x1)).append(' ').append(n(y1)).append(" m ").append(n(x2)).append(' ').append(n(y2)).append(" l S\n");
    }

    public void rectangulo(double x, double y, double w, double h) {
        contenido.append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re S\n");
    }

    public void rectanguloRelleno(double x, double y, double w, double h) {
        contenido.append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re f\n");
    }

    // Color en formato #rrggbb
    public void color(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        color(((v >> 16) & 255) / 255.0, ((v >> 8) & 255) / 255.0, (v & 255) / 255.0);
    }

    // Rectángulo relleno con radios por esquina (inferior izquierda, inferior derecha, superior derecha, superior izquierda):
    // las barras de los gráficos redondean solo el extremo del dato
    public void rectanguloRedondeado(double x, double y, double w, double h, double ii, double id, double sd, double si) {
        double tope = Math.min(w, h) / 2;
        ii = Math.min(ii, tope);
        id = Math.min(id, tope);
        sd = Math.min(sd, tope);
        si = Math.min(si, tope);
        double k = 0.5523;
        contenido.append(n(x + ii)).append(' ').append(n(y)).append(" m ")
                .append(n(x + w - id)).append(' ').append(n(y)).append(" l ")
                .append(curva(x + w - id + id * k, y, x + w, y + id - id * k, x + w, y + id))
                .append(n(x + w)).append(' ').append(n(y + h - sd)).append(" l ")
                .append(curva(x + w, y + h - sd + sd * k, x + w - sd + sd * k, y + h, x + w - sd, y + h))
                .append(n(x + si)).append(' ').append(n(y + h)).append(" l ")
                .append(curva(x + si - si * k, y + h, x, y + h - si + si * k, x, y + h - si))
                .append(n(x)).append(' ').append(n(y + ii)).append(" l ")
                .append(curva(x, y + ii - ii * k, x + ii - ii * k, y, x + ii, y)).append("f\n");
    }

    public void circulo(double cx, double cy, double r) {
        double k = 0.5523 * r;
        contenido.append(n(cx + r)).append(' ').append(n(cy)).append(" m ")
                .append(curva(cx + r, cy + k, cx + k, cy + r, cx, cy + r))
                .append(curva(cx - k, cy + r, cx - r, cy + k, cx - r, cy))
                .append(curva(cx - r, cy - k, cx - k, cy - r, cx, cy - r))
                .append(curva(cx + k, cy - r, cx + r, cy - k, cx + r, cy)).append("S\n");
    }

    // Onda senoidal de (x0, y0) a (x1, y1) con la amplitud y la cantidad de medias ondas indicadas (curvas de Bézier).
    // fase 0 empieza subiendo y fase 1 bajando; dos ondas opuestas forman el cordón del marco
    public void onda(double x0, double y0, double x1, double y1, double amplitud, int mediasOndas, int fase) {
        double largo = Math.hypot(x1 - x0, y1 - y0);
        double ux = (x1 - x0) / largo;
        double uy = (y1 - y0) / largo;
        double paso = largo / mediasOndas;
        // Media onda de seno aproximada: puntos de control a 0,4244 del paso y altura 4/3 de la amplitud
        double kx = paso * 0.4244;
        StringBuilder sb = new StringBuilder(n(x0)).append(' ').append(n(y0)).append(" m ");
        for (int i = 0; i < mediasOndas; i++) {
            double signo = ((i + fase) % 2 == 0 ? 1 : -1) * amplitud * 4 / 3;
            double ax = x0 + ux * paso * i;
            double ay = y0 + uy * paso * i;
            double px = -uy * signo;
            double py = ux * signo;
            sb.append(curva(ax + ux * kx + px, ay + uy * kx + py, ax + ux * (paso - kx) + px, ay + uy * (paso - kx) + py,
                    ax + ux * paso, ay + uy * paso));
        }
        contenido.append(sb).append("S\n");
    }

    private static String curva(double x1, double y1, double x2, double y2, double x3, double y3) {
        return n(x1) + " " + n(y1) + " " + n(x2) + " " + n(y2) + " " + n(x3) + " " + n(y3) + " c ";
    }

    // Imagen ajustada a la caja (sin deformarla) y centrada en ella. jpeg: comprime con pérdida (fotos y logos)
    public void imagen(BufferedImage img, double x, double y, double w, double h, boolean jpeg) {
        double escala = Math.min(w / img.getWidth(), h / img.getHeight());
        double iw = img.getWidth() * escala;
        double ih = img.getHeight() * escala;
        imagenes.add(new Imagen(img, jpeg));
        contenido.append("q ").append(n(iw)).append(" 0 0 ").append(n(ih)).append(' ').append(n(x + (w - iw) / 2)).append(' ')
                .append(n(y + (h - ih) / 2)).append(" cm /Im").append(imagenes.size()).append(" Do Q\n");
    }

    // Código QR: lado = ancho de los módulos (el margen blanco queda alrededor, sobre la hoja)
    public void qr(boolean[][] modulos, double x, double y, double lado) {
        int total = modulos.length;
        double m = lado / total;
        StringBuilder sb = new StringBuilder("0 0 0 rg\n");
        for (int fila = 0; fila < total; fila++) {
            for (int col = 0; col < total; col++) {
                if (modulos[fila][col]) {
                    // Los módulos oscuros seguidos de una fila se dibujan como un solo rectángulo
                    int fin = col;
                    while (fin + 1 < total && modulos[fila][fin + 1]) {
                        fin++;
                    }
                    sb.append(n(x + col * m)).append(' ').append(n(y + lado - (fila + 1) * m)).append(' ')
                            .append(n((fin - col + 1) * m)).append(' ').append(n(m)).append(" re\n");
                    col = fin;
                }
            }
        }
        contenido.append(sb).append("f\n");
    }

    // ---------------------------------------------------------------------
    // Archivo
    // ---------------------------------------------------------------------

    public byte[] generar(String titulo) throws IOException {
        // 1 catálogo, 2 árbol de páginas y, por cada página, su diccionario y su contenido
        List<byte[]> objetos = new ArrayList<>();
        objetos.add(null);
        objetos.add(null);
        for (StringBuilder pagina : paginas) {
            objetos.add(null);
            objetos.add(flujo("", pagina.toString().getBytes(WIN_ANSI), true));
        }
        StringBuilder recursosFuentes = new StringBuilder();
        for (int i = 0; i < fuentes.size(); i++) {
            recursosFuentes.append("/F").append(i + 1).append(' ').append(objetos.size() + 1).append(" 0 R ");
            agregarFuente(objetos, fuentes.get(i), i);
        }
        StringBuilder recursosImagenes = new StringBuilder();
        for (int i = 0; i < imagenes.size(); i++) {
            recursosImagenes.append("/Im").append(i + 1).append(' ').append(objetos.size() + 1).append(" 0 R ");
            agregarImagen(objetos, imagenes.get(i));
        }
        objetos.set(0, ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder hijos = new StringBuilder();
        for (int i = 0; i < paginas.size(); i++) {
            hijos.append(3 + 2 * i).append(" 0 R ");
            objetos.set(2 + 2 * i, ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(ancho) + " " + n(alto) + "] /Resources << /Font << "
                    + recursosFuentes + ">> /XObject << " + recursosImagenes + ">> >> /Contents " + (4 + 2 * i) + " 0 R >>"));
        }
        objetos.set(1, ascii("<< /Type /Pages /Kids [" + hijos + "] /Count " + paginas.size() + " >>"));
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.writeBytes(ascii("<< /Title ("));
        info.writeBytes(escapar(titulo).getBytes(WIN_ANSI));
        info.writeBytes(ascii(") /Producer (Sistema de Gestion Academica ICAP - Grupo 15 SC) >>"));
        objetos.add(info.toByteArray());

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.writeBytes(ascii("%PDF-1.5\n%âãÏÓ\n"));
        long[] posiciones = new long[objetos.size()];
        for (int i = 0; i < objetos.size(); i++) {
            posiciones[i] = pdf.size();
            pdf.writeBytes(ascii((i + 1) + " 0 obj\n"));
            pdf.writeBytes(objetos.get(i));
            pdf.writeBytes(ascii("\nendobj\n"));
        }
        long xref = pdf.size();
        StringBuilder tabla = new StringBuilder("xref\n0 " + (objetos.size() + 1) + "\n0000000000 65535 f \n");
        for (long p : posiciones) {
            tabla.append(String.format(Locale.ROOT, "%010d 00000 n \n", p));
        }
        tabla.append("trailer\n<< /Size ").append(objetos.size() + 1).append(" /Root 1 0 R /Info ").append(objetos.size())
                .append(" 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        pdf.writeBytes(ascii(tabla.toString()));
        return pdf.toByteArray();
    }

    // TrueType simple con codificación WinAnsi: diccionario, descriptor y archivo de la fuente (subconjunto)
    private void agregarFuente(List<byte[]> objetos, Fuente f, int indice) throws IOException {
        if (f.tt == null) {
            objetos.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /" + (f.negrita ? "Helvetica-Bold" : "Helvetica")
                    + " /Encoding /WinAnsiEncoding >>"));
            return;
        }
        FuenteTrueType tt = f.tt;
        // Prefijo de subconjunto: seis letras mayúsculas distintas por fuente
        StringBuilder prefijo = new StringBuilder();
        for (int i = 0, v = Math.abs((tt.getNombre() + indice).hashCode()); i < 6; i++, v /= 26) {
            prefijo.append((char) ('A' + v % 26));
        }
        String nombre = prefijo + "+" + tt.getNombre();
        int numero = objetos.size() + 1;
        StringBuilder anchos = new StringBuilder();
        CharsetDecoder dec = WIN_ANSI.newDecoder().onUnmappableCharacter(CodingErrorAction.REPLACE).onMalformedInput(CodingErrorAction.REPLACE);
        for (int codigo = 32; codigo <= 255; codigo++) {
            char c = dec.decode(ByteBuffer.wrap(new byte[] { (byte) codigo })).charAt(0);
            anchos.append(Math.round(tt.ancho(c))).append(' ');
        }
        objetos.add(ascii("<< /Type /Font /Subtype /TrueType /BaseFont /" + nombre + " /FirstChar 32 /LastChar 255 /Widths [ "
                + anchos + "] /Encoding /WinAnsiEncoding /FontDescriptor " + (numero + 1) + " 0 R >>"));
        int[] caja = tt.getCaja();
        objetos.add(ascii("<< /Type /FontDescriptor /FontName /" + nombre + " /Flags " + (32 + (tt.esItalica() ? 64 : 0))
                + " /FontBBox [" + caja[0] + " " + caja[1] + " " + caja[2] + " " + caja[3] + "] /ItalicAngle " + n(tt.getAnguloItalica())
                + " /Ascent " + tt.getAscenso() + " /Descent " + tt.getDescenso() + " /CapHeight " + tt.getAlturaMayusculas()
                + " /StemV 80 /FontFile2 " + (numero + 2) + " 0 R >>"));
        byte[] archivo = tt.subconjunto(f.usados);
        objetos.add(flujo("/Length1 " + archivo.length, archivo, true));
    }

    private void agregarImagen(List<byte[]> objetos, Imagen im) throws IOException {
        BufferedImage img = im.img;
        int w = img.getWidth();
        int h = img.getHeight();
        boolean transparente = img.getColorModel().hasAlpha();
        int numero = objetos.size() + 1;
        byte[] colores;
        String filtro;
        if (im.jpeg) {
            colores = jpeg(sinTransparencia(img));
            filtro = "/DCTDecode";
        } else {
            colores = new byte[w * h * 3];
            for (int y = 0, i = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y);
                    colores[i++] = (byte) (p >> 16);
                    colores[i++] = (byte) (p >> 8);
                    colores[i++] = (byte) p;
                }
            }
            colores = comprimir(colores);
            filtro = "/FlateDecode";
        }
        String mascara = transparente ? " /SMask " + (numero + 1) + " 0 R" : "";
        objetos.add(flujo("/Type /XObject /Subtype /Image /Width " + w + " /Height " + h + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter "
                + filtro + mascara, colores, false));
        if (transparente) {
            byte[] alfa = new byte[w * h];
            for (int y = 0, i = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    alfa[i++] = (byte) (img.getRGB(x, y) >>> 24);
                }
            }
            objetos.add(flujo("/Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                    + " /ColorSpace /DeviceGray /BitsPerComponent 8", alfa, true));
        }
    }

    // Colores sin la transparencia (la máscara la aplica aparte)
    private static BufferedImage sinTransparencia(BufferedImage img) {
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                rgb.setRGB(x, y, img.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return rgb;
    }

    private static byte[] jpeg(BufferedImage rgb) throws IOException {
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam parametros = escritor.getDefaultWriteParam();
        parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parametros.setCompressionQuality(0.92f);
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream flujo = new MemoryCacheImageOutputStream(salida)) {
            escritor.setOutput(flujo);
            escritor.write(null, new IIOImage(rgb, null, null), parametros);
        } finally {
            escritor.dispose();
        }
        return salida.toByteArray();
    }

    // Objeto stream; comprimido con Flate si se pide
    private static byte[] flujo(String diccionario, byte[] datos, boolean comprimido) {
        byte[] cuerpo = comprimido ? comprimir(datos) : datos;
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.writeBytes(ascii("<< " + diccionario + (comprimido ? " /Filter /FlateDecode" : "") + " /Length " + cuerpo.length + " >>\nstream\n"));
        s.writeBytes(cuerpo);
        s.writeBytes(ascii("\nendstream"));
        return s.toByteArray();
    }

    private static byte[] comprimir(byte[] datos) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(datos);
        d.finish();
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        byte[] bufer = new byte[8192];
        while (!d.finished()) {
            s.write(bufer, 0, d.deflate(bufer));
        }
        d.end();
        return s.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    // Registra los caracteres usados (para el subconjunto) y escapa el texto
    private String cadena(int f, String s) {
        for (char c : s.toCharArray()) {
            fuentes.get(f).usados.add(c);
        }
        return escapar(s);
    }

    // Paréntesis y barras invertidas se escapan; lo que no existe en WinAnsi se reemplaza
    private static String escapar(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 32) {
                sb.append(' ');
            } else if (WIN_ANSI.newEncoder().canEncode(c)) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static String n(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
