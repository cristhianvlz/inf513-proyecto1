package src.utilidades;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// Genera la matriz de un código QR (ISO/IEC 18004) en modo byte con corrección de errores nivel M (~15%).
// El proyecto no usa librerías externas, por eso se implementa aquí: versiones 1 a 10 (hasta 213 bytes),
// corrección Reed-Solomon, patrones de posición, alineación y temporización, y la mejor de las 8 máscaras
public final class CodigoQR {

    // Por versión (índice 1 a 10), para el nivel M: total de codewords, bloques y codewords de corrección por bloque
    private static final int[] TOTAL_CODEWORDS = { 0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346 };
    private static final int[] BLOQUES = { 0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5 };
    private static final int[] CORRECCION_POR_BLOQUE = { 0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 };
    private static final int[][] ALINEACION = { {}, {}, { 6, 18 }, { 6, 22 }, { 6, 26 }, { 6, 30 }, { 6, 34 },
            { 6, 22, 38 }, { 6, 24, 42 }, { 6, 26, 46 }, { 6, 28, 50 } };
    private static final int VERSION_MAXIMA = 10;
    // Bits del nivel de corrección M en la información de formato
    private static final int NIVEL_M = 0;

    private final int version;
    private final int tamano;
    private final boolean[][] oscuro;
    private final boolean[][] funcion;

    private CodigoQR(int version) {
        this.version = version;
        this.tamano = version * 4 + 17;
        this.oscuro = new boolean[tamano][tamano];
        this.funcion = new boolean[tamano][tamano];
    }

