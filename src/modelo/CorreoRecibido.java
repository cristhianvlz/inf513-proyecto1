package src.modelo;

import java.util.List;

// Correo leído del buzón: el asunto trae el comando y los adjuntos sus archivos
public class CorreoRecibido {
    private final String remitente;
    private final String asunto;
    private final int numero;
    private final String uid;
    private final List<Adjunto> adjuntos;

    public CorreoRecibido(String remitente, String asunto, int numero, String uid, List<Adjunto> adjuntos) {
        this.remitente = remitente;
        this.asunto = asunto;
        this.numero = numero;
        this.uid = uid;
        this.adjuntos = adjuntos;
    }

    public String getRemitente() { return remitente; }
    public String getAsunto() { return asunto; }
    public int getNumero() { return numero; }
    public String getUid() { return uid; }
    public List<Adjunto> getAdjuntos() { return adjuntos; }
}
