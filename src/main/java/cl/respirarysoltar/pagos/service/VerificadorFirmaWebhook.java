package cl.respirarysoltar.pagos.service;

import cl.respirarysoltar.pagos.api.dto.WebhookRequest;
import cl.respirarysoltar.pagos.exception.FirmaInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Valida que el webhook lo envía la pasarela de pago (corrección EP3: antes era un TODO).
 *
 * La pasarela firma "pagoId:evento" con HMAC-SHA256 usando un secreto compartido y lo
 * envía en la cabecera X-Firma-Pasarela (hex). El secreto llega como secreto de Docker
 * Swarm (/run/secrets/PAGOS_WEBHOOK_SECRET). Sin secreto configurado (desarrollo local)
 * la validación se omite y se advierte en el log.
 */
@Component
public class VerificadorFirmaWebhook {

    private static final Logger log = LoggerFactory.getLogger(VerificadorFirmaWebhook.class);
    private static final String ALGORITMO = "HmacSHA256";

    private final byte[] secreto;

    public VerificadorFirmaWebhook(@Value("${PAGOS_WEBHOOK_SECRET:}") String secreto) {
        this.secreto = secreto == null || secreto.isBlank() ? null : secreto.trim().getBytes(StandardCharsets.UTF_8);
        if (this.secreto == null) {
            log.warn("PAGOS_WEBHOOK_SECRET no configurado: el webhook acepta peticiones sin firma (solo desarrollo)");
        }
    }

    public void verificar(WebhookRequest request, String firmaRecibida) {
        if (secreto == null) {
            return;
        }
        if (firmaRecibida == null || firmaRecibida.isBlank()) {
            throw new FirmaInvalidaException("Falta la cabecera X-Firma-Pasarela");
        }
        byte[] esperada = firmar(request.pagoId() + ":" + request.evento());
        byte[] recibida;
        try {
            recibida = HexFormat.of().parseHex(firmaRecibida.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new FirmaInvalidaException("La firma no es hexadecimal");
        }
        // Comparación en tiempo constante para no filtrar información por tiempos de respuesta
        if (!MessageDigest.isEqual(esperada, recibida)) {
            throw new FirmaInvalidaException("Firma de la pasarela inválida");
        }
    }

    /** Calcula la firma (la usan también las pruebas y el simulador de la pasarela). */
    public byte[] firmar(String contenido) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(secreto, ALGORITMO));
            return mac.doFinal(contenido.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular la firma HMAC", e);
        }
    }
}
