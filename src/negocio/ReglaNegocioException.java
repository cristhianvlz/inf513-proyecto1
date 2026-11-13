package src.negocio;

// Violación de una regla del reglamento ICAP: aborta la transacción y su mensaje se envía al remitente
public class ReglaNegocioException extends RuntimeException {
    public ReglaNegocioException(String mensaje) {
        super(mensaje);
    }
}
