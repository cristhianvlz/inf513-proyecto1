package src.utilidades;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

// Fuente TrueType (.ttf o una de las fuentes de un .ttc) para incrustarla en el PDF: lee sus medidas y anchos y
// arma un subconjunto con solo los caracteres usados, para que el certificado se vea igual en cualquier equipo
// sin pesar varios megabytes. Solo se usan fuentes cuya licencia permite incrustarlas (fsType)
public final class FuenteTrueType {

    // Tablas que necesita un visor de PDF para dibujar la fuente (las de diseño tipográfico avanzado se omiten)
    private static final Set<String> TABLAS_NECESARIAS = Set.of("cmap", "cvt ", "fpgm", "glyf", "head", "hhea", "hmtx",
            "loca", "maxp", "name", "OS/2", "post", "prep");

    private final byte[] datos;
    private final Map<String, int[]> tablas = new TreeMap<>();
    private final Map<Integer, Integer> cmap = new HashMap<>();
    private final String nombre;
    private final int unidades;
    private final int xMin, yMin, xMax, yMax;
    private final int ascenso, descenso, alturaMayusculas;
    private final double anguloItalica;
    private final int numGlifos, numMetricas;
    private final boolean locLarga;

    private FuenteTrueType(byte[] datos, int indice) throws IOException {
        this.datos = datos;
        int inicio = 0;
        if (datos[0] == 't' && datos[1] == 't' && datos[2] == 'c' && datos[3] == 'f') {
            if (indice >= u32(8)) {
                throw new IOException("La colección no tiene la fuente " + indice);
            }
            inicio = u32(12 + 4 * indice);
        }
        int num = u16(inicio + 4);
        for (int i = 0; i < num; i++) {
            int r = inicio + 12 + 16 * i;
            tablas.put(new String(datos, r, 4, StandardCharsets.ISO_8859_1), new int[] { u32(r + 8), u32(r + 12) });
        }
        for (String t : new String[] { "head", "hhea", "hmtx", "maxp", "loca", "glyf", "cmap" }) {
            if (!tablas.containsKey(t)) {
                throw new IOException("La fuente no tiene la tabla " + t);
            }
        }
        int os2 = tablas.containsKey("OS/2") ? tabla("OS/2") : -1;
        // Bit 1 de fsType: licencia que no permite incrustar la fuente
        if (os2 >= 0 && (u16(os2 + 8) & 0x000F) == 0x0002) {
            throw new IOException("La licencia de la fuente no permite incrustarla");
        }
        int head = tabla("head");
        unidades = u16(head + 18);
        xMin = i16(head + 36);
        yMin = i16(head + 38);
        xMax = i16(head + 40);
        yMax = i16(head + 42);
        locLarga = i16(head + 50) == 1;
        int hhea = tabla("hhea");
        ascenso = i16(hhea + 4);
        descenso = i16(hhea + 6);
        numMetricas = u16(hhea + 34);
        numGlifos = u16(tabla("maxp") + 4);
        alturaMayusculas = os2 >= 0 && u16(os2) >= 2 ? i16(os2 + 88) : (int) (ascenso * 0.7);
        anguloItalica = tablas.containsKey("post") ? u32(tabla("post") + 4) / 65536.0 : 0;
        nombre = leerNombre();
        leerCmap();
    }

    // Archivo .ttf, o .ttc con el índice de la fuente dentro de la colección
    public static FuenteTrueType cargar(Path archivo, int indice) throws IOException {
        return new FuenteTrueType(Files.readAllBytes(archivo), indice);
    }

    public String getNombre() { return nombre; }
    public boolean esItalica() { return anguloItalica != 0; }
    public double getAnguloItalica() { return anguloItalica; }

    // Medidas en milésimas del tamaño de la letra, como las pide el PDF
    public int escala(int valor) { return (int) Math.round(valor * 1000.0 / unidades); }
    public int[] getCaja() { return new int[] { escala(xMin), escala(yMin), escala(xMax), escala(yMax) }; }
    public int getAscenso() { return escala(ascenso); }
    public int getDescenso() { return escala(descenso); }
    public int getAlturaMayusculas() { return escala(alturaMayusculas); }

    // Ancho del carácter en milésimas del tamaño de la letra
    public double ancho(char c) {
        int g = glifo(c);
        int avance = u16(tabla("hmtx") + 4 * Math.min(g, numMetricas - 1));
        return avance * 1000.0 / unidades;
    }

    private int glifo(char c) {
        return cmap.getOrDefault((int) c, 0);
    }

    // ---------------------------------------------------------------------
    // Subconjunto: los glifos que no se usan quedan vacíos; así no cambian los números de glifo ni las métricas
    // ---------------------------------------------------------------------

