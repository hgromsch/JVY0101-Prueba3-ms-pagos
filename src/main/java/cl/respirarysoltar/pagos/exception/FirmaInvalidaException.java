package cl.respirarysoltar.pagos.exception;

/** La petición al webhook no trae una firma válida de la pasarela (responde 401). */
public class FirmaInvalidaException extends RuntimeException {
    public FirmaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
