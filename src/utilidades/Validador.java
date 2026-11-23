package src.utilidades;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class Validador {

    public static boolean vacio(String s) {
        return s == null || s.trim().isEmpty();
    }

    // Quita tildes, espacios repetidos y pasa a mayúsculas: "Secretaría  académica" -> "SECRETARIA ACADEMICA"
    public static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    public static boolean esEmail(String s) {
        return s != null && s.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }

    // Números y letras, con complemento opcional: 7654321 o 7654321-1A. También se usa para nombrar carpetas
    public static boolean esCi(String s) {
        return s != null && s.trim().matches("^[0-9A-Za-z-]{4,20}$");
    }

    // Los comandos públicos reciben texto de cualquiera y luego se muestra en correos HTML del personal
    public static boolean contieneHtml(String s) {
        return s != null && (s.contains("<") || s.contains(">"));
    }

    // Devuelve null si el texto no tiene el formato YYYY-MM-DD
    public static LocalDate fecha(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    // Acepta "7:00", "19:30" o "19:30:00". Devuelve null si el formato no es válido
    public static LocalTime hora(String s) {
        if (vacio(s)) {
            return null;
        }
        try {
            return LocalTime.parse(s.trim(), DateTimeFormatter.ofPattern("H:mm"));
        } catch (DateTimeParseException e) {
            try {
                return LocalTime.parse(s.trim());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    public static int edad(LocalDate fechaNacimiento) {
        return Period.between(fechaNacimiento, LocalDate.now()).getYears();
    }

    // Hash del código QR de los certificados (64 caracteres hexadecimales)
    public static String sha256(String texto) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error al generar hash SHA-256: " + e.getMessage(), e);
        }
    }

    public static String encriptarMD5(String texto) {
        if (texto == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error al generar hash MD5: " + e.getMessage(), e);
        }
    }
}