    public byte[] subconjunto(Set<Character> usados) throws IOException {
        Set<Integer> glifos = new TreeSet<>();
        glifos.add(0);
        for (char c : usados) {
            glifos.add(glifo(c));
        }
        // Una letra con tilde suele estar compuesta por otras dos (la "e" y el acento): también se conservan
        int glyf = tabla("glyf");
        Deque<Integer> pendientes = new ArrayDeque<>(glifos);
        while (!pendientes.isEmpty()) {
            int g = pendientes.pop();
            int ini = loca(g);
            int fin = loca(g + 1);
            if (fin > ini && i16(glyf + ini) < 0) {
                int p = glyf + ini + 10;
                int banderas;
                do {
                    banderas = u16(p);
                    int componente = u16(p + 2);
                    p += 4 + ((banderas & 0x0001) != 0 ? 4 : 2);
                    p += (banderas & 0x0008) != 0 ? 2 : (banderas & 0x0040) != 0 ? 4 : (banderas & 0x0080) != 0 ? 8 : 0;
                    if (glifos.add(componente)) {
                        pendientes.push(componente);
                    }
                } while ((banderas & 0x0020) != 0);
            }
        }
        ByteArrayOutputStream nuevaGlyf = new ByteArrayOutputStream();
        ByteArrayOutputStream nuevaLoca = new ByteArrayOutputStream();
        for (int g = 0; g < numGlifos; g++) {
            escribir32(nuevaLoca, nuevaGlyf.size());
            if (glifos.contains(g)) {
                int ini = loca(g);
                nuevaGlyf.write(datos, glyf + ini, loca(g + 1) - ini);
                while (nuevaGlyf.size() % 4 != 0) {
                    nuevaGlyf.write(0);
                }
            }
        }
        escribir32(nuevaLoca, nuevaGlyf.size());

        Map<String, byte[]> salida = new TreeMap<>();
        for (Map.Entry<String, int[]> t : tablas.entrySet()) {
            if (TABLAS_NECESARIAS.contains(t.getKey())) {
                byte[] copia = new byte[t.getValue()[1]];
                System.arraycopy(datos, t.getValue()[0], copia, 0, copia.length);
                salida.put(t.getKey(), copia);
            }
        }
        salida.put("glyf", nuevaGlyf.toByteArray());
        salida.put("loca", nuevaLoca.toByteArray());
        // head: loca en formato largo y sin el ajuste de suma de verificación
        byte[] head = salida.get("head");
        head[50] = 0;
        head[51] = 1;
        head[8] = head[9] = head[10] = head[11] = 0;
        // post versión 3: sin los nombres de los glifos
        if (salida.containsKey("post")) {
            byte[] post = new byte[32];
            System.arraycopy(salida.get("post"), 0, post, 0, 32);
            post[0] = 0;
            post[1] = 3;
            post[2] = 0;
            post[3] = 0;
            salida.put("post", post);
        }
        salida.put("cmap", cmapMinimo(usados));
        return armar(salida);
    }

    // cmap (3,1) formato 4 con un segmento por carácter usado
    private byte[] cmapMinimo(Set<Character> usados) throws IOException {
        TreeMap<Integer, Integer> codigos = new TreeMap<>();
        for (char c : usados) {
            if (glifo(c) != 0) {
                codigos.put((int) c, glifo(c));
            }
        }
        int segmentos = codigos.size() + 1;
        int potencia = Integer.highestOneBit(segmentos);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        escribir16(s, 0);
        escribir16(s, 1);
        escribir16(s, 3);
        escribir16(s, 1);
        escribir32(s, 12);
        escribir16(s, 4);
        escribir16(s, 16 + 8 * segmentos);
        escribir16(s, 0);
        escribir16(s, 2 * segmentos);
        escribir16(s, 2 * potencia);
        escribir16(s, Integer.numberOfTrailingZeros(potencia));
        escribir16(s, 2 * segmentos - 2 * potencia);
        for (int c : codigos.keySet()) {
            escribir16(s, c);
        }
        escribir16(s, 0xFFFF);
        escribir16(s, 0);
        for (int c : codigos.keySet()) {
            escribir16(s, c);
        }
        escribir16(s, 0xFFFF);
        for (Map.Entry<Integer, Integer> e : codigos.entrySet()) {
            escribir16(s, (e.getValue() - e.getKey()) & 0xFFFF);
        }
        escribir16(s, 1);
        for (int i = 0; i < segmentos; i++) {
            escribir16(s, 0);
        }
        return s.toByteArray();
    }