    // Matriz [fila][columna]: true = módulo oscuro. No incluye el margen blanco
    public static boolean[][] generar(String texto) {
        byte[] datos = texto.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= VERSION_MAXIMA && capacidad(version) < datos.length) {
            version++;
        }
        if (version > VERSION_MAXIMA) {
            throw new IllegalArgumentException("Texto demasiado largo para el código QR: " + datos.length + " bytes");
        }
        CodigoQR qr = new CodigoQR(version);
        qr.dibujarPatrones();
        qr.colocarDatos(qr.intercalar(qr.codificar(datos)));
        qr.aplicarMejorMascara();
        return qr.oscuro;
    }

    private static int codewordsDeDatos(int v) {
        return TOTAL_CODEWORDS[v] - BLOQUES[v] * CORRECCION_POR_BLOQUE[v];
    }

    // Bytes que caben: 4 bits de modo + contador (8 bits hasta la versión 9, 16 desde la 10)
    private static int capacidad(int v) {
        return (codewordsDeDatos(v) * 8 - 4 - (v < 10 ? 8 : 16)) / 8;
    }

    // ---------------------------------------------------------------------
    // Codificación de los datos
    // ---------------------------------------------------------------------

    private byte[] codificar(byte[] datos) {
        int capacidadBits = codewordsDeDatos(version) * 8;
        Bits bits = new Bits();
        bits.agregar(0b0100, 4);
        bits.agregar(datos.length, version < 10 ? 8 : 16);
        for (byte b : datos) {
            bits.agregar(b & 0xFF, 8);
        }
        bits.agregar(0, Math.min(4, capacidadBits - bits.largo));
        bits.agregar(0, (8 - bits.largo % 8) % 8);
        for (int relleno = 0xEC; bits.largo < capacidadBits; relleno ^= 0xEC ^ 0x11) {
            bits.agregar(relleno, 8);
        }
        return bits.bytes();
    }

    // Divide los datos en bloques, calcula la corrección de cada uno e intercala los codewords
    private byte[] intercalar(byte[] datos) {
        int bloques = BLOQUES[version];
        int correccion = CORRECCION_POR_BLOQUE[version];
        int total = TOTAL_CODEWORDS[version];
        int cortos = bloques - total % bloques;
        int largoCorto = total / bloques;
        byte[] divisor = divisorReedSolomon(correccion);

        byte[][] bloque = new byte[bloques][];
        for (int i = 0, k = 0; i < bloques; i++) {
            int largoDatos = largoCorto - correccion + (i < cortos ? 0 : 1);
            byte[] dat = new byte[largoDatos];
            System.arraycopy(datos, k, dat, 0, largoDatos);
            k += largoDatos;
            byte[] ecc = restoReedSolomon(dat, divisor);
            // Los bloques cortos llevan un hueco para que todos tengan el mismo largo al intercalar
            byte[] b = new byte[largoCorto + 1];
            System.arraycopy(dat, 0, b, 0, largoDatos);
            System.arraycopy(ecc, 0, b, largoCorto + 1 - correccion, correccion);
            bloque[i] = b;
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (int i = 0; i < largoCorto + 1; i++) {
            for (int j = 0; j < bloques; j++) {
                if (i != largoCorto - correccion || j >= cortos) {
                    salida.write(bloque[j][i]);
                }
            }
        }
        return salida.toByteArray();
    }

    private static byte[] divisorReedSolomon(int grado) {
        byte[] r = new byte[grado];
        r[grado - 1] = 1;
        int raiz = 1;
        for (int i = 0; i < grado; i++) {
            for (int j = 0; j < grado; j++) {
                r[j] = (byte) multiplicar(r[j] & 0xFF, raiz);
                if (j + 1 < grado) {
                    r[j] ^= r[j + 1];
                }
            }
            raiz = multiplicar(raiz, 0x02);
        }
        return r;
    }

    private static byte[] restoReedSolomon(byte[] datos, byte[] divisor) {
        byte[] r = new byte[divisor.length];
        for (byte b : datos) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) {
                r[i] ^= (byte) multiplicar(divisor[i] & 0xFF, factor);
            }
        }
        return r;
    }

    // Producto en GF(256) con el polinomio x^8 + x^4 + x^3 + x^2 + 1
    private static int multiplicar(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------------------------------------------------------------------
    // Patrones fijos y ubicación de los datos
    // ---------------------------------------------------------------------

    private void fijar(int columna, int fila, boolean valor) {
        oscuro[fila][columna] = valor;
        funcion[fila][columna] = true;
    }

    private void dibujarPatrones() {
        for (int i = 0; i < tamano; i++) {
            fijar(6, i, i % 2 == 0);
            fijar(i, 6, i % 2 == 0);
        }
        dibujarPosicion(3, 3);
        dibujarPosicion(tamano - 4, 3);
        dibujarPosicion(3, tamano - 4);
        int[] pos = ALINEACION[version];
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                boolean esquinaDePosicion = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                if (!esquinaDePosicion) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            fijar(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                        }
                    }
                }
            }
        }
        // Reserva el lugar del formato; el valor definitivo se escribe al elegir la máscara
        dibujarFormato(0);
        if (version >= 7) {
            int resto = version;
            for (int i = 0; i < 12; i++) {
                resto = (resto << 1) ^ ((resto >>> 11) * 0x1F25);
            }
            int bits = version << 12 | resto;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = tamano - 11 + i % 3;
                int b = i / 3;
                fijar(a, b, bit);
                fijar(b, a, bit);
            }
        }
    }

    // Patrón de posición de 7x7 con su separador blanco
    private void dibujarPosicion(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int distancia = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;
                if (xx >= 0 && xx < tamano && yy >= 0 && yy < tamano) {
                    fijar(xx, yy, distancia != 2 && distancia != 4);
                }
            }
        }
    }

    private void dibujarFormato(int mascara) {
        int datos = NIVEL_M << 3 | mascara;
        int resto = datos;
        for (int i = 0; i < 10; i++) {
            resto = (resto << 1) ^ ((resto >>> 9) * 0x537);
        }
        int bits = (datos << 10 | resto) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            fijar(8, i, bit(bits, i));
        }
        fijar(8, 7, bit(bits, 6));
        fijar(8, 8, bit(bits, 7));
        fijar(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            fijar(14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            fijar(tamano - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            fijar(8, tamano - 15 + i, bit(bits, i));
        }
        // Módulo oscuro fijo
        fijar(8, tamano - 8, true);
    }

    private static boolean bit(int valor, int i) {
        return ((valor >>> i) & 1) != 0;
    }

    // Recorre la matriz en columnas de dos, en zigzag de abajo hacia arriba y de derecha a izquierda
    private void colocarDatos(byte[] codewords) {
        int i = 0;
        for (int derecha = tamano - 1; derecha >= 1; derecha -= 2) {
            if (derecha == 6) {
                derecha = 5;
            }
            for (int vertical = 0; vertical < tamano; vertical++) {
                for (int j = 0; j < 2; j++) {
                    int x = derecha - j;
                    boolean subiendo = ((derecha + 1) & 2) == 0;
                    int y = subiendo ? tamano - 1 - vertical : vertical;
                    if (!funcion[y][x] && i < codewords.length * 8) {
                        oscuro[y][x] = bit(codewords[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Máscara: se elige la de menor penalización para que los lectores lo lean mejor
    // ---------------------------------------------------------------------

    private void aplicarMejorMascara() {
        int mejor = 0;
        int menorPenalizacion = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            aplicarMascara(m);
            dibujarFormato(m);
            int penalizacion = penalizacion();
            if (penalizacion < menorPenalizacion) {
                menorPenalizacion = penalizacion;
                mejor = m;
            }
            // Aplicarla otra vez la deshace (XOR)
            aplicarMascara(m);
        }
        aplicarMascara(mejor);
        dibujarFormato(mejor);
    }

    private void aplicarMascara(int m) {
        for (int y = 0; y < tamano; y++) {
            for (int x = 0; x < tamano; x++) {
                boolean invertir;
                switch (m) {
                    case 0: invertir = (x + y) % 2 == 0; break;
                    case 1: invertir = y % 2 == 0; break;
                    case 2: invertir = x % 3 == 0; break;
                    case 3: invertir = (x + y) % 3 == 0; break;
                    case 4: invertir = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invertir = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invertir = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invertir = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!funcion[y][x] && invertir) {
                    oscuro[y][x] = !oscuro[y][x];
                }
            }
        }
    }

    // Reglas de la norma: rachas de 5 o más, bloques de 2x2, patrones parecidos al de posición y balance de oscuros
    private int penalizacion() {
        int total = 0;
        for (int horizontal = 0; horizontal < 2; horizontal++) {
            for (int a = 0; a < tamano; a++) {
                int racha = 0;
                boolean anterior = false;
                StringBuilder linea = new StringBuilder();
                for (int b = 0; b < tamano; b++) {
                    boolean actual = horizontal == 0 ? oscuro[a][b] : oscuro[b][a];
                    linea.append(actual ? '1' : '0');
                    if (b > 0 && actual == anterior) {
                        racha++;
                    } else {
                        if (racha >= 5) {
                            total += racha - 2;
                        }
                        racha = 1;
                    }
                    anterior = actual;
                }
                if (racha >= 5) {
                    total += racha - 2;
                }
                String l = "0000" + linea + "0000";
                for (int k = l.indexOf("1011101"); k >= 0; k = l.indexOf("1011101", k + 1)) {
                    if ((k >= 4 && l.startsWith("0000", k - 4)) || l.startsWith("0000", k + 7)) {
                        total += 40;
                    }
                }
            }
        }
        int oscuros = 0;
        for (int y = 0; y < tamano; y++) {
            for (int x = 0; x < tamano; x++) {
                if (oscuro[y][x]) {
                    oscuros++;
                }
                if (y + 1 < tamano && x + 1 < tamano && oscuro[y][x] == oscuro[y][x + 1] && oscuro[y][x] == oscuro[y + 1][x]
                        && oscuro[y][x] == oscuro[y + 1][x + 1]) {
                    total += 3;
                }
            }
        }
        int celdas = tamano * tamano;
        total += Math.abs(oscuros * 20 - celdas * 10) / celdas * 10;
        return total;
    }

    // Acumulador de bits que se entregan como bytes
    private static final class Bits {
        private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
        private int actual;
        private int largo;

        void agregar(int valor, int cantidad) {
            for (int i = cantidad - 1; i >= 0; i--) {
                actual = (actual << 1) | ((valor >>> i) & 1);
                largo++;
                if (largo % 8 == 0) {
                    salida.write(actual);
                    actual = 0;
                }
            }
        }

        byte[] bytes() {
            return salida.toByteArray();
        }
    }
}