    // Archivo TrueType con las tablas indicadas (ordenadas por etiqueta y alineadas a 4 bytes)
    private static byte[] armar(Map<String, byte[]> salida) throws IOException {
        int n = salida.size();
        int potencia = Integer.highestOneBit(n);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        escribir32(f, 0x00010000);
        escribir16(f, n);
        escribir16(f, 16 * potencia);
        escribir16(f, Integer.numberOfTrailingZeros(potencia));
        escribir16(f, 16 * n - 16 * potencia);
        int desplazamiento = 12 + 16 * n;
        for (Map.Entry<String, byte[]> t : salida.entrySet()) {
            f.write(t.getKey().getBytes(StandardCharsets.ISO_8859_1));
            escribir32(f, sumaDeVerificacion(t.getValue()));
            escribir32(f, desplazamiento);
            escribir32(f, t.getValue().length);
            desplazamiento += (t.getValue().length + 3) & ~3;
        }
        for (byte[] t : salida.values()) {
            f.write(t);
            for (int i = t.length; i % 4 != 0; i++) {
                f.write(0);
            }
        }
        return f.toByteArray();
    }

    private static int sumaDeVerificacion(byte[] t) {
        int suma = 0;
        for (int i = 0; i < t.length; i += 4) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                v = (v << 8) | (i + j < t.length ? t[i + j] & 0xFF : 0);
            }
            suma += v;
        }
        return suma;
    }

    // ---------------------------------------------------------------------
    // Lectura del archivo
    // ---------------------------------------------------------------------

    // cmap Unicode de Windows (3,1) o, si no hay, Unicode (0,3); ambos en formato 4
    private void leerCmap() throws IOException {
        int c = tabla("cmap");
        int elegido = -1;
        for (int i = 0; i < u16(c + 2); i++) {
            int r = c + 4 + 8 * i;
            int plataforma = u16(r);
            int codificacion = u16(r + 2);
            int sub = c + u32(r + 4);
            if (u16(sub) == 4 && ((plataforma == 3 && codificacion == 1) || (plataforma == 0 && elegido < 0))) {
                elegido = sub;
            }
        }
        if (elegido < 0) {
            throw new IOException("La fuente no tiene un cmap Unicode en formato 4");
        }
        int segmentos = u16(elegido + 6) / 2;
        int finales = elegido + 14;
        int iniciales = finales + 2 * segmentos + 2;
        int deltas = iniciales + 2 * segmentos;
        int rangos = deltas + 2 * segmentos;
        for (int s = 0; s < segmentos; s++) {
            int fin = u16(finales + 2 * s);
            int ini = u16(iniciales + 2 * s);
            int delta = i16(deltas + 2 * s);
            int rango = u16(rangos + 2 * s);
            for (int cod = ini; cod <= fin && cod != 0xFFFF; cod++) {
                int g;
                if (rango == 0) {
                    g = (cod + delta) & 0xFFFF;
                } else {
                    g = u16(rangos + 2 * s + rango + 2 * (cod - ini));
                    if (g != 0) {
                        g = (g + delta) & 0xFFFF;
                    }
                }
                if (g != 0 && g < numGlifos) {
                    cmap.put(cod, g);
                }
            }
        }
    }

    // Nombre PostScript (nameID 6), sin espacios
    private String leerNombre() {
        if (!tablas.containsKey("name")) {
            return "Fuente";
        }
        int n = tabla("name");
        int cantidad = u16(n + 2);
        int textos = n + u16(n + 4);
        for (int i = 0; i < cantidad; i++) {
            int r = n + 6 + 12 * i;
            if (u16(r + 6) == 6) {
                int largo = u16(r + 8);
                int p = textos + u16(r + 10);
                String s = u16(r) == 3 || u16(r) == 0 ? new String(datos, p, largo, StandardCharsets.UTF_16BE)
                        : new String(datos, p, largo, StandardCharsets.ISO_8859_1);
                return s.replaceAll("[^A-Za-z0-9-]", "");
            }
        }
        return "Fuente";
    }

    private int tabla(String etiqueta) {
        return tablas.get(etiqueta)[0];
    }

    private int loca(int g) {
        int l = tabla("loca");
        return locLarga ? u32(l + 4 * g) : u16(l + 2 * g) * 2;
    }

    private int u16(int p) {
        return ((datos[p] & 0xFF) << 8) | (datos[p + 1] & 0xFF);
    }

    private int i16(int p) {
        return (short) u16(p);
    }

    private int u32(int p) {
        return ((datos[p] & 0xFF) << 24) | ((datos[p + 1] & 0xFF) << 16) | ((datos[p + 2] & 0xFF) << 8) | (datos[p + 3] & 0xFF);
    }

    private static void escribir16(ByteArrayOutputStream s, int v) {
        s.write((v >>> 8) & 0xFF);
        s.write(v & 0xFF);
    }

    private static void escribir32(ByteArrayOutputStream s, int v) {
        s.write((v >>> 24) & 0xFF);
        s.write((v >>> 16) & 0xFF);
        s.write((v >>> 8) & 0xFF);
        s.write(v & 0xFF);
    }
}
